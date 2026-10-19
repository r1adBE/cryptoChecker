package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.data.portfolio.WatchedAddressEntity
import com.cryptochecker.app.data.portfolio.toDomain
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Einstand für Adressguthaben (Build 84): Prüfung, Abdeckung gegen das Guthaben (FIFO), Speicherform und Sicherung (hin
 * und zurück, Sicherung ohne das Feld). Gegenstück: `Tests/AddressCostLotsTests.swift`.
 */
class AddressCostLotsTest {

    private val btc = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"

    private fun lot(id: Long, t: Long, amount: String, price: String? = null, cost: String? = null) =
        AddressCostLot(id, "btc", t, BigDecimal(amount), price?.let(::BigDecimal), cost?.let(::BigDecimal))

    @Test
    fun cleanDropsInvalidAndSorts() {
        val lots = AddressCostLots.clean(
            listOf(
                lot(2, 20, "0.1", price = "100"),
                lot(1, 10, "0.2", cost = "50"),
                lot(3, 30, "0", price = "1"), // Menge 0
                lot(4, 40, "1"), // ohne Einstand
                lot(5, 0, "1", price = "1"), // ohne Datum
                lot(6, 50, "1", price = "-1"), // negativer Preis
            ),
        )
        assertEquals(listOf(1L, 2L), lots.map { it.id })
        assertEquals("BTC", lots[0].coin)
        assertEquals(0, BigDecimal("250").compareTo(lots[0].price))
        assertEquals(0, BigDecimal("10").compareTo(lots[1].cost))
        assertEquals(3L, AddressCostLots.nextId(lots))
    }

    @Test
    fun coverConsumesOldestFirstAndKeepsPricePerCoin() {
        val lots = AddressCostLots.clean(listOf(lot(1, 10, "0.2", cost = "2000"), lot(2, 20, "0.3", price = "300")))
        val full = AddressCostLots.cover("BTC", lots, BigDecimal("1"))
        assertFalse(full.capped)
        assertEquals(0, BigDecimal("0.5").compareTo(full.lotted))
        assertEquals(0, BigDecimal("0.5").compareTo(full.unlotted))
        assertEquals(lots, full.lots)

        val capped = AddressCostLots.cover("BTC", lots, BigDecimal("0.25"))
        assertTrue(capped.capped)
        assertEquals(0, BigDecimal("0.25").compareTo(capped.excess))
        assertEquals(listOf(2L), capped.lots.map { it.id })
        assertEquals(0, BigDecimal("0.25").compareTo(capped.lots[0].amount))
        assertEquals(0, BigDecimal("300").compareTo(capped.lots[0].price))
        assertEquals(0, BigDecimal.ZERO.compareTo(capped.unlotted))

        // Angebrochener Posten mit Gesamt-Einstand: Preis je Coin bleibt
        val partial = AddressCostLots.cover("BTC", lots, BigDecimal("0.4"))
        assertEquals(0, BigDecimal("0.1").compareTo(partial.lots[0].amount))
        assertEquals(0, BigDecimal("10000").compareTo(partial.lots[0].price))
        assertEquals(0, BigDecimal("1000").compareTo(partial.lots[0].cost))
    }

    @Test
    fun addressPartShrinksByLotsAndXpubCoveredAddressesDoNotCount() {
        val a = WatchedAddress(id = 7, chain = AddressChain.BTC, address = btc, lastBalance = "1", costLots = listOf(lot(1, 10, "0.4", price = "100")))
        val trades = AddressBalance.trades(listOf(a), emptyList(), 20_000)
        val (real, part) = AddressBalance.split(trades)
        assertEquals(0, BigDecimal("0.6").compareTo(part["BTC"]))
        assertEquals(listOf(AddressCostLots.tradeId(7, 0)), real.map { it.id })
        assertEquals(btc.take(6) + "…" + btc.takeLast(4), real[0].exchange)
        assertEquals(0, BigDecimal("0.6").compareTo(AddressBalance.amountFor("BTC", listOf(a))))
        // Ganz belegt: kein Adress-Teil mehr (FR-Vermerk entfällt), Detail zeigt nichts «ohne Kaufpreis»
        val full = a.copy(costLots = listOf(lot(1, 10, "1", price = "100")))
        assertTrue(AddressBalance.split(AddressBalance.trades(listOf(full), emptyList(), 20_000)).second.isEmpty())
        assertEquals(0, BigDecimal.ZERO.compareTo(AddressBalance.amountFor("BTC", listOf(full))))
        // Zeiträume/Verlauf lassen Posten weg (Adress-Einträge)
        assertTrue(real.all { AddressBalance.isAddressTrade(it.id) && !AddressBalance.isAddressPart(it.id) })
    }

    @Test
    fun storageRoundTrip() {
        val lots = AddressCostLots.clean(listOf(lot(1, 10, "0.00000001", price = "65000.5"), lot(2, 20, "2", cost = "100")))
        val text = AddressCostLots.encode(lots)!!
        assertEquals(lots, AddressCostLots.decode(text))
        assertNull(AddressCostLots.encode(emptyList()))
        assertTrue(AddressCostLots.decode(null).isEmpty())
        assertTrue(AddressCostLots.decode("kaputt").isEmpty())
        // Spalte der Entity: eigene Spalte, Zusatzdaten unberührt
        val a = WatchedAddress(id = 3, chain = AddressChain.BTC, address = btc, costLots = lots)
        val entity = WatchedAddressEntity.from(a)
        assertEquals(text, entity.costLots)
        assertEquals(lots, entity.toDomain()!!.costLots)
        assertTrue(entity.copy(costLots = null).toDomain()!!.costLots.isEmpty())
    }

