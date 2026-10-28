package com.cryptochecker.app.domain.model

import com.cryptochecker.app.domain.refresh.ExchangeFailure
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
    /**
     * Paar-Kennungen, deren Zeile in einer sonst lesbaren Antwort unlesbar war
     * ([com.cryptochecker.marketdata.util.BulkTickerRows]): Fehler statt «unverändert».
     */
    val failed: Set<String> = emptySet(),
    /**
     * Angefragte Paare, deren Teilabfrage scheiterte (z. B. Binance Alpha, während Spot klappte): Über sie sagt
     * die Liste nichts — kein «nicht mehr gehandelt», auch wenn [complete] gilt.
     */
    val incomplete: Set<String> = emptySet(),
    /** Art des Fehlers zu [error], beim Abruf bestimmt ([ExchangeFailure]); null = nur der Text ist bekannt. */
    val failure: ExchangeFailure? = null,
)
