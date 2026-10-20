import Foundation

/// Reine Rechenregeln der «≈ Umrechnung» — wie `CurrencyConversion.kt`.
enum CurrencyConversion {
    /// Art der Quote-Währung eines Paars.
    enum QuoteKind: Sendable {
        /// USD oder ein an den USD gebundener Stablecoin (Wert nach `stableToUsd`).
        case usd
        /// Fiat-Währung mit Devisenkurs (z. B. EUR, JPY).
        case fiat
        /// Alles andere gilt als Krypto (z. B. BTC, ETH) und wird über USDT bewertet.
        case crypto
        /// Leer oder kein gültiges Kürzel.
        case invalid
    }

    /// Die eine Stablecoin-Liste der App. «≈ Umrechnung» der Merkliste und Portfolio bewerten sie
    /// mit Marktkurs (`stableToUsd` bzw. `PortfolioStables`): USD 1, USDT sein Marktkurs USDT/USD (ohne Kurs 1),
    /// andere ihr Marktkurs in USDT.
    /// Ohne Kurs: kein ≈-Wert bzw. Portfolio kein Wert («ohne Kurs»).
    static let usdStables: Set<String> = [
        "USD", "USDT", "USDC", "FDUSD", "BUSD", "DAI", "TUSD", "USDE", "USD1",
        "RLUSD", "PYUSD", "USDP", "GUSD",
    ]

