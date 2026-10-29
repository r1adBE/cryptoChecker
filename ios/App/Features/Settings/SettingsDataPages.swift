import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Sichern & Wiederherstellen: eine Datei, die man selbst ablegt.
@MainActor
struct BackupSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @ObservedObject private var lock = AppLock.shared

    @State private var toast: ToastMessage?
    /// Ergebnis mit Hinweisen oder langem Text (`BackupResultPresentation`); Mitteilungs-Frage erst nach dem Schliessen.
    @State private var resultDialog: String?
    /// Ergebnis wartet, bis Rückfrage, Blatt und Dateiauswahl zu sind (`BackupResultPresentation.canPresent`).
    @State private var pendingResult: String?
    /// Anzeige des wartenden Ergebnisses ist schon geplant (`showPendingResult`): kein zweiter Auftrag.
    @State private var resultScheduled = false
    /// Blatt «Sichern» bzw. Passwort offen, bis sein onDismiss kam (auch während es sich schliesst).
    @State private var sheetShown = false
    @State private var askAfterResult = false
    @State private var exportDocument: BackupDocument?
    @State private var exporting = false
    @State private var importing = false
    @State private var pendingRestore: Data?
    /// Ungültige Einträge und solche über den Grenzen in der gewählten Sicherung (die Rückfrage nennt sie).
    @State private var pendingPreview = BackupManager.RestorePreview()
    /// Gewählte Sicherung war verschlüsselt; ohne Passwort warnt die Rückfrage (keine Echtheitsprüfung).
    @State private var pendingEncrypted = false
    @State private var confirmRestore = false
    /// Blatt «Sichern» offen; Vorwahl «Mit Passwort schützen» (true mit Portfolio-Daten).
    @State private var exportOptions: ExportOptions?
    /// Im Blatt gewählter Schutz; die Dateiablage öffnet erst nach dem Schliessen des Blatts.
    @State private var exportChoice: ExportChoice?
    /// Verschlüsselte Sicherung gewählt: Datei bis zur Passwort-Eingabe.
    @State private var encryptedFile: EncryptedFile?
    /// Datei wird gelesen, geprüft bzw. gebaut (abseits des Haupt-Threads, `BackupFileWork`): Fortschritt, Knöpfe aus.
    @State private var working = false

    init() {}

    var body: some View {
        SettingsSubPage(title: L("backup_title")) {
            SettingsHint(text: L("backup_hint"), top: Spacing.sm)
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
                        Task {
                            working = true
                            var ok = true
                            if let written { ok = await BackupFileWork.verified(url, expected: written) }
                            working = false
                            guard ok else { return fail("backup_not_verified") }
                            Haptics.play(.success)
                            show("backup_exported", kind: .success)
                        }
                    case .failure(let error):
                        if !Self.isCancel(error) { fail("backup_failed") }
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
                        Task {
                            working = true
                            // Höchstens 64 MB (`BackupLimits`), gelesen und geprüft abseits des Haupt-Threads
                            let checked = await BackupFileWork.pick(url)
                            working = false
                            picked(checked)
                        }
                    case .failure(let error):
                        if !Self.isCancel(error) { fail("backup_failed") }
                    }
                }
            }
            .disabled(working)
            .overlay { if working { ProgressView() } }
            .settingsAnchor("backup.actions")
            .padding(.bottom, 12)
        }
        .toast($toast)
        .alert(L("backup_title"), isPresented: resultShown, presenting: resultDialog) { _ in
            Button(L("action_close"), role: .cancel) { closeResult() }
        } message: { Text($0) }
        .sheet(item: $exportOptions, onDismiss: {
            // Dateiablage des Systems, mit gewähltem Schutz
            if let choice = exportChoice {
                exportChoice = nil
                export(password: choice.password)
            }
            sheetClosed()
        }) { options in
            BackupExportSheet(defaultProtect: options.defaultProtect) { password in
                exportChoice = ExportChoice(password: password)
            }
        }
        .sheet(item: $encryptedFile, onDismiss: {
            // Passwort richtig: Rückfrage «Ersetzen?» erst nach dem Schliessen des Blatts
            // (nicht, wenn unterdessen wieder gesperrt)
            if pendingRestore != nil && !lock.locked { confirmRestore = true }
            sheetClosed()
        }) { file in
            BackupImportPasswordSheet { password in
                // Schlüsselableitung (Argon2id 64 MiB, bis wenige s) und Prüfen im Hintergrund — die Oberfläche bleibt bedienbar
                let backgroundTask = BackgroundTaskGuard.begin("backupDecrypt")
                let result = await BackupFileWork.decrypt(file.data, password: password)
                backgroundTask.end()
                // Während der Prüfung wieder gesperrt: Blatt ist schon zu, keine Rückfrage
                if lock.locked {
                    encryptedFile = nil
                    return .handled
                }
                do {
                    // Sicherung einer neueren App-Version: schon dort abgelehnt, vor der Rückfrage (wie Android)
                    let checked = try result.get()
                    pendingPreview = checked.preview
                    pendingEncrypted = true
                    pendingRestore = checked.plain
                    return .accepted
                } catch BackupCrypto.Failure.wrongPassword {
                    return .wrongPassword
                } catch {
                    // Kein erneuter Versuch: zu wenig Speicher, zu gross, neuere/unbekannte Version, kaputt (`failureKey`)
                    encryptedFile = nil
                    fail(Self.failureKey(error))
                    return .handled
                }
            }
        }
        .alert(L("backup_import"), isPresented: $confirmRestore, presenting: pendingRestore) { json in
            Button(L("backup_import"), role: .destructive) {
                restore(json, acceptInvalid: pendingPreview.invalid, encrypted: pendingEncrypted)
            }
            .accessibilityIdentifier(UiTestTags.backupRestoreConfirm)
            Button(L("action_cancel"), role: .cancel) { pendingRestore = nil }
        } message: { _ in
            Text(Self.confirmText(pendingPreview, encrypted: pendingEncrypted))
        }
        // Wartendes Ergebnis zeigen, sobald Rückfrage bzw. Dateiauswahl zu sind (statt fester Wartezeit)
        .onChange(of: [confirmRestore, importing, exporting]) { _, _ in showPendingResult() }
        .onDisappear(perform: leftPage)
        // Wieder gesperrt (Hintergrund-Limit), während ein Blatt oder die Rückfrage offen ist:
        // schliessen wie die Erfassen-Blätter
        .onChange(of: lock.locked) { _, locked in
            if locked { dropOpenDialogs() }
        }
        .backupCryptoSelfCheck()
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

    /// Sichern: Enthält die Datei Portfolio-Daten (Trades oder beobachtete Adressen inkl. xpub) und ist
    /// gesperrt, erst entsperren — wie Android.
    private func startExport() {
        let hasPortfolio = PortfolioLockPolicy.backupHasPortfolioData(
            transactions: data.portfolio.count, watchedAddresses: data.watchedAddresses.count)
        Task {
            let open = await AppLock.shared.requireUnlock { locked in
                PortfolioLockPolicy.backupExportNeedsUnlock(locked: locked, hasPortfolioData: hasPortfolio)
            }
            guard open else { return }
            sheetShown = true
            exportOptions = ExportOptions(defaultProtect: hasPortfolio)
        }
    }

    /// Nach dem Blatt «Sichern»: Datei bauen (mit Passwort verschlüsselt) und ablegen lassen. Auf dem MainActor nur die
    /// Wertkopie des Stands; Bauen, Kodieren und Verschlüsseln (Argon2id mit 64 MiB, einige 100 ms bis wenige Sekunden)
    /// im Hintergrund (`BackupFileWork.build`; nie eine Datei, die sich danach nicht mehr einlesen lässt, wie Android).
    private func export(password: String?) {
        let input = BackupManager.exportInput(data)
        Task {
            // Zu Ende führen, auch wenn die App währenddessen in den Hintergrund geht
            let backgroundTask = BackgroundTaskGuard.begin("backupExport")
            defer { backgroundTask.end() }
            working = true
            let result = await BackupFileWork.build(input, password: password)
            working = false
            switch result {
            case .success(let file):
                presentExport(file)
            case .failure(BackupCrypto.Failure.notEnoughMemory):
                fail("backup_not_enough_memory")
            case .failure(BackupLimits.Failure.exportTooLarge):
                fail("backup_export_too_large")
            case .failure:
                fail("backup_failed")
            }
        }
    }

    private func presentExport(_ file: Data) {
        #if DEBUG
        // UI-Test: in die Datei des Tests schreiben und zurücklesen statt die Dateiablage zu öffnen
        if let url = UITesting.exportURL {
            show(UITesting.writeExport(file, to: url))
            return
        }
        #endif
        exportDocument = BackupDocument(data: file)
        exporting = true
    }

    /// Datei gelesen und geprüft (`BackupFileWork.pick`): verschlüsselt → Passwort, sonst (unverschlüsselte Sicherung)
    /// gleich die Rückfrage.
    private func picked(_ result: Result<BackupFileWork.Picked, BackupFileWork.PickFailure>) {
        // Unterwegs wieder gesperrt: verwerfen (Wiederherstellen verlangt Entsperren)
        guard !lock.locked else { return }
        switch result {
        case .success(.encrypted(let file)):
            pendingRestore = nil
            sheetShown = true
            encryptedFile = EncryptedFile(data: file)
        case .success(.plain(let file, let preview)):
            pendingPreview = preview
            pendingEncrypted = false
            pendingRestore = file
            confirmRestore = true
        case .failure(.read(let error)):
            fail(error as? BackupLimits.Failure == .tooLarge ? "backup_too_large" : "backup_failed")
        case .failure(.check(let error)):
            // Zu gross, Sicherung einer neueren App-Version (nichts übernommen) oder unlesbar (`failureKey`)
            fail(Self.failureKey(error))
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
        pendingPreview = BackupManager.RestorePreview()
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
            Haptics.play(.success)
            let text = Self.restoredText(result)
            let asDialog = Self.restoredAsDialog(result, text: text)
            present(text, asDialog: asDialog, kind: .success)
            // Wie Android: Erlaubnis für Mitteilungen erfragen (eine Neuinstallation hat nie gefragt — sonst
            // kommen Alarme der eingespielten Paare nie an) und gleich Kurse holen (Widgets, Alarme)
            // (erst die kurze Erklärung, `NotificationPrePrompt`; nach einem Ergebnis-Dialog erst nach dessen Schliessen)
            if result.watches > 0 {
                if asDialog { askAfterResult = true } else { NotificationPrePrompt.request(.pairsAdded) }
                Task { await data.refreshAll() }
            }
        } catch BackupVersion.Failure.newer {
            fail("backup_restore_newer_version")
        } catch BackupManager.Failure.journalUnavailable {
            // Ohne Journal nichts ersetzt: später erneut
            fail("backup_restore_journal_unavailable")
        } catch {
            fail("backup_failed")
        }
    }

    /// Fehlgeschlagen: Hinweis mit Fehler-Haptik (wie Android `BackupMessage.haptic`).
    private func fail(_ key: String) {
        Haptics.play(.error)
        show(key, kind: .error)
    }

    /// Meldung `key`: lang als Dialog, sonst als Toast der Art `kind` (`BackupResultPresentation`, wie Android).
    private func show(_ key: String, kind: SnackbarKind = .info) {
        let text = L(key)
        let asDialog = BackupResultPresentation.asDialog(key: key, text: text, fontScale: Self.fontScale())
        present(text, asDialog: asDialog, kind: kind)
    }

    private func present(_ text: String, asDialog: Bool, kind: SnackbarKind = .info) {
        guard asDialog else {
            toast = ToastMessage(text, kind: kind)
            return
        }
        pendingResult = text
        showPendingResult()
    }

    /// Wartendes Ergebnis zeigen, sobald Rückfrage, Blatt, Dateiauswahl und voriges Ergebnis zu sind (sonst erscheint
    /// der Dialog nicht). Rückfrage, Dateiauswahl und Dialog melden «zu» schon beim Start der Schliess-Animation: erst
    /// danach zeigen (`presentDelayNanos`), erneut geprüft. `immediately`: onDismiss eines Blatts (Animation fertig).
    private func showPendingResult(immediately: Bool = false) {
        guard pendingResult != nil, canShowResult else { return }
        guard !immediately else { return takePendingResult() }
        guard !resultScheduled else { return }
        resultScheduled = true
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: BackupResultPresentation.presentDelayNanos)
            resultScheduled = false
            if canShowResult { takePendingResult() }  // sonst ruft das nächste «zu» erneut
        }
    }

    private func takePendingResult() {
        guard let text = pendingResult else { return }
        pendingResult = nil
        resultDialog = text
    }

    private var canShowResult: Bool {
        BackupResultPresentation.canPresent(confirmOpen: confirmRestore, sheetOpen: sheetShown || exportOptions != nil
            || encryptedFile != nil, pickerOpen: importing || exporting, resultOpen: resultDialog != nil)
    }

    private func sheetClosed() {
        sheetShown = false
        showPendingResult(immediately: true)
    }

    /// Seite verlassen, solange die Mitteilungs-Frage nach dem Ergebnis aussteht: trotzdem fragen (eigenes Fenster).
    private func leftPage() {
        guard askAfterResult else { return }
        askAfterResult = false
        NotificationPrePrompt.request(.pairsAdded)
    }

    private var resultShown: Binding<Bool> {
        Binding(get: { resultDialog != nil }, set: { if !$0 { closeResult() } })
    }

    /// Ergebnis-Dialog zu: erst jetzt nach Mitteilungen fragen (falls Paare eingespielt), dann wartendes Ergebnis.
    private func closeResult() {
        resultDialog = nil
        if askAfterResult {
            askAfterResult = false
            NotificationPrePrompt.request(.pairsAdded)
        }
        showPendingResult()
    }
}
