import SwiftUI

// MARK: Gruppe wählen

/// Gruppe wählen: «Keine Gruppe», bestehende Gruppen oder eine neue anlegen.
/// Eine Auswahl gilt sofort; nur die neue Gruppe braucht «Speichern» — wie `GroupDialog`.
@MainActor
struct WatchGroupSheet: View {
    /// Höchstlänge eines Gruppennamens (wie `GROUP_NAME_MAX`).
    static let maxNameLength = Watch.groupNameMax

    let current: String?
    let groups: [String]
    let onSelect: (String?) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent
    @State private var creating = false
    @State private var name = ""
    @FocusState private var nameFocused: Bool

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    option(L("group_none"), icon: "tray", selected: current == nil && !creating) { pick(nil) }
                    ForEach(groups, id: \.self) { group in
                        RowDivider()
                        option(group, icon: "folder", selected: current == group && !creating) { pick(group) }
                    }
                    RowDivider()
                    option(L("group_new"), icon: "plus", selected: creating) {
                        withAnimation(.easeInOut(duration: 0.2)) { creating = true }
                        // Feld erst nach dem Einblenden fokussieren
                        Task { @MainActor in
                            try? await Task.sleep(nanoseconds: 250_000_000)
                            nameFocused = true
                        }
                    }
                    if creating {
                        TextField(L("group_name"), text: $name)
                            .focused($nameFocused)
                            .textInputAutocapitalization(.words)
                            .submitLabel(.done)
                            .onSubmit(saveNew)
                            .onChange(of: name) { _, value in
                                if value.count > Self.maxNameLength {
                                    name = String(value.prefix(Self.maxNameLength))
                                }
                            }
                            .padding(.horizontal, Spacing.md)
                            .padding(.vertical, 12)
                            .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .padding(.bottom, 12)
                            .transition(.opacity.combined(with: .move(edge: .top)))
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 4)
                .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                .padding(.horizontal, Spacing.lg)
                .padding(.top, 8)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(AppColors.background.ignoresSafeArea())
            .navigationTitle(L("group_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                }
                if creating {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L("action_save"), action: saveNew)
                            .fontWeight(.semibold)
                            .disabled(trimmed.isEmpty)
                    }
                }
            }
        }
        .tint(accent.primary)
    }

    private func option(_ title: String, icon: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.body.weight(.medium))
                    .foregroundStyle(accent.primary)
                    .frame(width: 26)
                Text(title)
                    .font(.body)
                    .foregroundStyle(AppColors.onSurface)
                    .lineLimit(1)
                Spacer(minLength: 8)
                if selected {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(accent.primary)
                        .transition(.scale.combined(with: .opacity))
                }
            }
            .padding(.vertical, Spacing.md)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func pick(_ group: String?) {
        Haptics.play(.selection)
        onSelect(group)
        dismiss()
    }

    private func saveNew() {
        guard !trimmed.isEmpty else { return }
        pick(trimmed)
    }
}
