package com.cryptochecker.app.domain.exceptions

/** Fehler ohne bekannte Zuordnung (siehe parseMarketError). */
class UnknownMarketError(cause: Throwable?) : MarketError(cause)
