package com.cryptochecker.app.domain.model

import com.cryptochecker.app.domain.refresh.MarketFailure
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker

/**
 * Kurs eines Paars samt Paarangaben; [error] gesetzt = Abruf gescheitert (Ticker dann leer). [failure]: Art des
 * Fehlers, beim Abruf bestimmt ([MarketFailure]); null = nur der Text ist bekannt.
 */
class MarketTickerResult(
    val ticker: Ticker,
    val pairInfo: PairQuery,
    val error: String? = null,
    val failure: MarketFailure? = null,
)
