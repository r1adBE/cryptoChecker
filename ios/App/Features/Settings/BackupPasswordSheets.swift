import SwiftUI

/*
 * Runde 25: Passwortschutz der Sicherungsdatei (BackupCrypto) — wie `BackupDialogs.kt`.
 * Passwörter nur im `@State` des Blatts, nie in einer Meldung.
 */

/// Vor dem Sichern: «Mit Passwort schützen» (vorgewählt, wenn Portfolio-Daten dabei sind),
/// Passwort und Wiederholung (mindestens `BackupCrypto.minPasswordLength` Zeichen).
/// `onConfirm` erhält das Passwort oder nil (ohne Schutz).
struct BackupExportSheet: View {
    let onConfirm: (String?) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent
    @State private var protect: Bool
    @State private var password = ""
    @State private var repeated = ""
    @State private var visible = false

    init(defaultProtect: Bool, onConfirm: @escaping (String?) -> Void) {
        self.onConfirm = onConfirm
        _protect = State(initialValue: defaultProtect)
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
                        BackupPasswordInput(
                            title: L("backup_password"),
                            text: $password,
                            visible: $visible,
                            hint: L("backup_password_min", BackupCrypto.minPasswordLength),
                            isError: tooShort
                        )
                        let mismatch = !repeated.isEmpty && repeated != password
                        BackupPasswordInput(
                            title: L("backup_password_repeat"),
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
                        let chosen: String? = protect ? password : nil
                        dismiss()
                        onConfirm(chosen)
                    }
                    .fontWeight(.semibold)
                    .disabled(!canExport)
                }
            }
        }
        .tint(accent.primary)
        .presentationDetents([.large])
    }
}

/// Verschlüsselte Sicherung gewählt: Passwort abfragen. `check` prüft das Passwort und gibt
/// true zurück, wenn es passt; sonst bleibt das Blatt mit Hinweis offen (erneut versuchen).
struct BackupImportPasswordSheet: View {
    let check: (String) -> Bool

    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent
    @State private var password = ""
    @State private var visible = false
    @State private var wrong = false

    init(check: @escaping (String) -> Bool) {
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
                        .fontWeight(.semibold)
                        .disabled(password.isEmpty)
                }
            }
        }
        .tint(accent.primary)
        .presentationDetents([.medium, .large])
    }

    private func submit() {
        guard !password.isEmpty else { return }
        if check(password) {
            dismiss()
        } else {
            // Falsch oder Datei verändert: Feld leeren, Hinweis zeigen, nochmals versuchen
            UINotificationFeedbackGenerator().notificationOccurred(.error)
            wrong = true
            password = ""
        }
    }
}

/// Passwortfeld mit «Anzeigen»/«Verbergen» und Hinweis darunter.
private struct BackupPasswordInput: View {
    let title: String
    @Binding var text: String
    @Binding var visible: Bool
    var hint: String?
    var isError = false
    var onSubmit: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Group {
                    if visible {
                        TextField(title, text: $text)
                    } else {
                        SecureField(title, text: $text)
                    }
                }
                .textContentType(.password)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.done)
                .onSubmit(onSubmit)
                Button {
                    visible.toggle()
                } label: {
                    Image(systemName: visible ? "eye.slash" : "eye")
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L(visible ? "backup_password_hide" : "backup_password_show"))
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.md)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay {
                if isError {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(AppColors.error, lineWidth: 1)
                }
            }
            if let hint {
                Text(hint)
                    .font(.caption)
                    .foregroundStyle(isError ? AppColors.error : AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
