import Foundation

// Löschen, Wiederherstellen und Leeren des Portfolios als reine Schritte auf der Portfolio-Datei (`AppData` gibt den
// Stand und das Schreiben `setPortfolioFile` hinein). Eigene Datei, damit die Regel «nur Geschriebenes gilt als
// erledigt» ohne App-Zustand prüfbar ist (Tests mit scheiterndem Schreiben).

enum PortfolioEdits {
    /// Alle Transaktionen des Coins (auch Tausche in diesen Coin) löschen und bei Transaktionen anderer Coins die Gebühr in
    /// diesem Coin entfernen (`paysFee`); gibt die gelöschten und die geänderten in ihrer Originalfassung zurück (für
    /// «Rückgängig»: `restore` ersetzt je id). Leer, wenn nichts da ist oder `write` scheitert (gesperrte Datei, ohne
    /// Schlüssel): dann gilt nichts als gelöscht. Wie `PortfolioCoinDeletion.apply` (Android).
    static func deleteCoin(_ coin: String, in file: PortfolioFile, write: (PortfolioFile) -> Bool) -> [PortfolioTx] {
        let key = PortfolioCalculator.normalizeCoin(coin)
        let removed = file.transactions.filter { involves($0, key) }
        let feeRefs = file.transactions.filter { paysFee($0, in: key) }
        guard !removed.isEmpty || !feeRefs.isEmpty else { return [] }
        var next = file
        next.transactions.removeAll { involves($0, key) }
        next.transactions = next.transactions.map { tx in
            guard paysFee(tx, in: key) else { return tx }
            var t = tx
            t.feeCoin = nil
            t.feeCoinAmountExact = nil
            t.feeCoinPriceExact = nil
            return t
        }
        guard write(next) else { return [] }
        return removed + feeRefs
    }

    /// `tx` gehört zum Coin `key` (vereinheitlicht): Coin der Transaktion oder beim Tausch der erhaltene Coin.
    static func involves(_ tx: PortfolioTx, _ key: String) -> Bool {
        tx.coins.contains { PortfolioCalculator.normalizeCoin($0) == key }
    }

    /// `tx` eines anderen Coins bezahlt seine Gebühr in `key` (dritter Coin, z. B. BNB). Bliebe sie stehen, erschiene `key`
    /// nach dem Löschen wieder als Position «mehr abgegeben als erfasst» — sie wird daher mit entfernt.
    static func paysFee(_ tx: PortfolioTx, in key: String) -> Bool {
        guard !involves(tx, key), tx.feeCoinAmountExact != nil, let fc = tx.feeCoin else { return false }
        return PortfolioCalculator.normalizeCoin(fc) == key
    }

    /// Eine Transaktion löschen. - Returns: false, wenn nicht geschrieben (Transaktion bleibt).
    static func deleteTx(_ id: Int64, in file: PortfolioFile, write: (PortfolioFile) -> Bool) -> Bool {
        var next = file
        next.transactions.removeAll { $0.id == id }
        return write(next)
    }

    /// «Rückgängig»: Transaktionen mit ihren alten ids wieder anlegen (ersetzt je id). - Returns: false, wenn nicht
    /// geschrieben; nichts zurückzulegen gilt als erledigt.
    static func restore(_ transactions: [PortfolioTx], in file: PortfolioFile, write: (PortfolioFile) -> Bool) -> Bool {
        guard !transactions.isEmpty else { return true }
        var next = file
        let ids = Set(transactions.map(\.id))
        next.transactions.removeAll { ids.contains($0.id) }
        next.transactions.append(contentsOf: transactions)
        return write(next)
    }

    /// «Portfolio leeren»: alle Transaktionen löschen; `afterSave` (gemerkte Kurse löschen) nur nach geschriebener Datei,
    /// sonst stünden die Coins weiter im Portfolio, ihre Kurse aber nicht mehr. - Returns: false, wenn nicht geschrieben.
    static func clear(in file: PortfolioFile, write: (PortfolioFile) -> Bool, afterSave: () -> Void) -> Bool {
        var next = file
        next.transactions.removeAll()
        guard write(next) else { return false }
        afterSave()
        return true
    }
}
