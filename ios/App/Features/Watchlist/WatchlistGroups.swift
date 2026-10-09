import SwiftUI

// Gruppen der Merkliste — wie `WatchlistGroups.kt` (GroupChips, GroupDialog). Bestände zeigt die Merkliste
// seit 16.2.2 nicht mehr; sie gehören in den Portfolio-Tab.

// MARK: Gruppen-Chips

/// Gruppen-Auswahl oben: «Alle · Favoriten · Gruppe 1 · Gruppe 2 … · +», waagrecht scrollbar.
/// Tippen filtert, lange drücken öffnet «Gruppe bearbeiten», «+» legt eine an.
/// Nur was es gibt (wie Android): «Favoriten» erst mit einem Favoriten, «+» erst mit einer Gruppe
/// (die erste entsteht über das Aktionsblatt eines Paars); gibt es weder noch, bleibt die Zeile leer.
@MainActor
struct WatchlistGroupChips: View {
    let groups: [String]
    /// nil = «Alle».
    let selected: String?
    var hasFavorites: Bool = false
    let onSelect: (String?) -> Void
    /// Lange drücken auf eine Gruppe (nicht «Alle»).
    var onEdit: ((String) -> Void)? = nil
    /// «+»: neue Gruppe.
    var onAdd: (() -> Void)? = nil

    @Environment(\.appAccent) private var accent

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
              if Self.showsChips(groups: groups, selected: selected, hasFavorites: hasFavorites) {
                chip(L("group_all"), isSelected: selected == nil, action: { onSelect(nil) })
                // Auch ohne Favoriten sichtbar, solange die Ansicht gewählt ist (sonst kein Weg zurück)
                if hasFavorites || WatchFilter.isFavorites(selected) {
                    chip(L("group_favorites"), isSelected: WatchFilter.isFavorites(selected),
                         action: { onSelect(WatchFilter.favorites) })
                }
                ForEach(groups, id: \.self) { group in
                    chip(group, isSelected: selected == group, action: { onSelect(group) },
                         longPress: onEdit.map { edit -> () -> Void in { edit(group) } })
                }
                if let onAdd, !groups.isEmpty {
                    Button(action: onAdd) {
                        Image(systemName: "plus")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .frame(width: 34, height: 32)
                            .background(AppColors.containerHigh, in: Capsule())
                            // Tippfläche 44 pt hoch, sichtbar bleibt das kleine Feld
                            .contentShape(Capsule().inset(by: -6))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L("group_add"))
                }
              }
            }
            .padding(.vertical, 2)
        }
        .scrollClipDisabled()
        .sensoryFeedback(.selection, trigger: selected)
    }

    /// Chips zeigen? Nur wenn es neben «Alle» etwas zu wählen gibt (oder eine Auswahl aktiv ist).
    static func showsChips(groups: [String], selected: String?, hasFavorites: Bool) -> Bool {
        !groups.isEmpty || hasFavorites || selected != nil
    }

    private func chip(_ title: String, isSelected: Bool, action: @escaping () -> Void,
                      longPress: (() -> Void)? = nil, spoken: String? = nil) -> some View {
        Text(title)
            .font(.subheadline.weight(isSelected ? .semibold : .regular))
            .lineLimit(1)
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.sm)
            .foregroundStyle(isSelected ? accent.onContainer : AppColors.onSurface)
            .background(isSelected ? accent.container : AppColors.containerHigh, in: Capsule())
            .overlay(Capsule().strokeBorder(isSelected ? accent.primary.opacity(0.6) : .clear, lineWidth: 1))
            // Tippfläche höher als der Chip (44 pt), ohne die Zeile höher zu machen
            .contentShape(Rectangle().inset(by: -6))
            // Tippen und langes Drücken getrennt (ein Button kennt kein langes Drücken)
            .onTapGesture(perform: action)
            .onLongPressGesture(minimumDuration: 0.45) {
                guard let longPress else { return }
                WatchlistHaptics.impact()
                longPress()
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(spoken ?? title)
            .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
            .accessibilityAction(named: Text(L("group_edit_title"))) {
                if let longPress { longPress() }
            }
    }
}

// MARK: Gruppe wählen

/// Gruppe wählen: «Keine Gruppe», bestehende Gruppen oder eine neue anlegen.
/// Eine Auswahl gilt sofort; nur die neue Gruppe braucht «Speichern» — wie `GroupDialog`.
@MainActor
struct WatchGroupSheet: View {
    /// Höchstlänge eines Gruppennamens (wie `MAX_GROUP_NAME`).
    static let maxNameLength = 24

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
                .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
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
        WatchlistHaptics.selection()
        onSelect(group)
        dismiss()
    }

    private func saveNew() {
        guard !trimmed.isEmpty else { return }
        pick(trimmed)
    }
}
