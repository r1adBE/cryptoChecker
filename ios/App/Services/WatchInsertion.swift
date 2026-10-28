import Foundation

/// Neue Paare in die Merkliste einfügen — die Regeln von `AppData.insertOutcome` (wie Android
/// `WatchRepository.tryAddWatch`): Kontrakttyp angleichen, schon vorhanden geht vor «voll», Gruppe nach `AutoGroup`,
/// neue Paare mit `Watch.newPairNotificationEnabled`, Ids nie neu vergeben (`nextWatchId`). Vorhandene Identitäten,
/// höchste Id und nächster Platz werden EINMAL aus dem Stand berechnet und je eingefügtem Paar fortgeschrieben: viele
/// Paare auf einmal (`AppData.addWatches`) bleiben linear (`AddWatchesPerformanceTests`).
struct WatchInsertion {
    private var present: Set<WatchIdentity.Key>
    private var maxId: Int64
    private var nextOrder: Int

    /// Stand von `s`; danach `s` nur noch über `insert` ändern.
    init(_ s: SharedStorage.Snapshot) {
        present = Set(s.watches.map(\.identity))
        maxId = s.watches.map(\.id).max() ?? 0
        nextOrder = (s.watches.map(\.sortOrder).max() ?? -1) + 1
    }

    /// Ein Paar einfügen: angelegt (mit Id), schon vorhanden oder Merkliste voll (`WatchlistLimits.maxPairs`).
    mutating func insert(_ s: inout SharedStorage.Snapshot, exchange: Exchange, pair rawPair: ExchangePair,
                         group: String? = nil) -> AddWatchOutcome {
        // Paar aus einer älteren, noch nicht neu geladenen Paarliste: inverse Perpetuals wie heute gespeichert
        var pair = rawPair
        pair.contractType = FuturesContractType.normalized(exchangeKey: exchange.key, pairId: pair.pairId,
                                                           pair.contractType)
        let wanted = WatchIdentity.key(exchangeKey: exchange.key, base: pair.base, quote: pair.quote,
                                       contract: String(pair.contractType.rawValue), pairId: pair.pairId)
        if present.contains(wanted) { return .duplicate }
        guard WatchlistLimits.canAddPair(s.watches.count) else { return .full }
        let id = max(s.nextWatchId, maxId + 1)
        s.nextWatchId = id + 1
        maxId = id
        var watch = Watch(id: id, exchangeKey: exchange.key, exchangeName: exchange.name,
                          baseAsset: pair.base, quoteAsset: pair.quote,
                          contractType: pair.contractType, pairId: pair.pairId, sortOrder: nextOrder)
        nextOrder += 1
        // Ohne Wahl: TradFi-Futures nach «TradFi», Laufzeit-Futures nach «QTLY»
        watch.groupName = AutoGroup.resolve(group, pair: pair)
        // Kurs-Mitteilung neuer Paare nach `Watch.newPairNotificationEnabled` (umschalten im Paar-Menü)
        watch.notificationEnabled = Watch.newPairNotificationEnabled
        s.watches.append(watch)
        present.insert(wanted)
        return .added(id)
    }

    /// Mehrere Paare: vorhandene übersprungen; ist die Merkliste voll, kommen die übrigen nicht mehr dazu (`refused`).
    static func insertAll(_ s: inout SharedStorage.Snapshot, exchange: Exchange, pairs: [ExchangePair],
                          group: String? = nil) -> BulkAddResult {
        var insertion = WatchInsertion(s)
        var result = BulkAddResult(added: 0, skipped: 0)
        for p in pairs {
            switch insertion.insert(&s, exchange: exchange, pair: p, group: group) {
            case .added(let id):
                result.added += 1
                result.addedIds.append(id)
            case .duplicate: result.skipped += 1
            case .full: result.refused += 1
            case .locked: break  // kommt hier nicht vor (`insert` sperrt nicht)
            }
        }
        return result
    }
}
