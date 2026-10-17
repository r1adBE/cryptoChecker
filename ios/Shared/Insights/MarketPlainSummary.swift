import Foundation

// «Kurz gesagt» oben im Markt-Tab: höchstens drei Sätze in Alltagssprache aus Daten, die der Tab
// ohnehin hat — Richtung des Gesamtmarkts, Stimmung, was heraussticht. Reine Regeln ohne Netz;
// die Sätze macht die Oberfläche (übersetzbar). Wie `MarketPlainSummary.kt` (Android).
//
// Grundsätze: nur beschreiben, was die Daten zeigen (keine Prognose, keine Empfehlung), neutrale
// Wörter für die Richtung, und lieber gar kein Satz als einer auf fehlenden oder veralteten Daten
// («veraltet» wie die Nebenzeilen der Karten: `DataFreshness.isStale`).

/// Eingaben; was fehlt, ist nil.
/// - `pulseMarketChange24h`: Veränderung der Marktkapitalisierung aller Coins über 24 h in %
///   (CoinGecko `/global`, mit dem Puls geholt), Stand `pulseStamp`
/// - `globalMarketChange24h`: derselbe Wert aus der Karte «Krypto-Markt» (eigener Abruf, Stand
///   `globalStamp`) — Ersatz, wenn der Puls ihn nicht hat
/// - `fearGreedTime`: Zeitpunkt des Index-Werts selbst (alternative.me: Tagesbeginn); nil = unbekannt
/// - `unusual`: «Heute auffällig», Stand `unusualStamp`
struct PlainSummaryInput: Equatable, Sendable {
    let now: Int64
    var pulseMarketChange24h: Double? = nil
    var pulseStamp: DataStamp? = nil
    var globalMarketChange24h: Double? = nil
    var globalStamp: DataStamp? = nil
    var fearGreed: Int? = nil
    var fearGreedTime: Int64? = nil
    var fearGreedStamp: DataStamp? = nil
    var unusual: UnusualReport? = nil
    var unusualStamp: DataStamp? = nil
}

/// Richtung des Gesamtmarkts über 24 h.
enum PlainDirectionKind: String, Equatable, Sendable {
    case flat = "FLAT", upSlight = "UP_SLIGHT", upClear = "UP_CLEAR", downSlight = "DOWN_SLIGHT", downClear = "DOWN_CLEAR"
}

/// Satz 1: Richtung samt Veränderung in % (mit Vorzeichen).
struct PlainDirection: Equatable, Sendable {
    let kind: PlainDirectionKind
    let changePercent: Double
}

/// Stimmung nach den Stufen der Fear-&-Greed-Karte.
enum PlainMoodKind: String, Equatable, Sendable {
    case extremeFear = "EXTREME_FEAR", fear = "FEAR", neutral = "NEUTRAL", greed = "GREED", extremeGreed = "EXTREME_GREED"
}

/// Satz 2: Stimmung samt Index-Wert 0–100.
struct PlainMood: Equatable, Sendable {
    let kind: PlainMoodKind
    let value: Int
}

/// Was heraussticht: die auffälligste Tatsache der obersten Zeile von «Heute auffällig» — oder nichts.
enum PlainStandoutKind: String, Equatable, Sendable {
    case aboveBtc = "ABOVE_BTC", belowBtc = "BELOW_BTC", againstBtc = "AGAINST_BTC", volume = "VOLUME"
    case futuresUp = "FUTURES_UP", futuresDown = "FUTURES_DOWN", quiet = "QUIET"
}

/// Satz 3. Bei `.quiet` sind alle Felder ausser `kind` leer.
/// - `coin`: Name des Coins (sonst sein Symbol)
/// - `change24h`, `btc24h`: 24-h-Veränderung von Coin und Bitcoin in % (für die Vergleiche mit Bitcoin)
/// - `volumeRatio`: Umsatz ÷ üblich (nur `.volume`)
struct PlainStandout: Equatable, Sendable {
    let kind: PlainStandoutKind
    var coin: String? = nil
    var change24h: Double? = nil
    var btc24h: Double? = nil
    var volumeRatio: Double? = nil
}

/// Die drei Sätze; nil = Satz entfällt. Alle nil = der Block entfällt.
struct PlainSummary: Equatable, Sendable {
    let direction: PlainDirection?
    let mood: PlainMood?
    let standout: PlainStandout?

    var isEmpty: Bool { direction == nil && mood == nil && standout == nil }
}

enum MarketPlainSummary {

    /// Darunter «kaum verändert» (Betrag in %).
    static let flatBelowPercent = 1.0

    /// Ab hier «deutlich» statt «etwas» (Betrag in %).
    static let clearFromPercent = 3.0

