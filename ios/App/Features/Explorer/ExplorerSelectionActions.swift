import Foundation
import Accessibility
import SwiftUI
import UIKit

// Auswahl «Erweitert / nach Börse» (Teil von `ExplorerViewModel`): Börse, Coin, Gegenwährung, Laufzeit,
// Paarliste neu laden, Kurs holen und das gewählte Paar hinzufügen.
// Eigene Datei, weil der Schritt-für-Schritt-Ablauf eine eigene Aufgabe ist; der Zustand bleibt in der Klasse
// (wie `ExplorerSelectionActions.kt`).

extension ExplorerViewModel {
    // MARK: Börse

    func selectExchange(_ market: Market) {
        // Gewähltes Paar merken: gibt es Coin/Gegenwährung/Kontrakt auch an der neuen Börse, bleiben sie
        let remembered = (coin: coin, quote: quote, contract: contract)
        if exchange?.key != market.key { clearSelectionResults() }
        exchange = market
        canReloadPairs = MarketService.supportsPairSync(market)
        exchangeLoadTask?.cancel()
        let key = market.key
        let withAlpha = appliesAssetScope
        exchangeLoadTask = Task { [weak self] in
            let loaded = await Self.loadPairs(key, alpha: withAlpha)
            guard let self, !Task.isCancelled, self.isStill(key) else { return }
            self.pairList = self.scopedPairs(loaded, marketKey: key)
            if self.coin == nil { self.coin = remembered.coin }
            if self.quote == nil { self.quote = remembered.quote }
            if self.contract == nil { self.contract = remembered.contract }
            self.reconcile()
            // Leere Liste: sofort laden, ein eigener «Sync»-Schritt ist nicht nötig
            let shouldSync = loaded.pairs.isEmpty && self.canReloadPairs && !self.pairListSync.inProgress
            if shouldSync { self.reloadPairList() }
        }
    }

    /// Vorauswahl aus einem gemerkten Paar («Paar bearbeiten»). DEX-Pools kommen nur über die
    /// DEX-Suche – bei ihnen bleibt die Börse leer.
    func prefill(_ watch: Watch) {
        coin = watch.baseAsset
        quote = watch.quoteAsset
        contract = watch.contractType
        guard watch.marketKey != Self.dexKey else { return }
        if let match = markets.first(where: { $0.key == watch.marketKey }) {
            selectExchange(match)
        }
    }

    /// Paarliste der gewählten Börse neu holen (vollständige öffentliche Liste).
    func reloadPairList() {
        syncTask?.cancel()
        guard let market = exchange else { return }
        let key = market.key
        let withAlpha = appliesAssetScope
        pairListSync = ExplorerPairListSync(inProgress: true)
        syncTask = Task { [weak self] in
            do {
                try await PairCache.shared.sync(key)
                let fresh = await Self.loadPairs(key, alpha: withAlpha)
                guard let self, !Task.isCancelled, self.isStill(key) else { return }
                self.searchCache[key] = fresh
                self.pairList = self.scopedPairs(fresh, marketKey: key)
                self.pairListSync = ExplorerPairListSync()
                // Die Suche kennt die Liste jetzt auch
                if !self.searchMarkets.isEmpty { self.updateUnloaded() }
                self.reconcile()
            } catch {
                if error is CancellationError { return }
                guard let self, !Task.isCancelled, self.isStill(key) else { return }
                self.pairListSync = ExplorerPairListSync(error: ConnectionErrors.friendly(error))
            }
        }
    }

    /// Ist diese Börse noch die gewählte?
    func isStill(_ key: String) -> Bool { exchange?.key == key }

    /// Was zur vorigen Börse gehörte (Liste, Kurs, Sammelaufnahme, DEX, Kennzahlen), verwerfen.
    private func clearSelectionResults() {
        syncTask?.cancel()
        syncTask = nil
        pairListSync = ExplorerPairListSync()
        pairList = nil
        dropBulkList()
        priceTask?.cancel()
        price = nil
        priceKey = nil
        dexTask?.cancel()
        dexResults = []
        dexSearching = false
        dexNoResults = false
        bulkStatsTask?.cancel()
        bulkStatsKey = nil
        bulkStats = [:]
        reconcile()
    }

    /// Liste der Börse laden: für «Paar hinzufügen» mit Binance-Alpha (`PairCache.selectionPairs`),
    /// beim Bearbeiten nur die gespeicherte.
    nonisolated private static func loadPairs(_ key: String, alpha: Bool) async -> MarketPairsInfo {
        if alpha { return await PairCache.shared.selectionPairs(for: key) }
        return await PairCache.shared.pairs(for: key)
    }

    /// Auf das reduzieren, was «Token & TradFi» und «inkl. Alpha-Tokens» zeigen; trägt ein Paar
    /// deutlich einen anderen Namen als das Alpha-Token, gilt es nicht als Alpha (`AssetKind.of`).
    private func scopedPairs(_ info: MarketPairsInfo, marketKey: String) -> MarketPairsInfo {
        info.scoped(assetScope, marketKey: marketKey, hiddenAlpha: hiddenAlpha,
                    pairNames: TokenIdentityStore.shared.marketNames(marketKey),
                    alphaNames: CoinLogoStore.alphaNames)
    }

    // MARK: Paar

    func selectCoin(_ base: String) {
        coin = base
        reconcile()
    }

