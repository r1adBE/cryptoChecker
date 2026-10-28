import Foundation

/// Börsenauswahl in Abschnitten: Spot · Futures · DEX-Pools · Forex (Referenzkurs), darin nach dem
/// Volumen am Tag der Installation (`ExchangeOrder`, `ExchangeRankingStore`), sonst alphabetisch (wie bisher).
/// Gegenstück zu den Abschnitten der Börsen-`DropdownField` (Android `ExplorerSteps.kt`).
extension ExplorerViewModel {
    /// Abschnitte der angebotenen Börsen (`markets`, «Token & TradFi» beachtet) mit übersetztem Titel.
    var marketSections: [(title: String, markets: [Market])] {
        // Eingabe wie bisher alphabetisch (`allMarkets`); ohne Ranking bleibt diese Reihenfolge je Abschnitt
        ExchangeOrder.sections(of: markets, volumes: exchangeRanking).map { section in
            (ExplorerViewModel.sectionTitle(section.category), section.markets)
        }
    }

    /// Gewählte Quelle ist ein täglicher Referenzkurs (EZB): Hinweis unter der Börse.
    var isReferenceRate: Bool { exchange?.priceIsDailyReference == true }

    nonisolated static func sectionTitle(_ category: MarketCategory) -> String {
        switch category {
        case .spot: return L("explorer_section_spot")
        case .futures: return L("explorer_section_futures")
        case .dex: return L("explorer_section_dex")
        case .forex: return L("explorer_section_forex")
        }
    }

    /// Fehlt das Ranking, einmal je Sitzung von CoinGecko holen; danach gilt es sofort.
    func loadExchangeRanking() {
        guard exchangeRanking == nil else { return }
        Task { @MainActor [weak self] in
            guard await ExchangeRankingStore.ensureLoaded() else { return }
            self?.exchangeRanking = ExchangeRankingStore.volumes()
        }
    }
}
