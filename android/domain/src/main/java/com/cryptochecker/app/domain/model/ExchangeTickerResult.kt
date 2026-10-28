package com.cryptochecker.app.domain.model

import com.cryptochecker.app.domain.refresh.ExchangeFailure
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker

/**
 * Kurs eines Paars samt Paarangaben; [error] gesetzt = Abruf gescheitert (Ticker dann leer). [failure]: Art des
 * Fehlers, beim Abruf bestimmt ([ExchangeFailure]); null = nur der Text ist bekannt.
 */
class ExchangeTickerResult(
    val ticker: Ticker,
    val pairInfo: PairQuery,
    val error: String? = null,
    val failure: ExchangeFailure? = null,
)
