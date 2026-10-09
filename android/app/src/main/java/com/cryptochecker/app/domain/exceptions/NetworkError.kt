package com.cryptochecker.app.domain.exceptions

import com.cryptochecker.app.domain.exceptions.MarketError

class NetworkError(cause: Throwable) : MarketError(cause)