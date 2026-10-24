import BackgroundTasks
import Foundation
import SwiftUI
import WidgetKit
import UIKit
import UserNotifications

// Portfolio in AppData: Transaktion speichern, CSV-Import und Rücknahme, löschen, wiederherstellen, Bestände,
// Übernahme alter Merklisten-Bestände und die Portfolio-Datei.
// Eigene Datei, weil das Portfolio ein eigener Bereich ist (wie `PortfolioEditActions.kt` auf Android).

extension AppData {
    // MARK: Portfolio

    /// Neu anlegen (id 0) oder ändern — wie `PortfolioRepository.save`.
    /// Coin wird vereinheitlicht (Grossbuchstaben), leere Notiz entfällt.
    /// Ungültige Einträge (ohne Coin, Menge ≤ 0) werden nicht gespeichert.
    @discardableResult
    func savePortfolioTx(_ tx: PortfolioTx) -> Int64? {
        guard let clean = Self.cleanedPortfolioTx(tx) else { return nil }
        var file = currentPortfolioFile()
        let id: Int64
        if clean.id <= 0 {
            id = PortfolioStore.insert(clean, into: &file)
        } else {
            // Wie `@Update`: gibt es die Transaktion nicht mehr, passiert nichts
            guard PortfolioStore.update(clean, in: &file) else { return nil }
            id = clean.id
        }
        guard setPortfolioFile(file) else { return nil }
        return id
    }

    /// Herkunftsschlüssel schon importierter Zeilen (CSV-Import: Doppelte) samt dem zweiten Schlüssel verknüpfter
    /// Umbuchungen (`linkedImportKey`: beide Seiten gelten als importiert) — wie `PortfolioRepository.importKeys`.
    var portfolioImportKeys: Set<String> { Self.importKeys(portfolio) }

    nonisolated static func importKeys(_ txs: [PortfolioTx]) -> Set<String> {
        Set(txs.compactMap(\.importKey) + txs.compactMap(\.linkedImportKey))
    }

    /// CSV-Importläufe mit Anzahl, Zeit und Format, neueste zuerst — wie `PortfolioRepository.importBatches`.
    var portfolioImportBatches: [PortfolioImportBatchInfo] { PortfolioImportBatch.summarize(portfolio.map(\.importBatch)) }

    /// «Import rückgängig»: löscht die Transaktionen des Laufs `batch`; eine vom Lauf verknüpfte Umbuchung mit gespeicherter
    /// Seite eines früheren Imports wird auf deren Zustand davor zurückgesetzt, eine verknüpfte Umbuchung, deren gespeicherte
    /// Seite aus diesem Lauf stammt, wieder die darin aufgegangene Zeile der späteren Datei (bleibt in deren Lauf)
    /// (`CsvTransferMatch.undoPlan`; beide verlieren Verknüpfung und Zustand) — wie `PortfolioRepository.undoImport`. Gibt
    /// die gelöschten und zurückgesetzten Transaktionen im Zustand vorher zurück (`restorePortfolioTxs` ersetzt je id und
    /// stellt die Verknüpfung wieder her). Leer, wenn nichts da ist oder nicht geschrieben wurde.
    @discardableResult
    func undoPortfolioImport(_ batch: String) -> [PortfolioTx] {
        var file = currentPortfolioFile()
        let plan = CsvTransferMatch.undoPlan(
            file.transactions.map { CsvImportedRef(id: $0.id, importBatch: $0.importBatch, restoreState: $0.importRestoreState) },
            batch: batch)
        let touched = file.transactions.filter {
            plan.delete.contains($0.id) || plan.revert[$0.id] != nil || plan.detach[$0.id] != nil
        }
        guard !touched.isEmpty else { return [] }
        file.transactions = file.transactions.compactMap { tx in
            if plan.delete.contains(tx.id) { return nil }
            if let a = plan.detach[tx.id] {
                var detached = tx
                detached.type = a.type
                detached.amountExact = a.amount
                detached.priceExact = a.priceUsdt
                detached.time = a.time
                detached.note = nil
                detached.feeExact = a.fee
                detached.feeUnit = a.feeUnit
                detached.exchange = nil
                detached.transferFrom = nil
                detached.transferTo = nil
                detached.feeCoin = a.feeCoin
                detached.feeCoinAmountExact = a.feeCoinAmount
                detached.feeCoinPriceExact = nil
                detached.importKey = a.importKey
                detached.acquiredTime = nil
                detached.linkedImportKey = nil
                detached.importRestoreState = nil
                return detached
            }
            guard let o = plan.revert[tx.id] else { return tx }
            var reverted = tx
            reverted.type = o.type
            reverted.amountExact = o.amount
            reverted.priceExact = o.priceUsdt
            reverted.time = o.time
            reverted.feeExact = o.fee
            reverted.feeUnit = o.feeUnit
            reverted.feeCoin = o.feeCoin
            reverted.feeCoinAmountExact = o.feeCoinAmount
            reverted.feeCoinPriceExact = o.feeCoinPrice
            reverted.transferFrom = o.transferFrom
            reverted.transferTo = o.transferTo
            reverted.acquiredTime = o.acquiredTime
            reverted.exchange = o.exchange
            reverted.importKey = o.importKey
            reverted.importBatch = o.importBatch
            reverted.linkedImportKey = nil
            reverted.importRestoreState = nil
            return reverted
        }
        guard setPortfolioFile(file) else { return [] }
        return touched
    }

