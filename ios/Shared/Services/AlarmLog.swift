import Foundation

/// Ergebnis einer Alarm-Prüfung im Alarm-Protokoll (`AlarmLog`); `rawValue` steht in der Datei und im gemeinsamen
/// Testfall `alarm_log.json` (gleich auf Android, `AlarmLogOutcome`).
enum AlarmLogOutcome: String, CaseIterable, Sendable {
    /// Gemeldet und gespeichert.
    case FIRED = "fired"
    /// Bedingung nicht erfüllt (auch: Bezug eben gesetzt, Kursmarke wieder scharf gestellt).
    case NOT_REACHED = "not_reached"
    /// Kursmarke schon gemeldet: wartet, bis der Kurs auf die andere Seite zurück ist.
    case ALREADY_TRIGGERED = "already_triggered"
    /// Erfüllt, aber Ruhezeit nach der letzten Meldung (bzw. nach einer unbestätigten Meldung).
    case COOLDOWN = "cooldown"
    /// Kurs laut Börse älter als `AlarmFreshness.exchangeStaleMillis`.
    case STALE = "stale"
    /// Nur der Mark-Preis statt eines Handelskurses.
    case MARK_PRICE = "mark_price"
    /// DEX-Pool unter der Alarm-Liquidität oder ohne Angabe (`DexLiquidity`).
    case LOW_LIQUIDITY = "low_liquidity"
    /// Erfüllt, aber Nachtruhe: wartet bis zum Ende (`QuietHours.holdsAlarms`).
    case QUIET_HOURS = "quiet_hours"
    /// Erfüllt, aber «1 h später» aus der Mitteilung läuft noch (`AlarmSnooze`).
    case SNOOZED = "snoozed"
    /// Erfüllt, aber keine zustellbare Mitteilung (Erlaubnis bzw. Mitteilungen aus).
    case NOTIFICATIONS_OFF = "notifications_off"
    /// Erfüllt und gemeldet, die Mitteilung kam aber nicht an (Neuversuch nach der Abklingzeit).
    case NOT_SHOWN = "not_shown"
    /// Schwelle in anderer Währung, aber kein Devisenkurs: diesmal nicht geprüft.
    case NO_RATE = "no_rate"
    /// Kein Kurs: Abfrage bei der Börse fehlgeschlagen.
    case NO_PRICE = "no_price"
    /// Schwelle in anderer Währung, Devisenkurs älter als 36 h Bankarbeitszeit: diesmal nicht geprüft (bleibt scharf).
    case FX_STALE = "fx_stale"
    /// Schwelle in anderer Währung, USDT/USD nur über USDC und USDC/USDT mehr als 1 % neben 1: diesmal nicht geprüft.
    case FX_UNSURE = "fx_unsure"
}

/// Was die Prüfung eines Alarms an den bestehenden Entscheidungsstellen gesehen hat (nur beobachtet — die Entscheidung
/// selbst fällt unverändert in `AlarmEvaluator`, `AlarmFreshness`, `AlarmDelivery` usw.). `AlarmLog.outcome` macht daraus
/// genau ein Ergebnis; welche Stelle zuerst greift, legt allein diese Reihenfolge fest. Wie `AlarmLogTrace` in `AlarmLog.kt`.
struct AlarmLogTrace: Equatable, Sendable {
    var noPrice = false
    var lowLiquidity = false
    var markPrice = false
    var stale = false
    var noRate = false
    /// Devisenkurs zu alt (`FiatAlarmGuard.Skip.FX_STALE`).
    var fxStale = false
    /// USDT/USD unsicher (`FiatAlarmGuard.Skip.UNSURE`).
    var fxUnsure = false
    /// Bedingung erfüllt, der Alarm wäre fällig (bei einer Sperre: Vorschau mit denselben reinen Regeln).
    var due = false
    /// Nicht fällig nur wegen der Ruhezeit nach der letzten Meldung (Bedingung selbst erfüllt).
    var coolingDown = false
    /// Kursmarke schon gemeldet, noch nicht wieder scharf.
    var waitingRearm = false
    var quietHours = false
    /// «1 h später» läuft (`AlarmSnooze.isSnoozed`).
    var snoozed = false
    var notificationsOff = false
    /// Unbestätigte Meldung: Neuversuch erst nach der Abklingzeit.
    var retryBlocked = false
    var notShown = false
}

