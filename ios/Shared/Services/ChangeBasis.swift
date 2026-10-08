import Foundation
import SwiftUI

/// «Basis der %-Änderung» (Einstellungen › Darstellung, wie bei Binance «Change(%) & Chart
/// Timezone») — wie `ChangeBasis.kt`. Worauf sich Prozent-Pille, Puls-Zeile, Aktionsblatt,
/// Widgets und Live Activity beziehen; Alarme rechnen unabhängig davon.
///  - `ROLLING_24H`: rollende 24 Stunden (Standard).
///  - `LOCAL_DAY`: seit 00:00 Ortszeit (Zeitzone des Geräts, mit Sommerzeit).
///  - `utc(_:)`: seit 00:00 in einer festen Zone UTC−12 … UTC+14 (volle Stunden, ohne
///    Sommerzeit); `UTC_DAY` ist UTC+0.
/// Gespeichert und gesichert unter `rawValue` (Schlüssel «changeBasis», wie Android): «ROLLING_24H»,
/// «LOCAL_DAY», «UTC_DAY» (UTC+0) und «UTC_DAY+8» / «UTC_DAY-5». Ältere Versionen kennen die
/// Namen mit Versatz nicht und nehmen dann den Standard.
struct ChangeBasis: RawRepresentable, Hashable, Codable, CaseIterable, Sendable {
    enum Kind: Sendable { case rolling24h, localDay, utcDay }

    let kind: Kind
    /// Abstand zu UTC in Stunden, nur bei `utcDay`.
    let utcOffsetHours: Int

    private init(kind: Kind, utcOffsetHours: Int) {
        self.kind = kind
        self.utcOffsetHours = utcOffsetHours
    }

    /// Feste Zonen: UTC−12 … UTC+14 (wie Binance, volle Stunden).
    static let minOffset = -12
    static let maxOffset = 14

    static let ROLLING_24H = ChangeBasis(kind: .rolling24h, utcOffsetHours: 0)
    static let LOCAL_DAY = ChangeBasis(kind: .localDay, utcOffsetHours: 0)
    static let UTC_DAY = ChangeBasis(kind: .utcDay, utcOffsetHours: 0)

    static let `default`: ChangeBasis = .ROLLING_24H

    /// Seit 00:00 in UTC+`hours`; ausserhalb von `minOffset` … `maxOffset` nil.
    static func utc(_ hours: Int) -> ChangeBasis? {
        (minOffset...maxOffset).contains(hours) ? ChangeBasis(kind: .utcDay, utcOffsetHours: hours) : nil
    }

    /// Alle Möglichkeiten in der Reihenfolge der Auswahl: rollend, Gerät, dann UTC+14 … UTC−12.
    static let allCases: [ChangeBasis] =
        [.ROLLING_24H, .LOCAL_DAY] + stride(from: maxOffset, through: minOffset, by: -1).compactMap { utc($0) }

    /// Tages-Basis (seit 00:00)? Dann nie der rollende Ticker-Wert.
    var isDay: Bool { kind != .rolling24h }

    /// Gespeicherter Name, siehe Typ.
    var rawValue: String {
        switch kind {
        case .rolling24h: return "ROLLING_24H"
        case .localDay: return "LOCAL_DAY"
        case .utcDay:
            if utcOffsetHours > 0 { return "UTC_DAY+\(utcOffsetHours)" }
            if utcOffsetHours < 0 { return "UTC_DAY\(utcOffsetHours)" }
            return "UTC_DAY"
        }
    }

    /// Name → Basis; nil bei unbekanntem Namen oder Versatz ausserhalb des Bereichs.
    init?(rawValue: String) {
        switch rawValue {
        case "ROLLING_24H": self = .ROLLING_24H
        case "LOCAL_DAY": self = .LOCAL_DAY
        case "UTC_DAY": self = .UTC_DAY
        default:
            guard rawValue.hasPrefix("UTC_DAY") else { return nil }
            let rest = rawValue.dropFirst("UTC_DAY".count)
            // Nur «+n» oder «-n» mit Ziffern; «+0»/«-0» heisst «UTC_DAY»
            guard rest.count >= 2, let sign = rest.first, sign == "+" || sign == "-",
                  rest.dropFirst().allSatisfy({ $0.isASCII && $0.isNumber }),
                  let value = Int(rest.dropFirst()), value != 0,
                  let basis = ChangeBasis.utc(sign == "-" ? -value : value) else { return nil }
            self = basis
        }
    }

    /// Unbekannt oder fehlend (ältere Version, neuere Sicherung): Standard.
    static func from(name: String?) -> ChangeBasis {
        name.flatMap { ChangeBasis(rawValue: $0) } ?? .default
    }

