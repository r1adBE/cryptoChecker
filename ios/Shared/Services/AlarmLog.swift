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
    /// Erfüllt, aber keine zustellbare Mitteilung (Erlaubnis bzw. Mitteilungen aus).
    case NOTIFICATIONS_OFF = "notifications_off"
    /// Erfüllt und gemeldet, die Mitteilung kam aber nicht an (Neuversuch nach der Abklingzeit).
    case NOT_SHOWN = "not_shown"
    /// Schwelle in anderer Währung, aber kein Devisenkurs: diesmal nicht geprüft.
    case NO_RATE = "no_rate"
    /// Kein Kurs: Abfrage bei der Börse fehlgeschlagen.
    case NO_PRICE = "no_price"
}

/// Was die Prüfung eines Alarms an den bestehenden Entscheidungsstellen gesehen hat (nur beobachtet — die Entscheidung
/// selbst fällt unverändert in `AlarmEvaluator`, `AlarmFreshness`, `AlarmDelivery` usw.). `AlarmLog.outcome` macht daraus
/// genau ein Ergebnis; welche Stelle zuerst greift, legt allein diese Reihenfolge fest. Wie `AlarmLogTrace.kt`.
struct AlarmLogTrace: Equatable, Sendable {
    var noPrice = false
    var lowLiquidity = false
    var markPrice = false
    var stale = false
    var noRate = false
    /// Bedingung erfüllt, der Alarm wäre fällig (bei einer Sperre: Vorschau mit denselben reinen Regeln).
    var due = false
    /// Nicht fällig nur wegen der Ruhezeit nach der letzten Meldung (Bedingung selbst erfüllt).
    var coolingDown = false
    /// Kursmarke schon gemeldet, noch nicht wieder scharf.
    var waitingRearm = false
    var quietHours = false
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

    /// Letzte Prüfung dieses Eintrags.
    var lastAt: Int64 { Swift.max(at, until) }
}

/// Stand des Protokolls: je Alarm die letzte Prüfung und die Liste bemerkenswerter Prüfungen (neueste zuerst).
struct AlarmLogState: Equatable, Sendable {
    var lastChecks: [Int64: AlarmLogEntry] = [:]
    var events: [AlarmLogEntry] = []

    static let empty = AlarmLogState()
}

/// Alarm-Protokoll (Build 80): beantwortet «Warum hat es nicht gepiepst?». Zwei Teile — je Kursalarm die letzte Prüfung
/// (bei jeder Prüfung überschrieben) und ein Ringpuffer mit höchstens `maxEvents` bemerkenswerten Prüfungen der letzten
/// `retentionMillis` (ausgelöst bzw. zurückgehalten, obwohl fällig — nicht jedes «nicht erreicht»). Nur auf dem Gerät,
/// nie in einem Geräte-Backup. Reine Logik — wie `AlarmLog.kt`.
enum AlarmLog {
    static let maxEvents = 200
    static let retentionMillis: Int64 = 14 * 24 * 60 * 60_000
    /// Live-Kurse kommen sekündlich: dann höchstens so oft schreiben (bemerkenswerte Einträge sofort).
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
        if t.due {
            if t.quietHours { return .QUIET_HOURS }
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
        case .LOW_LIQUIDITY, .MARK_PRICE, .STALE: return trace.due
        case .QUIET_HOURS, .NOTIFICATIONS_OFF, .NOT_SHOWN, .COOLDOWN: return true
        case .NOT_REACHED, .ALREADY_TRIGGERED, .NO_RATE, .NO_PRICE: return false
        }
    }

    /// Ablauf «fälligen Alarm melden» (`AlarmDelivery.deliver`) von aussen beobachtet: `canShow` bzw. `shown` = was die
    /// Schritte geliefert haben (nil = nicht erreicht). nil zurück = gemeldet, aber nicht gespeichert (Alarm inzwischen
    /// bearbeitet oder gelöscht, Mitteilung zurückgezogen) — dann kein Eintrag.
    static func deliveryTrace(holdAlarms: Bool, canShow: Bool?, shown: Bool?, saved: Bool) -> AlarmLogTrace? {
        if holdAlarms { return AlarmLogTrace(due: true, quietHours: true) }
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
            until: Swift.max(int64(o["until"]) ?? 0, 0))
    }
}

/// Speicher des Alarm-Protokolls: `alarm_log.json` im App-Group-Ordner neben «Letzte Prüfungen» (`CheckRunStore`) —
/// App, Hintergrund-Aufgabe und Widget schreiben, abgestimmt über `SharedStorage.coordinatedWrite`. Inhalt mit Paaren und
/// Schwellen wie die Merkliste, darum verschlüsselt wie sie (`ProtectedFile`, in `ProtectedStorage.files`) und aus den
/// Geräte-Backups ausgenommen. Darf die Prüfung nie stören: Fehler bleiben still, Live-Kurse höchstens alle
/// `AlarmLog.liveWriteIntervalMillis` (bemerkenswerte Einträge sofort). Wie `AlarmLogStore.kt`.
enum AlarmLogStore {
    static let fileName = "alarm_log.json"

