package com.cryptochecker.app.domain.exceptions

/** Antwort der Börse liess sich nicht lesen. */
open class ParseError(cause: Throwable?) : ExchangeError(cause)
