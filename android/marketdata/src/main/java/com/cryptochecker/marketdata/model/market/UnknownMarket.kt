package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.CurrencyPairsMap
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.PairQuery

/** Name und Sprachname des Platzhalters. */
private const val UNKNOWN_LABEL = "UNKNOWN"

/**
 * Platzhalter für einen gespeicherten Börsenschlüssel, den diese Version nicht (mehr) kennt — etwa aus einer
 * Sicherung mit einer inzwischen entfernten Börse. Fragt nichts ab (leere Adresse) und bietet keine Paare an
 * (leere Tabelle), damit nichts Neues darauf angelegt wird. Gegenstück auf iOS: `UnknownMarket.swift`.
 */
class UnknownMarket : Market(UNKNOWN_LABEL, UNKNOWN_LABEL, CurrencyPairsMap()) {
    override val key = "UnknownMarket"

    // ── Einzelkurs ──

    override fun tickerAddress(call: Int, info: PairQuery): String = ""
}