    /// Bereinigt wie `PortfolioRepository.save`; ungültig (ohne Coin, Menge ≤ 0) = nil. Rein (auch im Hintergrund,
    /// `withImported`).
    nonisolated static func cleanedPortfolioTx(_ tx: PortfolioTx) -> PortfolioTx? {
        var clean = tx
        clean.coin = PortfolioCalculator.normalizeCoin(tx.coin)
        clean.note = tx.note.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.flatMap { $0.isEmpty ? nil : $0 }
        // Gebühr nur gültig (> 0) und dann immer mit Einheit; ohne Gebühr keine Einheit (wie Android)
        clean.feeExact = PortfolioTx.validFee(tx.feeExact)
        clean.feeUnit = clean.feeExact == nil ? nil : (tx.feeUnit ?? .QUOTE)
        // Umbuchung: von/nach statt Börse (wie Android)
        let transfer = tx.type == .TRANSFER
        clean.exchange = transfer ? nil : PortfolioTx.cleanExchange(tx.exchange)
        clean.transferFrom = transfer ? PortfolioTx.cleanExchange(tx.transferFrom) : nil
        clean.transferTo = transfer ? PortfolioTx.cleanExchange(tx.transferTo) : nil
        // An Ehe-/Lebenspartner gibt es nur bei einer Schenkung (wie Android)
        clean.giftToSpouse = tx.isGiftToSpouse
        // Gebühr im dritten Coin nur vollständig (Coin ≠ Coin der Transaktion, Menge > 0); Preis ≥ 0 oder keiner
        let third = PortfolioTx.cleanFeeCoin(tx.feeCoin, coin: clean.coin, amount: tx.feeCoinAmountExact)
        clean.feeCoin = third?.coin
        clean.feeCoinAmountExact = third?.amount
        clean.feeCoinPriceExact = third == nil ? nil : tx.feeCoinPriceExact.flatMap { $0 >= 0 ? $0 : nil }
        clean.importKey = PortfolioTx.cleanImportKey(tx.importKey)
        // Verknüpfte Umbuchung: zweiter Herkunftsschlüssel nur zu einem ersten, Zustand für «Rückgängig» nur zur Verknüpfung
        clean.linkedImportKey = clean.importKey == nil ? nil : PortfolioTx.cleanImportKey(tx.linkedImportKey)
        clean.importRestoreState = clean.linkedImportKey == nil ? nil : tx.importRestoreState
        clean.acquiredTime = PortfolioTx.cleanAcquiredTime(tx.type, tx.acquiredTime)
        // Tausch: erhaltener Coin ≠ Coin und Menge > 0 (sonst ungültig), Wert ≥ 0 oder unbekannt (wie Android)
        let swap = tx.type == .SWAP
        clean.toCoin = swap ? PortfolioTx.cleanToCoin(tx.toCoin, coin: clean.coin) : nil
        clean.toAmountExact = swap ? tx.toAmountExact.flatMap { $0 > 0 ? $0 : nil } : nil
        clean.swapValueExact = swap ? tx.swapValueExact.flatMap { $0 >= 0 ? $0 : nil } : nil
        clean.importBatch = PortfolioImportBatch.clean(tx.importBatch)
        if swap && (clean.toCoin == nil || clean.toAmountExact == nil) { return nil }
        guard !clean.coin.isEmpty, clean.amountExact > 0 else { return nil }
        return clean
    }

