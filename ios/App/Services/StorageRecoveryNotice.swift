import Foundation
import SwiftUI

/// Hinweise zum Zustand der gespeicherten Daten über der App — wie `StorageRecoveryNotice.kt`:
///  - `.setAside` «Gespeicherte Daten liessen sich nicht entsperren»: Verschlüsselte Dateien ohne Schlüssel im Schlüsselbund
///    (z. B. ein Finder-Backup auf einem anderen iPhone eingespielt) legt `ProtectedStorage.prepareAtLaunch` unverändert
///    beiseite (`*.locked`), die App beginnt leer. Bleibt, bis der Nutzer ihn bestätigt oder eine Sicherung einspielt
///    (`acknowledge`) — ein einmaliger Hinweis ging zu leicht unter. Direkte Aktion «Sicherung wiederherstellen».
///  - `.keyRestored`: Eine beiseitegelegte Datei öffnete beim nächsten Start doch mit dem Schlüssel und ist zurück. Einmal.
///  - `.restoreRolledBack` / `.restoreIncomplete`: Ein Wiederherstellen brach ab; der Stand davor wurde zurückgeschrieben
///    bzw. liess sich nicht zurückschreiben (`RestoreJournal`). Einmal.
///  - `.settingsReset`: Die gespeicherten Einstellungen liessen sich nicht lesen und wurden durch Standardwerte
///    ersetzt (`AppData`, `SharedStorage.settingsCorrupt`) — Portfolio-Sperre, Intervall und Währung stehen still auf dem
///    Standard. Bleibt vorgemerkt, bis der Nutzer den Hinweis schliesst. Nicht bei einer neuen Installation und nicht bei
///    einer (noch) gesperrten Ablage.
/// Gemerkt in den Einstellungen der App (nicht der App Group): Die Hinweise gehören zur App auf diesem Gerät.
enum StorageRecoveryNotice {
    enum Kind: Equatable {
        case setAside, keyRestored, restoreRolledBack, restoreIncomplete, settingsReset
    }

    private static let pendingKey = "storage_recovery_notice_pending"
    private static let restoredKey = "storage_recovery_notice_restored"
    private static let interruptedKey = "storage_recovery_notice_restore_interrupted"
    private static let settingsResetKey = "storage_recovery_notice_settings_reset"
    private static let rollBackAttemptKey = "storage_recovery_notice_rollback_attempted_for"

    /// Neue Vormerkung in diesem Prozess (z. B. ein eben zurückgerolltes Wiederherstellen): der Hinweis prüft erneut.
    static let changed = Notification.Name("StorageRecoveryNoticeChanged")

    /// In diesem Prozess weggetippt (ohne Bestätigung): erst beim nächsten Start wieder.
    @MainActor private static var setAsideHidden = false

    static func record(defaults: UserDefaults = .standard) {
        defaults.set(true, forKey: pendingKey)
    }

    /// Beiseitegelegte Daten zurückgeholt: nicht mehr «nicht entsperrt», sondern einmal «wieder entsperrt».
    static func recordRestored(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: pendingKey)
        defaults.set(true, forKey: restoredKey)
    }

    /// Abgebrochenes Wiederherstellen; `rolledBack` = Stand davor vollständig zurückgeschrieben.
    static func recordInterruptedRestore(rolledBack: Bool, defaults: UserDefaults = .standard) {
        defaults.set(rolledBack ? "rolled_back" : "incomplete", forKey: interruptedKey)
        NotificationCenter.default.post(name: changed, object: nil)
    }

    /// Für das Journal mit Zeitpunkt `journalCreatedAt` wollte ein Start schon zurückrollen (gescheitert)
    /// oder gab auf — der nächste Start rollt es nicht mehr zurück (`RestoreRecoveryPolicy`). Ein neues Journal hat einen
    /// anderen Zeitpunkt, darum muss der Wert nie gelöscht werden. Wie Android.
    static func recordRollBackAttempt(_ journalCreatedAt: Int64, defaults: UserDefaults = .standard) {
        defaults.set(NSNumber(value: journalCreatedAt), forKey: rollBackAttemptKey)
    }

    /// Gegenstück zu `recordRollBackAttempt`; nil = noch kein Versuch vermerkt.
    static func rollBackAttemptedFor(defaults: UserDefaults = .standard) -> Int64? {
        (defaults.object(forKey: rollBackAttemptKey) as? NSNumber)?.int64Value
    }

    /// Einstellungen unlesbar und durch Standardwerte ersetzt vormerken.
    static func recordSettingsReset(defaults: UserDefaults = .standard) {
        defaults.set(true, forKey: settingsResetKey)
        NotificationCenter.default.post(name: changed, object: nil)
    }

    /// Rein (testbar, wie Android `StorageRecoveryNotice.next`): Reihenfolge der Hinweise. Abgebrochenes Wiederherstellen
    /// zuerst (betrifft alle Daten), «nicht entsperrt» zuletzt (bleibt ohnehin, bis bestätigt).
    static func next(restoreInterrupted: String?, keyRestored: Bool, settingsReset: Bool, setAside: Bool,
                     setAsideHidden: Bool) -> Kind? {
        if let restoreInterrupted { return restoreInterrupted == "rolled_back" ? .restoreRolledBack : .restoreIncomplete }
        if keyRestored { return .keyRestored }
        if settingsReset { return .settingsReset }
        if setAside && !setAsideHidden { return .setAside }
        return nil
    }

    /// Nächster zu zeigender Hinweis; nil = keiner. `.setAside` bleibt, bis `acknowledge`, `.settingsReset` bis zum
    /// Schliessen; die anderen zeigt die App einmal.
    @MainActor
    static func pending(defaults: UserDefaults = .standard) -> Kind? {
        next(restoreInterrupted: defaults.string(forKey: interruptedKey), keyRestored: defaults.bool(forKey: restoredKey),
             settingsReset: defaults.bool(forKey: settingsResetKey), setAside: defaults.bool(forKey: pendingKey),
             setAsideHidden: setAsideHidden)
    }

    /// Hinweis erledigt («Verstanden», gezeigt bzw. Sicherung eingespielt).
    @MainActor
    static func acknowledge(_ kind: Kind, defaults: UserDefaults = .standard) {
        switch kind {
        case .setAside: defaults.removeObject(forKey: pendingKey)
        case .keyRestored: defaults.removeObject(forKey: restoredKey)
        case .restoreRolledBack, .restoreIncomplete: defaults.removeObject(forKey: interruptedKey)
        case .settingsReset: defaults.removeObject(forKey: settingsResetKey)
        }
    }

    /// «Später» bzw. weggetippt: in diesem Prozess nicht mehr zeigen, beim nächsten Start wieder.
    @MainActor
    static func hideSetAsideForNow() { setAsideHidden = true }
}

