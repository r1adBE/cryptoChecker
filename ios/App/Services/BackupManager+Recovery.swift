import Foundation

// Zurückrollen eines abgebrochenen Wiederherstellens beim Start (Teil von `BackupManager`) — wie Android
// `BackupManager.recoverInterruptedRestore`.
extension BackupManager {
    /// Beim Start: Liegt ein Journal (`RestoreJournal`) da, ist ein Wiederherstellen abgebrochen — den Stand davor
    /// zurückschreiben und das nennen (`StorageRecoveryNotice.recordInterruptedRestore`). Seit Build 93 nur, solange
    /// `RestoreRecoveryPolicy` es erlaubt (genau ein weiterer Versuch nach einem gescheiterten, nie mit einem Journal älter
    /// als einen Tag) — sonst aufgeben: Journal weg, Hinweis «nicht zurückgeschrieben» einmal. Wie Android.
    @MainActor
    static func recoverInterruptedRestore(_ data: AppData) {
        switch RestoreJournal.read() {
        case .absent, .unavailable:
            return
        case .unreadable:
            RestoreJournal.delete()
            StorageRecoveryNotice.recordInterruptedRestore(rolledBack: false)
        case .found(let json):
            let createdAt = RestoreRecoveryPolicy.createdAt(stored: RestoreRecoveryPolicy.storedCreatedAt(json),
                                                            fileModified: RestoreJournal.modifiedAt())
            let attemptedFor = StorageRecoveryNotice.rollBackAttemptedFor()
            let markAttempt = { StorageRecoveryNotice.recordRollBackAttempt(createdAt) }
            if RestoreRecoveryPolicy.decide(createdAt: createdAt, attemptedFor: attemptedFor, now: TimeUtils.nowMillis) == .giveUp {
                giveUpRecovery(showNotice: RestoreRecoveryPolicy.noticeOnGiveUp(createdAt: createdAt, attemptedFor: attemptedFor),
                               markAttempt: markAttempt)
                return
            }
            // Eigener Stand dieses Geräts: vertrauenswürdig (Sperre wie gemerkt), ungültige Einträge gibt es darin nicht
            let outcome: RecoveryOutcome
            do {
                _ = try restore(json, into: data, acceptInvalid: .max, encrypted: true, journal: false)
                outcome = .rolledBack
            } catch {
                outcome = recoveryOutcome(failure: error)
            }
            finishRecovery(outcome, markAttempt: markAttempt)
        }
    }

    /// Ergebnis des Zurückrollens beim Start.
    enum RecoveryOutcome: Equatable {
        case rolledBack
        /// Journal nicht als Sicherung lesbar: nie zurückrollbar, wie Android `previousFromJournal`.
        case unreadable
        /// Zurückschreiben scheiterte (Portfolio, Adressen): Journal behalten, der nächste Start versucht es genau einmal erneut.
        case incomplete
    }

    /// Scheiterte das Zurückrollen beim Schreiben (`restore` hat dann selbst zurückgesetzt) oder schon beim Lesen?
    static func recoveryOutcome(failure: Error) -> RecoveryOutcome {
        if case Failure.invalid(let step) = failure, step == "portfolio" || step == WatchedAddressRules.backupKey {
            return .incomplete
        }
        return .unreadable
    }

    /// Nach dem Zurückrollen (Tests): Journal nur löschen, wenn es erledigt oder nie zurückrollbar ist — nach einem
    /// gescheiterten Schreiben bleibt es für genau einen weiteren Versuch (`markAttempt`, seit Build 93), wie Android.
    /// Den Hinweis gibt es in jedem Fall.
    static func finishRecovery(_ outcome: RecoveryOutcome, deleteJournal: () -> Void = { RestoreJournal.delete() },
                               notice: (Bool) -> Void = { StorageRecoveryNotice.recordInterruptedRestore(rolledBack: $0) },
                               markAttempt: () -> Void) {
        if outcome == .incomplete { markAttempt() } else { deleteJournal() }
        notice(outcome == .rolledBack)
    }

    /// Aufgeben (Tests): erst vermerken (scheitert das Löschen, rollt auch der nächste Start nicht zurück), dann Journal
    /// weg; Hinweis «nicht zurückgeschrieben» nur, wenn ihn nicht schon der gescheiterte Versuch gezeigt hat.
    static func giveUpRecovery(showNotice: Bool, markAttempt: () -> Void,
                               deleteJournal: () -> Void = { RestoreJournal.delete() },
                               notice: (Bool) -> Void = { StorageRecoveryNotice.recordInterruptedRestore(rolledBack: $0) }) {
        markAttempt()
        deleteJournal()
        if showNotice { notice(false) }
    }
}
