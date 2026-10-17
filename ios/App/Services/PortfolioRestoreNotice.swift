import Foundation
import SwiftUI

/// Einmaliger Hinweis «X Einträge aus der System-Sicherung waren ungültig» — wie `PortfolioRestoreNotice.kt`.
///
/// Unter iOS stellt das Geräte-Backup `portfolio.json` unverändert wieder her; unlesbare Einträge fallen
/// beim Lesen weg (`PortfolioFile.dropped`, die Datei wird vor dem nächsten Schreiben daneben gesichert).
/// `AppData` merkt sich die Anzahl beim Start ([record]); das Portfolio zeigt sie beim nächsten Öffnen
/// einmal ([take]). Dieselbe Datei meldet sich nicht bei jedem Start erneut (Prüfsumme `signature`).
enum PortfolioRestoreNotice {
    private static let pendingKey = "portfolio_restore_notice_invalid"
    private static let seenKey = "portfolio_restore_notice_signature"

    /// Prüfsumme einer Datei: Länge und FNV-1a (64 Bit), hexadezimal.
    static func signature(_ data: Data) -> String {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in data {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01b3
        }
        return "\(data.count)-" + String(hash, radix: 16)
    }

    /// Vormerken? Nur mit verworfenen Einträgen und nicht für eine schon gemeldete Datei.
    static func shouldRecord(dropped: Int, signature: String, seen: String?) -> Bool {
        dropped > 0 && signature != seen
    }

    /// Beim Start: `dropped` verworfene Einträge der Datei `fileData` vormerken (aufsummiert).
    static func record(dropped: Int, fileData: Data, defaults: UserDefaults = .standard) {
        let sig = signature(fileData)
        guard shouldRecord(dropped: dropped, signature: sig, seen: defaults.string(forKey: seenKey)) else { return }
        defaults.set(sig, forKey: seenKey)
        defaults.set(BackupRestorePolicy.noticeTotal(pending: defaults.integer(forKey: pendingKey), added: dropped),
                     forKey: pendingKey)
    }

    /// Vorgemerkte Anzahl lesen und löschen (0 = nichts zu melden).
    static func take(defaults: UserDefaults = .standard) -> Int {
        let count = defaults.integer(forKey: pendingKey)
        if count > 0 { defaults.removeObject(forKey: pendingKey) }
        return count
    }
}

/// Zeigt den Hinweis beim Erscheinen einmal (Portfolio-Tab) — wie Android `SystemRestoreNoticeDialog`.
private struct SystemRestoreNoticeModifier: ViewModifier {
    @State private var invalid = 0

    func body(content: Content) -> some View {
        content
            .onAppear {
                let count = PortfolioRestoreNotice.take()
                if count > 0 { invalid = count }
            }
            .alert(
                L("portfolio_system_restore_invalid", count: invalid),
                isPresented: Binding(get: { invalid > 0 }, set: { if !$0 { invalid = 0 } })
            ) {
                Button(L("action_close"), role: .cancel) { invalid = 0 }
            }
    }
}

extension View {
    /// Ungültige Einträge aus der Systemsicherung einmal nennen (`PortfolioRestoreNotice`).
    func systemRestoreNotice() -> some View { modifier(SystemRestoreNoticeModifier()) }
}