/// Zeigt die Hinweise über der App — wie Android `StorageRecoveryNoticeDialog`.
private struct StorageRecoveryNoticeModifier: ViewModifier {
    @State private var shown: StorageRecoveryNotice.Kind?
    let onRestoreBackup: () -> Void

    private var visible: Binding<Bool> {
        Binding(get: { shown != nil }, set: { if !$0 { close() } })
    }

    func body(content: Content) -> some View {
        content
            .onAppear(perform: refresh)
            .onReceive(NotificationCenter.default.publisher(for: StorageRecoveryNotice.changed)) { _ in refresh() }
            .onChange(of: shown) { _, now in if now == nil { refresh() } }
            .alert(title, isPresented: visible) {
                if shown == .setAside {
                    Button(L("storage_recovery_restore_action")) {
                        StorageRecoveryNotice.hideSetAsideForNow()
                        shown = nil
                        onRestoreBackup()
                    }
                    Button(L("storage_recovery_acknowledge"), role: .cancel) {
                        StorageRecoveryNotice.acknowledge(.setAside)
                        shown = nil
                    }
                } else {
                    Button(L("action_close"), role: .cancel) { close() }
                }
            } message: {
                Text(message)
            }
    }

    private var title: String {
        switch shown {
        case .setAside, .none: return L("storage_recovery_title")
        case .keyRestored: return L("storage_recovery_restored_title")
        case .restoreRolledBack, .restoreIncomplete: return L("backup_restore_interrupted_title")
        case .settingsReset: return L("settings_reset_notice_title")
        }
    }

    private var message: String {
        switch shown {
        case .setAside, .none: return L("storage_recovery_message", L("backup_title"))
        case .keyRestored: return L("storage_recovery_restored_message")
        case .restoreRolledBack: return L("backup_restore_interrupted_rolled_back")
        case .restoreIncomplete: return L("backup_restore_interrupted_incomplete", L("backup_title"))
        case .settingsReset: return L("settings_reset_notice")
        }
    }

    /// Weggetippt: «nicht entsperrt» kommt beim nächsten Start wieder; «Einstellungen zurückgesetzt» ist jetzt erledigt,
    /// die anderen einmaligen Hinweise schon beim Zeigen.
    private func close() {
        if shown == .setAside { StorageRecoveryNotice.hideSetAsideForNow() }
        if shown == .settingsReset { StorageRecoveryNotice.acknowledge(.settingsReset) }
        shown = nil
    }

    private func refresh() {
        guard shown == nil, let next = StorageRecoveryNotice.pending() else { return }
        if next != .setAside && next != .settingsReset { StorageRecoveryNotice.acknowledge(next) }
        shown = next
    }
}

extension View {
    /// Hinweise zu beiseitegelegten bzw. zurückgeholten Daten und abgebrochenem Wiederherstellen (`StorageRecoveryNotice`).
    func storageRecoveryNotice(onRestoreBackup: @escaping () -> Void = {}) -> some View {
        modifier(StorageRecoveryNoticeModifier(onRestoreBackup: onRestoreBackup))
    }
}
