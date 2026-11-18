package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.domain.portfolio.FxRates
import com.cryptochecker.app.domain.portfolio.HistoricPrices
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFeed
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFeed
import javax.inject.Inject

/**
 * Woher [PortfolioViewModel] Kurse nimmt: aktuelle Kurse der Coins, Wechselkurse, Verlauf des Gesamtwerts und Kurse
 * vergangener Tage. Ohne eigenen Zustand — die Quellen halten ihre Zwischenspeicher selbst.
 */
class PortfolioPriceSources @Inject constructor(
    val priceSource: PortfolioPriceFeed,
    val fxSource: FxRates,
    val historySource: PortfolioHistoryFeed,
    val historicSource: HistoricPrices,
)
