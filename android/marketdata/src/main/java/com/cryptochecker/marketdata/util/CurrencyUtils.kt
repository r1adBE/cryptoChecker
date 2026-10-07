package com.cryptochecker.marketdata.util

import com.cryptochecker.marketdata.model.CurrencySubunit
import com.cryptochecker.marketdata.model.currency.CurrenciesSubunits
import com.cryptochecker.marketdata.model.currency.CurrencySymbols

object CurrencyUtils {
    fun getCurrencySymbol(currency: String): String {
        return CurrencySymbols.CURRENCY_SYMBOLS[currency] ?: currency
    }

    fun getCurrencySubunit(currency: String?, subunitToUnit: Long): CurrencySubunit {
        val subunits = CurrenciesSubunits.CURRENCIES_SUBUNITS[currency]
        if (subunits != null) {
            if (subunits.containsKey(subunitToUnit)) return subunits.getValue(subunitToUnit)
        }
        return CurrencySubunit(currency ?: "", 1)
    }
}