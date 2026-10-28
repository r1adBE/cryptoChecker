package com.cryptochecker.app.domain.exceptions

/** Fehler ohne bekannte Zuordnung (siehe parseExchangeError). */
class UnknownExchangeError(cause: Throwable?) : ExchangeError(cause)
