import Foundation

/// Börsen der Übersicht: Abschnitte Spot · Futures · DEX-Pools · Forex (Referenzkurs), darin nach dem Volumen am Tag der
/// Installation (`ExchangeOrder`, `ExchangeRankingStore`), sonst alphabetisch — die Liste «Alle Börsen»
/// (`ExplorerBrowseUi.swift`) ordnet so. Wie `exchangeSections` (Android `ExplorerSelection.kt`).
extension ExplorerViewModel {
    /// Gewählte Quelle ist ein täglicher Referenzkurs (EZB): Hinweis unter der Börse.
    var isReferenceRate: Bool { exchange?.priceIsDailyReference == true }

    /// Fehlt das Ranking, einmal je Sitzung von CoinGecko holen; danach gilt es sofort.
    func loadExchangeRanking() {
        guard exchangeRanking == nil else { return }
        Task { @MainActor [weak self] in
            guard await ExchangeRankingStore.ensureLoaded() else { return }
            self?.exchangeRanking = ExchangeRankingStore.volumes()
        }
    }
}
