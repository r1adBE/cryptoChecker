import Foundation

/// Plausibilitätsprüfung für Kerzenkurse im Portfolio (Wertverlauf und Stichtag-Export) — Spiegel
/// von `PricePlausibility.kt`. Gleiche Schwelle und Idee wie die Prozent-Änderung
/// (`DayChange.maxPriceGap`): Weicht ein Kurs um mehr als 25 % vom Bezugskurs ab, gehören Kerzen
/// und Kurs wohl nicht zum selben Coin (gleiches Kürzel, anderer Token) — dann wird der
/// Kerzenkurs verworfen statt still verwendet.
///  - Wertverlauf und «7 T» (`closesMatchLive`): nur IDENTITÄTSPRÜFUNG mit weitem Faktor
///    (`tokenFactor`, Verhältnis ausserhalb 1/3…3 = anderer Token), NICHT die 25-%-Regel: Der
///    jüngste Schluss kann bis zu 12 h alt sein (Zwischenspeicher), ein echter Tag mit 30 % Bewegung
///    darf den Coin nicht aus dem Verlauf werfen. Der heutige Punkt nimmt ohnehin den aktuellen
///    Kurs. Passt die Reihe nicht, zählt sie nicht (Coin unter «ohne …», auch Stablecoins ausser USDT).
///  - Stichtag (`acceptSourceClose`): Kurs einer Quelle nur, wenn dieselbe Quelle den Coin heute
///    als denselben Token führt (ihr jüngster Schluss gegen den aktuellen Kurs, `sameToken`;
///    Ausweich-Quellen zusätzlich ±25 %); sonst nächste Quelle. Ohne aktuellen Kurs: nicht übernommen.
///  - Aktueller Kurs aus der letzten Stundenkerze (`candleReference`, `acceptCandle`): wenn
///    Binance-Spot den Coin nicht führt, nur bei gleichem Token wie ein unabhängiger Bezug
///    (Merkliste oder letzter guter Portfolio-Kurs); sonst kein Kurs («—»). Ohne jeden Bezug ebenfalls
///    kein Kurs: Der Coin heisst «ungeprüft» und zählt nirgends als Geld (Summe, «Seit Kauf», «Heute»,
///    Portfolio-Alarm) — gleiche Regel wie der Stichtag-Export.
///  - Ohne Bezugskurs: `matches` lässt den Kurs gelten (nicht prüfbar), die Identitätsprüfung
///    `sameToken` dagegen NICHT — ohne Bezug ist der gleiche Token nicht bestätigt. Wertverlauf/«7 T»:
///    Reihe ohne aktuellen Kurs zählt nicht (Coin unter «ohne …»), Stichtag ebenso («ungeprüft»),
///    aktueller Kurs aus der Kerze ebenso (`acceptCandle`).
enum PricePlausibility {
    /// Grösste erlaubte Abweichung (Anteil) — dieselbe wie bei der Prozent-Änderung.
    static let maxGap = DayChange.maxPriceGap

    /// Identitätsprüfung Kerzenreihe ↔ aktueller Kurs (`closesMatchLive`): Liegt das Verhältnis
    /// ausserhalb 1/`tokenFactor` … `tokenFactor`, ist es wohl ein anderer Token.
    static let tokenFactor = 3.0

    private static func valid(_ v: Double?) -> Double? {
        guard let v, v > 0, v.isFinite else { return nil }
        return v
    }

    /// Passt `value` zum Bezug `reference` (|value / reference − 1| ≤ `maxGap`)?
    /// Ungültiger Wert: nein. Ohne gültigen Bezug: ja (nicht prüfbar).
    static func matches(_ value: Double?, reference: Double?) -> Bool {
        guard let v = valid(value) else { return false }
        guard let r = valid(reference) else { return true }
        return abs(v / r - 1) <= maxGap
    }

    /// Gleicher Token? Verhältnis `value` / `reference` innerhalb 1/`tokenFactor` … `tokenFactor`.
    /// Ungültiger Wert: nein. Ohne gültigen Bezug: nein (gleicher Token nicht bestätigt).
    static func sameToken(_ value: Double?, reference: Double?) -> Bool {
        guard let v = valid(value) else { return false }
        guard let r = valid(reference) else { return false }
        let ratio = v / r
        return ratio >= 1 / tokenFactor && ratio <= tokenFactor
    }

