package com.cryptochecker.app.domain.model

import com.cryptochecker.marketdata.model.Ticker

/**
 * Ergebnis einer Massenabfrage.
 * @param complete true, wenn die Antwort sicher alle gehandelten Paare der
 *   Börse enthält. Dann wird ein fehlendes Paar nicht mehr einzeln abgefragt.
 */
data class BulkTickers(
    val tickers: Map<String, Ticker> = emptyMap(),
    val complete: Boolean = false,
)
