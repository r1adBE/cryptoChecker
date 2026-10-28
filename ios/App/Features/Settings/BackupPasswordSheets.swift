import SwiftUI

/*
 * Passwortschutz der Sicherungsdatei (BackupCrypto) — wie `BackupDialogs.kt`.
 * Passwörter nur im `@State` des Blatts, nie in einer Meldung.
 */

/// Vor dem Sichern: «Mit Passwort schützen» (vorgewählt, wenn Portfolio-Daten dabei sind),
/// Passwort und Wiederholung (mindestens `BackupCrypto.minPasswordLength` Zeichen). Ein leicht zu
/// erratendes Passwort (`BackupPasswordStrength`) zeigt einen Fehler statt des Mindestlängen-Hinweises und sperrt.
/// Ohne Schutz folgt ein ausdrücklicher Bestätigungsschritt mit dem Inhalt der Datei (`hasPortfolioData`:
/// Portfolio und Adressen) — wie `BackupPlainConfirmDialog` (Android).
/// `onConfirm` erhält das Passwort oder nil (ohne Schutz).
struct BackupExportSheet: View {
    let onConfirm: (String?) -> Void
    let hasPortfolioData: Bool

    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent
    @State private var protect: Bool
    @State private var password = ""
    @State private var repeated = ""
    @State private var visible = false
    /// Bestätigungsschritt «Ohne Passwort sichern?» offen
    @State private var confirmPlain = false

    init(defaultProtect: Bool, hasPortfolioData: Bool? = nil, onConfirm: @escaping (String?) -> Void) {
        self.onConfirm = onConfirm
        self.hasPortfolioData = hasPortfolioData ?? defaultProtect
        _protect = State(initialValue: defaultProtect)
    }

    /// Inhalt der unverschlüsselten Datei als Aufzählung (immer Merkliste usw., dazu Portfolio und Adressen).
    private var plainMessage: String {
        var lines: [String] = [L("backup_plain_confirm_text"), "• " + L("backup_plain_contents_base")]
        if hasPortfolioData {
            lines.append("• " + L("backup_plain_contents_portfolio"))
            lines.append("• " + L("backup_plain_contents_addresses"))
        }
        return lines.joined(separator: "\n")
    }

    private var canExport: Bool {
        BackupCrypto.canExport(protect: protect, password: password, repeated: repeated)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.sm) {
                    SwitchRow(
                        title: L("backup_protect"),
                        subtitle: L("backup_protect_hint"),
                        isOn: $protect
                    )
                    if protect {
                        let tooShort = !password.isEmpty && !BackupCrypto.isPasswordLongEnough(password)
                        let weak = BackupPasswordStrength.showWeakHint(password)
                        BackupPasswordInput(
                            title: L("backup_password"),
                            testTag: UiTestTags.backupPassword,
                            text: $password,
                            visible: $visible,
                            hint: weak ? L("backup_password_too_weak") : L("backup_password_min", BackupCrypto.minPasswordLength),
                            isError: tooShort || weak
                        )
                        let mismatch = !repeated.isEmpty && repeated != password
                        BackupPasswordInput(
                            title: L("backup_password_repeat"),
                            testTag: UiTestTags.backupPasswordRepeat,
                            text: $repeated,
                            visible: $visible,
                            hint: mismatch ? L("backup_password_mismatch") : nil,
                            isError: mismatch
                        )
                        Text(L("backup_password_warning"))
                            .font(.footnote)
                            .foregroundStyle(AppColors.error)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.horizontal, Spacing.lg)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .background(AppColors.background.ignoresSafeArea())
            .navigationTitle(L("backup_export"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("backup_export")) {
                        guard protect else {
                            confirmPlain = true
                            return
                        }
                        let chosen: String = password
                        dismiss()
                        onConfirm(chosen)
                    }
                    .accessibilityIdentifier(UiTestTags.backupExportConfirm)
                    .fontWeight(.semibold)
                    .disabled(!canExport)
                }
            }
            .alert(L("backup_plain_confirm_title"), isPresented: $confirmPlain) {
                Button(L("backup_plain_confirm")) {
                    dismiss()
                    onConfirm(nil)
                }
                // Zurück zur Auswahl (Passwort doch noch setzen)
                Button(L("action_back"), role: .cancel) {}
            } message: {
                Text(plainMessage)
            }
        }
        .tint(accent.primary)
        .presentationDetents([.large])
    }
}

