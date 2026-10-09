import Foundation
import Accessibility
import SwiftUI
import UIKit

/// Laden der Paarliste der gewählten Börse — wie `MarketPairsUpdateState.kt`.
struct ExplorerPairsUpdateState: Equatable {
    var inProgress = false
    var error: String? = nil
}

/// Kurs-Vorschau des gewählten Paars.
enum ExplorerTickerState {
    case loading
    case loaded(Ticker, CurrencyPairInfo)
    case failed(String)
}

/// Rückmeldung unten am Bildschirm (wie die Snackbar in Android).
struct ExplorerMessage: Equatable, Identifiable {
    let id = UUID()
    let text: String
    /// «Ansehen» → Merkliste öffnen.
    var showView: Bool = true
    /// Länger stehen lassen (Sammel-Ergebnis).
    var long: Bool = false
    /// Statt «Ansehen»: «Alarm setzen» für dieses Paar (erstes Paar ohne Alarm).
    var alarmWatchId: Int64? = nil
}

/// Logik der Seite «Paar hinzufügen» — wie `ExplorerViewModel.kt`.
@MainActor
final class ExplorerViewModel: ObservableObject {
    static let dexKey = "DexScreener"

    private let data = AppData.shared

    // MARK: Börse

    /// Alle Börsen, nach Name sortiert.
    let markets: [Market] = MarketsConfig.all
        .filter { !($0 is UnknownMarket) }
        .sorted { $0.name.lowercased() < $1.name.lowercased() }

    @Published private(set) var currentMarket: Market?
    @Published private(set) var pairsInfo: MarketPairsInfo?
    @Published private(set) var canUpdatePairs = false
    @Published private(set) var updateState = ExplorerPairsUpdateState()

    // MARK: Paar

    @Published private(set) var baseAssets: [String] = []
    @Published private(set) var quoteAssets: [String] = []
    @Published private(set) var contractTypes: [FuturesContractType] = []
    @Published private(set) var currentBase: String?
    @Published private(set) var currentQuote: String?
    @Published private(set) var currentContract: FuturesContractType?
    @Published private(set) var currentPair: CurrencyPairInfo?
    @Published private(set) var ticker: ExplorerTickerState?

    // MARK: Mehrere Paare

    @Published private(set) var bulkQuotes: [String] = []
    @Published private(set) var bulkQuote: String?
    @Published private(set) var bulkPairs: [CurrencyPairInfo] = []
    @Published private(set) var bulkEmpty = false

    // MARK: Suche über alle Börsen

    @Published private(set) var searchQuery = ""
    @Published private(set) var searchHits: [ExplorerSearchHit] = []
    @Published private(set) var searchProgress: ExplorerSearchProgress?

    // MARK: DEX

    @Published private(set) var dexResults: [DexPool] = []
    @Published private(set) var dexSearching = false
    @Published private(set) var dexNoResults = false

    // MARK: Rückmeldung

    @Published var message: ExplorerMessage?

    private var searchCache: [String: MarketPairsInfo] = [:]
    private var searchMarkets: [Market] = []
    private var pendingSearchMarkets: [Market] = []
    private var searchLoadedCount = 0
    private var preloadTask: Task<Void, Never>?
    private var searchTask: Task<Void, Never>?
    private var syncTask: Task<Void, Never>?
    private var tickerTask: Task<Void, Never>?
    private var dexTask: Task<Void, Never>?
    private var marketLoadTask: Task<Void, Never>?
    private var tickerKey: String?

    init() {}

    // MARK: Abgeleitet

    var isDex: Bool { currentMarket?.key == Self.dexKey }
    var hasPairs: Bool { !baseAssets.isEmpty }
    var syncing: Bool { updateState.inProgress }
    var pairSelected: Bool { hasPairs && currentBase != nil && currentQuote != nil }

    /// Kontrakt nur bei Futures; auswählbar nur bei mehreren Laufzeiten.
    var hasContractTypes: Bool {
        !contractTypes.isEmpty && !(contractTypes.count == 1 && contractTypes[0] == .none)
    }

    var contractSelectable: Bool { hasContractTypes && contractTypes.count > 1 }

    nonisolated static func contractName(_ type: FuturesContractType) -> String {
        type == .none ? L("market_screen_spot") : type.backupName
    }

    // MARK: Börse wählen

