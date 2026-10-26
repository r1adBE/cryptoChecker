package com.cryptochecker.app.domain.exceptions

import com.cryptochecker.app.domain.refresh.ExchangeBackoff

/**
 * Antwort der Börse mit einem HTTP-Fehlercode. [retryAfterSeconds] ist der Wert von «Retry-After»
 * (bei 429/503) für die Pause je Börse ([ExchangeBackoff]); er wird an den Text gehängt, weil
 * Fehler nur als Text weiterwandern.
 */
class HttpMarketError(
    val httpCode: Int,
    val responseString: String?,
    val retryAfterSeconds: Long? = null,
) : MarketError() {
    override val message: String
        get() = buildString {
            append("HttpCode: ").append(httpCode)
            append(ExchangeBackoff.retryAfterSuffix(retryAfterSeconds))
        }
}
