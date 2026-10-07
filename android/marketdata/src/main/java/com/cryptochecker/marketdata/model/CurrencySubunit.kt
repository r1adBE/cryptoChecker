package com.cryptochecker.marketdata.model

class CurrencySubunit(
    val name: String,
    val subunitToUnit: Long,
    val allowDecimal: Boolean = true
)