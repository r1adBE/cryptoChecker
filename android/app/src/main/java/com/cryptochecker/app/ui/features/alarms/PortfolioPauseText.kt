package com.cryptochecker.app.ui.features.alarms

import android.content.Context
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.alarm.PortfolioAlarmPause

/**
 * Satz «Portfolio-Alarme pausiert: …» der Alarm-Übersicht ([PortfolioAlarmPause]) — gleich wie iOS
 * `PortfolioPauseText`. Höchstens [MAX_COINS] Coin- bzw. Adressnamen, dann «…»; [hideCoins] (Portfolio gesperrt oder
 * «Beträge verbergen») ohne Namen, die Zusammensetzung des Bestands bleibt verborgen.
 */
object PortfolioPauseText {
    const val MAX_COINS = 3

    fun text(context: Context, pause: PortfolioAlarmPause, hideCoins: Boolean): String = when (pause) {
        is PortfolioAlarmPause.AddressesPending -> context.getString(R.string.portfolio_alarms_paused_addresses)
        is PortfolioAlarmPause.NoPrice -> coins(pause.coins, hideCoins)
            ?.let { context.getString(R.string.portfolio_alarms_paused_price, it) }
            ?: context.getString(R.string.portfolio_alarms_paused_price_generic)
        // Adressnamen wie Coins: verborgen ohne Namen
        is PortfolioAlarmPause.TokensStale -> coins(pause.addresses, hideCoins)
            ?.let { context.getString(R.string.portfolio_alarms_paused_tokens, it) }
            ?: context.getString(R.string.portfolio_alarms_paused_tokens_generic)
        is PortfolioAlarmPause.AddressesFailing -> coins(pause.addresses, hideCoins)
            ?.let { context.getString(R.string.portfolio_alarms_address_failing, it) }
            ?: context.getString(R.string.portfolio_alarms_address_failing_generic)
    }

    /** «BTC, ETH, SOL …»; leer oder verborgen = null. */
    fun coins(coins: List<String>, hideCoins: Boolean): String? {
        if (hideCoins || coins.isEmpty()) return null
        val shown = coins.take(MAX_COINS).joinToString(", ")
        return if (coins.size > MAX_COINS) "$shown …" else shown
    }
}
