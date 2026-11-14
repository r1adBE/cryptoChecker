import Foundation

// Portfolio-Datei beim Wiederherstellen (Teil von `BackupManager`; nicht in `AppData`, das nur dafür nicht weiter wachsen
// soll): ersetzen und zurückrollen — auch über eine beschädigte Datei, die unverändert daneben kommt (R4-02).

extension BackupManager {
    /// Nach einer Wiederherstellung — wie Android: Transaktionen der Sicherung übernehmen. Ist `portfolio.json` nicht
    /// (ganz) lesbar, ist Wiederherstellen der Rettungsweg: nur über eine beschädigte Datei, nie über eine gerade gesperrte
    /// (`ProtectedFile.unreadableIsDamaged`). Sie kommt unverändert daneben (Bytes exakt, wie die Adressen); scheitert das
    /// Schreiben, kommt sie zurück.
    /// - Returns: false, wenn das Portfolio nicht geschrieben werden konnte.
    @MainActor
    static func restorePortfolio(_ transactions: [PortfolioTx], into data: AppData) -> Bool {
        let current = data.currentPortfolioFile()
        var file = PortfolioFile(transactions: [], nextId: current.nextId)
        for tx in transactions {
            var item = tx
            item.coin = PortfolioCalculator.normalizeCoin(tx.coin)
            PortfolioStore.insert(item, into: &file)
        }
        if data.portfolioIntact { return data.setPortfolioFile(file) }
        guard ProtectedFile.unreadableIsDamaged(PortfolioStore.url), PortfolioStore.setAsideUnreadable() else { return false }
        data.portfolioIntact = true
        guard data.setPortfolioFile(file) else {
            if PortfolioStore.putBackUnreadable() { adoptPutBackPortfolio(data) }
            return false
        }
        return true
    }

    /// Wiederherstellen gescheitert: War die Datei vorher lesbar (`wasIntact`), den alten Stand zurückschreiben; war sie
    /// beschädigt, die daneben gelegte Datei unverändert zurücklegen — nie den leeren Ersatzstand als «alten» Stand
    /// schreiben (wie `AppData.rollBackWatchedAddresses`). Geht das Zurücklegen nicht, bleibt der wiederhergestellte Stand.
    @MainActor
    @discardableResult
    static func rollBackPortfolio(_ file: PortfolioFile, wasIntact: Bool, into data: AppData) -> Bool {
        if wasIntact { return data.setPortfolioFile(file) }
        guard PortfolioStore.putBackUnreadable() else { return false }
        adoptPutBackPortfolio(data)
        return true
    }

    /// Nach dem Zurücklegen der beschädigten Datei: deren Stand wieder übernehmen (nicht lesbar bzw. nur das Lesbare).
    @MainActor
    private static func adoptPutBackPortfolio(_ data: AppData) {
        let loaded = PortfolioStore.loadChecked()
        data.portfolioIntact = loaded.intact
        data.portfolioFile = loaded.file
        data.portfolio = PortfolioStore.newestFirst(loaded.file.transactions)
        data.updatePortfolioWidgetInBackground(loaded.file.transactions)
    }
}
