import SwiftUI
import UIKit

/// Hilfstypen und -werte der Seite «Sichern & Wiederherstellen».
extension BackupSettingsPage {
    /// Blatt «Sichern» offen; Vorwahl «Mit Passwort schützen».
    struct ExportOptions: Identifiable {
        let id = UUID()
        let defaultProtect: Bool
    }

    /// Im Blatt gewählter Schutz (nil = ohne Passwort).
    struct ExportChoice {
        let password: String?
    }

    /// Verschlüsselte Sicherung bis zur Passwort-Eingabe.
    struct EncryptedFile: Identifiable {
        let id = UUID()
        let data: Data
    }

    /// Schriftgrösse als Faktor (1 = Standard `.large`) — wie Android `Configuration.fontScale`; ohne `category` die
    /// des Systems.
    static func fontScale(_ category: ContentSizeCategory? = nil) -> Double {
        let traits = category.map { UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory($0)) }
        return Double(UIFontMetrics.default.scaledValue(for: 1, compatibleWith: traits))
    }

    static func isCancel(_ error: Error) -> Bool {
        (error as? CocoaError)?.code == .userCancelled
    }

    /// Meldung (Schlüssel) zu einem Fehler beim Lesen bzw. Entschlüsseln einer Sicherung.
    static func failureKey(_ error: Error) -> String {
        switch error {
        case BackupCrypto.Failure.notEnoughMemory: return "backup_not_enough_memory"
        case BackupLimits.Failure.tooLarge: return "backup_too_large"
        case BackupVersion.Failure.newer: return "backup_restore_newer_version"
        default: return "backup_failed"
        }
    }
}

extension View {
    /// Nur Debug ohne Test-Ziel: Prüfwert (backup_crypto.json) einmal nachrechnen (gleiche Bytes wie Android).
    @ViewBuilder
    func backupCryptoSelfCheck() -> some View {
        #if DEBUG
        task {
            let ok = await Task.detached(priority: .utility) { BackupCrypto.verifyTestVector() }.value
            assert(ok, "BackupCrypto: Prüfwert weicht von Android ab")
        }
        #else
        self
        #endif
    }
}
