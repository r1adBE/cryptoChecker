import Foundation

/// Ein gemerkter USDT-Kurs und wann er geholt wurde (Epoch-ms).
struct StoredPrice: Sendable, Equatable {
    var price: Double
    var at: Int64
    /// Binance-Kurs mit unbestätigtem Token («Kurs ungeprüft»): gilt als Wert, aber nie als bestätigter
    /// Bezug (Kerzen, Tagesschlüsse) — auch nach einem Neustart, darum mit dem Kurs gemerkt. Wie Android.
    var unverified = false

    /// Brauchbar: endlicher, positiver Kurs mit Zeitpunkt.
    var valid: Bool { price.isFinite && price > 0 && at > 0 }
}

/// Zustand der gezeigten Portfolio-Kurse für die Status-Pille (wie die Merkliste).
enum PortfolioPriceState: Sendable, Equatable {
    /// Nichts zu zeigen (keine offenen Coins oder noch nie ein Kurs, Gerät online).
    case none
    /// Kurse jünger als die Frische-Schwelle.
    case fresh
    /// Online, aber die Kurse sind älter als die Schwelle (z. B. Abruf gescheitert).
    case stale
    /// Gerät ohne Netz: letzter Stand mit Uhrzeit.
    case offline
}

/// Letzter bekannter Stand der Portfolio-Kurse — damit das Portfolio ohne Netz (und nach einem
/// Neustart) nicht «0.00 USDT» zeigt, sondern den letzten Wert mit «Stand …». Reine Logik,
/// wie Android `PortfolioPriceFallback`:
///  - `merge`: Speicher und gemerkter Stand, je Coin gewinnt der neuere Kurs
///  - `prune`: höchstens `maxAgeMillis` alt und `maxEntries` Einträge (die neuesten)
///  - `encode`/`decode`: kleines Zeilenformat «COIN;Kurs;Zeit» (Version in der ersten Zeile)
///  - `state`/`mayRecord`: aktuell, veraltet oder offline; ob ein Stand für Widget/Verlauf taugt
enum PortfolioPriceFallback {
    /// Ältere Kurse werden nicht mehr gezeigt (lieber «—» als ein Monate alter Wert).
    static let maxAgeMillis: Int64 = 30 * 24 * 60 * 60_000
    /// Obergrenze der gemerkten Coins (auch Kurse aus dem Erfassen-Blatt landen im Speicher).
    static let maxEntries = 300
    /// Kopfzeile der Datei; eine andere Version gilt als «nichts gespeichert».
    static let formatHeader = "portfolio-prices-v1"
    /// 4. Feld einer Zeile: Kurs ungeprüft (`StoredPrice.unverified`); ältere Dateien ohne Feld = geprüft. Wie Android.
    static let unverifiedMark = "u"

    /// Frische-Schwelle der Portfolio-Kurse: wie die Merkliste ohne Live-Modus (`OutdatedRule`) —
    /// dreimal das Hintergrund-Intervall, mindestens 15 Minuten. Der Live-Modus zählt nicht: Das
    /// Portfolio holt seine Kurse beim Öffnen und per Ziehen, nicht im Live-Takt.
    static func staleAfterMillis(backgroundIntervalMinutes: Int) -> Int64 {
        OutdatedRule.afterMillis(backgroundIntervalMinutes: backgroundIntervalMinutes)
    }

    /// Je Coin der neuere gültige Kurs aus `current` und `stored`; ungültige fallen weg.
    static func merge(current: [String: StoredPrice], stored: [String: StoredPrice]) -> [String: StoredPrice] {
        var out: [String: StoredPrice] = [:]
        for (coin, entry) in stored where entry.valid { out[coin] = entry }
        for (coin, entry) in current where entry.valid {
            if let known = out[coin], entry.at < known.at { continue }
            out[coin] = entry
        }
        return out
    }

