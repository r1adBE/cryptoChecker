import Foundation

/// Reine Rechenregeln der «≈ Umrechnung» — wie `CurrencyConversion.kt`.
enum CurrencyConversion {
    /// Art der Quote-Währung eines Paars.
    enum QuoteKind: Sendable {
        /// USD oder ein an den USD gebundener Stablecoin: 1 Einheit = 1 USD.
        case usd
        /// Fiat-Währung mit Devisenkurs (z. B. EUR, JPY).
        case fiat
        /// Alles andere gilt als Krypto (z. B. BTC, ETH) und wird über USDT bewertet.
        case crypto
        /// Leer oder kein gültiges Kürzel.
        case invalid
    }

    /// Die eine Stablecoin-Liste der App. Die «≈ Umrechnung» der Merkliste rechnet sie als 1 USD;
    /// das Portfolio bewertet sie nach `PortfolioStables` (USDT 1, andere ihr Marktkurs, sonst 1).
    static let usdStables: Set<String> = [
        "USD", "USDT", "USDC", "FDUSD", "BUSD", "DAI", "TUSD", "USDE", "USD1",
        "RLUSD", "PYUSD", "USDP", "GUSD",
    ]

    static func normalize(_ code: String?) -> String {
        (code ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
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

    /// Wert einer Einheit der Quote-Währung in USD; nil, wenn der nötige Kurs fehlt.
    /// - Parameters:
    ///   - usdToQuote: Einheiten der Quote-Währung je USD (nur bei `.fiat`)
    ///   - cryptoUsdt: Kurs der Quote-Währung in USDT (nur bei `.crypto`)
    static func quoteToUsd(_ kind: QuoteKind, usdToQuote: Double?, cryptoUsdt: Double?) -> Double? {
        switch kind {
        case .usd: return 1
        case .fiat:
            guard let r = usdToQuote, isValidRate(r) else { return nil }
            return 1 / r
        case .crypto:
            guard let p = cryptoUsdt, isValidRate(p) else { return nil }
            return p
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
/// Quote → USD: USD-Stablecoins = 1, Fiat über `FxRateSource`, alles andere als
/// Krypto über den USDT-Kurs der `PortfolioPriceSource`. Danach USD → Ziel über
/// `FxRateSource`. Liegt in Shared/, weil auch die Alarmprüfung (Widget-Erweiterung,
/// Hintergrund) sie braucht; beide Quellen haben eigene Zwischenspeicher.
enum CurrencyConverter {

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
        // Krypto-Kurse gesammelt holen, damit nicht jede Quote einzeln abfragt
        let cryptos = wanted.filter { CurrencyConversion.classify($0) == .crypto }
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
        case .usd: quoteUsd = 1
        case .fiat: quoteUsd = CurrencyConversion.quoteToUsd(.fiat, usdToQuote: FxRateSource.cached(from), cryptoUsdt: nil)
        case .crypto:
            quoteUsd = CurrencyConversion.quoteToUsd(.crypto, usdToQuote: nil,
                                                     cryptoUsdt: PortfolioPriceSource.cached([from]).prices[from])
        case .invalid: quoteUsd = nil
        }
        let usdToTarget: Double?
        switch CurrencyConversion.classify(to) {
        case .usd: usdToTarget = 1
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
        case .usd: return 1
        case .fiat: return await FxRateSource.usdTo(to)
        default: return nil
        }
    }

    private static func quoteToUsd(_ quote: String) async -> Double? {
        let code = CurrencyConversion.normalize(quote)
        let kind = CurrencyConversion.classify(code)
        switch kind {
        case .usd: return 1
        case .fiat:
            let usdToQuote = await FxRateSource.usdTo(code)
            return CurrencyConversion.quoteToUsd(kind, usdToQuote: usdToQuote, cryptoUsdt: nil)
        case .crypto:
            let price = await PortfolioPriceSource.price(code)
            return CurrencyConversion.quoteToUsd(kind, usdToQuote: nil, cryptoUsdt: price)
        case .invalid: return nil
        }
    }
}
