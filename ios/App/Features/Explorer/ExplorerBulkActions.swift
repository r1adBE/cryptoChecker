import Foundation
import Accessibility
import SwiftUI
import UIKit

// «Mehrere Paare auf einmal» (Teil von `ExplorerViewModel`): Gegenwährung, Liste sammeln, Kennzahlen für
// die Schnellfilter, Paare an-/abwählen und gemeinsam hinzufügen.
// Eigene Datei, weil die Sammelaufnahme ein eigener Modus ist (wie `ExplorerBulkActions.kt`).

extension ExplorerViewModel {
    func selectBulkPair(_ pair: ExchangePair) {
        coin = pair.base
        quote = pair.quote
        contract = pair.contractType
        reconcile()
    }

    // MARK: Sammelaufnahme

    func setBulkQuote(_ quote: String) {
        // Eine gesammelte Liste passt nur zur Gegenwährung, mit der sie entstand
        if quote != bulkQuote { dropBulkList() }
        bulkQuote = quote
    }

    func dropBulkList() {
        bulkPairs = []
        bulkEmpty = false
    }

    /// Alle Paare der gewählten Gegenwährung (für den aktuellen Kontrakttyp) zusammenstellen.
    func collectBulkPairs() {
        guard let info = pairList, let counter = bulkQuote else {
            bulkPairs = []
            bulkEmpty = true
            return
        }
        let collected = info.pairs(withQuote: counter, contractType: contract)
        bulkPairs = collected
        bulkEmpty = collected.isEmpty
        guard let lead = collected.first else { return }
        quote = lead.quote
        coin = lead.base
        reconcile()
    }

    /// Schnellfilter-Werte der Liste «Alle …-Paare» holen – nur über die Sammelabfrage der Börse
    /// (dieselbe wie beim Aktualisieren der Merkliste), nie Paar für Paar. Für dieselbe Liste
    /// höchstens einmal je Minute. Gegenstück zu `loadBulkStats` (Android).
    func loadBulkStats() {
        guard let exchange = exchange, exchange.key != Self.dexKey, exchange.bulkTickersNumOfRequests > 0 else { return }
        var unique = Set<String>()
        var ids: [String] = []
        for case let id? in bulkPairs.map(\.pairId) where unique.insert(id).inserted { ids.append(id) }
        if ids.isEmpty { return }
        let key = "\(exchange.key)|\(ids.sorted().joined(separator: ","))"
        let now = Date()
        if key == bulkStatsKey, now.timeIntervalSince(bulkStatsAt) < 60 { return }
        bulkStatsKey = key
        bulkStatsAt = now
        bulkStatsTask?.cancel()
        let exchangeKey = exchange.key
        bulkStatsTask = Task { [weak self] in
            let answer = await ExchangeService.fetchBulkTickers(exchange: exchange, pairIds: ids)
            guard let self, !Task.isCancelled, self.isStill(exchangeKey) else { return }
            self.bulkStats = answer.tickers.mapValues { ExplorerQuickFilters.stats(of: $0) }
        }
    }

    func addBulkPairs() {
        guard let exchange = exchange, !bulkPairs.isEmpty else {
            show(ExplorerMessage(text: L("explorer_no_pair_selected"), showView: false))
            return
        }
        let pairs = bulkPairs
        // EINE Rückfrage F-08 für alle Paare, vorher; «Abbrechen» = nichts angelegt, keine Meldung
        data.withWatchlistConfirm { [weak self] in self?.addBulkPairsNow(exchange, pairs) }
    }

    private func addBulkPairsNow(_ exchange: Exchange, _ bulkPairs: [ExchangePair]) {
        let outcome = data.addWatches(exchange: exchange, pairs: bulkPairs, group: data.addTargetGroup)
        // Merkliste gesperrt: keine Zählung «0 hinzugefügt», Hinweis und Haptik kommen von `.watchlistLockedHint`
        if outcome.locked { return }
        let anyAdded = outcome.added > 0
        var text = L("explorer_bulk_added", count: outcome.added, outcome.added, outcome.skipped)
        // Merkliste voll: nur bis zur Grenze angelegt, die Rückmeldung nennt die übrigen — wie Android
        if outcome.refused > 0 {
            text += " " + L("explorer_bulk_not_added_limit", count: outcome.refused, outcome.refused,
                            LocaleNumbers.integer(WatchlistLimits.maxPairs, grouping: true))
        }
        // Neue Paare angelegt: «Rückgängig» entfernt genau diese wieder — wie Android
        show(ExplorerMessage(text: text, long: true, undoIds: anyAdded ? outcome.addedIds : nil,
                             undoFinishedAt: EpochTime.nowMillis,
                             kind: SnackbarKind.bulkAdded(added: outcome.added, refused: outcome.refused)))
        haptic(anyAdded ? .success : .warning)
        if anyAdded { requestNotifications() }
    }