/// Eine Prüfung eines Alarms, wie sie im Protokoll steht (Paar und Börse als Text — bleibt auch nach dem Löschen lesbar).
struct AlarmLogEntry: Equatable, Sendable {
    var alarmId: Int64
    var at: Int64
    /// Paar wie in der Merkliste, z. B. «BTC/CHF».
    var pair: String
    var exchange: String
    var quote: String
    /// Gesehener Kurs in der Quote; nil ohne Kurs.
    var price: Double?
    var condition: AlarmCondition
    var threshold: Double
    var windowHours: Int = 1
    /// Währung der Schwelle, wenn sie nicht die Quote ist (Kursmarke in eigener Währung).
    var currency: String? = nil
    /// Kurs in `currency` (umgerechnet); nil ohne Umrechnung.
    var shownPrice: Double? = nil
    var outcome: AlarmLogOutcome
    /// Zeit des Kurses laut Börse (für «Kurs veraltet (Stand …)»).
    var exchangeTime: Int64? = nil
    /// Kommt ins Protokoll (nicht nur «zuletzt geprüft»): ausgelöst oder zurückgehalten, obwohl fällig.
    var notable = false
    /// Gleiches Ergebnis mehrmals hintereinander: zuletzt gesehen um (0 = nur einmal).
    var until: Int64 = 0
    /// Wer geprüft hat (App, Hintergrund-Aufgabe, Widget); nil = unbekannt (ältere Einträge).
    var source: CheckTrigger? = nil

    /// Letzte Prüfung dieses Eintrags.
    var lastAt: Int64 { Swift.max(at, until) }
}

/// Stand des Protokolls: je Alarm die letzte Prüfung und die Liste bemerkenswerter Prüfungen (neueste zuerst).
struct AlarmLogState: Equatable, Sendable {
    var lastChecks: [Int64: AlarmLogEntry] = [:]
    var events: [AlarmLogEntry] = []

    static let empty = AlarmLogState()
}

/// Alarm-Protokoll: beantwortet «Warum hat es nicht gepiepst?». Zwei Teile — je Kursalarm die letzte Prüfung
/// (bei jeder Prüfung überschrieben) und ein Ringpuffer mit höchstens `maxEvents` bemerkenswerten Prüfungen der letzten
/// `retentionMillis` (ausgelöst bzw. zurückgehalten, obwohl fällig — nicht jedes «nicht erreicht»). Nur auf dem Gerät,
/// nie in einem Geräte-Backup. Reine Logik — wie `AlarmLog.kt`.
enum AlarmLog {
    static let maxEvents = 200
    static let retentionMillis: Int64 = 14 * 24 * 60 * 60_000
    /// Live-Kurse kommen sekündlich: dann höchstens so oft schreiben (ein neuer Protokoll-Eintrag sofort, `startsEvent`).
    static let liveWriteIntervalMillis: Int64 = 60_000
    static let formatVersion = 1

    /// Erfasste Alarme: Kursmarken und Prozent-Alarme (die übrigen brauchen Zusatzdaten mit eigenen Gründen).
    static func covers(_ condition: AlarmCondition) -> Bool {
        switch condition {
        case .PRICE_ABOVE, .PRICE_BELOW, .CHANGE_PERCENT_UP, .CHANGE_PERCENT_DOWN, .MOVE_PERCENT_WINDOW: return true
        default: return false
        }
    }