    /// - Returns: false, wenn nicht geschrieben (gesperrte Datei, ohne Schlüssel) — die Transaktion bleibt.
    @discardableResult
    func deletePortfolioTx(_ id: Int64) -> Bool {
        PortfolioEdits.deleteTx(id, in: currentPortfolioFile(), write: { setPortfolioFile($0) })
    }

    /// Wischen auf einem Coin: alle seine Transaktionen (auch Tausche in diesen Coin) löschen und zurückgeben (für
    /// «Rückgängig») — wie `PortfolioRepository.deleteCoin`. Leer, wenn nichts da ist oder nicht geschrieben wurde.
    @discardableResult
    func deletePortfolioCoin(_ coin: String) -> [PortfolioTx] {
        PortfolioEdits.deleteCoin(coin, in: currentPortfolioFile(), write: { setPortfolioFile($0) })
    }

    /// «Rückgängig»: gelöschte Transaktionen mit ihren alten ids wieder anlegen — wie
    /// `PortfolioRepository.restore`. Die ids vergibt `nextId` nie doppelt.
    /// - Returns: false, wenn nicht geschrieben.
    @discardableResult
    func restorePortfolioTxs(_ transactions: [PortfolioTx]) -> Bool {
        PortfolioEdits.restore(transactions, in: currentPortfolioFile(), write: { setPortfolioFile($0) })
    }

    /// «Portfolio leeren» (nach Rückfrage): alle Transaktionen löschen — wie
    /// `PortfolioRepository.clearAll`. Gemerkte Kurse nennen die gehaltenen Coins: mit dem Portfolio weg (wie Android),
    /// aber nur, wenn das Leeren geschrieben wurde. - Returns: false, wenn nicht geschrieben.
    @discardableResult
    func clearPortfolio() -> Bool {
        PortfolioEdits.clear(in: currentPortfolioFile(), write: { setPortfolioFile($0) },
                             afterSave: { PortfolioPriceSource.clearStored() })
    }

    /// Bestand eines Coins ohne die Transaktion `excludingId` (für die Verkaufs-Warnung).
    /// Exakter Bestand ohne die Transaktion `excludingId` (Prüfung «mehr als vorhanden»).
    func portfolioHoldings(of coin: String, excludingId: Int64) -> Decimal {
        // Nur eigener Bestand: Adress-Guthaben sind keine Posten, ein Verkauf kann sie nicht abgeben (wie Android)
        let trades = AddressBalance.withAddresses(portfolio, addresses: watchedAddresses).filter { $0.id != excludingId }
        return PortfolioCalculator.position(coin, trades: trades, currentPrice: nil).ledgerHoldingsExact
    }

    /// `.FIFO_WALLET`: Bestand von `coin` in `wallet` ohne die Transaktion `excludingId` (offene Posten dieser Wallet,
    /// `PortfolioTxForm.walletHoldings`); andere Methoden: nil — wie `walletHoldingsOf` (Android).
    func portfolioWalletHoldings(of coin: String, wallet: String?, excludingId: Int64) -> Decimal? {
        guard settings.costMethod == .FIFO_WALLET else { return nil }
        let trades = portfolio.filter { $0.id != excludingId }
        return PortfolioTxForm.walletHoldings(PortfolioCalculator.openLots(coin, trades: trades, method: .FIFO_WALLET), wallet: wallet)
    }

    /// Einmalige Übernahme des alten Bestands je Paar (`Watch.holdings`) ins
    /// Portfolio: je Coin ein Kauf ohne Preis (Mengen gleicher Coins addiert),
    /// danach wird der Bestand in der Merkliste geleert — wie
    /// `PortfolioRepository.migrateHoldingsOnce`. Merker «holdings_migrated».
    func migrateHoldingsOnce() {
        guard !PortfolioStore.holdingsMigrated else { return }
        if currentPortfolioFile().holdingsImportDone {
            // Käufe stehen schon im Portfolio (Abbruch nach dem Speichern): nur noch leeren
            clearWatchHoldings()
        } else {
            // Portfolio-Datei gerade nicht schreibbar: beim nächsten Start nochmals versuchen
            guard importHoldings(onlyNewCoins: false, markDone: true) else { return }
        }
        PortfolioStore.holdingsMigrated = true
    }

