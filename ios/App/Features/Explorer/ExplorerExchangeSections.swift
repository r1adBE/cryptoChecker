import Foundation

/// Börsen der Übersicht: Abschnitte Spot · Futures · DEX-Pools · Forex (Referenzkurs), darin nach dem Volumen am Tag der
/// Installation (`ExchangeOrder`, `ExchangeRankingStore`), sonst alphabetisch — die Liste «Alle Börsen»
/// (`ExplorerBrowseUi.swift`) ordnet so. Wie `exchangeSections` (Android `ExplorerSelection.kt`).
extension ExplorerViewModel {
    /// Gewählte Quelle ist ein täglicher Referenzkurs (EZB): Hinweis unter der Börse.
    var isReferenceRate: Bool { exchange?.priceIsDailyReference == true }

    /// Fehlt das Ranking, einmal je Sitzung von CoinGecko holen; danach gilt es sofort. Mit Schalter «In der App»
    /// auch die Börsen-Logos (`ExchangeLogoStore`).
    func loadExchangeRanking() {
        let logos = data.settings.coinLogos
        guard exchangeRanking == nil || logos else { return }
        Task { @MainActor [weak self] in
            if await ExchangeRankingStore.ensureLoaded() {
                self?.exchangeRanking = ExchangeRankingStore.volumes()
            }
            if logos { await ExchangeLogoStore.ensure() }
        }
    }
}
