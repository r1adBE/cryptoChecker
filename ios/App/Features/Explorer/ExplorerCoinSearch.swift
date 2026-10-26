import Foundation

/// Ein Coin der Coin-Suche — wie `CoinHit` in `ExplorerCoinSearch.kt`. `symbol` gross wie in den Paarlisten;
/// `tradFi` trennt das Aktien-Kürzel vom gleichnamigen Token. `exchanges`/`pairs` zählen nur Paarlisten, die
/// schon auf dem Gerät liegen — 0 heisst: nur aus dem Namensverzeichnis bekannt. `alpha`: alle seine Paare
/// sind Alpha-Token («α»).
struct ExplorerCoinHit: Identifiable, Equatable, Sendable {
    let symbol: String
    let tradFi: Bool
    let name: String?
    let exchanges: Int
    let pairs: Int
    var alpha: Bool = false

    /// Kennung für Listen und Auswahl (TradFi im eigenen Namensraum wie bei den Logos).
    var id: String { CoinLogos.logoKey(symbol, tradFi: tradFi) }
}

/// Was beim Vorschlag des Standard-Paars zählt: die Start-Börse der Region (wie die Start-Merkliste,
/// `StarterPairs.marketKey`), die Lieblingsbörsen und die Umrechnungswährung der Einstellungen.
/// Wie `CoinPairPreference` in Android.
struct ExplorerCoinPairPreference: Sendable {
    var defaultExchange: String?
    var favoriteExchanges: Set<String> = []
    var displayCurrency: String?
    /// Börsen-Schlüssel → Volumenrang (0 = grösstes Volumen, über alle Abschnitte; `ExchangeOrder.ranks`); ohne
    /// Rang zählt `Int.max`. Erster Börsen-Vergleich nach Abschnitt, Vertragsart und Gegenwährung, vor dem Namen.
    var exchangeRank: [String: Int] = [:]

    /// Zuerst die eigene Währung (Kurs ohne Umrechnung), dann die gängigen Dollar-Stablecoins bzw. USD,
    /// die an fast jeder Börse die grössten Bücher haben.
    var quotes: [String] {
        var out: [String] = []
        let own = displayCurrency?.trimmingCharacters(in: .whitespaces).uppercased() ?? ""
        for q in (own.isEmpty ? [] : [own]) + Self.commonQuotes where !out.contains(q) { out.append(q) }
        return out
    }

    private static let commonQuotes = ["USDT", "USD", "USDC", "EUR", "BTC"]
}

/// Coin zuerst: «Bitcoin», «btc» oder «Ethereum» finden den Coin — nicht ein einzelnes Paar —, danach zeigt
/// `pairs(for:)` seine Paare über alle Börsen mit einem sinnvollen Standard vorne. Alles läuft auf dem Gerät:
/// Namen aus dem gespeicherten bzw. mitgelieferten Verzeichnis (`ExplorerNameIndex`), Paare aus den
/// Paarlisten, die schon geladen sind. Datenschutz: Die Suche fragt nie etwas zu einem bestimmten Coin ab;
/// fehlt eine Liste, bietet die Seite an, die ganze öffentliche Paarliste der Börse zu laden (für alle gleich).
/// Wie `CoinSearch` in Android (`ExplorerCoinSearch.kt`).
enum ExplorerCoinSearch {
    /// Höchstens so viele Coins als Treffer.
    static let limit = 10

    // Ränge: Kürzel genau, Name genau, Kürzel-Anfang, Name-Anfang, Name enthält
    private static let tierSymbol = 0
    private static let tierName = 1
    private static let tierSymbolPrefix = 2
    private static let tierNamePrefix = 3
    private static let tierNameContains = 4

    /// Suchbegriff als Coin: ein Wort ist Kürzel oder Name, mehrere Wörter nur ein Name («shiba inu»).
    private struct Terms {
        let symbol: String?
        let aliasSymbols: [String]
        let name: String

        init(_ words: [String]) {
            symbol = words.count == 1 ? words[0] : nil
            aliasSymbols = symbol.map(ExplorerPairSearch.aliases(of:)) ?? []
            name = ExplorerNameMatch.fold(words.joined(separator: " "))
        }

        /// Rang für das Kürzel `symbol` mit dem gefalteten Namen `foldedName`; nil = kein Treffer.
        func tier(_ symbol: String, _ foldedName: String?) -> Int? {
            if let term = self.symbol, symbol == term || aliasSymbols.contains(symbol) { return ExplorerCoinSearch.tierSymbol }
            if let foldedName, foldedName == name { return ExplorerCoinSearch.tierName }
            if let term = self.symbol, symbol.hasPrefix(term) { return ExplorerCoinSearch.tierSymbolPrefix }
            guard let score = foldedName.flatMap({ ExplorerNameMatch.score($0, name) }) else { return nil }
            return score == ExplorerNameMatch.prefix ? ExplorerCoinSearch.tierNamePrefix : ExplorerCoinSearch.tierNameContains
        }
    }

