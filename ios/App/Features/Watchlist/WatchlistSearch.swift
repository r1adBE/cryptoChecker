import SwiftUI

/// Suche in der Merkliste — wie `WatchlistSearch.kt`.
extension WatchlistScreen {
    /// Kompaktes Suchfeld anstelle der Statuszeile, mit Schliessen-Knopf rechts.
    var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .scaledFont(size: 13, weight: .semibold, relativeTo: .footnote)
                .foregroundStyle(searchFocused ? accent.primary : AppColors.onSurfaceVariant)
            TextField(L("watchlist_search_hint"), text: $query)
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurface)
                .focused($searchFocused)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .onSubmit { searchFocused = false }
            Button { closeSearch() } label: {
                Image(systemName: "xmark")
                    .scaledFont(size: 11, weight: .bold, relativeTo: .caption)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .frame(width: 26, height: 26)
                    .background(AppColors.containerHigh, in: Circle())
                    .contentShape(Circle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L("watchlist_search_close"))
        }
        .padding(.leading, 12)
        .padding(.trailing, 4)
        .frame(height: WatchlistSearch.rowHeight)
        .background(AppColors.container, in: Capsule())
        .overlay(Capsule().strokeBorder(AppColors.outlineVariant.opacity(0.6), lineWidth: 1))
        .onAppear {
            // Kurz warten, bis das Feld in der Liste steht — dann Tastatur auf
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 80_000_000)
                searchFocused = true
            }
        }
    }

    func openSearch() {
        WatchlistHaptics.impact(.light)
        editMode = .inactive
        withAnimation(.easeInOut(duration: 0.25)) { searching = true }
    }

    /// Schliessen leert die Suche und zeigt wieder den Status.
    func closeSearch() {
        searchFocused = false
        withAnimation(.easeInOut(duration: 0.25)) {
            searching = false
            query = ""
        }
    }
}

/// Suche in der Merkliste — wie `matchesSearch` in `WatchlistSearch.kt`.
enum WatchlistSearch {
    /// Gemeinsame Höhe von Statuszeile und Suchfeld, damit nichts springt.
    static let rowHeight: CGFloat = 34
    static let buttonSize: CGFloat = 28

    /// Gross-/Kleinschreibung egal; mehrere Wörter müssen alle passen
    /// (z. B. «btc kraken»). Geprüft werden Basis, Quote, «BASIS/QUOTE»,
    /// Vertragskürzel, Börse und Notiz.
    static func matches(_ watch: Watch, query: String) -> Bool {
        let fields = [
            watch.baseAsset,
            watch.quoteAsset,
            watch.displayPair,
            watch.displayName,
            watch.contractType.shortName ?? "",
            watch.marketName,
            watch.note ?? "",
        ].map { $0.lowercased() }
        let tokens = query.lowercased().split(whereSeparator: { $0.isWhitespace }).map(String.init)
        return tokens.allSatisfy { token in fields.contains { $0.contains(token) } }
    }
}
