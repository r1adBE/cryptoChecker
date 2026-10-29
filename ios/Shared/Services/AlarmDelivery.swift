import Foundation
import os

/// Ablauf «ausgelösten Alarm melden» — wie `AlarmDelivery.kt`: Nachtruhe, «1 h später» (`AlarmSnooze`) oder keine zustellbare
/// Mitteilung → nichts tun, Alarm bleibt scharf. Sonst ZUERST melden (`show`) und erst speichern
/// (`mark`), wenn die Mitteilung bestätigt ist; unbestätigte Meldungen werden frühestens nach der
/// Abklingzeit wiederholt (`retryBlocked`). Der Versuch wird schon VOR dem Melden als unbestätigt vermerkt und
/// erst nach dem Speichern gelöscht: Beendet iOS den Prozess zwischen Melden und Speichern, greift beim nächsten
/// Lauf die Abklingzeit statt einer sofortigen zweiten Meldung (Android hält den Prozess dafür mit `NonCancellable`).
///
/// Anders als Android liegt der unbestätigte Versuch im App-Group-Speicher statt nur im Speicher des
/// Prozesses: App, Hintergrund-Aufgabe und Widget melden Alarme, und ein Neuversuch im anderen Prozess
/// gleich danach wäre eine zweite Meldung.
///
/// Findet der nächste Lauf einen frischen Vermerk (`alreadyDelivered`, < `freshMillis`) und steht die
/// Mitteilung noch da (`isShown`), wird der Alarm nur noch als gemeldet gespeichert — kein zweiter Alarmton nach der
/// Abklingzeit. Wie Android `AlarmDeliveryMarker`. Gelesen und geschrieben wird nur unter der `AlarmLease`
/// (Paar-Alarme) bzw. auf dem MainActor der App (Portfolio-Alarme) — darum genügt hier eine Sperre. Der Speicher
/// (`UserDefaults`, nicht `Sendable`) liegt in ihr und wird nur unter ihr angefasst; so ist die Klasse vom Compiler
/// geprüft `Sendable`.
///
/// `isShown` bekommt den Zeitpunkt des Vermerks — nur eine danach gezeigte Mitteilung bestätigt
/// (`shownSinceMarker`), eine ältere desselben Alarms nicht; lässt sich der Alarm dann nicht speichern, wird die
/// Mitteilung entfernt (`retract`). Vermerke gelöschter bzw. ersetzter Alarme räumt `retain`/`remove` weg.
final class AlarmDelivery: Sendable {
    /// Paar-Alarme (App, Hintergrund, Widget).
    static let pairs = AlarmDelivery(storeKey: "alarm_unconfirmed_at")
    /// Portfolio-Alarme: eigene Ids, darum eigener Eintrag.
    static let portfolio = AlarmDelivery(storeKey: "portfolio_alarm_unconfirmed_at")

    /// Nicht bestätigter Alarm: frühestens so spät erneut melden (wie `NotificationBudget.MIN_RETRY_MINUTES`).
    static let minRetryMinutes = 5

    /// So lange gilt ein Vermerk als frisch (wie Android `AlarmDeliveryMarker.FRESH_MILLIS`).
    static let freshMillis: Int64 = 10 * 60_000

    /// Frischer Vermerk und die Mitteilung steht noch da — schon gemeldet, nur noch speichern
    /// (`alarm_delivery_marker.json`). Wie Android `AlarmDeliveryMarker.alreadyDelivered`.
    static func alreadyDelivered(markerAt: Int64?, now: Int64, shown: Bool) -> Bool {
        guard shown, let markerAt, markerAt > 0 else { return false }
        let age = now - markerAt
        return age >= 0 && age < freshMillis
    }

    /// Spielraum für das Datum der Mitteilung gegenüber dem Vermerk (wie Android
    /// `AlarmDeliveryMarker.POSTED_TOLERANCE_MILLIS`).
    static let postedToleranceMillis: Int64 = 1_000

    /// Zählt die zugestellte Mitteilung (Datum `postedAt` in ms, nil = keine) als die nach dem Vermerk
    /// `markerAt` gezeigte? Eine ältere desselben Alarms bestätigt nichts. Wie Android `AlarmDeliveryMarker.shownSinceMarker`.
    static func shownSinceMarker(postedAt: Int64?, markerAt: Int64) -> Bool {
        guard let postedAt else { return false }
        return postedAt >= markerAt - postedToleranceMillis
    }

