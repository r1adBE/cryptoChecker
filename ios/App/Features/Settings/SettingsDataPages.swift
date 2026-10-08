import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Sichern & Wiederherstellen: eine Datei, die man selbst ablegt.
@MainActor
struct BackupSettingsPage: View {
    @EnvironmentObject private var data: AppData

    @State private var toast: String?
    @State private var exportDocument: BackupDocument?
    @State private var exporting = false
    @State private var importing = false
    @State private var pendingRestore: Data?
    @State private var confirmRestore = false
    /// Blatt «Sichern» offen; Vorwahl «Mit Passwort schützen» (true mit Portfolio-Daten).
    @State private var exportOptions: ExportOptions?
    /// Im Blatt gewählter Schutz; die Dateiablage öffnet erst nach dem Schliessen des Blatts.
    @State private var exportChoice: ExportChoice?
    /// Verschlüsselte Sicherung gewählt: Datei bis zur Passwort-Eingabe.
    @State private var encryptedFile: EncryptedFile?

    private struct ExportOptions: Identifiable {
        let id = UUID()
        let defaultProtect: Bool
    }

    private struct ExportChoice {
        let password: String?
    }

    private struct EncryptedFile: Identifiable {
        let id = UUID()
        let data: Data
    }

    init() {}

    var body: some View {
        SettingsSubPage(title: L("backup_title")) {
            SettingsHint(text: L("backup_hint"), top: 10)
            HStack(spacing: Spacing.sm) {
                Button(action: startExport) {
                    Label(L("backup_export"), systemImage: "square.and.arrow.up")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(TonalButtonStyle())
                .fileExporter(
                    isPresented: $exporting,
                    document: exportDocument,
                    contentType: .json,
                    defaultFilename: BackupManager.defaultFilename()
                ) { result in
                    exportDocument = nil
                    switch result {
                    case .success:
                        UINotificationFeedbackGenerator().notificationOccurred(.success)
                        toast = L("backup_exported")
                    case .failure(let error):
                        if !Self.isCancel(error) { toast = L("backup_failed") }
                    }
                }

                Button(action: startImport) {
                    Label(L("backup_import"), systemImage: "square.and.arrow.down")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(TonalButtonStyle())
                .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText, .data]) { result in
                    switch result {
                    case .success(let url):
                        let scoped = url.startAccessingSecurityScopedResource()
                        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                        if let file = try? Data(contentsOf: url) {
                            picked(file)
                        } else {
                            toast = L("backup_failed")
                        }
                    case .failure(let error):
                        if !Self.isCancel(error) { toast = L("backup_failed") }
                    }
                }
            }
            .settingsAnchor("backup.actions")
            .padding(.bottom, 12)
        }
        .toast($toast)
        .sheet(item: $exportOptions, onDismiss: {
            // Gleicher Weg wie bisher (Dateiablage des Systems), nur mit gewähltem Schutz
            guard let choice = exportChoice else { return }
            exportChoice = nil
            export(password: choice.password)
        }) { options in
            BackupExportSheet(defaultProtect: options.defaultProtect) { password in
                exportChoice = ExportChoice(password: password)
            }
        }
        .sheet(item: $encryptedFile, onDismiss: {
            // Passwort richtig: Rückfrage «Ersetzen?» erst nach dem Schliessen des Blatts
            if pendingRestore != nil { confirmRestore = true }
        }) { file in
            BackupImportPasswordSheet { password in
                do {
                    pendingRestore = try BackupManager.plainJSON(file.data, password: password)
                    return true
                } catch BackupCrypto.Failure.wrongPassword {
                    return false
                } catch {
                    // Unbekannte Version oder kaputte Datei: kein erneuter Versuch
                    encryptedFile = nil
                    toast = L("backup_failed")
                    return false
                }
            }
        }
        .alert(L("backup_import"), isPresented: $confirmRestore, presenting: pendingRestore) { json in
            Button(L("backup_import"), role: .destructive) { restore(json) }
            Button(L("action_cancel"), role: .cancel) { pendingRestore = nil }
        } message: { _ in
            Text(L("backup_restore_confirm"))
        }
        #if DEBUG
        // Ohne Test-Ziel: Prüfwert aus BackupCryptoTest.kt einmal nachrechnen (gleiche Bytes wie Android)
        .task {
            let ok = await Task.detached(priority: .utility) { BackupCrypto.verifyTestVector() }.value
            assert(ok, "BackupCrypto: Prüfwert weicht von Android ab")
        }
        #endif
    }

    /// Sichern: Enthält die Datei Portfolio-Daten und ist gesperrt, erst entsperren.
    private func startExport() {
        let hasPortfolio = !data.portfolio.isEmpty
        Task {
            let open = await AppLock.shared.requireUnlock { locked in
                PortfolioLockPolicy.backupExportNeedsUnlock(locked: locked, hasPortfolioData: hasPortfolio)
            }
            guard open else { return }
            exportOptions = ExportOptions(defaultProtect: hasPortfolio)
        }
    }

    /// Nach dem Blatt «Sichern»: Datei bauen (mit Passwort verschlüsselt) und ablegen lassen.
    private func export(password: String?) {
        do {
            let file = try BackupManager.exportData(data, password: password)
            exportDocument = BackupDocument(data: file)
            exporting = true
        } catch {
            toast = L("backup_failed")
        }
    }

    /// Datei gewählt: verschlüsselt → Passwort, sonst gleich die Rückfrage. Alte, lesbare
    /// Sicherungen gehen wie bisher.
    private func picked(_ file: Data) {
        do {
            if try BackupManager.needsPassword(file) {
                pendingRestore = nil
                encryptedFile = EncryptedFile(data: file)
            } else {
                pendingRestore = file
                confirmRestore = true
            }
        } catch {
            toast = L("backup_failed")
        }
    }

    /// Wiederherstellen: solange gesperrt, erst entsperren (die Sicherung kann die Sperre ausschalten).
    private func startImport() {
        Task {
            let open = await AppLock.shared.requireUnlock(PortfolioLockPolicy.restoreNeedsUnlock(locked:))
            if open { importing = true }
        }
    }

    private func restore(_ json: Data) {
        pendingRestore = nil
        do {
            let result = try BackupManager.restore(json, into: data)
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            toast = L("backup_restored", L("backup_restored_pairs", count: result.watches),
                      L("backup_restored_alarms", count: result.alarms))
        } catch {
            UINotificationFeedbackGenerator().notificationOccurred(.error)
            toast = L("backup_failed")
        }
    }

    private static func isCancel(_ error: Error) -> Bool {
        (error as? CocoaError)?.code == .userCancelled
    }
}