    /// Nach einer Wiederherstellung — wie Android: Transaktionen der Sicherung
    /// übernehmen (fehlt «portfolio», bleibt das bestehende stehen), dann den
    /// Bestand alter Sicherungen ins Portfolio übernehmen (nur Coins ohne Transaktion).
    /// - Returns: false, wenn das Portfolio nicht geschrieben werden konnte.
    @discardableResult
    func restorePortfolio(_ transactions: [PortfolioTx]?) -> Bool {
        if let transactions {
            let current = currentPortfolioFile()
            var file = PortfolioFile(transactions: [], nextId: current.nextId,
                                     holdingsImportDone: current.holdingsImportDone)
            for tx in transactions {
                var item = tx
                item.coin = PortfolioCalculator.normalizeCoin(tx.coin)
                PortfolioStore.insert(item, into: &file)
            }
            guard setPortfolioFile(file) else { return false }
        }
        importHoldings(onlyNewCoins: true)
        return true
    }

    /// Stand der Portfolio-Datei vor dem Wiederherstellen (für das Rückrollen, `RestoreSteps`).
    func portfolioFileForRollback() -> PortfolioFile { currentPortfolioFile() }

    /// Wiederherstellen gescheitert: vorherige Portfolio-Datei unverändert zurückschreiben.
    @discardableResult
    func rollBackPortfolioFile(_ file: PortfolioFile) -> Bool { setPortfolioFile(file) }

    /// Bestände der Merkliste in Käufe umwandeln und leeren. Erst das Portfolio
    /// speichern, dann die Merkliste — so geht bei einem Abbruch nichts verloren.
    /// `markDone`: Merker «übernommen» in derselben Portfolio-Datei setzen (einmalige Übernahme),
    /// damit ein Abbruch vor dem Leeren der Merkliste keine doppelten Käufe ergibt.
    /// - Returns: false, wenn die Portfolio-Datei nicht geschrieben werden konnte (dann bleibt der Bestand
    ///   in der Merkliste stehen).
    @discardableResult
    private func importHoldings(onlyNewCoins: Bool, markDone: Bool = false) -> Bool {
        var watches = snapshot.watches
        var file = currentPortfolioFile()
        PortfolioStore.importHoldings(watches: &watches, into: &file, onlyNewCoins: onlyNewCoins, now: TimeUtils.nowMillis)
        guard watches != snapshot.watches else { return true }
        if markDone { file.holdingsImportDone = true }
        // Erst nach gespeichertem Portfolio den Bestand der Merkliste leeren
        guard setPortfolioFile(file) else { return false }
        clearWatchHoldings()
        return true
    }

    private func clearWatchHoldings() {
        guard snapshot.watches.contains(where: { $0.holdings != nil }) else { return }
        mutate({ s in
            for i in s.watches.indices { s.watches[i].holdings = nil }
        }, reloadWidgets: false)
    }