    /// Der Index-Wert selbst gilt so lange (er erscheint einmal am Tag mit dem Tagesbeginn als
    /// Zeitpunkt; zwei Tage decken Zeitzone und späte Veröffentlichung ab). Älter: Anbieter hängt.
    static let fearGreedMaxAgeMillis: Int64 = 48 * 60 * 60_000

    static func evaluate(_ input: PlainSummaryInput) -> PlainSummary {
        PlainSummary(direction: direction(input), mood: mood(input), standout: standout(input))
    }

    /// Satz 1 aus dem Puls, sonst aus der Karte «Krypto-Markt» — nur ein gültiger, frischer Wert zählt.
    static func direction(_ input: PlainSummaryInput) -> PlainDirection? {
        guard let change = usable(input.pulseMarketChange24h, input.pulseStamp, now: input.now)
            ?? usable(input.globalMarketChange24h, input.globalStamp, now: input.now)
        else { return nil }
        return PlainDirection(kind: directionKind(change), changePercent: change)
    }

    static func directionKind(_ change: Double) -> PlainDirectionKind {
        let size = abs(change)
        if size < flatBelowPercent { return .flat }
        if change > 0 { return size >= clearFromPercent ? .upClear : .upSlight }
        return size >= clearFromPercent ? .downClear : .downSlight
    }

    /// Satz 2: nur mit frischem Abruf und einem Index-Wert, der nicht älter als zwei Tage ist.
    static func mood(_ input: PlainSummaryInput) -> PlainMood? {
        guard let value = input.fearGreed, (0...100).contains(value),
              isFresh(input.fearGreedStamp, now: input.now) else { return nil }
        if let valueTime = input.fearGreedTime, input.now - valueTime > fearGreedMaxAgeMillis { return nil }
        return PlainMood(kind: moodKind(value), value: value)
    }

    /// Gleiche Stufen wie die Farbskala der Fear-&-Greed-Karte (`MarketScalePalette.level`).
    static func moodKind(_ value: Int) -> PlainMoodKind {
        if value < 25 { return .extremeFear }
        if value < 45 { return .fear }
        if value <= 55 { return .neutral }
        if value <= 75 { return .greed }
        return .extremeGreed
    }

    /// Satz 3 aus «Heute auffällig»: oberste Zeile (die Karte sortiert schon nach Auffälligkeit), ihre
    /// stärkste Tatsache. Leerer frischer Bericht → «nichts sticht heraus» (das ist eine Tatsache);
    /// fehlend oder veraltet → kein Satz.
    static func standout(_ input: PlainSummaryInput) -> PlainStandout? {
        guard let report = input.unusual, isFresh(input.unusualStamp, now: input.now) else { return nil }
        guard let row = report.rows.first else { return PlainStandout(kind: .quiet) }
        let name = row.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let coin = name.isEmpty ? row.symbol : name
        let fact = row.fact
        switch fact.kind {
        case .strongerThanBtc: return versusBtc(.aboveBtc, coin: coin, row: row, report: report)
        case .weakerThanBtc: return versusBtc(.belowBtc, coin: coin, row: row, report: report)
        case .againstMarket: return versusBtc(.againstBtc, coin: coin, row: row, report: report)
        case .volume:
            guard let ratio = fact.volumeRatio, ratio.isFinite, ratio > 0 else { return nil }
            return PlainStandout(kind: .volume, coin: coin, volumeRatio: ratio)
        case .fundingHigh: return PlainStandout(kind: .futuresUp, coin: coin)
        case .fundingNegative: return PlainStandout(kind: .futuresDown, coin: coin)
        }
    }

    /// Vergleich mit Bitcoin nur mit beiden Werten (ohne BTC-Wert gäbe es diese Tatsache gar nicht).
    private static func versusBtc(_ kind: PlainStandoutKind, coin: String, row: UnusualRow,
                                  report: UnusualReport) -> PlainStandout? {
        guard let btc = report.btc24h, btc.isFinite, row.change24h.isFinite else { return nil }
        return PlainStandout(kind: kind, coin: coin, change24h: row.change24h, btc24h: btc)
    }

    /// Wert nur, wenn er gültig ist und sein Stand bekannt und nicht veraltet.
    private static func usable(_ value: Double?, _ stamp: DataStamp?, now: Int64) -> Double? {
        guard let value, value.isFinite, isFresh(stamp, now: now) else { return nil }
        return value
    }

    /// Frisch genug für einen Satz: Stand bekannt und nicht veraltet (wie die Nebenzeilen der Karten).
    static func isFresh(_ stamp: DataStamp?, now: Int64) -> Bool {
        guard let stamp, stamp.savedAt > 0 else { return false }
        return !DataFreshness.isStale(savedAt: stamp.savedAt, now: now, ttl: stamp.ttl)
    }
}
