import SwiftUI
import UniformTypeIdentifiers

/// «Automatisch sichern» (`AutoBackup`): Schalter; beim Einschalten zuerst Ordner wählen (Dateien-App, auch iCloud
/// Drive), dann Passwort (immer verschlüsselt). Darunter Ordner, Stand der letzten Sicherung, «Jetzt sichern» und
/// «Ordner ändern». Mit Portfolio-Daten und Sperre erst entsperren (wie «Sichern»). Wie Android `AutoBackupSection`.
@MainActor
struct AutoBackupSection: View {
    @AppDataEnv private var data
    @ObservedObject private var auto = AutoBackup.shared

    /// Ordnerwahl offen; true = fürs Einschalten, false = nur Ordner ändern.
    @State private var choosingFolder = false
    @State private var forEnable = true
    /// Gewählter Ordner beim Einschalten, bis das Passwort feststeht.
    @State private var pickedFolder: PickedFolder?

    struct PickedFolder: Identifiable {
        let url: URL
        var id: String { url.absoluteString }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            SwitchRow(
                title: L("auto_backup_title"),
                subtitle: L("auto_backup_hint"),
                isOn: Binding(get: { auto.state.enabled }, set: { on in
                    if on { pickFolder(enable: true) } else { auto.disable() }
                })
            )
            .settingsAnchor("backup.auto")
            if auto.state.enabled {
                if let name = auto.state.folderName { SettingsHint(text: name) }
                Text(statusText)
                    .font(.footnote)
                    .foregroundStyle(auto.state.lastFailed ? AppColors.error : AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                Button { auto.backUpNow(data) } label: {
                    Text(L("auto_backup_now"))
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(TonalButtonStyle())
                .disabled(auto.running)
                .overlay { if auto.running { ProgressView() } }
                Button(L("auto_backup_change_folder")) { pickFolder(enable: false) }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Spacing.xs)
            }
        }
        .padding(.bottom, 12)
        .fileImporter(isPresented: $choosingFolder, allowedContentTypes: [.folder]) { result in
            guard case .success(let url) = result else { return }
            if forEnable { pickedFolder = PickedFolder(url: url) } else { auto.changeFolder(url, data: data) }
        }
        .sheet(item: $pickedFolder) { folder in
            // Immer mit Passwort: die Dateien liegen in einem fremden Ordner, oft in der Cloud
            BackupExportSheet(defaultProtect: true, hasPortfolioData: true, note: L("auto_backup_password_hint")) { password in
                if let password { auto.enable(folder: folder.url, password: password, data: data) }
            }
        }
    }

    private var statusText: String {
        if auto.state.lastFailed { return L("auto_backup_failed") }
        guard auto.state.lastSuccessAt > 0 else { return L("auto_backup_never") }
        let date = Date(timeIntervalSince1970: Double(auto.state.lastSuccessAt) / 1000)
        return L("auto_backup_last", date.formatted(date: .abbreviated, time: .shortened))
    }

    /// Mit Portfolio-Daten und Sperre erst entsperren (wie «Sichern»), dann die Ordnerwahl.
    private func pickFolder(enable: Bool) {
        let hasPortfolio = PortfolioLockPolicy.backupHasPortfolioData(
            transactions: data.portfolio.count, watchedAddresses: data.watchedAddresses.count,
            portfolioAlarms: data.portfolioAlarms.count)
        Task {
            let open = await AppLock.shared.requireUnlock { locked in
                PortfolioLockPolicy.backupExportNeedsUnlock(locked: locked, hasPortfolioData: hasPortfolio)
            }
            guard open else { return }
            forEnable = enable
            choosingFolder = true
        }
    }
}
