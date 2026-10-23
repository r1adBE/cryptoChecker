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

    /// Schriftgrösse des Systems als Faktor (1 = Standard) — wie Android `Configuration.fontScale`.
    static var fontScale: Double {
        Double(UIFontMetrics.default.scaledValue(for: 1))
    }

    static func isCancel(_ error: Error) -> Bool {
        (error as? CocoaError)?.code == .userCancelled
    }
}

extension View {
    /// Nur Debug ohne Test-Ziel: Prüfwerte v2 und v3 (backup_crypto.json) einmal nachrechnen (gleiche Bytes wie Android).
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
