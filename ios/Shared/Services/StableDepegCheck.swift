import Foundation

/// Depeg-Alarm für weitere Stablecoins (`StableDepeg`), von `DepegAlarmCheck` nach der USDT-Messung aufgerufen — nur mit
/// eingeschaltetem Alarm, im selben Takt (höchstens alle 5 Minuten). USDC immer (Kraken USDC/USD, dazu Binance USDC/USDT
/// aus der USDT-Messung ohne eigene Anfrage); die übrigen nur, wenn sie in Merkliste oder Portfolio stehen, über die Paare
/// des `Plan` (aus den gespeicherten Paarlisten, `StableDepegPlanner` in der App — hier wird keine Liste geladen).
/// Pausierte Börsen werden nicht abgefragt. Wie `StableDepegChecker` (Android).
enum StableDepegCheck {
    private static let keyWatched = "depeg_stable_watched"
    private static let keyPricePrefix = "depeg_stable_price_"
    private static let keyPriceAtPrefix = "depeg_stable_price_at_"
    private static let keyArmedPrefix = "depeg_stable_armed_state_"
    /// Schwelle (Zehntelprozent), zu der der Zustand je Stablecoin gehört; 0 = zwischendurch aus (`MarketAlertReset`).
    static let keyArmedTenthsPrefix = "depeg_stable_armed_tenths_"

    /// Ein Paar einer Börse als Quelle.
    struct Route: Sendable, Equatable {
        let exchangeKey: String
        let pair: ExchangePair
    }

    /// Geprüfte Stablecoins (`StableDepeg.watched`) und je übriger Stablecoin seine Paare (je Börse höchstens eins).
    struct Plan: Sendable, Equatable {
        var watched: [String] = [StableDepeg.usdc]
        var routes: [String: [Route]] = [:]
    }

    /// - Parameter usdtUsd: USDT/USD dieses Laufs aus direkten Quellen (nil = nur über USDC/USDT gemessen)
    /// - Parameter binanceUsdcUsdt: Binance USDC/USDT dieses Laufs (nil = fehlt)
    /// - Parameter generation: Stand der «Aus»-Schaltungen zu Beginn (`DepegAlarmCheck.mayStore`)
    static func run(plan: Plan, settings: AppSettings, usdtUsd: Double?, binanceUsdcUsdt: Double?,
                    mayFire: Bool, now: Int64, generation: Int) async {
        let defaults = SharedStorage.defaults
        let backoff = SharedStorage.exchangeBackoff
        func available(_ key: String) -> Bool {
            !ExchangeBackoff.isPaused(backoff[key], now: now) && BlockedSources.groupPausedUntil(key) == nil
        }
        var measured: [String: StableDepeg.Measurement] = [:]
        let kraken = await DepegAlarmCheck.lastPrice(available(DepegAlarm.krakenKey), now: now, DepegAlarm.krakenKey,
                                                     ExchangePair(StableDepeg.usdc, "USD", StableDepeg.krakenUsdcPairId))
        let cross = StableDepeg.toUsd(binanceUsdcUsdt, quote: "USDT", usdtUsd: usdtUsd, usdcUsd: nil)
        measured[StableDepeg.usdc] = StableDepeg.measure([.init(usd: kraken, cross: false), .init(usd: cross, cross: true)],
                                                         requireTwo: false)
        let usdcUsd = measured[StableDepeg.usdc].flatMap { $0.alarmable ? $0.price : nil }
        await withTaskGroup(of: (String, StableDepeg.Measurement?).self) { group in
            for coin in plan.watched where coin != StableDepeg.usdc {
                let routes = (plan.routes[coin] ?? []).filter { available($0.exchangeKey) }
                group.addTask {
                    (coin, await measureOther(coin, routes: routes, usdtUsd: usdtUsd, usdcUsd: usdcUsd, now: now))
                }
            }
            for await (coin, m) in group { if let m { measured[coin] = m } }
        }
        defaults.set(plan.watched.joined(separator: ","), forKey: keyWatched)
        for (coin, m) in measured {
            defaults.set(m.price, forKey: keyPricePrefix + coin)
            defaults.set(Double(now), forKey: keyPriceAtPrefix + coin)
        }
        let tenths = DepegAlarm.threshold(settings.depegThresholdTenths)
        let keys = SharedStorage.loadSnapshot().watches.filter { !$0.disabled }.map(\.exchangeKey)
        // Je Stablecoin eigener Zustand; nicht alarmfähig (Kreuzkurs allein, zu wenige Quellen): Zustand bleibt
        for coin in StableDepeg.coins {
            guard let m = measured[coin], m.alarmable else { continue }
            let armed = DepegAlarm.armedFor(storedArmed: defaults.object(forKey: keyArmedPrefix + coin) as? Bool ?? true,
                                            storedTenths: defaults.object(forKey: keyArmedTenthsPrefix + coin) as? Int,
                                            tenths: tenths)
            let deviation = DepegAlarm.deviationPercent(m.price)
            let result = DepegAlarm.evaluate(deviationPercent: deviation, thresholdPercent: DepegAlarm.thresholdPercent(tenths),
                                             armed: armed, mayFire: mayFire)
            let posted = result.fire
                ? await notify(coin: coin, price: m.price, deviation: deviation, keys: keys, settings: settings) : false
            // Seit Beginn ausgeschaltet: den Stand nicht schreiben, das «Aus» bleibt
            guard DepegAlarmCheck.mayStore(generation: generation, defaults: defaults) else { return }
            defaults.set(DepegAlarm.armedAfter(fire: result.fire, posted: posted, armedBefore: armed, next: result.armed),
                         forKey: keyArmedPrefix + coin)
            defaults.set(tenths, forKey: keyArmedTenthsPrefix + coin)
        }
    }

