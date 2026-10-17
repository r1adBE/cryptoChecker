import Foundation

/// Devisenkurs USD → Zielwährung für die Umrechnungszeile im Portfolio — wie `FxRateSource.kt`.
/// USDT gilt als USD. Quelle: Frankfurter (EZB, ohne Schlüssel), sonst
/// open.er-api.com. Neu geholt nach 3 h (`CurrencyConversion.fxStaleMillis`: ab da zeigt die
/// Merkliste die Uhrzeit des Kurses; im Speicher); schlägt die Abfrage fehl, gilt ein älterer Kurs
/// bis 24 h. Ohne Kurs bleibt die Zeile weg. Abrufzeit je Währung: `fetchedAt`.
enum FxRateSource {
    /// Wählbare Zielwährungen (EZB-Referenzkurse), die gebräuchlichsten zuerst.
    static let currencies = [
        "CHF", "EUR", "USD", "GBP", "JPY", "CAD", "AUD", "SEK", "NOK", "DKK", "PLN", "CZK",
        "HUF", "RON", "BGN", "ISK", "TRY", "CNY", "HKD", "SGD", "KRW", "INR", "IDR", "THB",
        "MYR", "PHP", "NZD", "ZAR", "BRL", "MXN", "ILS",
    ]

    /// Neu holen ab 3 h (vorher 6 h): ältere Kurse zeigt die Merkliste mit Uhrzeit.
    private static let ttlMillis: Int64 = CurrencyConversion.fxStaleMillis
    private static let staleMillis: Int64 = 24 * 60 * 60_000

    private static let lock = NSLock()
    /// Währung → (Kurs, Abfragezeit).
    nonisolated(unsafe) private static var cache: [String: (rate: Double, at: Int64)] = [:]
    private static let gate = PortfolioSerialGate()

    /// Dreistelliger Code aus A–Z, z. B. «CHF».
    static func isCurrencyCode(_ code: String) -> Bool {
        code.count == 3 && code.unicodeScalars.allSatisfy { $0.value >= 65 && $0.value <= 90 }
    }

    // MARK: Lew nach der Euro-Einführung

    /// Fester Umrechnungskurs Lew je Euro (Bulgarien führt am 1.1.2026 den Euro ein;
    /// die EZB veröffentlicht danach keinen BGN-Kurs mehr).
    static let bgnPerEur = 1.95583
    static let bgnEuroDay = "2026-01-01"

    /// Fehlender BGN-Kurs aus dem EUR-Kurs: USD → BGN = USD → EUR × 1.95583.
    /// `dayIso` = Kurstag «JJJJ-MM-TT» (historisch; nur ab 2026-01-01), nil = aktueller Kurs.
    /// nil, wenn nicht BGN, zu früh oder ohne gültigen EUR-Kurs.
    static func derivedBgn(currency: String, usdToEur: Double?, dayIso: String? = nil) -> Double? {
        guard currency.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == "BGN" else { return nil }
        if let dayIso, dayIso < bgnEuroDay { return nil }
        guard let eur = usdToEur, eur > 0, eur.isFinite else { return nil }
        return eur * bgnPerEur
    }

    /// Wie viele Einheiten `currency` ein USD ist; nil, wenn unbekannt. USD = 1.
    /// Fehlt BGN, gilt der EUR-Kurs × 1.95583 (siehe `derivedBgn`).
    static func usdTo(_ currency: String) async -> Double? {
        let target = currency.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if target == "USD" { return 1 }
        guard isCurrencyCode(target) else { return nil }
        if let rate = await loadUsdTo(target) { return rate }
        guard target == "BGN" else { return nil }
        let eur = await loadUsdTo("EUR")
        return derivedBgn(currency: target, usdToEur: eur)
    }

    private static func loadUsdTo(_ target: String) async -> Double? {
        await gate.run { () async -> Double? in
            let now = TimeUtils.nowMillis
            if let known = Self.entry(target) {
                let age = now - known.at
                if age >= 0 && age < Self.ttlMillis { return known.rate }
            }
            var fresh = await Self.frankfurter(target)
            if fresh == nil { fresh = await Self.openErApi(target) }
            if let fresh {
                Self.put(target, fresh, at: now)
                return fresh
            }
            // Älterer Kurs ist besser als keiner, aber nicht älter als ein Tag
            if let known = Self.entry(target) {
                let age = now - known.at
                if age >= 0 && age < Self.staleMillis { return known.rate }
            }
            return nil
        }
    }

    /// Zuletzt bekannter Kurs ohne Netz (für den ersten Aufbau) — höchstens 24 h alt wie bei `usdTo` (wie Android).
    static func cached(_ currency: String) -> Double? {
        let target = currency.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if target == "USD" { return 1 }
        guard let known = entry(target) else { return nil }
        let age = TimeUtils.nowMillis - known.at
        return age >= 0 && age < staleMillis ? known.rate : nil
    }

    /// Abrufzeit (Epoch-ms) des Kurses von `currency`, den `usdTo`/`cached` gerade liefern; nil für USD
    /// (kein Devisenkurs nötig) oder ohne höchstens 24 h alten Kurs. BGN ohne eigenen Kurs: die des
    /// EUR-Kurses (`derivedBgn`). Wie Android.
    static func fetchedAt(_ currency: String) -> Int64? {
        let target = currency.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if target == "USD" { return nil }
        let now = TimeUtils.nowMillis
        func fresh(_ code: String) -> Int64? {
            guard let known = entry(code) else { return nil }
            let age = now - known.at
            return age >= 0 && age < staleMillis ? known.at : nil
        }
        if let at = fresh(target) { return at }
        return target == "BGN" ? fresh("EUR") : nil
    }

    private static func entry(_ code: String) -> (rate: Double, at: Int64)? {
        lock.lock(); defer { lock.unlock() }
        return cache[code]
    }

    private static func put(_ code: String, _ rate: Double, at time: Int64) {
        lock.lock(); defer { lock.unlock() }
        cache[code] = (rate: rate, at: time)
    }

    private static func frankfurter(_ target: String) async -> Double? {
        guard let body = await get("https://api.frankfurter.app/latest?from=USD&to=\(target)") else { return nil }
        return rate(in: body, target)
    }

    private static func openErApi(_ target: String) async -> Double? {
        guard let body = await get("https://open.er-api.com/v6/latest/USD") else { return nil }
        return rate(in: body, target)
    }

    /// `{"rates":{"CHF":0.79,…}}` → Kurs; nil, wenn fehlend oder ≤ 0.
    private static func rate(in body: String, _ target: String) -> Double? {
        guard let data = body.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let rates = root["rates"] as? [String: Any],
              let value = (rates[target] as? NSNumber)?.doubleValue,
              value > 0, value.isFinite
        else { return nil }
        return value
    }

    private static func get(_ url: String) async -> String? {
        try? await MarketHTTP.call(url)
    }
}
