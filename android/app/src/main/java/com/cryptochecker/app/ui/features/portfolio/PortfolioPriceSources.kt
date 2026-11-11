package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.data.portfolio.HistoricPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource
import javax.inject.Inject

/**
 * Woher [PortfolioViewModel] Kurse nimmt: aktuelle Kurse der Coins, Wechselkurse, Verlauf des Gesamtwerts und Kurse
 * vergangener Tage. Ohne eigenen Zustand — die Quellen halten ihre Zwischenspeicher selbst.
 */
class PortfolioPriceSources @Inject constructor(
    val priceSource: PortfolioPriceSource,
    val fxSource: FxRateSource,
    val historySource: PortfolioHistorySource,
    val historicSource: HistoricPriceSource,
)