    /// Ein Ergebnis aus der beobachteten Prüfung — feste Reihenfolge, beide Plattformen gleich.
    static func outcome(_ t: AlarmLogTrace) -> AlarmLogOutcome {
        if t.noPrice { return .NO_PRICE }
        if t.lowLiquidity { return .LOW_LIQUIDITY }
        if t.markPrice { return .MARK_PRICE }
        if t.stale { return .STALE }
        if t.noRate { return .NO_RATE }
        if t.fxStale { return .FX_STALE }
        if t.fxUnsure { return .FX_UNSURE }
        if t.due {
            if t.quietHours { return .QUIET_HOURS }
            if t.snoozed { return .SNOOZED }
            if t.notificationsOff { return .NOTIFICATIONS_OFF }
            if t.retryBlocked { return .COOLDOWN }
            if t.notShown { return .NOT_SHOWN }
            return .FIRED
        }
        if t.coolingDown { return .COOLDOWN }
        if t.waitingRearm { return .ALREADY_TRIGGERED }
        return .NOT_REACHED
    }

    /// Ins Protokoll? Ausgelöst, oder zurückgehalten, obwohl die Bedingung erfüllt war.
    static func notable(_ outcome: AlarmLogOutcome, _ trace: AlarmLogTrace) -> Bool {
        switch outcome {
        case .FIRED: return true
        case .LOW_LIQUIDITY, .MARK_PRICE, .STALE, .FX_STALE, .FX_UNSURE: return trace.due
        case .QUIET_HOURS, .SNOOZED, .NOTIFICATIONS_OFF, .NOT_SHOWN, .COOLDOWN: return true
        case .NOT_REACHED, .ALREADY_TRIGGERED, .NO_RATE, .NO_PRICE: return false
        }
    }

    /// Ablauf «fälligen Alarm melden» (`AlarmDelivery.deliver`) von aussen beobachtet: `canShow` bzw. `shown` = was die
    /// Schritte geliefert haben (nil = nicht erreicht). nil zurück = gemeldet, aber nicht gespeichert (Alarm inzwischen
    /// bearbeitet oder gelöscht, Mitteilung zurückgezogen) — dann kein Eintrag. `snoozed`: «1 h später» lief.
    static func deliveryTrace(holdAlarms: Bool, canShow: Bool?, shown: Bool?, saved: Bool,
                              snoozed: Bool = false) -> AlarmLogTrace? {
        if holdAlarms { return AlarmLogTrace(due: true, quietHours: true) }
        if snoozed { return AlarmLogTrace(due: true, snoozed: true) }
        if canShow == false { return AlarmLogTrace(due: true, notificationsOff: true) }
        guard let shown else { return AlarmLogTrace(due: true, retryBlocked: true) }
        if !shown { return AlarmLogTrace(due: true, notShown: true) }
        return saved ? AlarmLogTrace(due: true) : nil
    }

    /// Eintrag mit Ergebnis und «bemerkenswert» aus `trace`.
    static func resolved(_ entry: AlarmLogEntry, _ trace: AlarmLogTrace) -> AlarmLogEntry {
        var e = entry
        e.outcome = outcome(trace)
        e.notable = notable(e.outcome, trace)
        return e
    }

    /// Prüfungen übernehmen: je Alarm die neueste als letzte Prüfung; bemerkenswerte vorne ins Protokoll. Dasselbe
    /// Ergebnis desselben Alarms gleich hintereinander (z. B. jede Prüfung der Nacht «Nachtruhe») wird zu einem Eintrag
    /// «von … bis …» zusammengefasst; Auslösungen bleiben immer einzeln. Wie `AlarmLog.record` (Android).
    static func record(_ state: AlarmLogState, _ entries: [AlarmLogEntry], now: Int64) -> AlarmLogState {
        if entries.isEmpty { return AlarmLogState(lastChecks: state.lastChecks, events: prune(state.events, now: now)) }
        var last = state.lastChecks
        var events = state.events
        for entry in stableSorted(entries, by: { $0.at < $1.at }) {
            if let previous = last[entry.alarmId] {
                if entry.at >= previous.at { last[entry.alarmId] = entry }
            } else {
                last[entry.alarmId] = entry
            }
            guard entry.notable else { continue }
            if let index = events.firstIndex(where: { $0.alarmId == entry.alarmId }), collapses(events[index], entry) {
                var merged = entry
                merged.at = events[index].at
                merged.until = entry.at
                events.remove(at: index)
                events.insert(merged, at: 0)
            } else {
                events.insert(entry, at: 0)
            }
        }
        return AlarmLogState(lastChecks: last, events: prune(events, now: now))
    }

