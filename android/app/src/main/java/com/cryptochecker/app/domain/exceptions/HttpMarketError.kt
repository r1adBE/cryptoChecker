package com.cryptochecker.app.domain.exceptions

import com.cryptochecker.app.domain.refresh.ExchangeBackoff

/**
 * HTTP-Fehler einer Börse. [retryAfterSeconds]: «Retry-After» der Antwort (bei 429/503), für die
 * Pause je Börse ([ExchangeBackoff]); steht im Text, weil Fehler als Text weitergereicht werden.
 */
class HttpMarketError(
    val httpCode: Int,
    val responseString: String?,
    val retryAfterSeconds: Long? = null,
) : MarketError() {
    override val message: String
        get() = "HttpCode: $httpCode" + ExchangeBackoff.retryAfterSuffix(retryAfterSeconds)
}
