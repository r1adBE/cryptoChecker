import SwiftUI
import UIKit

// Auswahlfeld und Auswahlliste von «Paar hinzufügen» (Börse, Coin, Gegenwährung, Laufzeit) mit Suche und
// Favoriten.
// Eigene Datei, weil die Liste mit Suche, Favoriten und Abschnitten eine eigene Ansicht ist.

// MARK: Auswahlfeld

/// Feld, das eine Auswahlliste öffnet (Rolle wie `ComboBox`): oben die Beschriftung, darunter der
/// gewählte Wert oder ein Platzhalter, optional mit Coin-Logo davor.
struct ExplorerPickerField: View {
    let label: String
    let value: String?
    var placeholder: String? = nil
    var enabled: Bool = true
    var badge: Bool = false
    let action: () -> Void
    @Environment(\.appAccent) var accent

    /// Gewählter Wert, sofern nicht leer.
    private var filled: String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }

    private var fieldShape: RoundedRectangle {
        RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if badge, let symbol = filled {
                    CoinBadge(symbol: symbol, size: 32)
                }
                captionAndValue
                Spacer(minLength: 4)
                if enabled {
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.md)
            .frame(minHeight: 56)
            .background(AppColors.containerHigh, in: fieldShape)
            .contentShape(fieldShape)
        }
        .buttonStyle(ExplorerPressStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.6)
    }

    private var captionAndValue: some View {
        let empty = value == nil
        return VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption.weight(.medium))
                .foregroundStyle(enabled ? accent.primary : AppColors.onSurfaceVariant)
            Text(filled ?? placeholder ?? "—")
                .font(.body.weight(empty ? .regular : .semibold))
                .foregroundStyle(empty ? AppColors.onSurfaceVariant : AppColors.onSurface)
                .lineLimit(1)
        }
    }
}

// MARK: Auswahlliste

/// Blatt mit Suchfeld für Börse, Coin, Gegenwährung oder Kontrakt (Rolle wie `ComboBox.kt`).
/// Favoriten – per Stern oder langem Drücken – stehen oben.
@MainActor
struct ExplorerPickerSheet: View {
    let title: String
    let items: [String]
    let selected: String?
    /// nil: ohne Favoriten (beim Kontrakt).
    let favoriteKind: FavoriteKind?
    var badges: Bool = false
    @ObservedObject var data: AppData
    let onSelect: (String) -> Void

    @Environment(\.dismiss) var dismiss
    @Environment(\.appAccent) var accent
    @State var query = ""

    private var favorites: Set<String> {
        favoriteKind.flatMap { data.favorites[$0] } ?? []
    }

    /// Einträge zum Suchtext: zuerst die, die damit beginnen, dann die, die ihn irgendwo enthalten;
    /// in beiden Teilen gehen Favoriten vor.
    private var visible: [String] {
        let needle = query.trimmingCharacters(in: .whitespaces)
        var leading: [String] = []
        var inner: [String] = []
        if needle.isEmpty {
            leading = items
        } else {
            for item in items {
                if item.range(of: needle, options: [.caseInsensitive, .anchored]) != nil {
                    leading.append(item)
                } else if item.range(of: needle, options: .caseInsensitive) != nil {
                    inner.append(item)
                }
            }
        }
        let ordered = leading + inner
        let favs = favorites
        var top: [String] = []
        var rest: [String] = []
        for item in ordered {
            if favs.contains(item) { top.append(item) } else { rest.append(item) }
        }
        return top + rest
    }

    var body: some View {
        NavigationStack {
            ScrollViewReader { scroller in
                list
                    .onSubmit(of: .search) {
                        if let first = visible.first { pick(first) }
                    }
                    .onAppear {
                        guard let selected, items.contains(selected) else { return }
                        scroller.scrollTo(selected, anchor: .center)
                    }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                }
            }
        }
        .tint(accent.primary)
        // Immer volle Höhe – sonst springt ein halbes Blatt mit der Tastatur auf «gross»
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .presentationBackground(AppColors.background)
    }

    private var list: some View {
        List {
            let entries = visible
            if entries.isEmpty {
                Text("—")
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .listRowBackground(AppColors.containerLow)
            }
            ForEach(entries, id: \.self) { entry in
                row(entry)
                    .id(entry)
            }
            if favoriteKind != nil {
                Section {
                    EmptyView()
                } footer: {
                    Text(L("hint_favorites_list"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always))
        .textInputAutocapitalization(.characters)
        .autocorrectionDisabled()
    }

    @ViewBuilder
    private func row(_ entry: String) -> some View {
        let chosen = entry == selected
        let starred = favorites.contains(entry)
        HStack(spacing: 12) {
            if badges { CoinBadge(symbol: entry, size: 30) }
            Text(entry)
                .font(.body.weight(chosen ? .semibold : .regular))
                .foregroundStyle(chosen ? accent.primary : AppColors.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
            if chosen {
                Image(systemName: "checkmark")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(accent.primary)
            }
            if let kind = favoriteKind {
                starButton(kind, entry, starred: starred)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { pick(entry) }
        .listRowBackground(chosen ? accent.container.opacity(0.45) : AppColors.containerLow)
        .contextMenu {
            if let kind = favoriteKind {
                Button {
                    toggle(kind, entry)
                } label: {
                    Label(L(starred ? "favorite_remove" : "favorite_add"),
                          systemImage: starred ? "star.slash" : "star")
                }
            }
        }
    }

    private func starButton(_ kind: FavoriteKind, _ entry: String, starred: Bool) -> some View {
        Button {
            toggle(kind, entry)
        } label: {
            Image(systemName: starred ? "star.fill" : "star")
                .font(.body)
                .foregroundStyle(starred ? accent.primary : AppColors.outline)
                // Antippfläche 44×44 pt nach Apple HIG, das Symbol behält seine Grösse
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(L(starred ? "favorite_remove" : "favorite_add"))
    }

    private func toggle(_ kind: FavoriteKind, _ entry: String) {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        withAnimation(.snappy) { data.toggleFavorite(kind, entry) }
    }

    private func pick(_ entry: String) {
        UISelectionFeedbackGenerator().selectionChanged()
        onSelect(entry)
        dismiss()
    }
}