/// Ergebnis der Passwortprüfung beim Wiederherstellen einer verschlüsselten Sicherung.
enum BackupPasswordCheck: Equatable {
    /// Passwort passt: Blatt schliessen, Rückfrage folgt.
    case accepted
    /// Falsches Passwort (oder Datei verändert): Blatt bleibt mit Hinweis offen, erneut versuchen.
    case wrongPassword
    /// Anderer Fehler (zu gross, neuere Version, kaputt, zu wenig Speicher) oder wieder gesperrt: der Aufrufer
    /// hat das Blatt schon geschlossen und den Fehler selbst gemeldet (samt Fehler-Haptik).
    case handled

    /// Haptik, die das Blatt selbst spielt: nur beim falschen Passwort — sonst kämen bei `handled` zwei
    /// Fehler-Vibrationen (Android spielt je Fehlschlag genau eine).
    var sheetHaptic: HapticEvent? { self == .wrongPassword ? .error : nil }
}

/// Verschlüsselte Sicherung gewählt: Passwort abfragen. `check` prüft das Passwort (im Hintergrund);
/// bei `.wrongPassword` bleibt das Blatt mit Hinweis offen (erneut versuchen).
/// Während der Prüfung ist «Weiter» gesperrt — wie `BackupPasswordDialog` (checking) unter Android.
@MainActor
struct BackupImportPasswordSheet: View {
    let check: @MainActor (String) async -> BackupPasswordCheck

    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent
    @State private var password = ""
    @State private var visible = false
    @State private var wrong = false
    @State private var checking = false

    init(check: @escaping @MainActor (String) async -> BackupPasswordCheck) {
        self.check = check
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: Spacing.sm) {
                Text(L("backup_password_prompt"))
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
                BackupPasswordInput(
                    title: L("backup_password"),
                    testTag: UiTestTags.backupRestorePassword,
                    text: $password,
                    visible: $visible,
                    hint: wrong ? L("backup_password_wrong") : nil,
                    isError: wrong,
                    onSubmit: submit
                )
                Spacer(minLength: 0)
            }
            .padding(.horizontal, Spacing.lg)
            .padding(.top, 8)
            .background(AppColors.background.ignoresSafeArea())
            .navigationTitle(L("backup_password_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("backup_password_continue"), action: submit)
                        .accessibilityIdentifier(UiTestTags.backupRestoreSubmit)
                        .fontWeight(.semibold)
                        .disabled(password.isEmpty || checking)
                }
            }
        }
        .tint(accent.primary)
        .presentationDetents([.medium, .large])
    }

    private func submit() {
        guard !password.isEmpty, !checking else { return }
        let entered = password
        checking = true
        Task {
            let outcome = await check(entered)
            checking = false
            if let haptic = outcome.sheetHaptic { Haptics.play(haptic) }
            switch outcome {
            case .accepted:
                dismiss()
            case .wrongPassword:
                // Falsch oder Datei verändert: Feld leeren, Hinweis zeigen, nochmals versuchen
                wrong = true
                password = ""
            case .handled:
                // Blatt ist schon zu, Fehler samt Haptik hat der Aufrufer gemeldet
                break
            }
        }
    }
}

/// Passwortfeld mit «Anzeigen»/«Verbergen» und Hinweis darunter. `testTag`: Kennung des Felds für UI-Tests,
/// der Hinweis hat sie mit «_hint» (`UiTestTags.hint`) — wie Android `PasswordField`.
private struct BackupPasswordInput: View {
    let title: String
    let testTag: String
    @Binding var text: String
    @Binding var visible: Bool
    var hint: String?
    var isError = false
    /// Hinweis als Warnung (Bernstein), z. B. schwaches Passwort; das Feld bleibt ohne Fehlerrahmen.
    var isWarning = false
    var onSubmit: () -> Void = {}

    private var hintColor: Color {
        if isError { return AppColors.error }
        return isWarning ? AppColors.warningText : AppColors.onSurfaceVariant
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Group {
                    if visible {
                        TextField(placeholder: title, text: $text)
                    } else {
                        SecureField(placeholder: title, text: $text)
                    }
                }
                .accessibilityIdentifier(testTag)
                .textContentType(.password)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.done)
                .onSubmit(onSubmit)
                Button {
                    visible.toggle()
                } label: {
                    // Trefferfläche 44 × 44 pt (Bedienungshilfen); das Feld bleibt gleich hoch — der Rand ragt
                    // in den Innenabstand des Felds
                    Image(systemName: visible ? "eye.slash" : "eye")
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .frame(width: 32, height: 32)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                        .padding(-6)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L(visible ? "backup_password_hide" : "backup_password_show"))
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.md)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
            .overlay {
                if isError {
                    RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                        .stroke(AppColors.error, lineWidth: 1)
                }
            }
            if let hint {
                Text(hint)
                    .accessibilityIdentifier(UiTestTags.hint(testTag))
                    .font(.caption)
                    .foregroundStyle(hintColor)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
