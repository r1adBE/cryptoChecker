package com.cryptochecker.app.domain.starter

/** Start-Merkliste: Region, Coins und Kurse (Umsetzung: `StarterCoinsRepository`). */
interface StarterCoinSource {
    /** Region für den Startschritt: Geräte-Region, bei Standort-Sperre «US». */
    fun starterRegion(deviceRegion: String?): String?

    /** Sperrt Binance den Standort? true = Ergebnis anders als vorher. */
    suspend fun probeBinance(deviceRegion: String?): Boolean

    fun cached(regionCountry: String?): List<StarterPairs.Coin>

    suspend fun fresh(regionCountry: String?): List<StarterPairs.Coin>?

    suspend fun prices(symbols: List<String>, regionCountry: String?): Map<String, StarterPrice>
}