    /// Unbekannte Werte (neuere Version) → Standard statt Fehler.
    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = ChangeBasis(rawValue: raw) ?? .default
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }
}

/// Mit welcher Basis und welchem Tagesbeginn die gespeicherten Veränderungen zuletzt (voller
/// Durchlauf) gerechnet wurden — wie `ChangeStamp` in Android. `dayStart` 0 bei rollend.
struct ChangeStamp: Codable, Equatable, Sendable {
    var basis: ChangeBasis
    var dayStart: Int64

    /// «UTC_DAY@1760054400000».
    var encoded: String { "\(basis.rawValue)@\(dayStart)" }

    /// nil bei fehlendem oder kaputtem Text.
    static func decode(_ text: String?) -> ChangeStamp? {
        guard let parts = text?.split(separator: "@", omittingEmptySubsequences: false), parts.count == 2,
              let basis = ChangeBasis(rawValue: String(parts[0])), let start = Int64(parts[1]) else { return nil }
        return ChangeStamp(basis: basis, dayStart: start)
    }
}

/// Gewählte Basis und ob die gespeicherten Werte noch dazu passen — sonst «—», bis neu gerechnet ist.
struct ChangeView: Equatable, Sendable {
    var basis: ChangeBasis = .default
    var current: Bool = true

    /// Veränderung zum Anzeigen: nil («—»), wenn die Werte nicht mehr zur Basis passen.
    func shown(_ change: Double?) -> Double? {
        guard current, let change, change.isFinite else { return nil }
        return change
    }

    static func of(stamp: ChangeStamp?, basis: ChangeBasis, now: Int64 = TimeUtils.nowMillis,
                   timeZone: TimeZone = .current) -> ChangeView {
        ChangeView(basis: basis, current: ChangeBasisMath.isCurrent(stamp: stamp, basis: basis, now: now, timeZone: timeZone))
    }

    /// Aus den gespeicherten Einstellungen und dem Stempel des letzten Durchlaufs (App und Widgets).
    static func stored(now: Int64 = TimeUtils.nowMillis) -> ChangeView {
        of(stamp: SharedStorage.changeStamp, basis: SharedStorage.loadSettings().changeBasis, now: now)
    }
}

/// Reine Regeln der %-Basis — wie `ChangeBasisMath` in Android (getestet dort in ChangeBasisTest).
///
/// Bezug für die Tages-Basen: Eröffnung der Stundenkerze, in der der Tagesbeginn liegt
/// (`openAt`) — bei ganzen Stunden genau die Kerze ab 00:00 (auch die laufende, wenn der Tag
/// gerade begonnen hat); Zonen mit halben Stunden nehmen die angebrochene Kerze davor (Näherung).
enum ChangeBasisMath {
    static let hourMillis: Int64 = 3_600_000
    static let dayMillis: Int64 = 24 * hourMillis
    /// Geladene Stundenkerzen: 24 für Mini-Chart und rollenden Bezug, zwei mehr für einen
    /// 25-Stunden-Tag (Ende der Sommerzeit) und angebrochene Stunden.
    static let candles = 26
    /// Davon Mini-Chart und rollender 24-h-Bezug (die jüngsten).
    static let rollingCandles = 24