    /// Beginnen `entries` auf `state` einen neuen Protokoll-Eintrag (Auslösung oder ein anderes Ergebnis als zuletzt)?
    /// Verlängern sie nur ein bestehendes «von … bis …», nicht — dann schreiben Live-Kurse gedrosselt wie jede andere
    /// Prüfung (sonst schriebe eine anhaltende Sperre die Datei jede Sekunde neu). Gleiche Zusammenfassung wie `record`;
    /// wie `AlarmLog.startsEvent` (Android).
    static func startsEvent(_ state: AlarmLogState, _ entries: [AlarmLogEntry]) -> Bool {
        var latest: [Int64: AlarmLogEntry] = [:]
        for event in state.events where latest[event.alarmId] == nil { latest[event.alarmId] = event }
        for entry in stableSorted(entries, by: { $0.at < $1.at }) where entry.notable {
            guard let old = latest[entry.alarmId], collapses(old, entry) else { return true }
            var merged = entry
            merged.at = old.at
            merged.until = entry.at
            latest[entry.alarmId] = merged
        }
        return false
    }

    private static func collapses(_ old: AlarmLogEntry, _ new: AlarmLogEntry) -> Bool {
        new.outcome != .FIRED && old.outcome == new.outcome && old.condition == new.condition
            && old.threshold == new.threshold && old.currency == new.currency && new.at >= old.lastAt
    }

    /// Älter als `retentionMillis` fällt weg; höchstens `maxEvents`, neueste (zuletzt gesehen) zuerst.
    static func prune(_ events: [AlarmLogEntry], now: Int64) -> [AlarmLogEntry] {
        let kept = events.filter { now - $0.lastAt <= retentionMillis }
        return Array(stableSorted(kept, by: { $0.lastAt > $1.lastAt }).prefix(maxEvents))
    }

    /// Sortieren mit fester Reihenfolge bei Gleichstand (wie `sortedBy` in Kotlin).
    private static func stableSorted(_ items: [AlarmLogEntry],
                                     by less: (AlarmLogEntry, AlarmLogEntry) -> Bool) -> [AlarmLogEntry] {
        items.enumerated().sorted { a, b in
            if less(a.element, b.element) { return true }
            if less(b.element, a.element) { return false }
            return a.offset < b.offset
        }.map(\.element)
    }

    /// Einträge eines Durchlaufs mit seiner Herkunft `source` versehen (schon gesetzte bleiben). Wie Android.
    static func withSource(_ entries: [AlarmLogEntry], _ source: CheckTrigger) -> [AlarmLogEntry] {
        entries.map { entry in
            guard entry.source == nil else { return entry }
            var e = entry
            e.source = source
            return e
        }
    }

    /// Alarm gelöscht: seine letzte Prüfung fällt weg (Protokoll-Einträge bleiben mit ihrem Text).
    static func removeAlarm(_ state: AlarmLogState, _ alarmId: Int64) -> AlarmLogState {
        var next = state
        next.lastChecks[alarmId] = nil
        return next
    }

    /// Nur letzte Prüfungen bestehender Alarme behalten (Paar samt Alarmen gelöscht).
    static func retainAlarms(_ state: AlarmLogState, _ alarmIds: Set<Int64>) -> AlarmLogState {
        var next = state
        next.lastChecks = state.lastChecks.filter { alarmIds.contains($0.key) }
        return next
    }

