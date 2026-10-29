import SwiftUI
import UIKit

// Auswahlfeld und Auswahlliste von «Paar hinzufügen» (Börse, Coin, Gegenwährung, Laufzeit) mit Suche und
// Favoriten.
// Eigene Datei, weil die Liste mit Suche, Favoriten und Abschnitten eine eigene Ansicht ist.

// MARK: Auswahlfeld

/// Feld, das eine Auswahlliste öffnet (Rolle wie `DropdownField`): oben die Beschriftung, darunter der
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

/// Blatt mit Suchfeld für Börse, Coin, Gegenwährung oder Kontrakt (Rolle wie `DropdownField.kt`).
/// Favoriten – per Stern oder langem Drücken – stehen oben.
@MainActor
struct ExplorerPickerSheet: View {
    let title: String
    let items: [String]
    let selected: String?
    /// nil: ohne Favoriten (beim Kontrakt).
    let favoriteKind: FavoriteKind?
    var badges: Bool = false
    /// Abschnitte (Börse: Spot · Futures · DEX-Pools · Forex): Favoriten oben ohne Titel, dann je Abschnitt eine
    /// Titelzeile und seine Einträge; die Suche läuft über alle. nil = eine flache Liste.
    var sections: [(title: String, items: [String])]? = nil
    /// Kleine zweite Zeile je Eintrag oder Abschnittstitel (Text → Unterzeile; DexScreener: «Uniswap, …»).
    var subtitles: [String: String] = [:]
    @ObservedObject var data: AppData
    let onSelect: (String) -> Void

    @Environment(\.dismiss) var dismiss
    @Environment(\.appAccent) var accent
    @State var query = ""

    private var favorites: Set<String> {
        favoriteKind.flatMap { data.favorites[$0] } ?? []
    }

    /// Einträge von `list` zum Suchtext: zuerst die, die damit beginnen, dann die, die ihn irgendwo enthalten.
    private func matching(_ list: [String]) -> [String] {
        let needle = query.trimmingCharacters(in: .whitespaces)
        if needle.isEmpty { return list }
        var leading: [String] = []
        var inner: [String] = []
        for item in list {
            if item.range(of: needle, options: [.caseInsensitive, .anchored]) != nil {
                leading.append(item)
            } else if item.range(of: needle, options: .caseInsensitive) != nil {
                inner.append(item)
            }
        }
        return leading + inner
    }

    /// Mit Abschnitten: Favoriten (Treffer, ohne Titel) und je Abschnitt die übrigen Treffer; leere fallen weg.
    private var sectioned: (top: [String], sections: [(title: String, items: [String])]) {
        let favs = favorites
        let top = matching(items).filter { favs.contains($0) }
        let rest = (sections ?? []).compactMap { section -> (title: String, items: [String])? in
            let hits = matching(section.items).filter { !favs.contains($0) }
            return hits.isEmpty ? nil : (section.title, hits)
        }
        return (top, rest)
    }

    /// Einträge zum Suchtext: zuerst die, die damit beginnen, dann die, die ihn irgendwo enthalten;
    /// in beiden Teilen gehen Favoriten vor. Mit Abschnitten: Favoriten, dann die Abschnitte der Reihe nach.
    private var visible: [String] {
        if sections != nil {
            let grouped = sectioned
            return grouped.top + grouped.sections.flatMap(\.items)
        }
        let ordered = matching(items)
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
            if sections != nil {
                let grouped = sectioned
                ForEach(grouped.top, id: \.self) { entry in
                    row(entry)
                        .id(entry)
                }
                ForEach(grouped.sections.indices, id: \.self) { index in
                    let section = grouped.sections[index]
                    Section {
                        ForEach(section.items, id: \.self) { entry in
                            row(entry)
                                .id(entry)
                        }
                    } header: {
                        // Titel und Unterzeile liest VoiceOver als eine Überschrift
                        VStack(alignment: .leading, spacing: 2) {
                            Text(section.title)
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(AppColors.onSurfaceVariant)
                            if let subtitle = subtitles[section.title] {
                                Text(subtitle)
                                    .font(.caption)
                                    .foregroundStyle(AppColors.onSurfaceVariant)
                                    .textCase(nil)
                            }
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityAddTraits(.isHeader)
                    }
                }
            } else {
                ForEach(entries, id: \.self) { entry in
                    row(entry)
                        .id(entry)
                }
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
            // Name und Unterzeile als ein Element für VoiceOver
            VStack(alignment: .leading, spacing: 2) {
                Text(entry)
                    .font(.body.weight(chosen ? .semibold : .regular))
                    .foregroundStyle(chosen ? accent.primary : AppColors.onSurface)
                if let subtitle = subtitles[entry] {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
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
        // VoiceOver: Zeile ist wählbar (Tipp statt Button wegen des Stern-Knopfs in der Zeile)
        .accessibilityAddTraits(chosen ? [.isButton, .isSelected] : .isButton)
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
        Haptics.play(.lightImpact)
        withAnimation(.snappy) { data.toggleFavorite(kind, entry) }
    }

    private func pick(_ entry: String) {
        Haptics.play(.selection)
        onSelect(entry)
        dismiss()
    }
}
