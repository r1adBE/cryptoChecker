import SwiftUI

/// Suche in der Merkliste — wie `WatchlistSearch.kt`.
extension WatchlistScreen {
    /// Kompaktes Suchfeld anstelle der Statuszeile, mit Schliessen-Knopf rechts.
    var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .scaledFont(size: 13, weight: .semibold, relativeTo: .footnote)
                .foregroundStyle(searchFocused ? accent.primary : AppColors.onSurfaceVariant)
            // Platzhalter in onSurfaceVariant (≥ 4.5:1) statt des blassen Systemgraus — wie Android
            TextField(text: $query, prompt: Text(L("watchlist_search_hint")).foregroundStyle(AppColors.onSurfaceVariant)) {
                Text(L("watchlist_search_hint"))
            }
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
                    // Tippfläche 44 pt, sichtbar bleibt der kleine Kreis
                    .contentShape(Circle().inset(by: -9))
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L("watchlist_search_close"))
        }
        .padding(.leading, 12)
        .padding(.trailing, 4)
        .padding(.vertical, 2)
        // Mindesthöhe statt fester Höhe: wächst mit grosser Schrift, sonst 34 pt — wie Android heightIn
        .frame(minHeight: WatchlistSearch.rowHeight)
        .background(AppColors.container, in: Capsule())
        .overlay(Capsule().strokeBorder(AppColors.outline, lineWidth: 1)) // Kontroll-Rand ≥ 3:1 (WCAG 1.4.11), wie Android
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
    /// Kreis von Lupe und «+» (wie das Coin-Logo `ListSegment.logo`); Tippfläche 44 pt.
    static let buttonSize: CGFloat = 34
    /// Symbol in Lupe und «+».
    static let buttonSymbolSize: CGFloat = 14
    /// Bis zu so vielen Paaren in der Merkliste zeigt «+» auch den Text «Paar hinzufügen» (wie Android).
    static let addLabelMaxPairs = 3

    /// Gross-/Kleinschreibung und Akzente egal; mehrere Wörter müssen alle passen
    /// (z. B. «btc kraken», «bitcoin usdt»). Geprüft werden Basis, Quote, «BASIS/QUOTE»,
    /// Vertragskürzel, Börse, Notiz und der Name des Coins (`name`, z. B. «Bitcoin»).
    static func matches(_ watch: Watch, query: String, name: String? = nil) -> Bool {
        let fields = [
            watch.baseAsset,
            watch.quoteAsset,
            watch.displayPair,
            watch.displayName,
            watch.contractType.shortName ?? "",
            watch.marketName,
            watch.note ?? "",
            name ?? "",
        ].map(ExplorerNameMatch.fold)
        let tokens = ExplorerNameMatch.fold(query).split(whereSeparator: { $0.isWhitespace }).map(String.init)
        return tokens.allSatisfy { token in fields.contains { $0.contains(token) } }
    }

    /// Name des Coins bzw. der Firma eines Paars für die Suche (`CoinLogos.nameKey`, TradFi beachtet,
    /// Aktie ohne Namen aus `stockNames`); nil bei DEX-Pools und unbekannten Coins — wie
    /// `searchName` in `WatchlistSearch.kt`.
    static func searchName(_ watch: Watch, names: [String: String], tradFiPairs: Set<String>,
                           stockNames: () -> [String: String] = { [:] }) -> String? {
        guard CoinLogos.allowed(forMarket: watch.marketKey) else { return nil }
        let tradFi = tradFiPairs.contains(watch.logoPairKey)
        if let name = names[CoinLogos.nameKey(watch.baseAsset, tradFi: tradFi)] { return name }
        return tradFi ? stockNames()[watch.baseAsset.uppercased()] : nil
    }

    /// Namen der Paare für die Suche (Schlüssel `Watch.id`), unabhängig vom Schalter «Namen anzeigen»;
    /// nur gespeicherte Namen, kein Abruf. Einmal je Suche gelesen, nicht je Zeile — wie
    /// `rememberSearchNames` in Android.
    static func names(for watches: [Watch]) -> [Int64: String] {
        let names = CoinLogoStore.names
        guard !names.isEmpty else { return [:] }
        let tradFiPairs = CoinLogoStore.tradFiPairs
        var out: [Int64: String] = [:]
        for watch in watches {
            if let name = searchName(watch, names: names, tradFiPairs: tradFiPairs, stockNames: { CoinLogoStore.stockNames }) {
                out[watch.id] = name
            }
        }
        return out
    }
}
