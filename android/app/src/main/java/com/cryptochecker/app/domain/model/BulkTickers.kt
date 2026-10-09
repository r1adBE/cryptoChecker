package com.cryptochecker.app.domain.model

import com.cryptochecker.marketdata.model.Ticker

/**
 * Ergebnis einer Massenabfrage.
 * @param complete true, wenn die Antwort sicher alle gehandelten Paare der
 *   Börse enthält. Dann wird ein fehlendes Paar nicht mehr einzeln abgefragt.
 * @param error gesetzt, wenn die Abfrage gescheitert ist.
 */
data class BulkTickers(
    val tickers: Map<String, Ticker> = emptyMap(),
    val complete: Boolean = false,
    /** Fehlertext, wenn die Abfrage scheiterte (für die Pause je Börse, z. B. HTTP 429). */
    val error: String? = null,
)
