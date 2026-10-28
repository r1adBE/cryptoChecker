package com.cryptochecker.app.domain.watch

/**
 * Wann zwei Einträge der Merkliste dasselbe Paar sind: Börse, Basis, Quote und Kontrakt — bei DexScreener
 * zusätzlich der Pool (`chainId/pairAddress`, gespeichert als `pairId`). Zwei Pools mit gleichem Symbol auf
 * derselben Chain sind verschiedene Märkte mit eigenem Kurs und eigener Liquidität, also zwei Zeilen. Bei den
 * Börsen bleibt der Schlüssel ohne Kennung: Dort benennt die Kennung nur das Paar, und ältere Einträge haben
 * keine (oder eine anders geschriebene). Gilt für Hinzufügen, Bearbeiten, «Rückgängig» und Sicherungen.
 * Reines Kotlin (WatchIdentityTest), gespiegelt in `WatchIdentity.swift` (testdata/parity/watch_identity.json).
 */
object WatchIdentity {
    /** Börse, deren Paar-Kennung zum Schlüssel gehört (`DexScreener.key`). */
    const val POOL_EXCHANGE = "DexScreener"

    /** Eindeutiger Schlüssel eines Eintrags; [pool] nur bei [POOL_EXCHANGE], sonst null. */
    data class Key(
        val exchangeKey: String,
        val base: String,
        val quote: String,
        val contract: String,
        val pool: String?,
    )

    /** Pool-Kennung, die zum Schlüssel gehört: die Paar-Kennung bei DexScreener, sonst null. */
    fun pool(exchangeKey: String, pairId: String?): String? =
        if (exchangeKey == POOL_EXCHANGE) pairId?.takeIf { it.isNotEmpty() } else null

    fun key(exchangeKey: String, base: String, quote: String, contract: String, pairId: String?): Key =
        Key(exchangeKey, base, quote, contract, pool(exchangeKey, pairId))
}
