package com.cryptochecker.app.domain.convert

/**
 * Wählbare Zielwährungen (EZB-Referenzkurse), die gebräuchlichsten zuerst. Liegt in :domain, weil auch die
 * Standardwährung der Einstellungen (`defaultCurrency`) sie braucht; `FxRateSource.CURRENCIES` verweist hierher.
 */
object FxCurrencies {
    val ALL = listOf(
        "CHF", "EUR", "USD", "GBP", "JPY", "CAD", "AUD", "SEK", "NOK", "DKK", "PLN", "CZK",
        "HUF", "RON", "BGN", "ISK", "TRY", "CNY", "HKD", "SGD", "KRW", "INR", "IDR", "THB",
        "MYR", "PHP", "NZD", "ZAR", "BRL", "MXN", "ILS",
    )
}
