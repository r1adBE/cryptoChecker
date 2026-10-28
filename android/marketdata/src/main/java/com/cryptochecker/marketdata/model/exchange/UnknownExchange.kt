package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.model.CurrencyPairsMap
import com.cryptochecker.marketdata.model.Exchange
import com.cryptochecker.marketdata.model.PairQuery

/** Name und Sprachname des Platzhalters. */
private const val UNKNOWN_LABEL = "UNKNOWN"

/**
 * Platzhalter für einen gespeicherten Börsenschlüssel, den diese Version nicht (mehr) kennt — etwa aus einer
 * Sicherung mit einer inzwischen entfernten Börse. Fragt nichts ab (leere Adresse) und bietet keine Paare an
 * (leere Tabelle), damit nichts Neues darauf angelegt wird. Gegenstück auf iOS: `UnknownExchange.swift`.
 */
class UnknownExchange : Exchange(UNKNOWN_LABEL, UNKNOWN_LABEL, CurrencyPairsMap()) {
    override val key = "UnknownExchange"

    // ── Einzelkurs ──

    override fun tickerAddress(call: Int, info: PairQuery): String = ""
}