    /// «Protokoll löschen»: die Liste leeren; die letzten Prüfungen bleiben (sie gehören zu den Alarmen).
    static func clearEvents(_ state: AlarmLogState) -> AlarmLogState {
        AlarmLogState(lastChecks: state.lastChecks, events: [])
    }

    // MARK: Datei (JSON; Schlüssel gleich auf Android)

    static func encode(_ state: AlarmLogState) -> Data? {
        let last = state.lastChecks.values.sorted { $0.alarmId < $1.alarmId }.map(encodeEntry)
        let root: [String: Any] = ["v": formatVersion, "last": last, "events": state.events.map(encodeEntry)]
        return try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
    }

    /// Unlesbares fällt weg (leerer Stand bzw. einzelne Einträge); höchstens `maxEvents` Einträge.
    static func decode(_ data: Data?) -> AlarmLogState {
        guard let data, !data.isEmpty,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              (root["v"] as? NSNumber)?.intValue == formatVersion else { return .empty }
        var last: [Int64: AlarmLogEntry] = [:]
        for raw in root["last"] as? [Any] ?? [] {
            guard let entry = decodeEntry(raw as? [String: Any]) else { continue }
            if let previous = last[entry.alarmId], previous.at > entry.at { continue }
            last[entry.alarmId] = entry
        }
        var events: [AlarmLogEntry] = []
        for raw in root["events"] as? [Any] ?? [] {
            if events.count >= maxEvents { break }
            if let entry = decodeEntry(raw as? [String: Any]) { events.append(entry) }
        }
        return AlarmLogState(lastChecks: last, events: events)
    }

    static func decode(_ text: String?) -> AlarmLogState { decode(text?.data(using: .utf8)) }

    private static func encodeEntry(_ e: AlarmLogEntry) -> [String: Any] {
        var o: [String: Any] = [
            "id": e.alarmId, "at": e.at, "pair": e.pair, "ex": e.exchange, "quote": e.quote,
            "cond": e.condition.rawValue, "win": e.windowHours, "out": e.outcome.rawValue,
        ]
        if e.threshold.isFinite { o["thr"] = e.threshold }
        if let price = e.price, price.isFinite { o["price"] = price }
        if let currency = e.currency { o["cur"] = currency }
        if let shown = e.shownPrice, shown.isFinite { o["shown"] = shown }
        if let exchangeTime = e.exchangeTime { o["exTime"] = exchangeTime }
        if e.notable { o["n"] = true }
        if e.until > 0 { o["until"] = e.until }
        if let source = e.source { o["src"] = source.rawValue }
        return o
    }

    private static func number(_ value: Any?) -> Double? {
        guard let n = value as? NSNumber else { return nil }
        let d = n.doubleValue
        return d.isFinite ? d : nil
    }

    private static func int64(_ value: Any?) -> Int64? { (value as? NSNumber)?.int64Value }

    private static func decodeEntry(_ o: [String: Any]?) -> AlarmLogEntry? {
        guard let o, let id = int64(o["id"]), let at = int64(o["at"]), at > 0,
              let condition = AlarmCondition(rawValue: o["cond"] as? String ?? ""),
              let outcome = AlarmLogOutcome(rawValue: o["out"] as? String ?? ""),
              let threshold = number(o["thr"]) else { return nil }
        let currency = (o["cur"] as? String).flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
        return AlarmLogEntry(
            alarmId: id, at: at,
            pair: o["pair"] as? String ?? "", exchange: o["ex"] as? String ?? "", quote: o["quote"] as? String ?? "",
            price: number(o["price"]), condition: condition, threshold: threshold,
            windowHours: (o["win"] as? NSNumber)?.intValue ?? 1,
            currency: currency, shownPrice: number(o["shown"]), outcome: outcome,
            exchangeTime: int64(o["exTime"]).flatMap { $0 > 0 ? $0 : nil },
            notable: (o["n"] as? Bool) ?? false,
            until: Swift.max(int64(o["until"]) ?? 0, 0),
            source: (o["src"] as? String).flatMap { CheckTrigger(rawValue: $0) })
    }
}
