import SwiftUI

/// Einstellungen › Portfolio › «Eigener Server für Adressen» (nur Bitcoin, `AddressServer`) — wie Android
/// `AddressServerCard`. Leer = öffentliche Knoten wie bisher; gesetzt = nur dorthin, ohne Rückfall.
@MainActor
struct SettingsAddressServerCard: View {
    @EnvironmentObject private var data: AppData
    @State private var input = ""
    @State private var test: TestState = .idle

    /// Ergebnis von «Testen».
    enum TestState: Equatable {
        case idle, running, ok(Int64), failed
    }

    var body: some View {
        let check = AddressServer.check(input)
        let saved = data.settings.addressServerBtc
        SettingsCard(title: L("settings_address_server_title")) {
            VStack(alignment: .leading, spacing: 0) {
                SettingsHint(text: L("settings_address_server_hint"))
                TextField(L("settings_address_server_field"), text: $input)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .textContentType(.URL)
                    .submitLabel(.done)
                    .onChange(of: input) { _, _ in inputChanged() }
                if let problem = check.problem {
                    Text(Self.message(problem))
                        .font(.footnote)
                        .foregroundStyle(AppColors.error)
                        .padding(.top, 4)
                }
                HStack(spacing: Spacing.lg) {
                    Button(L("action_save")) { save(check.url) }
                        .disabled(check.problem != nil || check.url == saved)
                    Button(L("settings_address_server_test")) { runTest(check.url) }
                        .disabled(check.problem != nil || check.url == nil || test == .running)
                }
                .font(.body)
                .padding(.vertical, Spacing.sm)
                SettingsHint(text: statusText(saved), top: 0)
                testResult
            }
            .padding(.bottom, Spacing.sm)
        }
        .settingsAnchor("portfolio.address_server")
        .onAppear { input = data.settings.addressServerBtc ?? "" }
        // Von aussen geändert (z. B. Sicherung wiederhergestellt): Feld nachziehen
        .onChange(of: data.settings.addressServerBtc) { _, value in input = value ?? "" }
    }

    @ViewBuilder private var testResult: some View {
        switch test {
        case .idle: EmptyView()
        case .running: SettingsHint(text: L("settings_address_server_testing"), top: 0)
        case .ok(let height): SettingsHint(text: L("settings_address_server_test_ok", String(height)), top: 0)
        case .failed:
            Text(L("settings_address_server_test_failed"))
                .font(.footnote)
                .foregroundStyle(AppColors.warningText)
                .padding(.bottom, Spacing.sm)
        }
    }

    private func statusText(_ saved: String?) -> String {
        saved.map { L("settings_address_server_active", AddressServer.host($0)) } ?? L("settings_address_server_none")
    }

    private func inputChanged() {
        if input.count > AddressServer.maxLength { input = String(input.prefix(AddressServer.maxLength)) }
        if test != .running { test = .idle }
    }

    /// Speichern (nil = öffentliche Knoten); das Feld zeigt danach die normalisierte Form.
    private func save(_ url: String?) {
        data.settings.addressServerBtc = url
        input = url ?? ""
        test = .idle
        Haptics.play(.success)
    }

    /// Blockhöhe abfragen (nennt keine Adresse).
    private func runTest(_ url: String?) {
        guard let url, test != .running else { return }
        test = .running
        Task {
            let height = await WatchedAddressSource.testBtcServer(url)
            test = height.map { .ok($0) } ?? .failed
        }
    }

    /// Meldung zu einem Prüfergebnis — wie Android `addressServerProblemText`.
    static func message(_ problem: AddressServer.Problem) -> String {
        switch problem {
        case .NOT_HTTPS: return L("settings_address_server_error_https")
        case .USER_INFO: return L("settings_address_server_error_userinfo")
        case .QUERY_OR_FRAGMENT: return L("settings_address_server_error_query")
        case .INVALID: return L("settings_address_server_error_invalid")
        }
    }
}
