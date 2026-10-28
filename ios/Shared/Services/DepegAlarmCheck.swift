import Foundation

/// Misst nach einer Kursaktualisierung (Vordergrund und Hintergrund) USDT in USD und prüft den USDT-Depeg-Alarm
/// (`DepegAlarm`): gemessen wird mit eingeschaltetem Alarm ODER sobald ein Portfolio da ist (`DepegAlarm.shouldMeasure`
/// — die Messung ist der Live-Kurs USDT/USD der Bewertung), höchstens alle 5 Minuten, mit den bestehenden
/// Ticker-Abrufen von Kraken (USDT/USD), Coinbase (USDT-USD) und Binance (USDC/USDT, nur dritte Quelle —
/// `DepegAlarm.measure`). Gemeldet wird nur mit eingeschaltetem Alarm und nie aus USDC/USDT allein (`DepegAlarm.mayAlarm`).
/// Pausierte Börsen (Backoff, gemeinsame Binance-Pause) werden nicht abgefragt. Wie `DepegAlarmChecker` (Android).
enum DepegAlarmCheck {
    private static let keyLastCheck = "depeg_last_check"
    private static let keyArmed = "depeg_armed"
    private static let keyLastPrice = "depeg_last_price"
    private static let keyLastPriceAt = "depeg_last_price_at"
    /// Messung nur aus Binance USDC/USDT (`DepegAlarm.Measurement.viaUsdc`); fehlt bei älteren Messungen (false).
    private static let keyLastPriceViaUsdc = "depeg_last_price_via_usdc"
    /// Höchstens alle 5 Minuten (Vordergrund kann viel öfter aktualisieren).
    private static let minInterval: Int64 = 5 * 60_000
    /// Keine Quelle erreichbar: so lange bis zum nächsten Versuch (wie Android).
    private static let retryAfterFailure: Int64 = 2 * 60_000

    /// Wie `DepegAlarmChecker.isDue` (Android): auch fällig, wenn die letzte Prüfung in der Zukunft liegt.
    static func isDue(now: Int64, lastCheck: Int64) -> Bool {
        EpochTime.isIntervalDue(now: now, last: lastCheck, minInterval: minInterval)
    }