    func setCurrentMarket(_ market: Market) {
        // Coin und Gegenwährung bleiben beim Börsenwechsel stehen, sofern es sie dort gibt.
        let keepBase = currentBase, keepQuote = currentQuote, keepContract = currentContract
        if currentMarket?.key != market.key { clearSelectionResults() }
        currentMarket = market
        canUpdatePairs = MarketService.supportsPairSync(market)
        marketLoadTask?.cancel()
        marketLoadTask = Task { [weak self] in
            let info = await PairCache.shared.pairs(for: market.key)
            guard let self, !Task.isCancelled, self.currentMarket?.key == market.key else { return }
            self.pairsInfo = info
            if self.currentBase == nil { self.currentBase = keepBase }
            if self.currentQuote == nil { self.currentQuote = keepQuote }
            if self.currentContract == nil { self.currentContract = keepContract }
            self.reconcile()
            // Noch keine Paare gespeichert? Dann gleich laden — «Sync» ist kein eigener Schritt.
            if info.pairs.isEmpty && self.canUpdatePairs && !self.updateState.inProgress {
                self.syncCurrencyPairs()
            }
        }
    }

    /// «Paar bearbeiten» (Aktionsblatt): Börse, Coin, Gegenwert und Kontrakt eines Eintrags
    /// vorwählen; die Paare der Börse laden wie beim Wählen. DEX-Pools lassen sich hier nicht
    /// wählen (nur über die DEX-Suche) — dann bleibt die Börse offen.
    func prefill(_ watch: Watch) {
        currentBase = watch.baseAsset
        currentQuote = watch.quoteAsset
        currentContract = watch.contractType
        if watch.marketKey != Self.dexKey, let market = markets.first(where: { $0.key == watch.marketKey }) {
            setCurrentMarket(market)
        }
    }

    /// Nach einem Börsenwechsel gehören Sammelliste, Kurs und Meldungen zur alten Börse.
    private func clearSelectionResults() {
        syncTask?.cancel()
        syncTask = nil
        updateState = ExplorerPairsUpdateState()
        pairsInfo = nil
        bulkPairs = []
        bulkEmpty = false
        tickerTask?.cancel()
        ticker = nil
        tickerKey = nil
        dexTask?.cancel()
        dexResults = []
        dexSearching = false
        dexNoResults = false
        reconcile()
    }

    func syncCurrencyPairs() {
        syncTask?.cancel()
        guard let market = currentMarket else { return }
        updateState = ExplorerPairsUpdateState(inProgress: true)
        syncTask = Task { [weak self] in
            do {
                try await PairCache.shared.sync(market.key)
                let info = await PairCache.shared.pairs(for: market.key)
                guard let self, !Task.isCancelled, self.currentMarket?.key == market.key else { return }
                self.searchCache[market.key] = info
                self.pairsInfo = info
                self.updateState = ExplorerPairsUpdateState()
                self.reconcile()
            } catch {
                guard let self, !Task.isCancelled, !(error is CancellationError),
                      self.currentMarket?.key == market.key else { return }
                self.updateState = ExplorerPairsUpdateState(error: ConnectionErrors.friendly(error))
            }
        }
    }

    // MARK: Paar wählen

    func setBase(_ base: String) {
        currentBase = base
        reconcile()
    }

    func setQuote(_ quote: String) {
        currentQuote = quote
        reconcile()
    }

    func setContract(_ type: FuturesContractType) {
        if currentContract != type {
            // Die Sammelliste gilt nur für den Kontrakttyp, für den sie erstellt wurde.
            bulkPairs = []
            bulkEmpty = false
        }
        currentContract = type
        reconcile()
    }

    func selectBulkPair(_ pair: CurrencyPairInfo) {
        currentBase = pair.base
        currentQuote = pair.quote
        currentContract = pair.contractType
        reconcile()
    }

    /// Hält Coin, Gegenwährung und Kontrakt gültig (wie die Sammler im Android-ViewModel)
    /// und lädt den Kurs, sobald sich das Paar ändert.
    private func reconcile() {
        let info = pairsInfo
        let bases = info?.baseCurrencies ?? []
        if baseAssets != bases { baseAssets = bases }
        if currentBase.map({ !bases.contains($0) }) ?? true { currentBase = bases.first }

        let quotes: [String] = {
            guard let info, let base = currentBase else { return [] }
            return info.quoteCurrencies(for: base)
        }()
        if quoteAssets != quotes { quoteAssets = quotes }
        if currentQuote.map({ !quotes.contains($0) }) ?? true { currentQuote = quotes.first }

        let types = info?.contractTypes(base: currentBase, quote: currentQuote) ?? []
        if contractTypes != types { contractTypes = types }
        if currentContract.map({ !types.contains($0) }) ?? true { currentContract = types.first }

        let newBulkQuotes = info?.bulkQuoteCurrencies ?? []
        if bulkQuotes != newBulkQuotes { bulkQuotes = newBulkQuotes }
        // Neue Börse oder neu synchronisiert: USDT vorwählen, sonst die häufigste.
        if bulkQuote.map({ !newBulkQuotes.contains($0) }) ?? true { bulkQuote = info?.defaultBulkQuote }

        if let info, let base = currentBase, let quote = currentQuote {
            currentPair = info.pair(base: base, quote: quote, contractType: currentContract ?? .none)
        } else {
            currentPair = nil
        }

        let key = currentMarket.flatMap { m in currentPair.map { "\(m.key)|\($0.base)|\($0.quote)|\($0.contractType.rawValue)|\($0.pairId ?? "")" } }
        if key != tickerKey {
            tickerKey = key
            loadTicker(debounce: true)
        }
    }