    /// Zwischenstand je Coin beim Durchgehen der Paarlisten.
    private struct Tally {
        let symbol: String
        let tradFi: Bool
        let tier: Int
        var exchanges = Set<String>()
        var pairs = 0
        var alphaPairs = 0
    }

    /// Nur ein Begriff für einen Coin sucht Coins; Laufzeit («perp», «qtly») oder Verfallsdatum meinen ein
    /// bestimmtes Paar (`ExplorerPairSearch`).
    private static func coinTerms(_ query: String) -> Terms? {
        guard let q = ExplorerPairSearch.parse(query), q.contracts == nil, q.date == nil else { return nil }
        return Terms(q.words)
    }

    /// Paar-Treffer zusätzlich zeigen? Bei mehreren Wörtern («eth usdt», «btc kraken»), Laufzeit oder Datum
    /// sucht jemand ein bestimmtes Paar; sonst nur, wenn kein Coin passt («BTCUSDT»).
    static func showPairHits(_ query: String, coinsFound: Bool) -> Bool {
        guard let q = ExplorerPairSearch.parse(query) else { return false }
        return q.words.count > 1 || q.contracts != nil || q.date != nil || !coinsFound
    }

    /// Coins zu `query` aus den geladenen Listen (`cache`) und dem Namensverzeichnis. Rang: Kürzel genau,
    /// Name genau, Kürzel-Anfang, Name-Anfang, Name enthält; darin mehr Börsen zuerst, dann der kürzere Name
    /// bzw. das kürzere Kürzel. `includeUnlisted`: auch Coins ohne geladenes Paar (solange noch Listen fehlen –
    /// sonst gibt es für sie nichts hinzuzufügen).
    static func coins(_ query: String, markets: [Market], cache: [String: MarketPairsInfo],
                      names: ExplorerNameIndex = .empty, scope: AssetScope = .BOTH,
                      hiddenAlpha: Set<String> = [], alphaSymbols: Set<String> = [],
                      alphaNames: [String: String] = [:], pairNames: [String: [String: String]] = [:],
                      includeUnlisted: Bool = false, limit: Int = ExplorerCoinSearch.limit) -> [ExplorerCoinHit] {
        guard let terms = coinTerms(query) else { return [] }
        // Je Coin einmal bewertet; nil = passt nicht (auch gemerkt, damit nicht jedes Paar neu rechnet)
        var tallies: [String: Tally?] = [:]

        func tallyKey(_ symbol: String, _ tradFi: Bool) -> String? {
            let key = CoinLogos.logoKey(symbol, tradFi: tradFi)
            if let known = tallies[key] { return known == nil ? nil : key }
            let tier = terms.tier(symbol, names.folded(CoinLogos.nameKey(symbol, tradFi: tradFi)))
            tallies[key] = .some(tier.map { Tally(symbol: symbol, tradFi: tradFi, tier: $0) })
            return tier == nil ? nil : key
        }

        for market in markets {
            // Keine Namen für DEX-Pools: dort darf ein fremder Token «BTC» heissen
            guard let info = cache[market.key], CoinLogos.allowed(forMarket: market.key) else { continue }
            let marketNames = pairNames[market.key] ?? [:]
            for pair in info.pairs where scope.allows(pair, marketKey: market.key, hiddenAlpha: hiddenAlpha,
                                                      pairNames: marketNames, alphaNames: alphaNames) {
                guard let key = tallyKey(pair.base.uppercased(), pair.isTradFi), var t = tallies[key] ?? nil else { continue }
                t.exchanges.insert(market.key)
                t.pairs += 1
                let kind = AssetKind.of(marketKey: market.key, base: pair.base, tradFi: pair.isTradFi,
                                        alphaSymbols: alphaSymbols, pairId: pair.pairId,
                                        pairName: marketNames[TokenIdentity.key(pair.base)], alphaNames: alphaNames)
                if kind == .ALPHA { t.alphaPairs += 1 }
                tallies[key] = .some(t)
            }
        }

        if includeUnlisted {
            for entry in names.entries {
                let tradFi = entry.key.hasPrefix(CoinLogos.tradFiPrefix)
                let symbol = tradFi ? String(entry.key.dropFirst(CoinLogos.tradFiPrefix.count)) : entry.key
                let shown = tradFi ? scope.allowsTradFi : scope.allowsTokens && !hiddenAlpha.contains(symbol)
                if symbol.isEmpty || !shown { continue }
                _ = tallyKey(symbol, tradFi)
            }
        }

        let found: [(tally: Tally, name: String?)] = tallies.values.compactMap { $0 }.map {
            (tally: $0, name: names.name(CoinLogos.nameKey($0.symbol, tradFi: $0.tradFi)))
        }
        let sorted = found.sorted { a, b in
            if a.tally.tier != b.tally.tier { return a.tally.tier < b.tally.tier }
            if a.tally.exchanges.count != b.tally.exchanges.count { return a.tally.exchanges.count > b.tally.exchanges.count }
            let la = lengthKey(a.tally, a.name), lb = lengthKey(b.tally, b.name)
            if la != lb { return la < lb }
            if a.tally.symbol != b.tally.symbol { return a.tally.symbol < b.tally.symbol }
            return !a.tally.tradFi && b.tally.tradFi
        }
        return sorted.prefix(limit).map { entry in
            let t = entry.tally
            return ExplorerCoinHit(symbol: t.symbol, tradFi: t.tradFi, name: entry.name, exchanges: t.exchanges.count,
                                   pairs: t.pairs, alpha: t.pairs > 0 && t.alphaPairs == t.pairs)
        }
    }

