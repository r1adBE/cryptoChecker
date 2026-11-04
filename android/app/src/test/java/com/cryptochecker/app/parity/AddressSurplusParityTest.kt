package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.AddressBooked
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressCutoff
import com.cryptochecker.app.domain.portfolio.AddressSurplus
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * «Nur Mehrbestand» ([AddressSurplus], `testdata/parity/address_surplus.json`): Karte, Reihe (Zeiträume/Verlauf) und
 * Stichtag zählen je Coin nur, was die zählenden Adressen über die eigenen Transaktionen hinaus halten. Gegenstück:
 * `Tests/AddressSurplusParityTests.swift`.
 */
class AddressSurplusParityTest {

    private val data = ParityJson.load("address_surplus.json")

    private fun address(raw: Any?): WatchedAddress {
        val o = raw.obj()
        return WatchedAddress(
            id = o["id"].num()!!.toLong(), chain = AddressChain.valueOf(o["chain"] as String), address = o["address"] as String,
            lastBalance = o["lastBalance"] as String?, lastUpdated = o["lastUpdated"].num()!!.toLong(),
        )
    }

    private fun trade(raw: Any?): PortfolioTrade {
        val o = raw.obj()
        return PortfolioTrade(
            id = o["id"].num()!!.toLong(), coin = o["coin"] as String, type = PortfolioTxType.valueOf(o["type"] as String),
            amount = BigDecimal(o["amount"] as String), priceUsdt = (o["price"] as String?)?.let(::BigDecimal),
            time = o["time"].num()!!.toLong(), exchange = o["exchange"] as String?,
        )
    }

    private fun same(label: String, expected: Map<String, Any?>, actual: Map<String, BigDecimal>) {
        assertEquals("$label coins", expected.keys, actual.keys)
        expected.forEach { (coin, v) -> assertEquals("$label $coin", 0, BigDecimal(v as String).compareTo(actual[coin])) }
    }

    @Test
    fun ruleAlone() {
        for (raw in data["counted"].list()) {
            val (address, ledger, expected) = raw.list().map { BigDecimal(it as String) }
            assertEquals("$address − $ledger", 0, expected.compareTo(AddressSurplus.counted(address, ledger)))
        }
    }

    @Test
    fun cardHistoryAndCutoffCountOnlyTheSurplus() {
        val today = data["today"].num()!!.toLong()
        val cutoff = data["cutoff"].num()!!.toLong()
        for (raw in data["cases"].list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val addresses = c["addresses"].list().map(::address)
            val real = c["trades"].list().map(::trade)
            val all = AddressBalance.withAddresses(real, addresses, today)
            same("$name card", c["addressPart"].obj(), AddressBalance.split(all).second)
            val holdings = c["holdings"].obj()
            holdings.forEach { (coin, v) ->
                val p = PortfolioCalculator.position(coin, all, null)
                assertEquals("$name holdings $coin", 0, BigDecimal(v as String).compareTo(p.holdingsExact))
            }
            c["addressPart"].obj().forEach { (coin, v) ->
                assertEquals("$name detail $coin", 0, BigDecimal(v as String).compareTo(AddressBalance.amountFor(coin, addresses, real)))
            }
            same("$name booked", c["booked"].obj(), AddressBalanceHistory.latestByCoin(AddressBooked.histories(addresses, real)))
            same("$name cutoff", c["cutoffAddress"].obj(), AddressCutoff.at(addresses, real, cutoff).part.balances)
        }
    }
}
