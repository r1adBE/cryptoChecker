package com.cryptochecker.app.domain.portfolio
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressWithdrawalMatch
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj

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
            // «Ziel ändern»: genannte Umbuchung → Adress-Wallet; danach kein Hinweis mehr
            val want = (c["retarget"] as Map<*, *>?)
            val r = AddressWithdrawalMatch.retarget(a, trades)
            assertEquals("$name (Ändern)", want?.get("id").num()?.toLong(), r?.trade?.id)
            assertEquals("$name (von)", want?.get("from"), r?.from)
            assertEquals("$name (nach)", want?.get("to"), r?.to)
            assertEquals("$name (Karte)", r, AddressWithdrawalMatch.retargetFor("BTC", listOf(a), trades))
            if (r != null) {
                val after = trades.map { if (it.id == r.trade.id) it.copy(transferTo = r.to) else it }
                assertEquals("$name (danach)", emptyList<Long>(), AddressWithdrawalMatch.matches(a, after).map { it.id })
                assertNull("$name (danach Karte)", AddressWithdrawalMatch.retargetFor("BTC", listOf(a), after))
            }
        }
    }

    /** Coin-Karte mit zwei Adressen: Ziel ist die Wallet der Adresse, zu der die genannte Umbuchung passt. */
    @Test
    fun cardTargetsTheMatchingAddress() {
        val cases = data["cases"].list().map { it.obj() }
        val c = cases.first { (it["matches"] as List<*>).isNotEmpty() }
        val trades = c["trades"].list().map(::trade)
        val hit = address(c["address"])
        val other = hit.copy(id = 9, address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", label = "Spare", lastBalance = "7")
        val r = AddressWithdrawalMatch.retargetFor("BTC", listOf(other, hit), trades)
        assertEquals("Cold", r?.to)
        assertEquals(2L, r?.trade?.id)
    }
}
