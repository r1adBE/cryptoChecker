import SwiftUI

/// Einstellungen › Portfolio › «Eigener Server für Adressen» (nur Bitcoin, `AddressServer`) — wie Android
/// `AddressServerCard`. Leer = öffentliche Knoten wie bisher; gesetzt = nur dorthin, ohne Rückfall.
@MainActor
struct SettingsAddressServerCard: View {
    @EnvironmentObject private var data: AppData
    @State private var input = ""
    /// «Testen»: Zustand und laufender Abruf (`AddressServerTestRun`).
    @StateObject private var testRun = AddressServerTestRun()
    /// Rückfrage vor dem Wechsel auf öffentliche Knoten (Bitcoin-Adressen/xpub vorhanden).
    @State private var askPublic = false

    /// Ergebnis von «Testen» samt geprüfter URL (angezeigt nur zur gleichen Eingabe, `AddressServerRules.testShown`).
    enum TestState: Equatable {
        case idle, running(String), ok(String, Int64), failed(String)

        var url: String? {
            switch self {
            case .idle: return nil
            case .running(let url), .ok(let url, _), .failed(let url): return url
            }
        }

        var isRunning: Bool {
            if case .running = self { return true }
            return false
        }
    }

    private var test: TestState { testRun.state }

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
                    Button(L("action_save")) { requestSave(check.url, saved: saved) }
                        .disabled(check.problem != nil || check.url == saved)
                    Button(L("settings_address_server_test")) { runTest(check.url) }
                        .disabled(check.problem != nil || check.url == nil || test.isRunning)
                }
                .font(.body)
                .padding(.vertical, Spacing.sm)
                SettingsHint(text: statusText(saved), top: 0)
                testResult(check.url)
            }
            .padding(.bottom, Spacing.sm)
        }
        .settingsAnchor("portfolio.address_server")
        .alert(L("settings_address_server_public_title"), isPresented: $askPublic) {
            Button(L("settings_address_server_public_confirm")) { save(nil) }
            // «Abbrechen» behält den eigenen Server
            Button(L("action_cancel"), role: .cancel) {}
        } message: {
            Text(publicMessage)
        }
        .onAppear { input = data.settings.addressServerBtc ?? "" }
        // Von aussen geändert (z. B. Sicherung wiederhergestellt): Feld nachziehen
        .onChange(of: data.settings.addressServerBtc) { _, value in input = value ?? "" }
    }

    /// Testergebnis nur zur aktuell geprüften Eingabe (`inputUrl`).
    @ViewBuilder private func testResult(_ inputUrl: String?) -> some View {
        if AddressServerRules.testShown(test.url, inputUrl: inputUrl) {
            switch test {
            case .idle: EmptyView()
            case .running: SettingsHint(text: L("settings_address_server_testing"), top: 0)
            case .ok: SettingsHint(text: Self.resultText(test), top: 0)
            case .failed:
                Text(Self.resultText(test))
                    .font(.footnote)
                    .foregroundStyle(AppColors.warningText)
                    .padding(.bottom, Spacing.sm)
            }
        }
    }

    /// Text eines fertigen Tests; derselbe wird für VoiceOver angesagt (Android: Live-Region).
    static func resultText(_ state: TestState) -> String {
        switch state {
        case .ok(_, let height): return L("settings_address_server_test_ok", String(height))
        case .failed: return L("settings_address_server_test_failed")
        case .idle, .running: return ""
        }
    }

    /// Rückfrage-Text mit den öffentlichen Bitcoin-Hosts.
    private var publicMessage: String {
        L("settings_address_server_public_text", ChainBalance.hosts(.BTC).joined(separator: ", "))
    }

    private func statusText(_ saved: String?) -> String {
        saved.map { L("settings_address_server_active", AddressServer.host($0)) } ?? L("settings_address_server_none")
    }

    /// Zu lang (z. B. eingefügt): kürzen wie Android; Ergebnis und laufender Test gehören zur alten Eingabe.
    private func inputChanged() {
        let clamped = AddressServerRules.clamp(input)
        if clamped != input { input = clamped }
        resetTest()
    }

    private func resetTest() {
        testRun.reset()
    }

    /// «Speichern»: vom eigenen Server auf öffentliche Knoten mit beobachteten Bitcoin-Adressen/xpub erst fragen.
    /// Liste nicht lesbar: fragen (wie Android).
    private func requestSave(_ url: String?, saved: String?) {
        let count = AddressServerRules.btcCount(data.watchedAddressIntact ? data.watchedAddresses : nil)
        if AddressServerRules.confirmPublicSwitch(current: saved, next: url, btcCount: count) {
            askPublic = true
        } else {
            save(url)
        }
    }

    /// Speichern (nil = öffentliche Knoten); das Feld zeigt danach die normalisierte Form. Laufender Test wird
    /// verworfen, Sperre/Pause des Hosts aufgehoben.
    private func save(_ url: String?) {
        resetTest()
        data.settings.addressServerBtc = url
        input = url ?? ""
        if let url { Task { await WatchedAddressSource.shared.clearServerPauses(url) } }
        Haptics.play(.success)
    }

    /// Blockhöhe abfragen (nennt keine Adresse).
    private func runTest(_ url: String?) {
        guard let url else { return }
        testRun.start(url)
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