    /// Floor-Division auch für negative Zeiten.
    private static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }

    /// Beginn des laufenden Tags (00:00) für `basis` zum Zeitpunkt `now`; nil bei rollend.
    /// Ortszeit: in `timeZone` mit Sommerzeit; fehlt 00:00 (Umstellung), die erste gültige Zeit.
    static func dayStart(_ basis: ChangeBasis, now: Int64, timeZone: TimeZone = .current) -> Int64? {
        switch basis.kind {
        case .rolling24h:
            return nil
        case .utcDay:
            // 00:00 in UTC+h liegt h Stunden vor 00:00 UTC
            let shift = Int64(basis.utcOffsetHours) * hourMillis
            return floorDiv(now + shift, dayMillis) * dayMillis - shift
        case .localDay:
            var cal = Calendar(identifier: .gregorian)
            cal.timeZone = timeZone
            let start = cal.startOfDay(for: Date(timeIntervalSince1970: Double(now) / 1000))
            return Int64((start.timeIntervalSince1970 * 1000).rounded())
        }
    }

    /// Tagesbeginne, deren Kerzen der Zwischenspeicher behält: UTC, Ortszeit und die gewählte
    /// Basis (ohne doppelte) — wie `keptDayStarts` in Android.
    static func keptDayStarts(_ selected: ChangeBasis, now: Int64, timeZone: TimeZone = .current) -> [Int64] {
        var result: [Int64] = []
        for basis in [ChangeBasis.UTC_DAY, .LOCAL_DAY, selected] {
            if let start = dayStart(basis, now: now, timeZone: timeZone), !result.contains(start) { result.append(start) }
        }
        return result
    }

    /// Zone als Text: «UTC», «UTC+8», «UTC-5», «UTC+5:30» (wie Binance und Android).
    static func zoneLabel(offsetSeconds: Int) -> String {
        if offsetSeconds == 0 { return "UTC" }
        let sign = offsetSeconds < 0 ? "-" : "+"
        let total = abs(offsetSeconds) / 60
        let hours = total / 60
        let minutes = total % 60
        return minutes == 0 ? "UTC\(sign)\(hours)" : "UTC\(sign)\(hours):" + String(format: "%02d", minutes)
    }

    /// Zone einer festen Basis («UTC+8»); nil bei rollend und Ortszeit.
    static func zoneLabel(_ basis: ChangeBasis) -> String? {
        basis.kind == .utcDay ? zoneLabel(offsetSeconds: basis.utcOffsetHours * 3600) : nil
    }

    /// Zone des Geräts zum Zeitpunkt `now` (mit Sommerzeit): «UTC+2».
    static func deviceZoneLabel(now: Int64, timeZone: TimeZone = .current) -> String {
        zoneLabel(offsetSeconds: timeZone.secondsFromGMT(for: Date(timeIntervalSince1970: Double(now) / 1000)))
    }

    /// Beginn der Stunde (UTC-Raster der Kerzen), in der `time` liegt.
    static func hourOf(_ time: Int64) -> Int64 { floorDiv(time, hourMillis) * hourMillis }

    /// Eröffnung der Stundenkerze, die `dayStart` enthält (`opens`: Startzeit → Eröffnung).
    static func openAt(_ opens: [Int64: Double], dayStart: Int64) -> Double? {
        guard let open = opens[hourOf(dayStart)], open.isFinite, open > 0 else { return nil }
        return open
    }

    /// Bezug seit Tagesbeginn: Eröffnung bei `dayStart` und letzter Schluss der Reihe.
    static func reference(_ opens: [Int64: Double], lastClose: Double?, dayStart: Int64) -> DayReference? {
        DayReference.of(open: openAt(opens, dayStart: dayStart), lastClose: lastClose)
    }

    /// Kerzen nötig? Tages-Basen immer, rollend nur ohne brauchbaren Ticker-Wert.
    static func needsCandles(_ basis: ChangeBasis, tickerChange: Double?) -> Bool {
        basis.isDay || DayChange.needsCandles(tickerChange)
    }

    /// Endgültiger Wert je Basis: rollend wie bisher (Ticker vor Kerzen), Tages-Basen nur aus Kerzen.
    static func choose(_ basis: ChangeBasis, tickerChange: Double?, candles: () -> Double?) -> Double? {
        guard basis.isDay else { return DayChange.choose(tickerChange: tickerChange, candles: candles) }
        guard let value = candles(), value.isFinite else { return nil }
        return value
    }

    /// Gelten die gespeicherten Werte noch? Ohne Stempel: rollend. Tages-Basen: gleicher Tagesbeginn.
    static func isCurrent(stamp: ChangeStamp?, basis: ChangeBasis, now: Int64, timeZone: TimeZone = .current) -> Bool {
        let s = stamp ?? ChangeStamp(basis: .ROLLING_24H, dayStart: 0)
        guard s.basis == basis else { return false }
        guard basis.isDay else { return true }
        return s.dayStart == dayStart(basis, now: now, timeZone: timeZone)
    }

    /// Stempel für einen Durchlauf mit `basis` zum Zeitpunkt `now`.
    static func stamp(_ basis: ChangeBasis, now: Int64, timeZone: TimeZone = .current) -> ChangeStamp {
        ChangeStamp(basis: basis, dayStart: dayStart(basis, now: now, timeZone: timeZone) ?? 0)
    }

    /// Stundenkerzen ab Tagesbeginn für den Chart «Heute»; weniger als zwei → die letzten zwei.
    static func sinceDayStart<T>(_ candles: [T], dayStart: Int64, openTime: (T) -> Int64) -> [T] {
        let from = hourOf(dayStart)
        let kept = candles.filter { openTime($0) >= from }
        return kept.count >= 2 ? kept : Array(candles.suffix(2))
    }
}

/// «Basis der %-Änderung» (Basis und ob die gespeicherten Werte noch passen) als Umgebungswert —
/// wie `LocalChangeView` in Android; Merkliste und Widgets setzen ihn, Pille, Puls, Blatt und
/// Widget-Zeilen lesen ihn.
private struct ChangeViewKey: EnvironmentKey {
    static let defaultValue = ChangeView()
}

extension EnvironmentValues {
    var changeView: ChangeView {
        get { self[ChangeViewKey.self] }
        set { self[ChangeViewKey.self] = newValue }
    }
}