    func selectQuote(_ counter: String) {
        quote = counter
        reconcile()
    }

    func selectContract(_ type: FuturesContractType) {
        // Eine gesammelte Liste passt nur zum Kontrakttyp, mit dem sie entstand
        if type != contract { dropBulkList() }
        contract = type
        reconcile()
    }

    /// Bringt die Auswahllisten und das gewählte Paar mit der aktuellen Liste in Einklang (fällt ein
    /// Wert weg, rückt der erste nach) und fordert bei einem neuen Paar den Kurs an.
    func reconcile() {
        let info = pairList

        // Zuweisungen nur bei echter Änderung (jede Zuweisung meldet sich bei SwiftUI)
        let offeredCoins = info?.baseCurrencies ?? []
        if coins != offeredCoins { coins = offeredCoins }
        if !Self.isOffered(coin, in: offeredCoins) { coin = offeredCoins.first }

        var offeredQuotes: [String] = []
        if let info, let base = coin { offeredQuotes = info.quoteCurrencies(for: base) }
        if quotes != offeredQuotes { quotes = offeredQuotes }
        if !Self.isOffered(quote, in: offeredQuotes) { quote = offeredQuotes.first }

        let kinds = info?.contractTypes(base: coin, quote: quote) ?? []
        if contracts != kinds { contracts = kinds }
        if !Self.isOffered(contract, in: kinds) { contract = kinds.first }

        let bulkChoices = info?.bulkQuoteCurrencies ?? []
        if bulkQuotes != bulkChoices { bulkQuotes = bulkChoices }
        // Neue oder frisch geladene Liste: USDT bzw. die häufigste Gegenwährung vorwählen
        if !Self.isOffered(bulkQuote, in: bulkChoices) { bulkQuote = info?.defaultBulkQuote }

        selectedPair = pair(in: info)

        let key = priceIdentity()
        guard key != priceKey else { return }
        priceKey = key
        loadPrice(debounce: true)
    }

    /// Das vollständig gewählte Paar (ohne Kontrakt = Spot) in `info`, sonst nil.
    func pair(in info: MarketPairsInfo?) -> MarketPair? {
        guard let info, let base = coin, let counter = quote else { return nil }
        return info.pair(base: base, quote: counter, contractType: contract ?? .none)
    }

    /// Kennung aus Börse und Paar; nil ohne beides.
    private func priceIdentity() -> String? {
        guard let market = exchange, let p = selectedPair else { return nil }
        return [market.key, p.base, p.quote, "\(p.contractType.rawValue)", p.pairId ?? ""].joined(separator: "|")
    }

    /// Gesetzt und in `options` enthalten?
    private static func isOffered<T: Equatable>(_ value: T?, in options: [T]) -> Bool {
        guard let value else { return false }
        return options.contains(value)
    }

    // MARK: Kurs

    func retryPrice() { loadPrice(debounce: false) }

    private func loadPrice(debounce: Bool) {
        priceTask?.cancel()
        guard let market = exchange, market.key != Self.dexKey, let pair = selectedPair else {
            price = nil
            return
        }
        price = .loading
        priceTask = Task { [weak self] in
            // Kleine Wartezeit: Coin und Gegenwährung wechseln oft direkt hintereinander
            if debounce { try? await Task.sleep(nanoseconds: 300_000_000) }
            guard !Task.isCancelled else { return }
            let outcome: ExplorerPriceState
            do {
                let fresh = try await MarketService.fetchTicker(market: market, info: pair)
                outcome = .loaded(fresh, pair)
            } catch {
                outcome = .failed(ConnectionErrors.friendly(error))
            }
            guard !Task.isCancelled else { return }
            self?.price = outcome
        }
    }

    // MARK: In die Merkliste

    /// Das aktuell gewählte Paar aufnehmen.
    func addSelectedPair() {
        guard let market = exchange, let pair = selectedPair else {
            show(ExplorerMessage(text: L("explorer_no_pair_selected"), showView: false))
            haptic(.warning)
            return
        }
        add(market: market, pair: pair)
    }

    func isInWatchlist(_ market: Market, _ pair: MarketPair) -> Bool {
        data.contains(marketKey: market.key, pair: pair)
    }

    func add(market: Market, pair: MarketPair) {
        insert(market: market, pair: pair,
               addedText: L("explorer_added_to_watchlist"),
               duplicateText: L("explorer_already_in_watchlist"))
    }

    /// Gemeinsamer Weg für Börsen-Paare und DEX-Pools: aufnehmen, melden, Haptik, Mitteilungen. Merkliste voll
    /// (`WatchlistLimits`): nichts angelegt, Hinweis — wie Android `launchAdd`.
    func insert(market: Market, pair: MarketPair, addedText: String, duplicateText: String) {
        let firstEver = data.isFirstPairAdd
        let outcome = data.tryAddWatch(market: market, pair: pair, group: data.addTargetGroup)
        guard case .added(let id) = outcome else {
            show(outcome == .full ? ExplorerMessage(text: WatchlistLimitText.full, long: true) : ExplorerMessage(text: duplicateText))
            haptic(.warning)
            return
        }
        if firstEver {
            showFirstAdd(id)
        } else {
            show(ExplorerMessage(text: addedText))
        }
        haptic(.success)
        requestNotifications()
    }
}
