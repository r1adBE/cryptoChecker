import SwiftUI

/// Notiz zu einem Paar bearbeiten (#233) — wie `NoteDialog` in Android.
/// Leer speichern oder «Notiz entfernen» löscht sie; höchstens `Watch.noteMax` Zeichen.
/// IOS5-R02: schliesst erst nach bestätigter Rückfrage F-08; «Abbrechen» = Blatt bleibt mit dem Text.
@MainActor
struct WatchNoteSheet: View {
    let initial: String?
    /// Der Aufrufer fragt, speichert und ruft `close` (siehe `ConfirmedFinish`).
    let onSave: ConfirmedFinish<String?>

    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent
    @State private var text: String
    @FocusState private var focused: Bool
    /// Rückfrage steht aus: ein zweiter Tipp fragt nicht nochmals (nach «Abbrechen» wieder frei).
    @State private var saving = false

    init(initial: String?, onSave: @escaping ConfirmedFinish<String?>) {
        self.initial = initial
        self.onSave = onSave
        _text = State(initialValue: String((initial ?? "").prefix(Watch.noteMax)))
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 8) {
                TextField(placeholder: L("note_hint"), text: $text, axis: .vertical)
                    .lineLimit(3...6)
                    .focused($focused)
                    .textInputAutocapitalization(.sentences)
                    .onChange(of: text) { _, value in
                        if value.count > Watch.noteMax { text = String(value.prefix(Watch.noteMax)) }
                    }
                    .padding(.horizontal, Spacing.md)
                    .padding(.vertical, 12)
                    .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                HStack {
                    if initial != nil {
                        Button(L("note_remove"), role: .destructive, action: remove)
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(AppColors.error)
                    }
                    Spacer()
                    // Ziffern nach der App-Sprache (Arabisch/Persisch) wie Android `NoteDialog`; count = sichtbare Zeichen
                    Text(verbatim: "\(LocaleNumbers.integer(text.count)) / \(LocaleNumbers.integer(Watch.noteMax))")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, Spacing.lg)
            .padding(.top, 8)
            .background(AppColors.background.ignoresSafeArea())
            .navigationTitle(L("note_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_save"), action: save)
                        .fontWeight(.semibold)
                }
            }
            .onAppear { focused = true }
        }
        .tint(accent.primary)
    }

    private func save() { finish(Watch.validNote(text)) }

    private func remove() { finish(nil) }

    private func finish(_ note: String?) {
        guard !saving else { return }
        saving = true
        onSave(note, { dismiss() }, { saving = false })
    }
}