    /// Bei Namens-Treffern zählt die Länge des Namens, sonst die des Kürzels — in UTF-16-Einheiten wie
    /// `String.length` in Kotlin, damit Gleichstände auf beiden Plattformen gleich ausgehen.
    private static func lengthKey(_ t: Tally, _ name: String?) -> Int {
        let byName = t.tier == tierName || t.tier == tierNamePrefix || t.tier == tierNameContains
        return byName ? (name?.utf16.count ?? Int.max) : t.symbol.utf16.count
    }

    /// Alle Paare von `coin` über die geladenen Listen, der Vorschlag zuerst: Start-Börse der Region, dann
    /// Lieblingsbörsen, dann die übrigen; darin Spot vor Perpetual vor Laufzeit, die Gegenwährungen in der
    /// Reihenfolge von `preference`, dann die Börse nach Volumenrang (`exchangeRank`), dann alphabetisch.
    static func pairs(for coin: ExplorerCoinHit, markets: [Market], cache: [String: MarketPairsInfo],
                      preference: ExplorerCoinPairPreference = ExplorerCoinPairPreference(),
                      scope: AssetScope = .BOTH, hiddenAlpha: Set<String> = [], alphaSymbols: Set<String> = [],
                      alphaNames: [String: String] = [:],
                      pairNames: [String: [String: String]] = [:]) -> [ExplorerSearchHit] {
        var found: [ExplorerSearchHit] = []
        for market in markets {
            guard let info = cache[market.key], CoinLogos.allowed(forMarket: market.key) else { continue }
            let marketNames = pairNames[market.key] ?? [:]
            for pair in info.pairs where pair.isTradFi == coin.tradFi && pair.base.uppercased() == coin.symbol {
                guard scope.allows(pair, marketKey: market.key, hiddenAlpha: hiddenAlpha, pairNames: marketNames,
                                   alphaNames: alphaNames) else { continue }
                let kind = AssetKind.of(marketKey: market.key, base: pair.base, tradFi: pair.isTradFi,
                                        alphaSymbols: alphaSymbols, pairId: pair.pairId,
                                        pairName: marketNames[TokenIdentity.key(pair.base)], alphaNames: alphaNames)
                found.append(ExplorerSearchHit(market: market, pair: pair, alpha: kind == .ALPHA))
            }
        }
        let quotes = preference.quotes
        func exchangeTier(_ key: String) -> Int {
            if key == preference.defaultExchange { return 0 }
            return preference.favoriteExchanges.contains(key) ? 1 : 2
        }
        func quoteRank(_ quote: String) -> Int { quotes.firstIndex(of: quote.uppercased()) ?? quotes.count }
        // Zahlen: Stufe, Vertragsart, Gegenwährung, Volumenrang; dann Börsenname, Gegenwährung, Laufzeit
        func numbers(_ hit: ExplorerSearchHit) -> (Int, Int, Int, Int) {
            (exchangeTier(hit.market.key), contractGroup(hit.pair.contractType), quoteRank(hit.pair.quote),
             preference.exchangeRank[hit.market.key] ?? Int.max)
        }
        func texts(_ hit: ExplorerSearchHit) -> (String, String, Int) {
            (hit.market.name.lowercased(), hit.pair.quote.uppercased(), ExplorerPairSearch.contractRank(hit.pair.contractType))
        }
        // Gleiche Schlüssel in der bisherigen Reihenfolge (Swift sortiert nicht garantiert stabil)
        let sorted = found.enumerated().sorted { l, r in
            let a = numbers(l.element), b = numbers(r.element)
            if a != b { return a < b }
            let c = texts(l.element), d = texts(r.element)
            if c != d { return c < d }
            return l.offset < r.offset
        }
        // Manche Börsen führen ein Paar doppelt — Listen-Schlüssel müssen eindeutig sein
        var seen = Set<String>()
        return sorted.map(\.element).filter { seen.insert($0.id).inserted }
    }

    /// Spot, Perpetual (auch invers), Laufzeit-Futures.
    private static func contractGroup(_ type: FuturesContractType) -> Int {
        switch type {
        case .none: return 0
        case .perpetual, .inversePerpetual: return 1
        default: return 2
        }
    }
}
