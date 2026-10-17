package com.cryptochecker.app.domain.model

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker

/** Kurs eines Paars samt Paarangaben; [error] gesetzt = Abruf gescheitert (Ticker dann leer). */
class MarketTickerResult(
    val ticker: Ticker,
    val pairInfo: PairQuery,
    val error: String? = null,
)