    // MARK: Kurs

    func retryTicker() { loadTicker(debounce: false) }

    private func loadTicker(debounce: Bool) {
        tickerTask?.cancel()
        guard let market = currentMarket, market.key != Self.dexKey, let pair = currentPair else {
            ticker = nil
            return
        }
        ticker = .loading
        tickerTask = Task { [weak self] in
            // Kurz entprellt, weil Coin und Gegenwährung oft kurz nacheinander wechseln.
            if debounce { try? await Task.sleep(nanoseconds: 300_000_000) }
            if Task.isCancelled { return }
            do {
                let t = try await MarketService.fetchTicker(market: market, info: pair)
                guard !Task.isCancelled else { return }
                self?.ticker = .loaded(t, pair)
            } catch {
                guard !Task.isCancelled else { return }
                self?.ticker = .failed(ConnectionErrors.friendly(error))
            }
        }
    }

    // MARK: Merkliste

    /// Übernimmt das gerade gewählte Paar in die Watchlist.
    func addCurrentPair() {
        guard let market = currentMarket, let pair = currentPair else {
            show(ExplorerMessage(text: L("explorer_no_pair_selected"), showView: false))
            haptic(.warning)
            return
        }
        add(market: market, pair: pair)
    }

    func addSearchHit(_ hit: ExplorerSearchHit) {
        add(market: hit.market, pair: hit.pair)
    }

    func isInWatchlist(_ market: Market, _ pair: CurrencyPairInfo) -> Bool {
        data.contains(marketKey: market.key, pair: pair)
    }

    private func add(market: Market, pair: CurrencyPairInfo) {
        let firstEver = data.isFirstPairAdd
        if let id = data.addWatch(market: market, pair: pair, group: data.addTargetGroup) {
            if firstEver {
                showFirstAdd(id)
            } else {
                show(ExplorerMessage(text: L("explorer_added_to_watchlist")))
            }
            haptic(.success)
            requestNotifications()
        } else {
            show(ExplorerMessage(text: L("explorer_already_in_watchlist")))
            haptic(.warning)
        }
    }

    // MARK: Mehrere Paare

    func setBulkQuote(_ quote: String) {
        if bulkQuote != quote {
            // Die Sammelliste gilt nur für die Gegenwährung, für die sie erstellt wurde.
            bulkPairs = []
            bulkEmpty = false
        }
        bulkQuote = quote
    }

    /// Sammelt alle Paare der gewählten Gegenwährung zum aktuellen Kontrakttyp.
    func applyAllPairs() {
        guard let info = pairsInfo, let quote = bulkQuote else {
            bulkPairs = []
            bulkEmpty = true
            return
        }
        let filtered = info.pairs(withQuote: quote, contractType: currentContract)
        bulkPairs = filtered
        bulkEmpty = filtered.isEmpty
        if let first = filtered.first {
            currentQuote = first.quote
            currentBase = first.base
            reconcile()
        }
    }

    func addAllBulkPairs() {
        guard let market = currentMarket, !bulkPairs.isEmpty else {
            show(ExplorerMessage(text: L("explorer_no_pair_selected"), showView: false))
            return
        }
        let result = data.addWatches(market: market, pairs: bulkPairs, group: data.addTargetGroup)
        show(ExplorerMessage(text: L("explorer_bulk_added", count: result.added, result.added, result.skipped), long: true))
        haptic(result.added > 0 ? .success : .warning)
        if result.added > 0 { requestNotifications() }
    }

    // MARK: Suche über alle Börsen

    func setSearchQuery(_ query: String) {
        searchQuery = query
        if !query.trimmingCharacters(in: .whitespaces).isEmpty { ensurePairsLoaded() }
        runSearch()
    }

