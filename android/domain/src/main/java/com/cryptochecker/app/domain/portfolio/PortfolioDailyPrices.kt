package com.cryptochecker.app.domain.portfolio

/**
 * Tageskerzen für den Wertverlauf: Schluss je Coin und UTC-Tag ([closes]) und, soweit vorhanden,
 * Eröffnung/Hoch/Tief derselben Kerzen ([ranges], für die Kerzen-Ansicht). Coins in [own]: Reihe von der
 * eigenen Börse (in USDT; vor [binanceBefore] Binance angeglichen); [routed] = Coins mit eigener Börse
 * (Paar gefunden) — ohne eigene Reihe skaliert sie [PortfolioExchangeRoute.historyCloses] oder lässt sie weg.
 */
data class PortfolioDailyPrices(
    val closes: Map<String, Map<Long, Double>>,
    val ranges: Map<String, Map<Long, PortfolioDayRange>> = emptyMap(),
    val own: Set<String> = emptySet(),
    val binanceBefore: Map<String, Long> = emptyMap(),
    val routed: Set<String> = emptySet(),
    /**
     * Tagesschluss USDT/USD je UTC-Tag (Kraken USDTZUSD, [PortfolioHistorySource.usdtUsdDaily]) für USDT zum Marktkurs
     * ([com.cryptochecker.app.domain.portfolio.UsdtUsdRates.daily]); leer = keiner (dann 1 als Näherung).
     */
    val usdtUsd: Map<Long, Double> = emptyMap(),
    /**
     * Coins, deren eigene Reihe ein älterer Stand ist (Börse nicht erreichbar) → Zeit dieses Stands; die Karte zeigt
     * «Stand …» ([com.cryptochecker.app.domain.portfolio.PortfolioHistoryAsOf]) statt eines scheinbar heutigen Verlaufs.
     */
    val ownStale: Map<String, Long> = emptyMap(),
    /**
     * Coins mit Reihe aus der Ausweich-Kette (Binance führt sie nicht oder ist nicht erreichbar, keine eigene Börse) →
     * Anbieter, z. B. «Kraken» ([HistoricCloseChain.SERIES_CHAIN]); die Karte nennt ihn («Tageskurse von …»).
     */
    val chainSources: Map<String, String> = emptyMap(),
)
