package com.cryptochecker.app.domain.portfolio

/**
 * Echte Transaktionen + Adress-Einzahlungen ohne Kaufpreis und Eröffnungsposten ([AddressBalance.withAddresses]) als Einträge
 * für Rechner, Verlauf, Widget und Alarme. Nie speichern: negative Ids.
 */
fun List<PortfolioTxEntity>.withAddresses(
    addresses: List<WatchedAddress>,
    now: Long = System.currentTimeMillis(),
): List<PortfolioTxEntity> {
    // Ohne Adressen oder schon zusammengeführt (negative Ids): unverändert
    if (addresses.isEmpty() || any { AddressBalance.isAddressTrade(it.id) }) return this
    val synthetic = AddressBalance.trades(addresses, map { it.toTrade() }, PortfolioHistory.epochDayOfUtcMillis(now))
    if (synthetic.isEmpty()) return this
    // Eröffnungsposten ([com.cryptochecker.app.domain.portfolio.AddressCostLots]) mit Preis und Wallet der Adresse
    return this + synthetic.map { t ->
        PortfolioTxEntity(
            id = t.id,
            coin = t.coin,
            type = t.type,
            amount = t.amount,
            priceUsdt = t.priceUsdt,
            time = t.time,
            exchange = t.exchange
        )
    }
}