    /// Ist die Auslösung zum Vermerk `markerAt` schon gespeichert? `mark` speichert `lastTriggeredAt` mit
    /// derselben Zeit wie der Vermerk; ein Vermerk aus der Zeit davor gehört zu einer ungespeicherten Auslösung.
    /// Wie Android `AlarmDeliveryMarker.savedSinceMarker`.
    static func savedSinceMarker(lastTriggeredAt: Int64, markerAt: Int64) -> Bool {
        markerAt > 0 && lastTriggeredAt >= markerAt
    }

    /// Vermerke löschen bzw. gegen die vorhandenen Alarme kürzen? Nicht, solange der nächste Prozess den
    /// Stand der Datei zurückbringt (gesperrt behalten bzw. nur im Speicher wartend). Wie Android
    /// `AlarmDeliveryMarker.shouldClear` (`alarm_delivery_marker.json`, «resetCases»).
    static func shouldClear(storedKept: Bool, volatileCopy: Bool) -> Bool {
        !storedKept && !volatileCopy
    }

    /// Der Speicher, nur unter der Sperre: sie hält zugleich jedes Lesen-Ändern-Schreiben zusammen.
    private let store: OSAllocatedUnfairLock<UserDefaults>
    private let storeKey: String

    init(storeKey: String, store: UserDefaults = SharedStorage.defaults) {
        self.storeKey = storeKey
        self.store = OSAllocatedUnfairLock(uncheckedState: store)
    }

    /// Alarm wurde gemeldet, die Mitteilung aber nicht bestätigt: Er bleibt scharf, meldet aber erst nach
    /// der Abklingzeit (mindestens `minRetryMinutes`) erneut — kein Dauerfeuer, falls die Prüfung irrt.
    /// Wie `NotificationBudget.retryBlocked` (Android).
    static func retryBlocked(unconfirmedAt: Int64?, now: Int64, cooldownMinutes: Int) -> Bool {
        guard let unconfirmedAt, unconfirmedAt > 0 else { return false }
        let wait = Int64(max(cooldownMinutes, minRetryMinutes)) * 60_000
        let age = now - unconfirmedAt
        return age >= 0 && age < wait
    }

    /// - Parameter snoozed: «1 h später» läuft (`AlarmSnooze.isSnoozed`): wie die Nachtruhe — nicht melden, scharf bleiben.
    /// - Parameter canShow: kann eine Mitteilung gerade sichtbar ankommen (`Notifier.isAuthorized`)?
    /// - Parameter show: meldet; true erst, wenn die Mitteilung bestätigt ist.
    /// - Parameter mark: speichert den Zustand nach dem Auslösen; false = nicht gespeichert (z. B. inzwischen bearbeitet).
    /// - Parameter retract: entfernt die schon gezeigte Mitteilung, wenn `mark` nichts speichern konnte — sie gehörte
    ///   zu einem Alarm, den es so nicht mehr gibt. Wie Android.
    /// - Parameter isShown: steht die Mitteilung dieses Alarms noch da? Mit dem Zeitpunkt
    ///   des Vermerks: nur eine danach gezeigte zählt (`shownSinceMarker`).
    /// - Parameter savedSince: ist die Auslösung zum Vermerk schon gespeichert (`savedSinceMarker` mit
    ///   `lastTriggeredAt` des ausgewerteten Alarms)? Dann ist der Vermerk liegen geblieben und wird verworfen.
    /// - Parameter persisted: (nur iOS) nach `mark` == true: ist der Zustand auch auf der Platte? Die App
    ///   übernimmt in den Speicher und schreibt über die Warteschlange; schlägt das Schreiben fehl, bleibt der Vermerk
    ///   (endet die App vor dem nächsten Speichern, meldet der nächste Prozess nicht ein zweites Mal). Die Mitteilung
    ///   bleibt — der Alarm gilt im Speicher als ausgelöst. Android: Room liefert das Ergebnis direkt aus `mark`.
    /// - Returns: true = gemeldet und gespeichert (zählt als ausgelöst).
    func deliver(alarmId: Int64, now: Int64, cooldownMinutes: Int, holdAlarms: Bool, snoozed: Bool = false,
                 canShow: () async -> Bool,
                 show: @escaping @Sendable () async -> Bool,
                 mark: @escaping @Sendable () async -> Bool,
                 retract: @escaping @Sendable () async -> Void = {},
                 isShown: (_ markerAt: Int64) async -> Bool = { _ in false },
                 savedSince: (_ markerAt: Int64) -> Bool = { _ in false },
                 persisted: @escaping @Sendable () async -> Bool = { true }) async -> Bool {
        // Liegengebliebener Vermerk einer schon gespeicherten Auslösung → verwerfen und normal melden
        // (sonst würde eine neue, echte Auslösung still als «schon gemeldet» gespeichert). Wie Android.
        if let stale = unconfirmedAt(alarmId), savedSince(stale) {
            setUnconfirmed(alarmId, at: nil)
        }
        // Voriger Lauf endete zwischen Melden und Speichern — nur speichern, nicht erneut melden.
        // false: in diesem Lauf nichts gemeldet (keine zweite Ansage)
        if let pending = unconfirmedAt(alarmId),
           Self.alreadyDelivered(markerAt: pending, now: now, shown: await isShown(pending)) {
            await Task {
                // Nicht gespeichert (inzwischen bearbeitet/gelöscht) → Mitteilung wieder weg
                if await mark() {
                    // Nicht auf der Platte → Vermerk bleibt
                    guard await persisted() else { return }
                } else {
                    await retract()
                }
                self.setUnconfirmed(alarmId, at: nil)
            }.value
            return false
        }
        if holdAlarms || snoozed { return false }
        let deliverable = await canShow()
        if !QuietHours.mayFire(holdAlarms: holdAlarms, deliverable: deliverable) { return false }
        if Self.retryBlocked(unconfirmedAt: unconfirmedAt(alarmId), now: now, cooldownMinutes: cooldownMinutes) {
            return false
        }
        // Melden, Prüfen und Speichern nicht abbrechbar (wie `NonCancellable`): ein eigener Task erbt den
        // Abbruch des Aufrufers nicht — sonst Mitteilung ohne gespeicherten Alarm
        return await Task {
            // «Wird gemeldet» vor dem Melden vermerken (wie ein unbestätigter Versuch)
            self.setUnconfirmed(alarmId, at: now)
            // Nicht bestätigt: Vermerk bleibt — frühestens nach der Abklingzeit erneut
            guard await show() else { return false }
            let saved = await mark()
            // Übernommen, aber nicht auf der Platte → Vermerk bleibt (Mitteilung auch)
            if saved, !(await persisted()) { return true }
            self.setUnconfirmed(alarmId, at: nil)
            if !saved { await retract() }
            return saved
        }.value
    }

