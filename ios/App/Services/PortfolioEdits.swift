import Foundation

// Löschen, Wiederherstellen und Leeren des Portfolios als reine Schritte auf der Portfolio-Datei (`AppData` gibt den
// Stand und das Schreiben `setPortfolioFile` hinein). Eigene Datei, damit die Regel «nur Geschriebenes gilt als
// erledigt» ohne App-Zustand prüfbar ist (Tests mit scheiterndem Schreiben).

enum PortfolioEdits {
    /// Alle Transaktionen des Coins (auch Tausche in diesen Coin) löschen; gibt die gelöschten zurück (für «Rückgängig»).
    /// Leer, wenn nichts da ist oder `write` scheitert (gesperrte Datei, ohne Schlüssel): dann gilt nichts als gelöscht.
    static func deleteCoin(_ coin: String, in file: PortfolioFile, write: (PortfolioFile) -> Bool) -> [PortfolioTx] {
        let key = PortfolioCalculator.normalizeCoin(coin)
        let involves: (PortfolioTx) -> Bool = { tx in tx.coins.contains { PortfolioCalculator.normalizeCoin($0) == key } }
        let removed = file.transactions.filter(involves)
        guard !removed.isEmpty else { return [] }
        var next = file
        next.transactions.removeAll(where: involves)
        guard write(next) else { return [] }
        return removed
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