    /// Wertverlauf: gehört die Kerzenreihe (aufsteigend nach Tag) zum aktuellen Kurs `live`?
    /// Nur Identitätsprüfung (`sameToken`) — echte grosse Tagesbewegungen bleiben im Verlauf.
    /// Leere Reihe: ja (nichts zu prüfen). Kein gültiger aktueller Kurs: nein — der Token ist nicht
    /// bestätigt, die Reihe zählt nicht (Coin unter «ohne …»).
    static func closesMatchLive(_ sortedCloses: [(day: Int, close: Double)], live: Double?) -> Bool {
        guard let last = sortedCloses.last?.close else { return true }
        return sameToken(live, reference: last)
    }

    /// Stichtag: Tagesschluss `close` einer Quelle übernehmen? Jede Quelle (auch Binance-Spot) muss den
    /// Coin heute als denselben Token führen wie das Portfolio: ihr jüngster Schluss `sourceLatest`
    /// gegen den aktuellen Portfolio-Kurs `current` — Binance-Spot mit der Identitätsprüfung
    /// (`sameToken`, 1/3…3, wie der Wertverlauf), Ausweich-Quellen zusätzlich mit ±25 % (`matches`).
    /// Streng: ohne gültigen aktuellen Kurs oder ohne heutigen Kurs der Quelle nicht übernommen
    /// (Export: Feld leer, Hinweis «ungeprüft»).
    /// - Parameters:
    ///   - primary: Binance-Spot (Quelle der aktuellen Portfolio-Kurse)
    ///   - sourceLatest: jüngster Schluss derselben Quelle für dasselbe Symbol (nil = unbekannt)
    ///   - current: aktueller Portfolio-Kurs des Coins in USDT (nil = unbekannt)
    static func acceptSourceClose(_ close: Double?, primary: Bool, sourceLatest: Double?, current: Double?) -> Bool {
        guard valid(close) != nil, let now = valid(current), let latest = valid(sourceLatest) else { return false }
        guard sameToken(latest, reference: now) else { return false }
        return primary || matches(now, reference: latest)
    }

    /// Unabhängiger Bezug für einen Portfolio-Kurs aus der letzten Stundenkerze (Binance-Spot führt
    /// den Coin nicht): der Median der Merklisten-Kurse desselben Coins gegen einen USD-Stablecoin
    /// (`watchPrices`, Spot — meist frischer), sonst der gemerkte letzte gute Portfolio-Kurs
    /// (`stored`, siehe `PortfolioPriceFallback`). Ungültige Werte zählen nicht; nil = kein Bezug.
    static func candleReference(watchPrices: [Double], stored: Double?) -> Double? {
        let watch = watchPrices.compactMap { valid($0) }.sorted()
        guard !watch.isEmpty else { return valid(stored) }
        let mid = watch.count / 2
        return watch.count % 2 == 1 ? watch[mid] : (watch[mid - 1] + watch[mid]) / 2
    }

    /// Kerzenkurs `candle` als aktuellen Portfolio-Kurs übernehmen? Nur gültig und vom selben Token
    /// wie der Bezug `reference` (`sameToken`, Verhältnis 1/3…3) — sonst kein Kurs («—») statt einer
    /// sicher wirkenden falschen Summe. Ohne Bezug (noch nie ein Kurs, keine passende Merkliste): NICHT
    /// übernommen — ein ungeprüfter Kurs zählt nicht als Geld (wie der Stichtag-Export). Die Quelle nennt
    /// den Coin dann «ungeprüft» (`candleUnchecked`); ein Merklisten-Paar an einer anderen Börse geht vor
    /// (`PortfolioWatchPair`). Wie Android.
    static func acceptCandle(_ candle: Double?, reference: Double?) -> Bool {
        sameToken(candle, reference: reference)
    }

    /// Gültiger Kerzenkurs, aber kein Bezug zum Prüfen → Coin ohne Kurs, «ungeprüft».
    static func candleUnchecked(_ candle: Double?, reference: Double?) -> Bool {
        valid(candle) != nil && valid(reference) == nil
    }
}