    /// Nach einer Kursaktualisierung aufrufen; tut nichts ohne Alarm und ohne Portfolio.
    /// - Parameter hasPortfolioData: Transaktionen oder beobachtete Adressen vorhanden (`AppData.hasPortfolioData`).
    /// - Parameter fiatConversion: Fiat-Alarm bzw. ≈ Umrechnung über USDT (`FiatAlarmGuard.needsUsdtMeasurement`).
    static func runIfDue(settings: AppSettings, hasPortfolioData: Bool, fiatConversion: Bool = false) async {
        guard DepegAlarm.shouldMeasure(depegAlarm: settings.depegAlarm, portfolioEnabled: settings.portfolioEnabled,
                                       hasPortfolioData: hasPortfolioData, fiatConversion: fiatConversion) else { return }
        let defaults = SharedStorage.defaults
        let now = EpochTime.nowMillis
        let last = Int64(defaults.double(forKey: keyLastCheck))
        guard isDue(now: now, lastCheck: last) else { return }
        defaults.set(Double(now), forKey: keyLastCheck)

        guard let measurement = await fetchUsdt(now: now) else {
            defaults.set(Double(now - minInterval + retryAfterFailure), forKey: keyLastCheck)
            return
        }
        // Messung merken: Live-Kurs USDT/USD der Bewertung (`DepegAlarm.liveUsdtUsd`)
        defaults.set(measurement.price, forKey: keyLastPrice)
        defaults.set(Double(now), forKey: keyLastPriceAt)
        defaults.set(measurement.viaUsdc, forKey: keyLastPriceViaUsdc)
        // Alarm aus: nur gemessen (Portfolio), nichts melden
        guard settings.depegAlarm else { return }
        // Nur USDC/USDT: weicht USDT oder USDC ab? Nicht unterscheidbar — kein Alarm, Zustand bleibt
        guard DepegAlarm.mayAlarm(measurement) else { return }
        let price = measurement.price
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

    /// Letzte Messung (USDT in USD, Zeitpunkt); nil = noch keine — wie `DepegReadingStore` (Android).
    static var lastReading: DepegAlarm.Reading? {
        let defaults = SharedStorage.defaults
        let at = Int64(defaults.double(forKey: keyLastPriceAt))
        guard at > 0, defaults.object(forKey: keyLastPrice) != nil else { return nil }
        return DepegAlarm.Reading(price: defaults.double(forKey: keyLastPrice), atMillis: at,
                                  viaUsdc: defaults.bool(forKey: keyLastPriceViaUsdc))
    }

    /// Live-Kurs USDT/USD der Bewertung (`DepegAlarm.liveUsdtUsd`: höchstens 6 Std. alt, plausibel); nil = keiner
    /// (Ersatz 1, Näherung) — wie `DepegReadingStore.liveUsdtUsd` (Android).
    static func liveUsdtUsd(now: Int64 = EpochTime.nowMillis) -> Double? {
        DepegAlarm.liveUsdtUsd(lastReading, now: now)
    }

    private static func notify(price: Double, deviation: Double, settings: AppSettings) async -> Bool {
        let keys = SharedStorage.loadSnapshot().watches.filter { !$0.disabled }.map(\.marketKey)
        let sign = deviation < 0 ? "\u{2212}" : "+"
        let percent = sign + PercentText.unsigned(deviation, decimals: 2)
        return await Notifier.showDepeg(
            title: L("notification_depeg_title", LocaleNumbers.decimal(price, maxDecimals: 4), percent),
            body: L("notification_depeg_text"),
            exchangeUrl: DepegAlarm.exchangeUrl(keys),
            settings: settings
        )
    }

    /// USDT in USD aus den drei Quellen (parallel, `DepegAlarm.measure`); pausierte Börsen bleiben aussen vor.
    private static func fetchUsdt(now: Int64) async -> DepegAlarm.Measurement? {
        let backoff = SharedStorage.exchangeBackoff
        func available(_ key: String) -> Bool {
            !ExchangeBackoff.isPaused(backoff[key], now: now) && BlockedSources.groupPausedUntil(key) == nil
        }
        let useKraken = available(DepegAlarm.krakenKey)
        let useCoinbase = available(DepegAlarm.coinbaseKey)
        let useBinance = available(DepegAlarm.binanceKey)
        async let kraken = lastPrice(useKraken, now: now, DepegAlarm.krakenKey,
                                     MarketPair("USDT", "USD", DepegAlarm.krakenPairId))
        async let coinbase = lastPrice(useCoinbase, now: now, DepegAlarm.coinbaseKey,
                                       MarketPair("USDT", "USD", DepegAlarm.coinbasePairId))
        async let binance = lastPrice(useBinance, now: now, DepegAlarm.binanceKey,
                                      MarketPair("USDC", "USDT", DepegAlarm.binancePairId))
        let k = await kraken
        let c = await coinbase
        let b = await binance
        return DepegAlarm.measure(kraken: k, coinbase: c, binanceUsdc: b)
    }

    /// Letzter Kurs eines Paars; nicht abgefragt (`enabled` false) oder Fehler: nil (still). Laut Börse älter als
    /// 2 Std.: ebenfalls nil (`DepegAlarm.usablePrice`) — keine Messung, kein Alarm, die Bewertung fällt zurück wie
    /// ohne frische Messung. Wie Android.
    private static func lastPrice(_ enabled: Bool, now: Int64, _ marketKey: String, _ info: MarketPair) async -> Double? {
        guard enabled, let market = MarketsConfig.market(marketKey),
              let ticker = try? await MarketService.fetchTicker(market: market, info: info) else { return nil }
        return DepegAlarm.usablePrice(last: ticker.last, exchangeTime: ticker.exchangeTime, now: now)
    }
}