    // MARK: Unbestätigte Versuche (App-Group-Speicher)

    func unconfirmedAt(_ alarmId: Int64) -> Int64? {
        store.withLock { store in
            guard let value = (store.dictionary(forKey: storeKey) as? [String: Double])?[String(alarmId)] else { return nil }
            return Int64(value)
        }
    }

    /// Vermerke dieser Alarme entfernen (gelöscht bzw. durch eine Sicherung ersetzt).
    func remove<S: Sequence>(_ alarmIds: S) where S.Element == Int64 {
        let keys = Set(alarmIds.map { String($0) })
        modifyAll { all in all = all.filter { entry in !keys.contains(entry.key) } }
    }

    /// Nur Vermerke noch vorhandener Alarme behalten.
    func retain(_ alarmIds: Set<Int64>) {
        modifyAll { all in all = all.filter { entry in Int64(entry.key).map { alarmIds.contains($0) } ?? false } }
    }

    /// Alle Vermerke löschen (Speicher neu angelegt — Ids beginnen von vorn).
    func removeAll() {
        modifyAll { all in all = [:] }
    }

    /// Vermerke beider Arten löschen (gespeicherter Stand beiseitegelegt, neu begonnen; wie Android
    /// beim Neuanlegen der Datenbanken).
    static func removeAllMarkers() {
        pairs.removeAll()
        portfolio.removeAll()
    }

    private func modifyAll(_ change: @Sendable (inout [String: Double]) -> Void) {
        store.withLock { store in
            guard var all = store.dictionary(forKey: storeKey) as? [String: Double] else {
                if store.object(forKey: storeKey) != nil { store.removeObject(forKey: storeKey) }
                return
            }
            let before = all.count
            change(&all)
            guard all.count != before else { return }
            if all.isEmpty {
                store.removeObject(forKey: storeKey)
            } else {
                store.set(all, forKey: storeKey)
            }
        }
    }

    private func setUnconfirmed(_ alarmId: Int64, at time: Int64?) {
        store.withLock { store in
            var all = (store.dictionary(forKey: storeKey) as? [String: Double]) ?? [:]
            let key = String(alarmId)
            guard all[key] != nil || time != nil else { return }
            all[key] = time.map { Double($0) }
            if all.isEmpty {
                store.removeObject(forKey: storeKey)
            } else {
                store.set(all, forKey: storeKey)
            }
        }
    }
}
