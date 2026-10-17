import Foundation

/// Prüft nach einer Kursaktualisierung (Vordergrund und Hintergrund) den USDT-Depeg-Alarm (`DepegAlarm`):
/// nur wenn eingeschaltet, höchstens alle 5 Minuten, mit den bestehenden Ticker-Abrufen von Kraken
/// (USDT/USD) und Binance (USDC/USDT). Pausierte Börsen (Backoff, gemeinsame Binance-Pause) werden nicht
/// abgefragt. Wie `DepegAlarmChecker` (Android).
enum DepegAlarmCheck {
    private static let keyLastCheck = "depeg_last_check"
    private static let keyArmed = "depeg_armed"
    private static let keyLastPrice = "depeg_last_price"
    private static let keyLastPriceAt = "depeg_last_price_at"
    /// Höchstens alle 5 Minuten (Vordergrund kann viel öfter aktualisieren).
    private static let minInterval: Int64 = 5 * 60_000
    /// Keine Quelle erreichbar: so lange bis zum nächsten Versuch (wie Android).
    private static let retryAfterFailure: Int64 = 2 * 60_000

    /// Nach einer Kursaktualisierung aufrufen; tut nichts, solange der Alarm aus ist.
    static func runIfDue(settings: AppSettings) async {
        guard settings.depegAlarm else { return }
        let defaults = SharedStorage.defaults
        let now = TimeUtils.nowMillis
        let last = Int64(defaults.double(forKey: keyLastCheck))
        guard now - last >= minInterval else { return }
        defaults.set(Double(now), forKey: keyLastCheck)

        guard let price = await fetchUsdt(now: now) else {
            defaults.set(Double(now - minInterval + retryAfterFailure), forKey: keyLastCheck)
            return
        }
        // Messung merken: Portfolio-Karte zeigt dieselbe Abweichung (`DepegAlarm.cardWarning`)
        defaults.set(price, forKey: keyLastPrice)
        defaults.set(Double(now), forKey: keyLastPriceAt)
        let deviation = DepegAlarm.deviationPercent(price)
        // Wie Kursalarme: in der Nachtruhe nicht auslösen, ohne zustellbare Mitteilung scharf bleiben
        let deliverable = await Notifier.isAuthorized()
        let mayFire = QuietHours.mayFire(holdAlarms: QuietHours.holdsAlarmsNow(settings), deliverable: deliverable)
        let armed = defaults.object(forKey: keyArmed) as? Bool ?? true
        let result = DepegAlarm.evaluate(deviationPercent: deviation,
                                         thresholdPercent: DepegAlarm.thresholdPercent(settings.depegThresholdTenths),
                                         armed: armed, mayFire: mayFire)
        let posted = result.fire ? await notify(price: price, deviation: deviation, settings: settings) : false
        defaults.set(DepegAlarm.armedAfter(fire: result.fire, posted: posted, armedBefore: armed, next: result.armed),
                     forKey: keyArmed)
    }

    /// Letzte Messung der Prüfung (für die Warnzeile der Portfolio-Karte); nil = noch keine — wie `DepegReadingStore` (Android).
    static var lastReading: DepegAlarm.Reading? {
        let defaults = SharedStorage.defaults
        let at = Int64(defaults.double(forKey: keyLastPriceAt))
        guard at > 0, defaults.object(forKey: keyLastPrice) != nil else { return nil }
        return DepegAlarm.Reading(price: defaults.double(forKey: keyLastPrice), atMillis: at)
    }

    private static func notify(price: Double, deviation: Double, settings: AppSettings) async -> Bool {
        let keys = SharedStorage.loadSnapshot().watches.filter { !$0.disabled }.map(\.marketKey)
        let sign = deviation < 0 ? "\u{2212}" : "+"
        let percent = sign + LocaleNumbers.decimal(abs(deviation), maxDecimals: 2) + " %"
        return await Notifier.showDepeg(
            title: L("notification_depeg_title", LocaleNumbers.decimal(price, maxDecimals: 4), percent),
            body: L("notification_depeg_text"),
            exchangeUrl: DepegAlarm.exchangeUrl(keys),
            settings: settings
        )
    }

    /// USDT in USD aus beiden Quellen (parallel); pausierte Börsen bleiben aussen vor.
    private static func fetchUsdt(now: Int64) async -> Double? {
        let backoff = SharedStorage.exchangeBackoff
        func available(_ key: String) -> Bool {
            !ExchangeBackoff.isPaused(backoff[key], now: now) && BlockedSources.groupPausedUntil(key) == nil
        }
        let useKraken = available(DepegAlarm.krakenKey)
        let useBinance = available(DepegAlarm.binanceKey)
        async let kraken = lastPrice(useKraken, DepegAlarm.krakenKey,
                                     MarketPair("USDT", "USD", DepegAlarm.krakenPairId))
        async let binance = lastPrice(useBinance, DepegAlarm.binanceKey,
                                      MarketPair("USDC", "USDT", DepegAlarm.binancePairId))
        let k = await kraken
        let b = await binance
        return DepegAlarm.combine(kraken: DepegAlarm.fromKraken(k), binance: DepegAlarm.fromBinanceUsdc(b))
    }

    /// Letzter Kurs eines Paars; nicht abgefragt (`enabled` false) oder Fehler: nil (still).
    private static func lastPrice(_ enabled: Bool, _ marketKey: String, _ info: MarketPair) async -> Double? {
        guard enabled, let market = MarketsConfig.market(marketKey) else { return nil }
        return try? await MarketService.fetchTicker(market: market, info: info).last
    }
}
