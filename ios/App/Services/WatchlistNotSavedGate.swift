import Foundation

/// Seit Build 104 (Review F-08): Merkliste wird gerade nicht gespeichert (`WatchlistStorageLock.Notice.notSaved`, kein
/// Schlüssel zum Versiegeln) — Änderungen gehen beim Neustart verloren. Vor der ersten Änderung des Nutzers je App-Lauf
/// einmal fragen: «Abbrechen» ändert nichts, «Trotzdem» führt die Änderung aus und fragt in diesem Lauf nicht mehr.
/// Gefragt wird zentral in `AppData.watchlistWriteRefused`; die Schreibwege sind hier synchron, darum wird die erste
/// Änderung abgefangen und nach «Trotzdem» erneut ausgeführt (`retry`) — im Ergebnis wie Android, wo der Schreibweg auf
/// die Antwort wartet. Den Dialog zeigt der oberste sichtbare Bildschirm mit `.watchlistLockedHint`. Ohne sichtbaren
/// Bildschirm (Hintergrund) nie fragen. Wie Android `WatchlistNotSavedGate` (Testfälle `watchlist_not_saved_gate.json`).
@MainActor
final class WatchlistNotSavedGate: ObservableObject {
    static let shared = WatchlistNotSavedGate()

    /// Reine Entscheidung: vor diesem Schreibweg fragen? Nur Schreibwege des Nutzers, nur ohne Bestätigung, nur mit
    /// sichtbarer Oberfläche. Wie Android.
    nonisolated static func shouldAsk(notSaved: Bool, userWrite: Bool, confirmed: Bool, canAsk: Bool) -> Bool {
        notSaved && userWrite && !confirmed && canAsk
    }

    /// Reine Folge einer Antwort: «Trotzdem» bestätigt für den Rest des Laufs, «Abbrechen» nicht. Wie Android.
    nonisolated static func confirmedAfter(confirmed: Bool, proceed: Bool) -> Bool { confirmed || proceed }

    /// Offene Frage (Dialog zeigen).
    @Published private(set) var pending = false
    private(set) var confirmed = false
    private var retries: [@MainActor () -> Void] = []

    init() {
        #if DEBUG
        // UI-Tests (`-uiTesting`): Der unsignierte Simulator hat keinen Schlüsselbund der App Group, die Merkliste ist dort
        // immer «nicht gespeichert» — die Abläufe gelten als bestätigt (sonst wartete jede erste Änderung auf den Dialog)
        if UITesting.isActive { confirmed = true }
        #endif
    }

    /// Vor einem Schreibweg des Nutzers: true = jetzt nicht schreiben (Frage offen); `retry` läuft nach «Trotzdem».
    func intercept(notSaved: Bool, canAsk: Bool, retry: (@MainActor () -> Void)?) -> Bool {
        guard Self.shouldAsk(notSaved: notSaved, userWrite: true, confirmed: confirmed, canAsk: canAsk) else { return false }
        if let retry { retries.append(retry) }
        if !pending { pending = true }
        return true
    }

    /// Antwort aus dem Dialog.
    func answer(proceed: Bool) {
        guard pending else { return }
        confirmed = Self.confirmedAfter(confirmed: confirmed, proceed: proceed)
        let waiting = retries
        retries = []
        pending = false
        if proceed { waiting.forEach { $0() } }
    }
}
