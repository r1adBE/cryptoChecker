package com.cryptochecker.app.domain.watch

/** Namen der Coins je Börse für die Oberfläche (Umsetzung: `TokenIdentityStore`). */
interface TokenNames {
    fun pairName(exchangeKey: String, base: String): String?

    fun exchangeNames(exchangeKey: String): Map<String, String>

    fun binanceName(base: String): String?

    fun otherNames(base: String): List<String?>

    fun fallbackSeriesAllowed(exchangeKey: String, base: String, provider: String?): Boolean
}