    /// Nur gültige Einträge, nicht älter als `maxAgeMillis` (Zeit in der Zukunft — Uhr verstellt —
    /// bleibt stehen), höchstens `maxEntries`, die neuesten zuerst behalten.
    static func prune(_ entries: [String: StoredPrice], now: Int64) -> [String: StoredPrice] {
        let kept = entries
            .filter { coin, entry in
                !coin.trimmingCharacters(in: .whitespaces).isEmpty && entry.valid && now - entry.at <= maxAgeMillis
            }
            .sorted { $0.value.at > $1.value.at }
            .prefix(maxEntries)
        var out: [String: StoredPrice] = [:]
        for (coin, entry) in kept { out[coin] = entry }
        return out
    }

    /// Nur bestätigte Kurse als Bezug (Kerzen-Prüfung): ohne «Kurs ungeprüft» (`StoredPrice.unverified`) — sonst
    /// bestätigte ein gemerkter Binance-Kurs eines womöglich fremden Tokens nach einem Ausfall dessen Kerzenkurs. Wie Android.
    static func confirmed(_ entries: [String: StoredPrice]) -> [String: StoredPrice] { entries.filter { !$0.value.unverified } }

    /// Coins aus `coins` mit «Kurs ungeprüft» laut `entries`; ein gekapselter Coin ohne eigenen Kurs, bewertet über seine
    /// Basis (`via`, `ChainAssets.priceViaBase`), erbt den Stand der Basis. Wie Android.
    static func unverified(_ coins: [String], entries: [String: StoredPrice], via: [String: String]) -> Set<String> {
        Set(coins.filter { coin in (entries[coin] ?? via[coin].flatMap { entries[$0] })?.unverified == true })
    }

    /// Zeilenformat: Kopfzeile, dann je Coin «COIN;Kurs;Zeit», bei «Kurs ungeprüft» «COIN;Kurs;Zeit;u».
    static func encode(_ entries: [String: StoredPrice]) -> String {
        var lines = [formatHeader]
        for (coin, entry) in entries.sorted(by: { $0.key < $1.key }) {
            guard entry.valid, !coin.isEmpty, !coin.contains(";"), !coin.contains("\n") else { continue }
            // «Kurs ungeprüft» als 4. Feld
            lines.append("\(coin);\(entry.price);\(entry.at)" + (entry.unverified ? ";\(unverifiedMark)" : ""))
        }
        return lines.joined(separator: "\n")
    }

    /// Liest `encode`; fremde Version oder kaputte Zeilen ergeben nichts bzw. fallen weg.
    static func decode(_ text: String?) -> [String: StoredPrice] {
        guard let text, !text.isEmpty else { return [:] }
        let lines = text.components(separatedBy: "\n")
        guard lines.first?.trimmingCharacters(in: .whitespaces) == formatHeader else { return [:] }
        var out: [String: StoredPrice] = [:]
        for line in lines.dropFirst() {
            let parts = line.trimmingCharacters(in: .whitespaces).components(separatedBy: ";")
            let marked = parts.count == 4 && parts[3].trimmingCharacters(in: .whitespaces) == unverifiedMark
            guard parts.count == 3 || marked,
                  let price = Double(parts[1]),
                  let at = Int64(parts[2])
            else { continue }
            let coin = parts[0].trimmingCharacters(in: .whitespaces)
            let entry = StoredPrice(price: price, at: at, unverified: marked)
            if !coin.isEmpty && entry.valid { out[coin] = entry }
        }
        return out
    }

    /// Älter als `staleAfterMillis`? Ohne Zeitpunkt (≤ 0) nie; Zeit in der Zukunft gilt als frisch.
    static func isStale(_ updatedAt: Int64, now: Int64, staleAfterMillis: Int64) -> Bool {
        updatedAt > 0 && now - updatedAt > staleAfterMillis
    }

    /// Zustand für die Status-Pille: ohne offene Coins nichts; ohne Netz «Offline» (mit Stand, falls
    /// es einen gibt); sonst ohne Kurs nichts, mit altem Kurs «veraltet», sonst «aktuell».
    static func state(updatedAt: Int64, now: Int64, staleAfterMillis: Int64, online: Bool,
                      hasOpenCoins: Bool) -> PortfolioPriceState {
        if !hasOpenCoins { return .none }
        if !online { return .offline }
        if updatedAt <= 0 { return .none }
        if isStale(updatedAt, now: now, staleAfterMillis: staleAfterMillis) { return .stale }
        return .fresh
    }