    private func runSearch() {
        searchTask?.cancel()
        let query = searchQuery.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else {
            searchHits = []
            return
        }
        let markets = searchMarkets
        let cache = searchCache
        searchTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 150_000_000)
            if Task.isCancelled { return }
            let hits = await Task.detached(priority: .userInitiated) {
                ExplorerPairSearch.find(query, markets: markets, cache: cache)
            }.value
            guard !Task.isCancelled else { return }
            self?.searchHits = hits
        }
    }

    /// Paarlisten aller Börsen bereitstellen: gespeicherte sofort, fehlende im
    /// Hintergrund laden (höchstens vier gleichzeitig). Nur einmal pro Sitzung.
    private func ensurePairsLoaded() {
        guard preloadTask == nil else { return }
        let list = markets.filter { $0.key != Self.dexKey }
        searchMarkets = list
        pendingSearchMarkets = list
        searchLoadedCount = 0
        searchProgress = ExplorerSearchProgress(done: 0, total: list.count)
        preloadTask = Task { @MainActor [weak self] in
            let workers = (0..<4).map { _ in
                Task { @MainActor [weak self] in
                    while let market = self?.nextPendingSearchMarket() {
                        let result = await ExplorerViewModel.loadPairsForSearch(market)
                        self?.searchPairsLoaded(key: result.0, info: result.1)
                    }
                }
            }
            for worker in workers { await worker.value }
            self?.searchProgress = nil
            self?.runSearch()
        }
    }

    private func nextPendingSearchMarket() -> Market? {
        pendingSearchMarkets.isEmpty ? nil : pendingSearchMarkets.removeFirst()
    }

    private func searchPairsLoaded(key: String, info: MarketPairsInfo?) {
        if let info { searchCache[key] = info }
        searchLoadedCount += 1
        searchProgress = ExplorerSearchProgress(done: searchLoadedCount, total: searchMarkets.count)
        runSearch()
    }

    /// Gespeicherte Liste; noch nie synchronisiert → jetzt laden (bei Fehler die gespeicherte).
    nonisolated private static func loadPairsForSearch(_ market: Market) async -> (String, MarketPairsInfo?) {
        let cached = await PairCache.shared.pairs(for: market.key)
        let synced = await PairCache.shared.hasSynced(market.key)
        if (cached.pairs.isEmpty || !synced) && MarketService.supportsPairSync(market) {
            if (try? await PairCache.shared.sync(market.key)) != nil {
                return (market.key, await PairCache.shared.pairs(for: market.key))
            }
        }
        return (market.key, cached.pairs.isEmpty ? nil : cached)
    }

    // MARK: DEX

    /// Sucht Pools zu Token-Name, Symbol oder Adresse.
    func searchDex(_ query: String) {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        dexTask?.cancel()
        dexSearching = true
        dexNoResults = false
        dexTask = Task { [weak self] in
            do {
                let response = try await MarketHTTP.call(DexScreener.searchURL(q))
                let pools = try DexScreener.parseSearch(response)
                guard let self, !Task.isCancelled else { return }
                var seen = Set<String>()
                self.dexResults = Array(pools.filter { seen.insert($0.pairId).inserted }.prefix(25))
                self.dexNoResults = pools.isEmpty
                self.dexSearching = false
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.dexResults = []
                self.dexSearching = false
                self.show(ExplorerMessage(text: L("something_went_wrong"), showView: false))
            }
        }
    }

    func addDexPool(_ pool: DexPool) {
        guard let market = currentMarket else { return }
        let pair = CurrencyPairInfo(pool.baseSymbol, pool.watchQuote, pool.pairId)
        let firstEver = data.isFirstPairAdd
        if let id = data.addWatch(market: market, pair: pair, group: data.addTargetGroup) {
            if firstEver {
                showFirstAdd(id)
            } else {
                show(ExplorerMessage(text: L("dex_added", pool.baseSymbol)))
            }
            haptic(.success)
            requestNotifications()
        } else {
            show(ExplorerMessage(text: L("dex_already", pool.baseSymbol)))
            haptic(.warning)
        }
    }

    // MARK: Hilfen

    /// Allererstes Paar: «BTC/USDT wird jetzt überwacht» (länger stehen lassen, VoiceOver
    /// sagt es an); die Merkliste spielt beim nächsten Öffnen den Moment der Zeile ab.
    /// Die Haptik (Erfolg) kommt wie bei jedem Hinzufügen einmal vom Aufrufer.
    private func showFirstAdd(_ watchId: Int64) {
        let text = data.celebrateFirstAdd(watchId)
        // Noch kein Alarm: «Alarm setzen» öffnet die Alarme des Paars (wie in Android)
        let noAlarm = (data.activeAlarmCounts[watchId] ?? 0) == 0
        show(ExplorerMessage(text: text, long: true, alarmWatchId: noAlarm ? watchId : nil))
        AccessibilityNotification.Announcement(text).post()
    }

    private func show(_ m: ExplorerMessage) {
        withAnimation(.spring(duration: 0.35)) { message = m }
    }

    private func haptic(_ type: UINotificationFeedbackGenerator.FeedbackType) {
        UINotificationFeedbackGenerator().notificationOccurred(type)
    }

    /// Neue Paare haben Meldungen an — Erlaubnis jetzt fragen, nicht beim Start.
    private func requestNotifications() {
        Task { _ = await Notifier.requestPermission() }
    }
}
