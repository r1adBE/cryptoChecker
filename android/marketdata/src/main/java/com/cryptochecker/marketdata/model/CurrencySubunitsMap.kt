package com.cryptochecker.marketdata.model

class CurrencySubunitsMap(vararg currencySubunits: CurrencySubunit) : LinkedHashMap<Long, CurrencySubunit>() {
    init {
        for (currencySubunit in currencySubunits) put(currencySubunit.subunitToUnit, currencySubunit)
    }
}