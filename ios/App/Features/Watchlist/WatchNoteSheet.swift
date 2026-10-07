import SwiftUI

/// Notiz zu einem Paar bearbeiten (#233) — wie `NoteDialog` in Android.
/// Leer speichern oder «Notiz entfernen» löscht sie; höchstens `Watch.noteMax` Zeichen.
struct WatchNoteSheet: View {
    let initial: String?
    let onSave: (String?) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent
    @State private var text: String
    @FocusState private var focused: Bool

    init(initial: String?, onSave: @escaping (String?) -> Void) {
        self.initial = initial
        self.onSave = onSave
        _text = State(initialValue: initial ?? "")
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 8) {
                TextField(L("note_hint"), text: $text, axis: .vertical)
                    .lineLimit(3...6)
                    .focused($focused)
                    .textInputAutocapitalization(.sentences)
                    .onChange(of: text) { _, value in
                        if value.count > Watch.noteMax { text = String(value.prefix(Watch.noteMax)) }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .background(AppColors.container, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                HStack {
                    if initial != nil {
                        Button(L("note_remove"), role: .destructive) {
                            onSave(nil)
                            dismiss()
                        }
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AppColors.error)
                    }
                    Spacer()
                    Text("\(text.count) / \(Watch.noteMax)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .background(AppColors.background.ignoresSafeArea())
            .navigationTitle(L("note_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_save")) {
                        onSave(Watch.validNote(text))
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
            .onAppear { focused = true }
        }
        .tint(accent.primary)
    }
}