    static var url: URL { SharedStorage.directory.appendingPathComponent(fileName) }

    private static let lock = NSLock()

    /// Je Prozess: noch nicht geschriebene Live-Einträge und Zeit des letzten Schreibens.
    private struct Pending {
        var entries: [AlarmLogEntry] = []
        var lastWriteAt: Int64 = 0
    }
    private static let pending = LockedBox(Pending())
    /// Höchstens so viele Einträge warten im Speicher.
    private static let maxPending = 2_000

    private enum Read {
        case ok(AlarmLogState)
        /// Verschlüsselt, Schlüssel gerade nicht verfügbar: nichts überschreiben.
        case locked
    }

    private static func read(from url: URL) -> Read {
        guard FileManager.default.fileExists(atPath: url.path) else { return .ok(.empty) }
        do {
            return .ok(AlarmLog.decode(try ProtectedFile.read(url)))
        } catch ProtectedFile.ReadError.damaged(_) {
            // Nur ein Blick zurück: neu beginnen, das nächste Schreiben ersetzt die Datei
            return .ok(.empty)
        } catch ProtectedFile.ReadError.locked {
            return .locked
        } catch {
            return .locked
        }
    }

    /// Für die Anzeige: Datei samt der noch nicht geschriebenen Live-Einträge dieses Prozesses.
    static func load(from url: URL = AlarmLogStore.url, now: Int64 = TimeUtils.nowMillis) -> AlarmLogState {
        var base = AlarmLogState.empty
        if case .ok(let state) = read(from: url) { base = state }
        let waiting = pending.current.entries
        return waiting.isEmpty ? base : AlarmLog.record(base, waiting, now: now)
    }

    /// Einträge eines Durchlaufs übernehmen und (ausser bei gedrosselten Live-Kursen) schreiben. Wirft nie.
    static func record(_ entries: [AlarmLogEntry], live: Bool, to url: URL = AlarmLogStore.url,
                       now: Int64 = TimeUtils.nowMillis) {
        guard !entries.isEmpty else { return }
        var toWrite: [AlarmLogEntry] = []
        pending.write { p in
            p.entries.append(contentsOf: entries)
            if p.entries.count > maxPending { p.entries.removeFirst(p.entries.count - maxPending) }
            let notable = entries.contains { $0.notable }
            if !live || notable || now - p.lastWriteAt >= AlarmLog.liveWriteIntervalMillis || now < p.lastWriteAt {
                toWrite = p.entries
                p.entries = []
                p.lastWriteAt = now
            }
        }
        guard !toWrite.isEmpty else { return }
        if !modify(at: url, { AlarmLog.record($0, toWrite, now: now) }) {
            // Nicht geschrieben (Schlüssel gesperrt, Schreibfehler): beim nächsten Mal nachtragen
            pending.write { p in
                p.entries = toWrite + p.entries
                if p.entries.count > maxPending { p.entries.removeFirst(p.entries.count - maxPending) }
            }
        }
    }

    /// Alarm gelöscht: seine letzte Prüfung entfernen.
    static func removeAlarm(_ alarmId: Int64, at url: URL = AlarmLogStore.url) {
        pending.write { $0.entries.removeAll { $0.alarmId == alarmId } }
        modify(at: url) { AlarmLog.removeAlarm($0, alarmId) }
    }

    /// Nur letzte Prüfungen bestehender Alarme behalten.
    static func retainAlarms(_ alarmIds: Set<Int64>, at url: URL = AlarmLogStore.url) {
        modify(at: url) { AlarmLog.retainAlarms($0, alarmIds) }
    }

    /// «Protokoll löschen».
    static func clearEvents(at url: URL = AlarmLogStore.url) {
        // Noch wartende Einträge kommen nach dem Löschen nicht zurück ins Protokoll — nur noch als letzte Prüfung
        pending.write { p in p.entries = p.entries.map { var e = $0; e.notable = false; return e } }
        modify(at: url) { AlarmLog.clearEvents($0) }
    }

    /// Lesen, ändern und verschlüsselt schreiben als ein abgestimmter Schritt über die Prozesse. false = nicht geschrieben.
    @discardableResult
    private static func modify(at url: URL, _ change: (AlarmLogState) -> AlarmLogState) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return SharedStorage.coordinatedWrite(at: url) { () -> Bool in
            guard case .ok(let current) = read(from: url) else { return false }
            let next = change(current)
            if next == current && FileManager.default.fileExists(atPath: url.path) { return true }
            guard let data = AlarmLog.encode(next) else { return false }
            do {
                try ProtectedFile.write(data, to: url)
                // Atomares Schreiben ersetzt die Datei: Merkmal jedes Mal neu setzen
                BackupExclusion.set(excluded: true, for: url)
                return true
            } catch {
                return false
            }
        }
    }
}