    static func normalize(_ code: String?) -> String {
        (code ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    /// Börse, deren Gegenwährung «U» ein USD-Stablecoin ist (United Stables: BTCU, ETHU).
    static let asterKey = "Aster"

    /// Quote eines Paars für die Umrechnung: «U» an Aster ist United Stables (USD-Stablecoin) und zählt 1:1 als USD;
    /// an anderen Börsen (CoinDCX, BingX, Binance) ist U ein Coin und bleibt Krypto — darum nicht global in
    /// `usdStables`. Sonst `normalize`. Wie Android.
    static func conversionQuote(marketKey: String?, quote: String?) -> String {
        let code = normalize(quote)
        let market = (marketKey ?? "").trimmingCharacters(in: .whitespaces).lowercased()
        return code == "U" && market == asterKey.lowercased() ? "USD" : code
    }

    /// Gleiche Währung (Gross-/Kleinschreibung egal) — dann gibt es nichts umzurechnen.
    static func sameCurrency(_ a: String?, _ b: String?) -> Bool {
        let x = normalize(a)
        return !x.isEmpty && x == normalize(b)
    }

    /// Einordnung von `quote`; `fiat` = Währungen mit Devisenkurs (`FxRateSource.currencies`).
    static func classify(_ quote: String?, fiat: [String] = FxRateSource.currencies) -> QuoteKind {
        let code = normalize(quote)
        guard !code.isEmpty, code.count <= 15, code.allSatisfy({ $0.isLetter || $0.isNumber }) else { return .invalid }
        if usdStables.contains(code) { return .usd }
        if fiat.contains(where: { $0.uppercased() == code }) { return .fiat }
        return .crypto
    }

    /// Stablecoin-Regel der «≈ Umrechnung» wie das Portfolio (`PortfolioStables`): USD genau 1; USDT sein
    /// Marktkurs USDT/USD `usdtUsd` (ohne Kurs 1, `PortfolioStables.usdtUsd`); jeder andere Stablecoin sein
    /// Marktkurs in USDT `marketUsdt` × USDT/USD; ohne gültigen Kurs nil (kein ≈-Wert statt eines stillen 1).
    /// Ohne Kürzel gilt USD. `usdtUsd` nil = USDT gilt 1 USD (bisherige Rechnung). Wie Android.
    static func stableToUsd(_ code: String?, marketUsdt: Double?, usdtUsd: Double? = nil) -> Double? {
        let c = normalize(code)
        if c.isEmpty || c == "USD" { return 1 }
        if c == "USDT" { return usdtFactor(usdtUsd) }
        guard let m = marketUsdt, isValidRate(m) else { return nil }
        return m * usdtFactor(usdtUsd)
    }

    /// USDT in USD für die Umrechnung: `usdtUsd` (plausibel) oder 1; nil = 1 (bisherige Rechnung).
    private static func usdtFactor(_ usdtUsd: Double?) -> Double {
        guard let usdtUsd else { return 1 }
        return PortfolioStables.usdtUsdLive(usdtUsd).rate
    }

    /// Braucht die Quote `code` (Stablecoin) einen Marktkurs? Alle ausser USD und USDT (leer = USD).
    static func stableNeedsMarket(_ code: String?) -> Bool {
        let c = normalize(code)
        return !c.isEmpty && c != "USD" && c != "USDT"
    }

    /// Ab diesem Alter (Abrufzeit) zeigt die ≈ Umrechnung die Uhrzeit des Devisenkurses (3 h).
    static let fxStaleMillis: Int64 = 3 * 60 * 60_000

    /// Höchstalter eines Krypto- bzw. Stablecoin-Kurses (Quote in USDT aus dem Zwischenspeicher der Portfolio-Kurse) für
    /// die Umrechnung (15 min): ein Alarm in CHF auf ein BTC-Paar darf nicht mit einem Kurs von gestern umrechnen.
    static let quoteMaxAgeMillis: Int64 = 15 * 60_000

    /// `price` nur, wenn seine Abrufzeit `time` höchstens `quoteMaxAgeMillis` vor `now` liegt; älter oder ohne Zeit nil —
    /// dann gibt es keinen Faktor (≈ fehlt, Alarm in fremder Währung prüft diesmal nicht). Wie Android `freshQuotePrice`.
    static func freshQuotePrice(_ price: Double?, time: Int64?, now: Int64) -> Double? {
        guard let price, let time, time > 0 else { return nil }
        return now - time <= quoteMaxAgeMillis ? price : nil
    }

    /// Abrufzeit des ältesten Devisenkurses im Faktor Quote → Ziel (`quoteFxAt`: Kurs der Fiat-Quote,
    /// `targetFxAt`: Kurs der Zielwährung; je nil = nicht beteiligt), wenn älter als `fxStaleMillis`;
    /// sonst nil (kein Hinweis).
    static func staleFxTime(quoteFxAt: Int64?, targetFxAt: Int64?, now: Int64) -> Int64? {
        guard let oldest = [quoteFxAt, targetFxAt].compactMap({ $0 }).min() else { return nil }
        return now - oldest > fxStaleMillis ? oldest : nil
    }

    /// Wert einer Einheit der Quote-Währung in USD; nil, wenn der nötige Kurs fehlt.
    /// - Parameters:
    ///   - usdToQuote: Einheiten der Quote-Währung je USD (nur bei `.fiat`)
    ///   - cryptoUsdt: Kurs der Quote-Währung in USDT (bei `.crypto` nötig; bei `.usd` = Marktkurs
    ///     des Stablecoins, ohne gültigen Kurs nil — siehe `stableToUsd`)
    ///   - code: Kürzel der Quote-Währung (nur bei `.usd`: USD ist immer genau 1, USDT siehe `stableToUsd`)
    ///   - usdtUsd: Marktkurs USDT/USD für USDT, andere Stablecoins und Krypto (über USDT bewertet); nil = 1
    static func quoteToUsd(_ kind: QuoteKind, usdToQuote: Double?, cryptoUsdt: Double?, code: String? = nil,
                           usdtUsd: Double? = nil) -> Double? {
        switch kind {
        case .usd: return stableToUsd(code, marketUsdt: cryptoUsdt, usdtUsd: usdtUsd)
        case .fiat:
            guard let r = usdToQuote, isValidRate(r) else { return nil }
            return 1 / r
        case .crypto:
            guard let p = cryptoUsdt, isValidRate(p) else { return nil }
            return p * usdtFactor(usdtUsd)
        case .invalid: return nil
        }
    }

    /// Faktor Quote → Ziel: Kurs in Quote × Faktor = Kurs in Ziel.
    static func rate(quoteUsd: Double?, usdToTarget: Double?) -> Double? {
        guard let q = quoteUsd, isValidRate(q), let t = usdToTarget, isValidRate(t) else { return nil }
        let r = q * t
        return isValidRate(r) ? r : nil
    }

    /// Wandelt einen Betrag um; nil ohne gültigen Faktor.
    static func convert(_ value: Double, rate: Double?) -> Double? {
        guard let rate, isValidRate(rate) else { return nil }
        return value * rate
    }

    private static func isValidRate(_ v: Double) -> Bool { v.isFinite && v > 0 }
}

/// «≈ Umrechnung»: Faktor von der Quote-Währung eines Paars in eine Zielwährung
/// (z. B. USDT → CHF, EUR → CHF, BTC → CHF) — wie `CurrencyConverter.kt`.
///
/// Quote → USD: Stablecoins mit Marktkurs (`CurrencyConversion.stableToUsd`: USD/USDT 1, andere
/// ihr Marktkurs in USDT von der `PortfolioPriceSource` — ohne Kurs kein Faktor, wie die Portfolio-Regel
/// `PortfolioStables`), Fiat über `FxRateSource`, alles andere als Krypto über den USDT-Kurs der
/// `PortfolioPriceSource`. Danach USD → Ziel über `FxRateSource`; Alter der Devisenkurse: `staleFxTime`. Krypto- und
/// Stablecoin-Kurse älter als `CurrencyConversion.quoteMaxAgeMillis` ergeben keinen Faktor (`freshMarket`). Liegt in Shared/, weil auch die Alarmprüfung (Widget-Erweiterung,
/// Hintergrund) sie braucht; beide Quellen haben eigene Zwischenspeicher.
/// USDT zum Marktkurs (Owner-Entscheid Okt. 2026): USDT und alles, was über USDT bewertet wird, × Live-Kurs USDT/USD
/// (`DepegAlarmCheck.liveUsdtUsd`, gemessen mit Portfolio oder Depeg-Alarm); ohne frische Messung 1 (bisherige Rechnung).
enum CurrencyConverter {

    /// Live-Kurs USDT/USD (`DepegAlarmCheck.liveUsdtUsd`); nil = keiner (USDT gilt 1, bisherige Rechnung).
    private static func liveUsdtUsd() -> Double? { DepegAlarmCheck.liveUsdtUsd() }

    /// Einheiten der Ziel-Stablecoin-Währung `code` je USD: USDT = 1 ÷ USDT/USD (Marktkurs, ohne Messung 1); USD und
    /// andere Stablecoins 1 (wie bisher). Wie Android `usdToStable`.
    private static func usdToStable(_ code: String) -> Double {
        guard code == "USDT" else { return 1 }
        return 1 / (CurrencyConversion.stableToUsd("USDT", marketUsdt: nil, usdtUsd: liveUsdtUsd()) ?? 1)
    }

    /// Faktor Quote → Ziel; gleiche Währung = 1, nil wenn ein Kurs fehlt.
    static func rate(quote: String, target: String) async -> Double? {
        if CurrencyConversion.sameCurrency(quote, target) { return 1 }
        guard let usdToTarget = await usdTo(target) else { return nil }
        let quoteUsd = await quoteToUsd(quote)
        return CurrencyConversion.rate(quoteUsd: quoteUsd, usdToTarget: usdToTarget)
    }

    /// Faktoren für mehrere Quote-Währungen; fehlende bleiben weg. Schlüssel in Grossbuchstaben.
    static func rates(quotes: [String], target: String) async -> [String: Double] {
        var seen = Set<String>()
        let wanted = quotes.map { CurrencyConversion.normalize($0) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
        // Krypto- und Stablecoin-Kurse (ausser USD/USDT) gesammelt holen, damit nicht jede Quote einzeln abfragt
        let cryptos = wanted.filter {
            let kind = CurrencyConversion.classify($0)
            return kind == .crypto || (kind == .usd && CurrencyConversion.stableNeedsMarket($0))
        }
        if !cryptos.isEmpty { _ = await PortfolioPriceSource.prices(cryptos) }
        var result: [String: Double] = [:]
        for quote in wanted {
            if let r = await rate(quote: quote, target: target) { result[quote] = r }
        }
        return result
    }

    /// Faktor nur aus den Zwischenspeichern, ohne Netz (für den ersten Aufbau).
    static func cachedRate(quote: String, target: String) -> Double? {
        if CurrencyConversion.sameCurrency(quote, target) { return 1 }
        let from = CurrencyConversion.normalize(quote)
        let to = CurrencyConversion.normalize(target)
        let quoteUsd: Double?
        switch CurrencyConversion.classify(from) {
        case .usd:
            quoteUsd = CurrencyConversion.quoteToUsd(.usd, usdToQuote: nil, cryptoUsdt: stableMarket(from), code: from,
                                                     usdtUsd: liveUsdtUsd())
        case .fiat: quoteUsd = CurrencyConversion.quoteToUsd(.fiat, usdToQuote: FxRateSource.cached(from), cryptoUsdt: nil)
        case .crypto:
            quoteUsd = CurrencyConversion.quoteToUsd(.crypto, usdToQuote: nil,
                                                     cryptoUsdt: freshMarket(PortfolioPriceSource.cached([from]), from),
                                                     usdtUsd: liveUsdtUsd())
        case .invalid: quoteUsd = nil
        }
        let usdToTarget: Double?
        switch CurrencyConversion.classify(to) {
        case .usd: usdToTarget = usdToStable(to)
        case .fiat: usdToTarget = FxRateSource.cached(to)
        default: usdToTarget = nil
        }
        return CurrencyConversion.rate(quoteUsd: quoteUsd, usdToTarget: usdToTarget)
    }

    /// Wie `cachedRate` für mehrere Quote-Währungen.
    static func cachedRates(quotes: [String], target: String) -> [String: Double] {
        var result: [String: Double] = [:]
        for quote in quotes {
            let code = CurrencyConversion.normalize(quote)
            guard !code.isEmpty, result[code] == nil else { continue }
            if let r = cachedRate(quote: code, target: target) { result[code] = r }
        }
        return result
    }

    /// USD → Ziel; nur USD/Stablecoins und Fiat sind gültige Ziele.
    private static func usdTo(_ target: String) async -> Double? {
        let to = CurrencyConversion.normalize(target)
        switch CurrencyConversion.classify(to) {
        case .usd: return usdToStable(to)
        case .fiat: return await FxRateSource.usdTo(to)
        default: return nil
        }
    }

    /// Devisenkurs im Faktor `quote` → `target`, wenn älter als 3 h: seine Abrufzeit
    /// (`CurrencyConversion.staleFxTime`); nil = frisch oder kein Devisenkurs beteiligt (USD,
    /// Stablecoins, Krypto in USD). Wie Android.
    static func staleFxTime(quote: String, target: String, now: Int64 = TimeUtils.nowMillis) -> Int64? {
        if CurrencyConversion.sameCurrency(quote, target) { return nil }
        let from = CurrencyConversion.normalize(quote)
        let to = CurrencyConversion.normalize(target)
        let quoteAt = CurrencyConversion.classify(from) == .fiat ? FxRateSource.fetchedAt(from) : nil
        let targetAt = CurrencyConversion.classify(to) == .fiat ? FxRateSource.fetchedAt(to) : nil
        return CurrencyConversion.staleFxTime(quoteFxAt: quoteAt, targetFxAt: targetAt, now: now)
    }

    /// Wie `staleFxTime` für mehrere Quote-Währungen; nur alte Kurse. Schlüssel in Grossbuchstaben.
    static func staleFxTimes(quotes: [String], target: String, now: Int64 = TimeUtils.nowMillis) -> [String: Int64] {
        var result: [String: Int64] = [:]
        for quote in quotes {
            let code = CurrencyConversion.normalize(quote)
            guard !code.isEmpty, result[code] == nil else { continue }
            if let at = staleFxTime(quote: code, target: target, now: now) { result[code] = at }
        }
        return result
    }

    /// Ältester Zeitpunkt (laut Quelle, `FxRateSource.rateTime`) der Devisenkurse im Faktor `quote` → `target` — für das
    /// Alter im Fiat-Alarm (`FiatAlarmGuard.fxTooOld`); nil = kein Devisenkurs beteiligt. Wie Android.
    static func fxRateTime(quote: String, target: String) -> Int64? {
        if CurrencyConversion.sameCurrency(quote, target) { return nil }
        let from = CurrencyConversion.normalize(quote)
        let to = CurrencyConversion.normalize(target)
        let quoteAt = CurrencyConversion.classify(from) == .fiat ? FxRateSource.rateTime(from) : nil
        let targetAt = CurrencyConversion.classify(to) == .fiat ? FxRateSource.rateTime(to) : nil
        return [quoteAt, targetAt].compactMap { $0 }.min()
    }

    /// Stand von USDT/USD im Faktor `quote` → `target` (`FiatAlarmGuard.usdtState`): nicht beteiligt, gemessen,
    /// angenommen (USDT = 1) oder unsicher (nur über USDC, USDC/USDT mehr als 1 % neben 1). Wie Android.
    static func usdtState(quote: String, target: String, now: Int64 = TimeUtils.nowMillis) -> FiatAlarmGuard.UsdtState {
        FiatAlarmGuard.usdtState(involved: FiatAlarmGuard.involvesUsdt(from: quote, to: target),
                                 reading: DepegAlarmCheck.lastReading, now: now)
    }

    /// Wie `usdtState` für mehrere Quote-Währungen; nur angenommene bzw. unsichere. Schlüssel in Grossbuchstaben.
    static func usdtNotes(quotes: [String], target: String,
                          now: Int64 = TimeUtils.nowMillis) -> [String: FiatAlarmGuard.UsdtState] {
        var result: [String: FiatAlarmGuard.UsdtState] = [:]
        for quote in quotes {
            let code = CurrencyConversion.normalize(quote)
            guard !code.isEmpty, result[code] == nil else { continue }
            let state = usdtState(quote: code, target: target, now: now)
            if state == .ASSUMED || state == .UNSURE { result[code] = state }
        }
        return result
    }

    /// Marktkurs eines Stablecoins in USDT, nur aus dem Zwischenspeicher der Portfolio-Kurse (keine
    /// zusätzliche Abfrage); nil = keiner bekannt (dann kein Faktor). USD/USDT brauchen keinen.
    private static func stableMarket(_ code: String) -> Double? {
        guard CurrencyConversion.stableNeedsMarket(code) else { return nil }
        return freshMarket(PortfolioPriceSource.cached([code]), code)
    }

    /// Kurs von `code` aus `prices`, nur wenn höchstens `CurrencyConversion.quoteMaxAgeMillis` alt: Die Kursquelle behält
    /// bei einem Fehler den letzten bekannten Kurs (für das Portfolio richtig) — für die Umrechnung von Alarmen und «≈»
    /// wäre ein Kurs von gestern still falsch. Wie Android.
    private static func freshMarket(_ prices: PortfolioPrices, _ code: String, now: Int64 = TimeUtils.nowMillis) -> Double? {
        CurrencyConversion.freshQuotePrice(prices.prices[code], time: prices.times[code], now: now)
    }

    private static func quoteToUsd(_ quote: String) async -> Double? {
        let code = CurrencyConversion.normalize(quote)
        let kind = CurrencyConversion.classify(code)
        switch kind {
        case .usd:
            // Stablecoin ausser USD/USDT: Marktkurs (wie das Portfolio), ohne Kurs kein Faktor; USDT × USDT/USD
            var market: Double?
            if CurrencyConversion.stableNeedsMarket(code) { market = freshMarket(await PortfolioPriceSource.prices([code]), code) }
            return CurrencyConversion.quoteToUsd(kind, usdToQuote: nil, cryptoUsdt: market, code: code,
                                                 usdtUsd: liveUsdtUsd())
        case .fiat:
            let usdToQuote = await FxRateSource.usdTo(code)
            return CurrencyConversion.quoteToUsd(kind, usdToQuote: usdToQuote, cryptoUsdt: nil)
        case .crypto:
            // Höchstens 15 min alt: scheitert die Abfrage, bleibt sonst der letzte Kurs beliebig lange stehen
            let price = freshMarket(await PortfolioPriceSource.prices([code]), code)
            return CurrencyConversion.quoteToUsd(kind, usdToQuote: nil, cryptoUsdt: price, usdtUsd: liveUsdtUsd())
        case .invalid: return nil
        }
    }
}
