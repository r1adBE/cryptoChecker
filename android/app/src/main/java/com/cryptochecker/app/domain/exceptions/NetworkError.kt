package com.cryptochecker.app.domain.exceptions

/** Keine Verbindung zur Börse (Ein-/Ausgabefehler ausser Zeitüberschreitung). */
class NetworkError(cause: Throwable) : MarketError(cause)