    @Test
    fun backupRoundTripAndBackupWithoutField() {
        val lots = AddressCostLots.clean(listOf(lot(1, 10, "0.5", price = "30000")))
        val a = WatchedAddress(chain = AddressChain.BTC, address = btc, label = "Cold", costLots = lots)
        val json = WatchedAddressRules.toJson(WatchedAddressRules.backupEntries(listOf(a)))
        assertTrue(json.getJSONObject(0).has("costLots"))
        val back = WatchedAddressRules.merge(emptyList(), WatchedAddressRules.fromJson(JSONArray(json.toString())))
        assertEquals(lots, back.single().costLots)
        // Ältere Sicherung ohne das Feld: Adresse ohne Posten
        val old = JSONArray("""[{"chain":"BTC","address":"$btc","addedAt":5}]""")
        val restored = WatchedAddressRules.merge(emptyList(), WatchedAddressRules.fromJson(old))
        assertTrue(restored.single().costLots.isEmpty())
        // Ohne Posten schreibt die Sicherung das Feld nicht
        assertFalse(WatchedAddressRules.toJson(WatchedAddressRules.backupEntries(listOf(a.copy(costLots = emptyList())))).getJSONObject(0).has("costLots"))
    }

    @Test
    fun exportTradesAddLotsAndFrHistoryWithoutLottedPart() {
        val history = listOf(
            PortfolioTrade(-1_000_001, "BTC", PortfolioTxType.DEPOSIT, BigDecimal("0.3"), null, 10),
            PortfolioTrade(-1_000_002, "BTC", PortfolioTxType.DEPOSIT, BigDecimal("0.5"), null, 20),
            PortfolioTrade(-1_000_003, "BTC", PortfolioTxType.WITHDRAWAL, BigDecimal("0.1"), null, 30),
        )
        val cut = AddressCostLots.withoutLotted(history, mapOf("BTC" to BigDecimal("0.4")))
        assertEquals(listOf(-1_000_002L, -1_000_003L), cut.map { it.id })
        assertEquals(0, BigDecimal("0.4").compareTo(cut[0].amount))
        assertEquals(history, AddressCostLots.withoutLotted(history, emptyMap()))
        // Ohne FR: nur eigene Transaktionen + Posten (Käufe mit Preis)
        val a = WatchedAddress(id = 2, chain = AddressChain.BTC, address = btc, lastBalance = "1", costLots = listOf(lot(1, 5, "0.4", price = "100")))
        val real = listOf(PortfolioTrade(1, "BTC", PortfolioTxType.BUY, BigDecimal("1"), BigDecimal("200"), 50))
        val export = AddressCostLots.exportTrades(real, listOf(a), frTotalValue = false)
        assertEquals(listOf(1L, AddressCostLots.tradeId(2, 0)), export.map { it.id })
        assertEquals(PortfolioTxType.BUY, export[1].type)
    }

    @Test
    fun recordedMovesOfTheAddressWalletAndNoNegativeBase() {
        fun t(id: Long, type: PortfolioTxType, amount: String, exchange: String? = null, from: String? = null, to: String? = null, coinFee: String? = null) =
            PortfolioTrade(
                id, "BTC", type, BigDecimal(amount), null, 10, fee = coinFee?.let(::BigDecimal),
                feeUnit = coinFee?.let { PortfolioFeeUnit.COIN }, exchange = exchange, transferFrom = from, transferTo = to,
            )
        val trades = listOf(
            t(1, PortfolioTxType.TRANSFER, "0.5", from = "Ledger", to = "Binance", coinFee = "0.0001"), // −0,5001
            t(2, PortfolioTxType.TRANSFER, "0.2", from = "Binance", to = " ledger "), // +0,2 (Schreibweise wie FIFO je Wallet)
            t(3, PortfolioTxType.WITHDRAWAL, "0.1", exchange = "Ledger"), // −0,1
            t(4, PortfolioTxType.BUY, "0.3", exchange = "Ledger", coinFee = "0.01"), // +0,29
            t(5, PortfolioTxType.TRANSFER, "1", from = "Ledger", to = "Ledger", coinFee = "0.001"), // −0,001
            t(6, PortfolioTxType.BUY, "5", exchange = "Binance"), // andere Wallet
            t(-1, PortfolioTxType.DEPOSIT, "9", exchange = "Ledger"), // Adress-Eintrag
        )
        assertEquals(0, BigDecimal("-0.1111").compareTo(AddressCostLots.recorded("BTC", "Ledger", trades)))
        assertEquals(0, BigDecimal.ZERO.compareTo(AddressCostLots.recorded("ETH", "Ledger", trades)))
        assertEquals(0, BigDecimal.ZERO.compareTo(AddressCostLots.recorded("BTC", " ", trades)))
        // Erfasste Zugänge über dem Guthaben: Bestand 0, alle Posten verbraucht, nichts negativ
        val c = AddressCostLots.cover("BTC", AddressCostLots.clean(listOf(lot(1, 10, "1", price = "1"))), BigDecimal("0.2"), BigDecimal("0.5"))
        assertEquals(0, BigDecimal.ZERO.compareTo(c.base))
        assertEquals(0, BigDecimal.ZERO.compareTo(c.lotted))
        assertEquals(0, BigDecimal.ZERO.compareTo(c.unlotted))
        assertEquals(0, BigDecimal("0.2").compareTo(c.accounted))
        assertTrue(c.lots.isEmpty())
    }
}
