import Foundation
import SwiftUI

/// Einmaliger Hinweis «Gespeicherte Daten liessen sich nicht entsperren» — wie `StorageRecoveryNotice.kt`.
///
/// Verschlüsselte Dateien ohne Schlüssel im Schlüsselbund (z. B. ein unverschlüsseltes Finder-Backup auf einem anderen
/// iPhone eingespielt) legt `ProtectedStorage.prepareAtLaunch` unverändert beiseite (`*.locked`), die App beginnt leer.
/// Weg zurück: eine Sicherungsdatei einspielen. Gemerkt in den Einstellungen der App (nicht der App Group): Der Hinweis
/// gehört zur App auf diesem Gerät, die Widgets brauchen ihn nicht.
enum StorageRecoveryNotice {
    private static let pendingKey = "storage_recovery_notice_pending"

    static func record(defaults: UserDefaults = .standard) {
        defaults.set(true, forKey: pendingKey)
    }

    /// Vorgemerkt? Liest und löscht (true = Hinweis einmal zeigen).
    static func take(defaults: UserDefaults = .standard) -> Bool {
        guard defaults.bool(forKey: pendingKey) else { return false }
        defaults.removeObject(forKey: pendingKey)
        return true
    }
}

/// Zeigt den Hinweis einmal über der App — wie Android `StorageRecoveryNoticeDialog`.
private struct StorageRecoveryNoticeModifier: ViewModifier {
    @State private var visible = false

    func body(content: Content) -> some View {
        content
            .onAppear {
                if StorageRecoveryNotice.take() { visible = true }
            }
            .alert(L("storage_recovery_title"), isPresented: $visible) {
                Button(L("action_close"), role: .cancel) { visible = false }
            } message: {
                Text(L("storage_recovery_message", L("backup_title")))
            }
    }
}

extension View {
    /// Beiseitegelegte, ohne Schlüssel unlesbare Daten einmal nennen (`StorageRecoveryNotice`).
    func storageRecoveryNotice() -> some View { modifier(StorageRecoveryNoticeModifier()) }
}
