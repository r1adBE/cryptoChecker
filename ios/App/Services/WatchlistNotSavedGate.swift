import Foundation

/// Seit Build 104 (Review F-08): Merkliste wird gerade nicht gespeichert (`WatchlistStorageLock.Notice.notSaved`, kein
/// Schlüssel zum Versiegeln) — Änderungen gehen beim Neustart verloren. Vor der ersten Änderung des Nutzers je App-Lauf
/// einmal fragen: «Abbrechen» ändert nichts, «Trotzdem» führt die Änderung aus und fragt in diesem Lauf nicht mehr.
/// Seit Build 105 (Review UX3-02/05, IOS3-R04): gefragt wird EINMAL je Nutzeraktion VOR der Aktion im Bildschirm
/// (`AppData.confirmWatchlistWrite`, wartet per `confirm`), danach läuft der normale Weg mit Banner, Rückgängig, Ansage und
/// Erst-Alarm. Der Schreibweg in AppData fragt nicht mehr und führt nichts erneut aus, sondern lehnt ohne Bestätigung ab
/// (`needsConfirmation`, Sicherung). Den Dialog zeigt `WatchlistNotSavedGatePresenter` über allem (eigenes Fenster, auch
/// über Blättern). Ohne sichtbare App nie fragen. Wie Android `WatchlistNotSavedGate` (`watchlist_not_saved_gate.json`).
/// Seit Build 106 (Review ARC4-R02, Variante A): Fehlt die Szene beim Bestätigen nur kurz, wartet `confirm` bis zu
/// `askerWaitMillis` auf sie und fragt dann. Ohne Szene durchgelassen, lehnt die Sicherung im Schreibweg danach nicht ab
/// (`needsConfirmation`) — bis zur nächsten beantworteten Frage.
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

    /// Seit Build 106 (ARC4-R02): so lange wartet `confirm` ohne sichtbare Szene auf sie (ms) — wie Android.
    nonisolated static let askerWaitMillis: UInt64 = 2_000

    /// Offene Frage (Dialog zeigen).
    @Published private(set) var pending = false
    private(set) var confirmed = false
    /// Seit Build 106: letzte Aktion ohne Szene durchgelassen (bis zur nächsten Antwort).
    private var passedUnasked = false
    /// Wartende Aktionen (seit Build 105), je mit eigener Kennung, damit ein abgebrochener Wartender sich austragen kann.
    private var waiters: [UUID: CheckedContinuation<Bool, Never>] = [:]

    init() {
        #if DEBUG
        // UI-Tests (`-uiTesting`): Der unsignierte Simulator hat keinen Schlüsselbund der App Group, die Merkliste ist dort
        // immer «nicht gespeichert» — die Abläufe gelten als bestätigt (sonst wartete jede erste Änderung auf den Dialog)
        if UITesting.isActive { confirmed = true }
        #endif
    }

    /// Sicherung im Schreibweg (seit Build 105): bräuchte er jetzt noch eine Antwort? Dann lehnt AppData ab — die Aktion
    /// hat `confirm` nicht vorher aufgerufen; fragt selbst nie. Wie Android `needsConfirmation`. Seit Build 106 nicht nach
    /// einem Durchlass ohne Szene: sonst lehnte sie dieselbe Aktion still ab (ARC4-R02).
    func needsConfirmation(notSaved: Bool, canAsk: Bool) -> Bool {
        !passedUnasked && Self.shouldAsk(notSaved: notSaved, userWrite: true, confirmed: confirmed, canAsk: canAsk)
    }

    /// Seit Build 106: Kann eine Aktion jetzt eine Frage brauchen (nicht gespeichert, noch nicht bestätigt), egal ob sichtbar?
    func mayAsk(notSaved: Bool) -> Bool {
        Self.shouldAsk(notSaved: notSaved, userWrite: true, confirmed: confirmed, canAsk: true)
    }

    /// Vor einer Aktion des Nutzers: true = ausführen. Fragt höchstens einmal gleichzeitig (weitere Aktionen warten auf
    /// dieselbe Antwort). Wird der Wartende abgebrochen, trägt er sich aus (false); wartet niemand mehr, verschwindet die
    /// Frage. Wie Android `confirm`. Seit Build 106: ohne Szene erst `awaitAsker` (true = Szene jetzt da, dann fragen).
    func confirm(notSaved: Bool, canAsk: Bool, awaitAsker: @MainActor () async -> Bool = { false }) async -> Bool {
        var asker = canAsk || !mayAsk(notSaved: notSaved)
        if !asker {
            asker = await awaitAsker()
            if Task.isCancelled { return false }
        }
        guard Self.shouldAsk(notSaved: notSaved, userWrite: true, confirmed: confirmed, canAsk: asker) else {
            // Ohne Szene durchgelassen: die Sicherung lehnt die Schreibwege dieser Aktion nicht ab
            if !asker && notSaved && !confirmed { passedUnasked = true }
            return true
        }
        let id = UUID()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
                guard !Task.isCancelled else { return continuation.resume(returning: false) }
                waiters[id] = continuation
                if !pending { pending = true }
            }
        } onCancel: {
            Task { @MainActor in self.drop(id) }
        }
    }

    /// Abgebrochenen Wartenden austragen (false); ohne weitere Wartende keine offene Frage mehr.
    private func drop(_ id: UUID) {
        guard let continuation = waiters.removeValue(forKey: id) else { return }
        continuation.resume(returning: false)
        if waiters.isEmpty { pending = false }
    }

    /// Kern von `AppData.withWatchlistConfirm`: gesperrt (`blocked`) oder keine Frage nötig → `action` sofort (synchron);
    /// sonst nach `confirm` (fragt über dieses Tor) erst bei «Trotzdem» `action`, bei «Abbrechen» `cancelled`. Eigene
    /// Funktion, damit Tests den Ablauf mit eigenem Tor prüfen (Zähler für close/cancelled, IOS6-02).
    func run(blocked: Bool, notSaved: Bool, confirm: @escaping @MainActor () async -> Bool,
             _ action: @escaping @MainActor () -> Void, cancelled: (@MainActor () -> Void)?) {
        guard !blocked, mayAsk(notSaved: notSaved) else { return action() }
        Task { @MainActor in
            if await confirm() { action() } else { cancelled?() }
        }
    }

    /// Wartende Aktionen (nur für Tests).
    var waitingCount: Int { waiters.count }

    /// Antwort aus dem Dialog.
    func answer(proceed: Bool) {
        guard pending else { return }
        confirmed = Self.confirmedAfter(confirmed: confirmed, proceed: proceed)
        passedUnasked = false
        let waiting = waiters
        waiters = [:]
        pending = false
        waiting.values.forEach { $0.resume(returning: proceed) }
    }
}
