package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressWithdrawalMatch
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * Hinweis auf eine nicht in die Adress-Wallet erfasste Umbuchung ([AddressWithdrawalMatch],
 * `testdata/parity/address_withdrawal_match.json`). Gegenstück: `Tests/AddressWithdrawalMatchParityTests.swift`.
 */
class AddressWithdrawalMatchParityTest {

    private val data = ParityJson.load("address_withdrawal_match.json")

    private fun address(raw: Any?): WatchedAddress {
        val o = raw.obj()
        return WatchedAddress(
            id = o["id"].num()!!.toLong(), chain = AddressChain.valueOf(o["chain"] as String), address = o["address"] as String,
            label = o["label"] as String?, lastBalance = o["lastBalance"] as String?,
            lastUpdated = o["lastUpdated"].num()!!.toLong(), countInPortfolio = o["countInPortfolio"] as Boolean? ?: true,
        )
    }

    private fun trade(raw: Any?): PortfolioTrade {
        val o = raw.obj()
        return PortfolioTrade(
            id = o["id"].num()!!.toLong(), coin = o["coin"] as String, type = PortfolioTxType.valueOf(o["type"] as String),
            amount = BigDecimal(o["amount"] as String), priceUsdt = (o["price"] as String?)?.let(::BigDecimal),
            time = o["time"].num()!!.toLong(), exchange = o["exchange"] as String?,
            transferFrom = o["transferFrom"] as String?, transferTo = o["transferTo"] as String?,
        )
    }

    @Test
    fun hintOnlyForTransfersIntoAnotherWallet() {
        for (raw in data["cases"].list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val a = address(c["address"])
            val trades = c["trades"].list().map(::trade)
            val expected = c["matches"].list().map { it.num()!!.toLong() }
            val matches = AddressWithdrawalMatch.matches(a, trades)
            assertEquals(name, expected, matches.map { it.id })
            assertEquals("$name (Ziel)", c["target"] as String?, AddressWithdrawalMatch.targetWallet(matches))
            assertEquals("$name (Coin)", expected, AddressWithdrawalMatch.matchesFor("btc", listOf(a), trades).map { it.id })
        }
    }
}
