import Foundation

/// Wichtige US-Wirtschaftsdaten — wie `MacroEventType` (Android).
enum MacroEventType: String, Codable, CaseIterable, Sendable {
    case CPI, PPI, NFP, FOMC, PCE

    /// Schlüssel der Beschriftung («US-Inflationsdaten (CPI)» …).
    var labelKey: String {
        switch self {
        case .CPI: "macro_cpi"
        case .PPI: "macro_ppi"
        case .NFP: "macro_nfp"
        case .FOMC: "macro_fomc"
        case .PCE: "macro_pce"
        }
    }
}

/// Ein Termin; `time` = Zeitpunkt der Veröffentlichung (Epoch-ms).
struct MacroEvent: Codable, Equatable, Hashable, Sendable {
    let type: MacroEventType
    let time: Int64
}

struct MacroHintItem: Equatable, Sendable {
    let event: MacroEvent
    /// Termin liegt am nächsten Kalendertag (Ortszeit).
    let tomorrow: Bool
}

/// Hinweis «Wirtschaftsdaten»; `released` = alle Termine mehr als 2 h vorbei.
struct MacroHint: Equatable, Sendable {
    let items: [MacroHintItem]
    let released: Bool

    var allToday: Bool { items.allSatisfy { !$0.tomorrow } }
    var allTomorrow: Bool { items.allSatisfy { $0.tomorrow } }
}

/// Kalender wichtiger US-Wirtschaftsdaten — reine Logik, wie `MacroCalendar.kt` (dort mit Tests).
/// Format: `{"version":1,"generated":"…","source":"…","events":[{"type":"CPI","time":"2026-10-14T12:30:00Z"},…]}`.
enum MacroCalendar {
    static let url = "https://r1adbe.github.io/cryptoChecker/macro/events.json"
    static let ttlMillis: Int64 = 24 * 60 * 60_000
    static let lookaheadMillis: Int64 = 18 * 60 * 60_000
    static let releasedAfterMillis: Int64 = 2 * 60 * 60_000
    static let maxAgeMillis: Int64 = 2 * 24 * 60 * 60_000
    /// Morgen-Mitteilung um 08:00 Ortszeit.
    static let notifyHour = 8

    /// Datei lesen; nil, wenn kein gültiges Kalender-Objekt. Unbekannte Typen und ungültige Zeiten
    /// werden übergangen, Termine älter als 2 Tage verworfen. Sortiert, ohne Doppelte.
    static func parse(_ text: String, now: Int64) -> [MacroEvent]? {
        guard let data = text.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let version = (root["version"] as? NSNumber)?.intValue, version >= 1,
              let events = root["events"] as? [Any] else { return nil }
        var seen = Set<MacroEvent>()
        var out: [MacroEvent] = []
        for item in events {
            guard let o = item as? [String: Any],
                  let typeName = (o["type"] as? String)?.trimmingCharacters(in: .whitespaces).uppercased(),
                  let type = MacroEventType(rawValue: typeName),
                  let timeText = o["time"] as? String,
                  let time = parseInstant(timeText),
                  time >= now - maxAgeMillis else { continue }
            let event = MacroEvent(type: type, time: time)
            if seen.insert(event).inserted { out.append(event) }
        }
        let order = MacroEventType.allCases
        return out.sorted { a, b in
            if a.time != b.time { return a.time < b.time }
            return (order.firstIndex(of: a.type) ?? 0) < (order.firstIndex(of: b.type) ?? 0)
        }
    }

    /// ISO-8601-Zeitpunkt («2026-10-14T12:30:00Z», auch mit Versatz oder Sekundenbruchteilen).
    static func parseInstant(_ text: String) -> Int64? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        if let date = plain.date(from: trimmed) { return Int64((date.timeIntervalSince1970 * 1000).rounded()) }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: trimmed) { return Int64((date.timeIntervalSince1970 * 1000).rounded()) }
        return nil
    }

    /// Termine von heute (Ortsdatum) und solche in den nächsten 18 h; nil = kein Hinweis.
    static func hint(_ events: [MacroEvent], now: Int64, calendar: Calendar = .current) -> MacroHint? {
        let nowDate = date(now)
        let items: [MacroHintItem] = events
            .filter { e in
                calendar.isDate(date(e.time), inSameDayAs: nowDate)
                    || (e.time > now && e.time - now <= lookaheadMillis)
            }
            .sorted { $0.time < $1.time }
            .map { e in
                MacroHintItem(event: e, tomorrow: !calendar.isDate(date(e.time), inSameDayAs: nowDate) && e.time > now)
            }
        guard !items.isEmpty else { return nil }
        let released = items.allSatisfy { now >= $0.event.time + releasedAfterMillis }
        return MacroHint(items: items, released: released)
    }

    /// Termine für die Morgen-Mitteilung eines Tages (`day` = beliebiger Zeitpunkt dieses Tages):
    /// am selben Ortsdatum und nach 08:00.
    static func eventsOnDay(_ events: [MacroEvent], day: Date, calendar: Calendar = .current) -> [MacroEvent] {
        guard let notifyAt = calendar.date(bySettingHour: notifyHour, minute: 0, second: 0, of: day) else { return [] }
        let after = Int64(notifyAt.timeIntervalSince1970 * 1000)
        return events.filter { calendar.isDate(date($0.time), inSameDayAs: day) && $0.time > after }
            .sorted { $0.time < $1.time }
    }

    static func isFresh(savedAt: Int64, now: Int64) -> Bool {
        savedAt > 0 && savedAt <= now && now - savedAt < ttlMillis
    }

    static func date(_ millis: Int64) -> Date { Date(timeIntervalSince1970: Double(millis) / 1000) }

    // MARK: Texte (wie `MacroTexts.kt`)

    static func label(_ type: MacroEventType) -> String { L(type.labelKey) }

    /// Uhrzeit in der Zeitzone des Geräts, im Format der Sprache (12/24 h wie im System).
    static func time(_ millis: Int64) -> String {
        date(millis).formatted(date: .omitted, time: .shortened)
    }

    /// Ein Satz für den Hinweis, z. B. «Heute 14:30: US-Inflationsdaten (CPI) – an solchen Tagen …».
    static func hintText(_ hint: MacroHint) -> String {
        let items = hint.items
        if hint.released {
            if items.count == 1 {
                return L("macro_released_one", time(items[0].event.time), label(items[0].event.type))
            }
            return L("macro_released_list", list(items.map(\.event)))
        }
        if items.count == 1 {
            let e = items[0].event
            return L(items[0].tomorrow ? "macro_hint_tomorrow" : "macro_hint_today", time(e.time), label(e.type))
        }
        if hint.allToday { return L("macro_hint_today_list", list(items.map(\.event))) }
        if hint.allTomorrow { return L("macro_hint_tomorrow_list", list(items.map(\.event))) }
        let parts = items.map { item in
            L(item.tomorrow ? "macro_item_tomorrow" : "macro_item_today", time(item.event.time), label(item.event.type))
        }
        return L("macro_hint_list", parts.joined(separator: " · "))
    }

    /// Text der Morgen-Mitteilung: die Termine des Tages.
    static func todayText(_ events: [MacroEvent]) -> String {
        if events.count == 1 {
            return L("macro_hint_today", time(events[0].time), label(events[0].type))
        }
        return L("macro_hint_today_list", list(events))
    }

    private static func list(_ events: [MacroEvent]) -> String {
        events.map { L("macro_item", time($0.time), label($0.type)) }.joined(separator: " · ")
    }
}