    /// «Rückgängig» nach dem Hinzufügen: die eben neu angelegten Paare wieder entfernen (vorhandene bleiben, ebenso
    /// neue, die inzwischen einen Alarm haben).
    func undoBulkAdd(_ ids: [Int64]) {
        guard !ids.isEmpty else { return }
        data.withWatchlistConfirm { [weak self] in _ = self?.data.deleteNewWatchesForUndo(ids: Set(ids)) }
    }
}

/// Wie viele Paare von «Alle …-Paare» sind noch nicht in der Merkliste? Gleiche Identität wie beim Anlegen
/// (`AppData.insertOutcome`, Kontrakttyp angeglichen) — wie Android `bulkNewCount`.
enum ExplorerBulkCount {
    static func newCount(exchangeKey: String?, pairs: [ExchangePair], watches: [Watch]) -> Int {
        guard let exchangeKey else { return 0 }
        let present = Set(watches.map(\.identity))
        return pairs.filter { p in
            let contract = FuturesContractType.normalized(exchangeKey: exchangeKey, pairId: p.pairId, p.contractType)
            let key = WatchIdentity.key(exchangeKey: exchangeKey, base: p.base, quote: p.quote,
                                        contract: String(contract.rawValue), pairId: p.pairId)
            return !present.contains(key)
        }.count
    }
}

/// Rückfrage vor «Rückgängig» nach «Mehrere Paare auf einmal»: Antippen von «Rückgängig» merkt nur die neu angelegten
/// Paare samt Ende der Aufnahme (`request`); erst «Entfernen» (`remove`) gibt sie an den Undo-Weg
/// (`ExplorerViewModel.undoBulkAdd`) weiter — und nur, solange «Rückgängig» noch gilt (`undoOffered`), sonst wird
/// nichts gelöscht. «Behalten» (`keep`) — ebenso Wischen bzw. Tippen neben den Dialog — verwirft sie. Wie Android
/// `BulkUndoConfirm`.
struct BulkUndoConfirm: Equatable {
    /// So lange nach dem Ende der Aufnahme gilt «Rückgängig» — wie Android `BulkAddRunner.UNDO_WINDOW_MS`.
    static let undoWindowMillis: Int64 = 60_000

    /// Ende (`finishedAt`, 0 = keins) höchstens `undoWindowMillis` vor `now`; Uhr rückwärts: nein — wie Android
    /// `BulkAddRunner.undoOffered`.
    static func undoOffered(finishedAt: Int64, now: Int64) -> Bool {
        finishedAt > 0 && (0...undoWindowMillis).contains(now - finishedAt)
    }

    /// Paare, für die der Dialog gerade fragt; nil = kein Dialog.
    private(set) var pending: [Int64]?
    /// Zahl der Paare der letzten Anfrage: Der Titel bleibt beim Ausblenden des Dialogs gleich (dann ist `pending`
    /// schon nil).
    private(set) var lastCount = 0
    /// Ende der Aufnahme dieser Paare (ms); damit prüft «Entfernen» das Zeitfenster erneut.
    private(set) var finishedAt: Int64 = 0

    var isPresented: Bool { pending != nil }

    /// «Rückgängig» getippt: Dialog für `ids` (Aufnahme beendet um `finishedAt`) öffnen (ohne Paare keiner).
    mutating func request(_ ids: [Int64], finishedAt: Int64) {
        guard !ids.isEmpty else { return }
        pending = ids
        lastCount = ids.count
        self.finishedAt = finishedAt
    }

    /// «Behalten»: nichts entfernen.
    mutating func keep() { pending = nil }

    /// «Entfernen»: Dialog zu; gilt «Rückgängig» um `now` noch, `undo` mit den Paaren des Dialogs (`presented`, sonst
    /// den gemerkten) — genau einmal; sonst `expired`, dann wird nichts gelöscht.
    mutating func remove(_ presented: [Int64]? = nil, now: Int64, expired: () -> Void, undo: ([Int64]) -> Void) {
        guard let ids = presented ?? pending, !ids.isEmpty else { return }
        pending = nil
        if Self.undoOffered(finishedAt: finishedAt, now: now) { undo(ids) } else { expired() }
    }
}