    /// Darf mit diesen Kursen eine neue Momentaufnahme (Widget, «heute», Wertverlauf, Alarme)
    /// entstehen? Nur mit frischen Kursen — ein gemerkter alter Stand würde sonst als «jetzt»
    /// aufgenommen und Verlauf wie Vergleichsbasis verfälschen.
    static func mayRecord(_ updatedAt: Int64, now: Int64, staleAfterMillis: Int64) -> Bool {
        updatedAt > 0 && !isStale(updatedAt, now: now, staleAfterMillis: staleAfterMillis)
    }

    /// Mindestanteil des Werts mit frischem Kurs für eine neue Aufnahme (`mayRecordShare`).
    static let minFreshShare = 0.8

    /// Anteil des aktuellen Werts mit frischem Kurs (je Coin geprüft, nicht der älteste für alle);
    /// USDT zählt immer als frisch. nil = kein Wert — wie Android.
    static func freshShare(values: [String: Double], times: [String: Int64], now: Int64, staleAfterMillis: Int64) -> Double? {
        var total = 0.0
        var fresh = 0.0
        for (coin, value) in values where value > 0 && value.isFinite {
            total += value
            let at = times[coin] ?? 0
            if !PortfolioStables.needsQuote(coin) || (at > 0 && !isStale(at, now: now, staleAfterMillis: staleAfterMillis)) {
                fresh += value
            }
        }
        return total > 0 ? fresh / total : nil
    }

    /// Neue Momentaufnahme (Widget, «heute», Verlauf) erlaubt, wenn mindestens `minFreshShare` des Werts frisch
    /// ist — ein einzelner Coin ohne frischen Kurs hält die übrigen nicht an; die Aufnahme trägt ihn dann als
    /// «teilweise alter Kurs» (`stalePrices`). Portfolio-Alarme prüfen nur ohne wesentliche alte Kurse
    /// (`PortfolioAlarmLogic.staleMaterial`). Ohne Wert: nur mit irgendeinem Kurs — wie Android.
    static func mayRecordShare(values: [String: Double], times: [String: Int64], now: Int64,
                               staleAfterMillis: Int64, anyPrice: Bool) -> Bool {
        guard let share = freshShare(values: values, times: times, now: now, staleAfterMillis: staleAfterMillis) else {
            return anyPrice
        }
        return share >= minFreshShare - 1e-9
    }

    /// `mayRecordShare` für ein Portfolio: Wert je offenem Coin mit den Abfragezeiten der Kurse.
    static func mayRecord(transactions: [PortfolioTx], prices: PortfolioPrices, now: Int64, staleAfterMillis: Int64) -> Bool {
        var values: [String: Double] = [:]
        for p in PortfolioCalculator.summarize(transactions, prices: prices.prices, withParts: false).open {
            if let v = p.value { values[PortfolioCalculator.normalizeCoin(p.coin)] = v }
        }
        return mayRecordShare(values: values, times: prices.times, now: now, staleAfterMillis: staleAfterMillis,
                              anyPrice: prices.updatedAt > 0)
    }

    /// Coins mit Wert (Schlüssel wie `values`, sortiert), deren Kurs NICHT frisch ist — USDT zählt immer als
    /// frisch. Leer = alle bewerteten Bestände frisch; sonst prüfen Portfolio-Alarme nur, wenn ihr Wert unter 1 % liegt;
    /// Widget und Verlauf zeigen den Wert, markiert als «teilweise alter Kurs». Wie Android.
    static func staleCoins(values: [String: Double], times: [String: Int64], now: Int64,
                           staleAfterMillis: Int64) -> [String] {
        var out: [String] = []
        for (coin, value) in values where value > 0 && value.isFinite && PortfolioStables.needsQuote(coin) {
            let at = times[coin] ?? 0
            let fresh = at > 0 && !isStale(at, now: now, staleAfterMillis: staleAfterMillis)
            if !fresh { out.append(coin) }
        }
        return out.sorted()
    }
}
