package com.cryptochecker.app.domain.model

import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.Ticker

class MarketTickerResult(
    val ticker: Ticker,
    val pairInfo: CheckerInfo,
    val error: String? = null
)