package com.cryptochecker.app.domain.exceptions

/** Die Börse hat nicht rechtzeitig geantwortet. */
class TimeoutError(cause: Throwable) : MarketError(cause)
