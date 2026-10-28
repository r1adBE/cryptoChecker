package com.cryptochecker.app.domain.watch

import com.cryptochecker.marketdata.model.exchange.DexScreener

/**
 * Alarme auf DEX-Pools nur mit genug Liquidität ([DexScreener.DEX_MIN_ALARM_LIQUIDITY_USD]): Ein dünner Pool lässt
 * sich mit kleinen Beträgen bewegen — ein Alarm wäre leicht auszulösen bzw. zu manipulieren. Solche Pools zeigt die
 * Suche mit «Geringe Liquidität – kein Alarm», der Alarm-Editor bleibt zu, und bestehende Alarme bleiben erhalten,
 * lösen aber nicht aus, solange die zuletzt gemessene Liquidität darunter liegt oder unbekannt ist. Börsen-Paare
 * betrifft das nie. Reine Logik — wie `DexLiquidity.swift`.
 */
object DexLiquidity {

    /** Börsen-Schlüssel der DEX-Pools. */
    const val EXCHANGE_KEY = "DexScreener"

    fun isDex(exchangeKey: String): Boolean = exchangeKey == EXCHANGE_KEY

    /** Schlüssel der gemerkten Liquidität: Pool-Kennung «chain/pairAddress», ohne Gross-/Kleinschreibung. */
    fun key(pairId: String?): String? = pairId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** Alarme dieses Paars pausieren? Nur DEX-Pools mit zu wenig oder unbekannter Liquidität. */
    fun blocksAlarms(exchangeKey: String, liquidityUsd: Double?): Boolean =
        isDex(exchangeKey) && !DexScreener.alarmCapable(liquidityUsd)
}
