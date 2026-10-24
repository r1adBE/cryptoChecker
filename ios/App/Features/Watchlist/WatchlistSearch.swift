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
        Haptics.play(.lightImpact)
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
    /// Für ganze Listen je Tastendruck `WatchlistSearchIndex` (Felder nur einmal gefaltet).
    static func matches(_ watch: Watch, query: String, name: String? = nil) -> Bool {
        key(watch, name: name).matches(tokens(query))
    }

    /// Gefaltete Suchfelder eines Paars (`ExplorerNameMatch.fold`), siehe `matches` — wie `WatchSearchKey` (Android).
    struct Key {
        let fields: [String]

        /// Jedes Wort der Anfrage (`tokens`) steht in mindestens einem Feld; keine Wörter: true.
        func matches(_ tokens: [String]) -> Bool {
            tokens.allSatisfy { token in fields.contains { $0.contains(token) } }
        }
    }

    /// Suchschlüssel: die Felder von `matches`, gefaltet; `name` = Coin-Name (`searchName`).
    static func key(_ watch: Watch, name: String?) -> Key {
        Key(fields: [
            watch.baseAsset,
            watch.quoteAsset,
            watch.displayPair,
            watch.displayName,
            watch.contractType.shortName ?? "",
            watch.marketName,
            watch.note ?? "",
            name ?? "",
        ].map(ExplorerNameMatch.fold))
    }

    /// Wörter der Anfrage, gefaltet; getrennt an Leerraum.
    static func tokens(_ query: String) -> [String] {
        ExplorerNameMatch.fold(query).split(whereSeparator: { $0.isWhitespace }).map(String.init)
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

/// Suchschlüssel der Merkliste je `Watch.id`, zwischengespeichert: Neu gefaltet wird nur ein Paar, dessen
/// durchsuchte Felder (Basis, Quote, Vertrag, Börse, Notiz) oder Coin-Name sich geändert haben — Kurse und andere
/// Felder zählen nicht. Paare, die nicht mehr in der Liste stehen, fallen weg; je Tastendruck wird nur die Anfrage
/// gefaltet. Ergebnis wie `filter { WatchlistSearch.matches($0, query:, name:) }`. Bewusst kein beobachteter
/// Zustand (wie `WatchlistJumpTracker`), nur aus der Ansicht benutzt — wie `WatchSearchIndex` (Android).
final class WatchlistSearchIndex {
    /// Was in den Schlüssel eingeht; gleich = der Schlüssel gilt weiter.
    private struct Source: Equatable {
        let base: String
        let quote: String
        let contract: FuturesContractType
        let market: String
        let note: String?
        let name: String?
    }

    private struct Entry {
        let source: Source
        let key: WatchlistSearch.Key
    }

    private var entries: [Int64: Entry] = [:]
    /// Zwei Paare mit gleicher Kennung, aber verschiedenen Feldern: dann ohne Zwischenspeicher prüfen.
    private var ambiguous = false

    /// Gespeicherte Schlüssel.
    var count: Int { entries.count }
    /// Wie oft bisher ein Schlüssel gefaltet wurde (für Tests und Messungen).
    private(set) var keysBuilt = 0

    /// Paare aus `watches`, die `query` trifft, in gleicher Reihenfolge; `names` = Coin-Namen je Kennung.
    func filter(_ watches: [Watch], query: String, names: [Int64: String]) -> [Watch] {
        update(watches, names: names)
        if ambiguous { return watches.filter { WatchlistSearch.matches($0, query: query, name: names[$0.id]) } }
        let tokens = WatchlistSearch.tokens(query)
        return watches.filter { watch in
            guard let entry = entries[watch.id] else {
                return WatchlistSearch.matches(watch, query: query, name: names[watch.id])
            }
            return entry.key.matches(tokens)
        }
    }

    /// Schlüssel auf den Stand von `watches` bringen; unveränderte Paare behalten ihren Schlüssel.
    func update(_ watches: [Watch], names: [Int64: String]) {
        var next: [Int64: Entry] = [:]
        next.reserveCapacity(watches.count)
        var clash = false
        for watch in watches {
            let name = names[watch.id]
            let source = Source(base: watch.baseAsset, quote: watch.quoteAsset, contract: watch.contractType,
                                market: watch.marketName, note: watch.note, name: name)
            if let seen = next[watch.id] {
                if seen.source != source { clash = true }
                continue
            }
            if let old = entries[watch.id], old.source == source {
                next[watch.id] = old
            } else {
                keysBuilt += 1
                next[watch.id] = Entry(source: source, key: WatchlistSearch.key(watch, name: name))
            }
        }
        entries = next
        ambiguous = clash
    }

    /// Alles vergessen (Suche zu).
    func clear() {
        if !entries.isEmpty { entries = [:] }
        ambiguous = false
    }
}