    /// Zuletzt geprüfte Stablecoins ohne USDT; vor der ersten Prüfung USDC. Wie `StableDepegStore.watched` (Android).
    static var watched: [String] {
        guard let raw = SharedStorage.defaults.string(forKey: keyWatched) else { return [StableDepeg.usdc] }
        return raw.split(separator: ",").map(String.init).filter(StableDepeg.coins.contains)
    }

    /// Letzte Messung je Stablecoin. Wie `StableDepegStore.readings` (Android).
    static var readings: [String: DepegAlarm.Reading] {
        let defaults = SharedStorage.defaults
        var out: [String: DepegAlarm.Reading] = [:]
        for coin in StableDepeg.coins {
            let at = Int64(defaults.double(forKey: keyPriceAtPrefix + coin))
            guard at > 0, defaults.object(forKey: keyPricePrefix + coin) != nil else { continue }
            out[coin] = DepegAlarm.Reading(price: defaults.double(forKey: keyPricePrefix + coin), atMillis: at)
        }
        return out
    }

    /// «Aus»: je gemerkte Schwelle 0 — beim Wiedereinschalten scharf (`DepegAlarm.armedFor`). Wie Android `depegOff`.
    static func resetArmed(defaults: UserDefaults) {
        for coin in StableDepeg.coins where defaults.object(forKey: keyArmedTenthsPrefix + coin) != nil {
            defaults.set(0, forKey: keyArmedTenthsPrefix + coin)
        }
    }

    private static func measureOther(_ coin: String, routes: [Route], usdtUsd: Double?, usdcUsd: Double?,
                                     now: Int64) async -> StableDepeg.Measurement? {
        let sources = await withTaskGroup(of: (Int, StableDepeg.Source).self) { group in
            for (i, route) in routes.enumerated() {
                group.addTask {
                    let last = await DepegAlarmCheck.lastPrice(true, now: now, route.exchangeKey, route.pair)
                    let usd = StableDepeg.toUsd(last, quote: route.pair.quote, usdtUsd: usdtUsd, usdcUsd: usdcUsd)
                    return (i, StableDepeg.Source(usd: usd, cross: route.pair.quote.uppercased() != "USD"))
                }
            }
            var out: [(Int, StableDepeg.Source)] = []
            for await item in group { out.append(item) }
            // Reihenfolge der Börsen wie Android
            return out.sorted { $0.0 < $1.0 }.map(\.1)
        }
        return StableDepeg.measure(sources, requireTwo: StableDepeg.requiresTwo(coin))
    }

    /// Mitteilung mit dem Kürzel; Knopf «Börse öffnen» (Seite USDC/USDT) nur für USDC.
    private static func notify(coin: String, price: Double, deviation: Double, keys: [String],
                               settings: AppSettings) async -> Bool {
        let sign = deviation < 0 ? "\u{2212}" : "+"
        let percent = sign + PercentText.unsigned(deviation, decimals: 2)
        return await Notifier.showDepeg(
            title: L("notification_depeg_stable_title", coin, LocaleNumbers.decimal(price, maxDecimals: 4), percent),
            body: L("notification_depeg_stable_text", coin),
            exchangeUrl: coin == StableDepeg.usdc ? DepegAlarm.exchangeUrl(keys) : nil,
            settings: settings,
            id: Notifier.depegId + "_" + coin
        )
    }
}