    /// Wartende, nicht versiegelt gespeicherte Stände (Portfolio, Portfolio-Alarme, beobachtete Adressen) jetzt schreiben.
    /// - Returns: true = die Merkliste darf gelesen werden (`PortfolioSaveRetry.allowsDiskRead`).
    @discardableResult
    func retryPendingPortfolioSaves() -> Bool {
        guard portfolioSavePending else { return true }
        var portfolioDone = true
        // Nur ein vollständig gelesener Stand (sonst überschriebe er eine nicht lesbare Datei)
        if portfolioIntact {
            let file = portfolioFile, include = settings.portfolioSystemBackup
            portfolioDone = Self.portfolioWrites.write { PortfolioStore.save(file, includeInBackup: include) } == .saved
        }
        if watchedAddressSavePending && watchedAddressIntact {
            let result = WatchedAddressStore.save(watchedAddressFile, includeInBackup: settings.portfolioSystemBackup)
            watchedAddressSavePending = result != .saved
        }
        // Portfolio-Alarme über die Schreib-Warteschlange wie `mutate` (neuere Kurse und Auslösungen der
        // Datei bleiben, kein doppelter Alarm) und nicht mehr blockierend auf dem Haupt-Thread (`PortfolioSaveRetry`)
        let alarmsQueued = PortfolioSaveRetry.queuesAlarms(alarmsPending: SharedStorage.portfolioAlarmsPending,
                                                           watchlistReadOnce: watchlistReadOnce)
        if alarmsQueued {
            let done = portfolioDone
            SharedStorage.saveSnapshotInBackground(snapshot, done: { [weak self] in
                guard let self else { return }
                self.portfolioSavePending = PortfolioSaveRetry.stillPending(
                    portfolioDone: done, addressesPending: self.watchedAddressSavePending,
                    alarmsPending: SharedStorage.portfolioAlarmsPending)
            })
        }
        portfolioSavePending = PortfolioSaveRetry.stillPending(portfolioDone: portfolioDone,
                                                               addressesPending: watchedAddressSavePending,
                                                               alarmsPending: SharedStorage.portfolioAlarmsPending)
        return PortfolioSaveRetry.allowsDiskRead(portfolioDone: portfolioDone, addressesPending: watchedAddressSavePending)
    }

    /// Stand zum Ändern: war die Datei nicht lesbar, zuerst neu lesen (z. B. jetzt entsperrt).
    func currentPortfolioFile() -> PortfolioFile {
        if !portfolioIntact {
            let loaded = PortfolioStore.loadChecked()
            if loaded.intact {
                portfolioFile = loaded.file
                portfolioIntact = true
                portfolio = PortfolioStore.newestFirst(loaded.file.transactions)
                recoverPortfolioIfDeferred()
            } else if loaded.file.dropped > 0 {
                // Teilweise lesbar: mit dem Lesbaren weiterarbeiten (die Datei wird vor dem Schreiben gesichert)
                portfolioFile = loaded.file
                portfolio = PortfolioStore.newestFirst(loaded.file.transactions)
            }
        }
        return portfolioFile
    }

    /// - Returns: false, wenn nicht gespeichert (nicht lesbare, gesperrte Datei).
    @discardableResult
    func setPortfolioFile(_ file: PortfolioFile) -> Bool {
        // Grund eines Fehlschlags für den Hinweis (gesperrt bzw. «Konnte nicht gespeichert werden»)
        portfolioWriteLocked = false
        // Nicht (ganz) lesbare Datei nie still überschreiben: erst daneben sichern; geht nicht einmal das
        // (Datei gesperrt), gar nicht schreiben — die Änderung fällt dann weg, die Daten bleiben
        if !portfolioIntact {
            guard PortfolioStore.preserveUnreadable(includeInBackup: settings.portfolioSystemBackup) else {
                portfolioWriteLocked = true
                return false
            }
            portfolioIntact = true
        }
        // Erst schreiben, dann anzeigen: Scheitert das Schreiben, zeigt die Liste nichts, was nicht gespeichert ist.
        // Ohne Schlüssel (nie Klartext): ohne Datei Stand im Speicher behalten und das nennen — das nächste Speichern
        // schreibt ihn; mit gespeichertem Stand in der Datei Fehler statt Erfolg (`PortfolioStore.keepsUnsealed`)
        // In der Schreib-Reihenfolge (`portfolioWrites`): ein Import im Hintergrund schreibt danach nicht mehr seinen
        // älteren Stand darüber
        let include = settings.portfolioSystemBackup
        switch Self.portfolioWrites.write({ PortfolioStore.save(file, includeInBackup: include) }) {
        case .saved: portfolioSavePending = SharedStorage.portfolioAlarmsPending || watchedAddressSavePending
        case .needsKey:
            let stored = FileManager.default.fileExists(atPath: PortfolioStore.url.path)
            guard PortfolioStore.keepsUnsealed(storedFileExists: stored) else {
                portfolioWriteLocked = true
                return false
            }
            portfolioSavePending = true
        case .failed: return false
        }
        portfolioFile = file
        portfolio = PortfolioStore.newestFirst(file.transactions)
        // Portfolio-Widget mit den bekannten Kursen nachführen
        PortfolioWidgetStore.updateFromCache(transactions: file.transactions, currency: settings.portfolioCurrency)
        return true
    }
}
