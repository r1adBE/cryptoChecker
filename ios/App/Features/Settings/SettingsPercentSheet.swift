import SwiftUI

/// Eigene Melde-Schwelle eingeben — wie der Dialog von Android `PercentChoiceRow`: «Sichern» bleibt
/// gesperrt, solange die Eingabe nicht 0 – 100 ergibt, und das Feld zeigt den Hinweis. Seit Build 99;
/// vorher schloss ein Alert bei «150» still, ohne etwas zu ändern.
struct SettingsPercentSheet: View {
    let title: String
    let onSave: (Double) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent
    @State private var text: String
    @FocusState private var focused: Bool

    init(title: String, initial: String, onSave: @escaping (Double) -> Void) {
        self.title = title
        self.onSave = onSave
        _text = State(initialValue: SettingsPercentOption.filterInput(initial))
    }

    private var parsed: Double? { SettingsPercentOption.parse(text) }
    private var isError: Bool { SettingsPercentOption.inputError(text) }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: Spacing.sm) {
                HStack(spacing: Spacing.sm) {
                    TextField("0", text: $text)
                        .keyboardType(.decimalPad)
                        .focused($focused)
                        .onChange(of: text) { _, value in
                            let filtered = SettingsPercentOption.filterInput(value)
                            if filtered != value { text = filtered }
                        }
                        .onSubmit(save)
                    Text(verbatim: "%")
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                .padding(.horizontal, Spacing.md)
                .padding(.vertical, 12)
                .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                        .stroke(AppColors.error, lineWidth: isError ? 1.5 : 0)
                )
                if isError {
                    Text(L("settings_percent_invalid"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.error)
                } else {
                    Text("0 = " + L("settings_notification_change_always"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, Spacing.lg)
            .padding(.top, 8)
            .background(AppColors.background.ignoresSafeArea())
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_save"), action: save)
                        .fontWeight(.semibold)
                        .disabled(parsed == nil)
                }
            }
            .onAppear { focused = true }
        }
        .tint(accent.primary)
        .presentationDetents([.medium])
    }

    private func save() {
        guard let value = parsed else { return }
        onSave(value)
        dismiss()
    }
}
