import SwiftUI

// «Gruppe bearbeiten» — wie `GroupEditSheet.kt` und `GroupNameDialog.kt`.

/// Gruppennamen: Höchstlänge, Säubern und Schreibweise.
enum WatchGroupNames {
    /// Höchstlänge eines Gruppennamens (wie `GROUP_NAME_MAX`).
    static let maxLength = 24

    /// Getrimmt und gekürzt; nil, wenn leer.
    static func clean(_ name: String) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return Watch.validGroupName(String(trimmed.prefix(maxLength)))
    }

    /// Gibt es die Gruppe schon (Gross-/Kleinschreibung egal), gilt deren
    /// Schreibweise — so entstehen keine Fast-Doppel wie «Defi» und «DeFi».
    static func canonical(_ name: String, in groups: [String]) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return groups.first { $0.caseInsensitiveCompare(trimmed) == .orderedSame } ?? trimmed
    }
}

/// Ziel des Blatts «Gruppe bearbeiten».
struct WatchGroupEditTarget: Identifiable, Equatable {
    let name: String
    /// Neue Gruppe: noch ohne Paare, «Löschen» entfällt.
    let isNew: Bool
    var id: String { (isNew ? "new:" : "edit:") + name }
}

/// Name ändern und per Häkchen festlegen, welche Paare zur Gruppe gehören.
/// Paare aus einer anderen Gruppe zeigen diese klein an; ein Häkchen
/// verschiebt sie hierher. Erst «Fertig» speichert alles auf einmal;
/// Abbrechen bzw. Wegwischen verwirft die Änderungen.
@MainActor
struct WatchGroupEditSheet: View {
    let groupName: String
    let isNew: Bool

    @EnvironmentObject private var data: AppData
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent

    @State private var name: String
    @State private var checked: Set<Int64>
    @State private var query = ""
    @State private var renaming = false
    @State private var renameText = ""
    @State private var askDelete = false

    /// `initialMembers`: die Paare, die schon in dieser Gruppe sind.
    init(groupName: String, isNew: Bool, initialMembers: Set<Int64>) {
        self.groupName = groupName
        self.isNew = isNew
        _name = State(initialValue: groupName)
        _checked = State(initialValue: initialMembers)
    }

    var body: some View {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        // Alle Paare in der Reihenfolge der Merkliste (Favoriten zuerst)
        let all = data.watches
        let shown = trimmed.isEmpty ? all : all.filter { WatchlistSearch.matches($0, query: trimmed) }
        NavigationStack {
            List {
                Section {
                    renameRow
                }
                Section {
                    if shown.isEmpty {
                        Text(L("watchlist_search_empty", trimmed))
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                            .listRowBackground(AppColors.container)
                    }
                    ForEach(shown) { watch in
                        row(watch)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .scrollDismissesKeyboard(.interactively)
            .background(AppColors.background.ignoresSafeArea())
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: L("group_search_hint"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                }
                // Kopf: «Gruppe bearbeiten» klein, Name darunter
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 1) {
                        Text(L(isNew ? "group_add" : "group_edit_title"))
                            .font(.caption)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                        Text(name)
                            .font(.headline)
                            .foregroundStyle(AppColors.onSurface)
                            .lineLimit(1)
                    }
                }
            }
            .safeAreaInset(edge: .bottom) { footer }
            .alert(L("group_rename"), isPresented: $renaming) {
                TextField(L("group_name"), text: $renameText)
                    .textInputAutocapitalization(.words)
                Button(L("action_save")) { applyRename() }
                Button(L("action_cancel"), role: .cancel) {}
            }
            .alert(L("group_delete"), isPresented: $askDelete) {
                Button(L("group_delete"), role: .destructive) {
                    WatchlistHaptics.impact()
                    data.deleteGroup(groupName)
                    dismiss()
                }
                Button(L("action_cancel"), role: .cancel) {}
            } message: {
                Text(L("group_delete_confirm"))
            }
        }
        .tint(accent.primary)
        .sensoryFeedback(.selection, trigger: checked)
    }

    // MARK: Teile

    private var renameRow: some View {
        Button {
            renameText = name
            renaming = true
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "pencil")
                    .font(.body.weight(.medium))
                    .foregroundStyle(accent.primary)
                    .frame(width: 26)
                Text(L("group_rename"))
                    .font(.body)
                    .foregroundStyle(AppColors.onSurface)
                Spacer(minLength: 8)
                Text(name)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(AppColors.container)
    }

    private func row(_ watch: Watch) -> some View {
        let isChecked = checked.contains(watch.id)
        // Steht in einer anderen Gruppe (und wird nicht gerade hierher verschoben)
        let other: String? = {
            guard let g = watch.groupName, g != groupName, !isChecked else { return nil }
            return g
        }()
        return Button {
            if isChecked { checked.remove(watch.id) } else { checked.insert(watch.id) }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: isChecked ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(isChecked ? accent.primary : AppColors.outline)
                    .contentTransition(.symbolEffect(.replace))
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 4) {
                        if watch.favorite {
                            Image(systemName: "star.fill")
                                .font(.caption2)
                                .foregroundStyle(accent.primary)
                        }
                        Text(watch.displayName)
                            .font(.body)
                            .foregroundStyle(AppColors.onSurface)
                            .lineLimit(1)
                    }
                    Text(watch.marketName)
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                if let other {
                    Text(L("group_in_other", other))
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(AppColors.container)
        .accessibilityAddTraits(isChecked ? .isSelected : [])
    }

    /// Fuss: Löschen (nur bestehende Gruppe) links, «Fertig» rechts.
    private var footer: some View {
        HStack(spacing: 12) {
            if !isNew {
                Button {
                    askDelete = true
                } label: {
                    Label(L("group_delete"), systemImage: "trash")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppColors.error)
                }
                .buttonStyle(.borderless)
            }
            Spacer(minLength: 8)
            Button(action: done) {
                Text(L("group_done"))
                    .font(.headline)
                    .padding(.horizontal, 28)
                    .padding(.vertical, 12)
            }
            .buttonStyle(AccentButtonStyle())
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 10)
        .background(.bar)
    }

    // MARK: Aktionen

    private func applyRename() {
        guard let clean = WatchGroupNames.clean(renameText) else { return }
        // Gleicher Name wie eine andere Gruppe: deren Schreibweise, die Gruppen werden zusammengeführt
        name = WatchGroupNames.canonical(clean, in: data.watchGroups.filter { $0 != groupName })
    }

    private func done() {
        WatchlistHaptics.selection()
        data.applyGroupEdit(oldName: isNew ? nil : groupName, newName: name, memberIds: checked)
        dismiss()
    }
}
