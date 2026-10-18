import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Sichern & Wiederherstellen: eine Datei, die man selbst ablegt.
@MainActor
struct BackupSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @ObservedObject private var lock = AppLock.shared

    @State private var toast: String?
    @State private var exportDocument: BackupDocument?
    @State private var exporting = false
    @State private var importing = false
    @State private var pendingRestore: Data?
    /// Ungültige Portfolio-Einträge der gewählten Sicherung (die Rückfrage nennt sie).
    @State private var pendingInvalid = 0
    /// Gewählte Sicherung war verschlüsselt; ohne Passwort warnt die Rückfrage (keine Echtheitsprüfung).
    @State private var pendingEncrypted = false
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
            // Schlüssel liegt im Schlüsselbund nur dieses Geräts (`SystemStorageKeychain`): ohne Sicherungsdatei kein Umzug
            SettingsHint(text: L("backup_device_bound"))
            if data.encryptionPending {
                SettingsHint(text: L("backup_encryption_pending"))
            }
            HStack(spacing: Spacing.sm) {
                Button(action: startExport) {
                    Label(L("backup_export"), systemImage: "square.and.arrow.up")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(TonalButtonStyle())
                .accessibilityIdentifier(UiTestTags.backupExport)
                .fileExporter(
                    isPresented: $exporting,
                    document: exportDocument,
                    contentType: .json,
                    defaultFilename: BackupManager.defaultFilename()
                ) { result in
                    let written = exportDocument?.data
                    exportDocument = nil
                    switch result {
                    case .success(let url):
                        // Zurücklesen und vergleichen (wie Android): ein Speicher-Anbieter, der die Datei kürzt, zeigte
                        // sich sonst erst beim Wiederherstellen
                        if let written, BackupManager.verifyWritten(url, expected: written) != nil {
                            UINotificationFeedbackGenerator().notificationOccurred(.error)
                            toast = L("backup_not_verified")
                            return
                        }
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
                .accessibilityIdentifier(UiTestTags.backupImport)
                .testableFileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText, .data],
                                      testFile: .backup) { result in
                    switch result {
                    case .success(let url):
                        let scoped = url.startAccessingSecurityScopedResource()
                        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                        do {
                            // Höchstens 16 MB (`BackupLimits`)
                            picked(try BackupManager.readFile(url))
                        } catch BackupLimits.Failure.tooLarge {
                            toast = L("backup_too_large")
                        } catch {
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
            // (nicht, wenn unterdessen wieder gesperrt)
            if pendingRestore != nil && !lock.locked { confirmRestore = true }
        }) { file in
            BackupImportPasswordSheet { password in
                // Schlüsselableitung (Argon2id 64 MiB bzw. PBKDF2 bei v2, bis wenige s) im Hintergrund — die Oberfläche bleibt bedienbar
                let encrypted = file.data
                let backgroundTask = BackgroundTaskGuard.begin("backupDecrypt")
                let result = await Task.detached(priority: .userInitiated) {
                    Result { try BackupManager.plainJSON(encrypted, password: password) }
                }.value
                backgroundTask.end()
                // Während der Prüfung wieder gesperrt: Blatt ist schon zu, keine Rückfrage
                if lock.locked {
                    encryptedFile = nil
                    return false
                }
                do {
                    let plain = try result.get()
                    // Sicherung einer neueren App-Version: vor der Rückfrage ablehnen (wie Android)
                    try BackupManager.checkVersion(plain)
                    pendingInvalid = (try? BackupManager.invalidEntries(plain)) ?? 0
                    pendingEncrypted = true
                    pendingRestore = plain
                    return true
                } catch BackupCrypto.Failure.wrongPassword {
                    return false
                } catch BackupCrypto.Failure.notEnoughMemory {
                    // Kein erneuter Versuch mit demselben Passwort: erst Speicher freigeben
                    encryptedFile = nil
                    toast = L("backup_not_enough_memory")
                    return false
                } catch BackupLimits.Failure.tooLarge {
                    encryptedFile = nil
                    toast = L("backup_too_large")
                    return false
                } catch BackupVersion.Failure.newer {
                    encryptedFile = nil
                    toast = L("backup_restore_newer_version")
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
            Button(L("backup_import"), role: .destructive) {
                restore(json, acceptInvalid: pendingInvalid, encrypted: pendingEncrypted)
            }
            .accessibilityIdentifier(UiTestTags.backupRestoreConfirm)
            Button(L("action_cancel"), role: .cancel) { pendingRestore = nil }
        } message: { _ in
            // Ohne Passwort keine Echtheitsprüfung: Herkunft prüfen lassen; ungültige Portfolio-Einträge
            // nennen, statt sie still wegzulassen (wie Android)
            let parts: [String] = [
                L("backup_restore_confirm"),
                pendingEncrypted ? nil : L("backup_restore_unencrypted_warning"),
                pendingInvalid > 0 ? L("backup_restore_invalid", count: pendingInvalid) : nil,
            ].compactMap { $0 }
            Text(parts.joined(separator: "\n\n"))
        }
        // Wieder gesperrt (Hintergrund-Limit), während ein Blatt oder die Rückfrage offen ist:
        // schliessen wie die Erfassen-Blätter
        .onChange(of: lock.locked) { _, locked in
            if locked { dropOpenDialogs() }
        }
        #if DEBUG
        // Ohne Test-Ziel: Prüfwerte v2 und v3 (backup_crypto.json) einmal nachrechnen (gleiche Bytes wie Android)
        .task {
            let ok = await Task.detached(priority: .utility) { BackupCrypto.verifyTestVector() }.value
            assert(ok, "BackupCrypto: Prüfwert weicht von Android ab")
        }
        #endif
    }

    /// Sperre greift wieder: offene Blätter und die Rückfrage schliessen. Nur die Oberfläche vor
    /// dem letzten Bestätigen fällt weg — eine bestätigte Sicherung (`exportChoice`, danach
    /// Dateiablage) bzw. ein laufendes Wiederherstellen läuft zu Ende. Erneut öffnen verlangt
    /// Entsperren.
    private func dropOpenDialogs() {
        exportOptions = nil
        pendingRestore = nil
        confirmRestore = false
        encryptedFile = nil
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
    /// Verschlüsselt wird im Hintergrund (Argon2id mit 64 MiB, einige 100 ms bis wenige Sekunden).
    private func export(password: String?) {
        let plain: Data
        do {
            plain = try BackupManager.plainExportData(data)
        } catch {
            toast = L("backup_failed")
            return
        }
        guard let password else {
            presentExport(plain)
            return
        }
        Task {
            // Verschlüsseln zu Ende führen, auch wenn die App währenddessen in den Hintergrund geht
            let backgroundTask = BackgroundTaskGuard.begin("backupExport")
            defer { backgroundTask.end() }
            let result = await Task.detached(priority: .userInitiated) {
                Result { try BackupManager.encryptedFile(plain, password: password) }
            }.value
            switch result {
            case .success(let file):
                presentExport(file)
            case .failure(BackupCrypto.Failure.notEnoughMemory):
                toast = L("backup_not_enough_memory")
            case .failure:
                toast = L("backup_failed")
            }
        }
    }

    private func presentExport(_ file: Data) {
        #if DEBUG
        // UI-Test: in die Datei des Tests schreiben statt die Dateiablage zu öffnen (wie deren Erfolg)
        if let url = UITesting.exportURL {
            toast = (try? file.write(to: url, options: .atomic)) != nil ? L("backup_exported") : L("backup_failed")
            return
        }
        #endif
        exportDocument = BackupDocument(data: file)
        exporting = true
    }

    /// Datei gewählt: verschlüsselt → Passwort, sonst gleich die Rückfrage. Alte, lesbare
    /// Sicherungen gehen wie bisher.
    private func picked(_ file: Data) {
        // Unterwegs wieder gesperrt: verwerfen (Wiederherstellen verlangt Entsperren)
        guard !lock.locked else { return }
        do {
            if try BackupManager.needsPassword(file) {
                pendingRestore = nil
                encryptedFile = EncryptedFile(data: file)
            } else {
                pendingInvalid = try BackupManager.invalidEntries(file)
                pendingEncrypted = false
                pendingRestore = file
                confirmRestore = true
            }
        } catch BackupLimits.Failure.tooLarge {
            toast = L("backup_too_large")
        } catch BackupVersion.Failure.newer {
            // Sicherung einer neueren App-Version: nichts übernommen
            toast = L("backup_restore_newer_version")
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

    private func restore(_ json: Data, acceptInvalid: Int, encrypted: Bool) {
        pendingRestore = nil
        pendingInvalid = 0
        pendingEncrypted = false
        // Wiederhergestellten Stand ganz auf die Platte bringen, auch wenn die App gleich in den Hintergrund geht
        // (`mutate` schreibt im Hintergrund); abgemeldet erst nach dem Schreiben
        let backgroundTask = BackgroundTaskGuard.begin("restore")
        defer {
            Task.detached(priority: .utility) {
                SharedStorage.flushSnapshotWrites()
                await MainActor.run { backgroundTask.end() }
            }
        }
        do {
            let result = try BackupManager.restore(json, into: data, acceptInvalid: acceptInvalid, encrypted: encrypted)
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            let restored = L("backup_restored", L("backup_restored_pairs", count: result.watches),
                             L("backup_restored_alarms", count: result.alarms))
            // Sperre blieb an (Sicherung ohne Passwort wollte sie ausschalten)
            toast = result.appLockKept ? restored + "\n" + L("backup_restore_app_lock_kept") : restored
            // Wie Android: Erlaubnis für Mitteilungen erfragen (eine Neuinstallation hat nie gefragt — sonst
            // kommen Alarme der eingespielten Paare nie an) und gleich Kurse holen (Widgets, Alarme)
            // (wie neue Paare: erst die kurze Erklärung, `NotificationPrePrompt`)
            if result.watches > 0 {
                NotificationPrePrompt.request(.pairsAdded)
                Task { await data.refreshAll() }
            }
        } catch BackupVersion.Failure.newer {
            UINotificationFeedbackGenerator().notificationOccurred(.error)
            toast = L("backup_restore_newer_version")
        } catch {
            UINotificationFeedbackGenerator().notificationOccurred(.error)
            toast = L("backup_failed")
        }
    }

    private static func isCancel(_ error: Error) -> Bool {
        (error as? CocoaError)?.code == .userCancelled
    }
}
