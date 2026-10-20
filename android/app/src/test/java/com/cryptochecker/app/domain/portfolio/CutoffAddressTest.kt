package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Adress-Guthaben im Stichtag-Export ([AddressBalanceHistory.balancesAt], [CutoffExport.addressRows]): heute das
 * aktuelle Guthaben, früher das an dem Tag beobachtete, sonst nicht enthalten mit Hinweis. Gegenstück:
 * `Tests/CutoffAddressTests.swift`.
 */
class CutoffAddressTest {

    private val day = 20_000L
    private fun t(d: Long) = d * 86_400_000L + 43_200_000L
    private val cutoff = (day + 1) * 86_400_000L - 1

    private val histories = listOf(
        // BTC: 0.5 seit 10 Tagen vor dem Stichtag, danach 0.7
        AddressBalanceHistory(
            AddressChain.BTC, "bc1qexampleaddress",
            listOf(AddressObservation(t(day - 10), BigDecimal("0.5")), AddressObservation(t(day + 2), BigDecimal("0.7"))),
        ),
        // ETH: erst nach dem Stichtag beobachtet
        AddressBalanceHistory(AddressChain.ETH, "0x00000000000000000000000000000000000000aa", listOf(AddressObservation(t(day + 1), BigDecimal("2")))),
    )

    private val texts = CutoffCsvTexts(
        coin = "Coin", amount = "Menge", priceUsdt = "Kurs USDT", valueUsdt = "Wert USDT",
        rate = "Kurs USD→CHF", valueTarget = "Wert CHF", note = "Hinweis", total = "Total",
        noPrice = "kein Kurs", noFx = "kein Devisenkurs", unchecked = "ungeprüft", incomplete = "unvollständig",
        commentFormat = "# Stichtag %1\$s, Währung %2\$s, Devisenkurs vom %3\$s",
        fromAddress = "aus Adresse – ohne Kaufpreis", addressUnobserved = "Adresse an diesem Tag nicht beobachtet – nicht enthalten",
    )

    @Test
    fun pastDateUsesObservedBalanceOtherwiseExcluded() {
        val at = AddressBalanceHistory.balancesAt(histories, cutoff)
        assertEquals(setOf("BTC"), at.balances.keys)
        assertEquals(0, BigDecimal("0.5").compareTo(at.balances.getValue("BTC")))
        assertEquals(setOf("ETH"), at.unobserved)

        val rows = CutoffExport.addressRows(at, mapOf("BTC" to BigDecimal("100")), BigDecimal("0.9"))
        val csv = CutoffExport.csv(LocalDate.ofEpochDay(day), "CHF", "2024-10-04", rows, texts)
        val lines = csv.removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }.drop(2)
        assertEquals(
            listOf(
                "BTC;0.5;100;50.00;0.9;45.00;aus Adresse – ohne Kaufpreis",
                "ETH;;;;0.9;;aus Adresse – ohne Kaufpreis, Adresse an diesem Tag nicht beobachtet – nicht enthalten",
                "Total;;;50.00;0.9;45.00;unvollständig",
            ),
            lines,
        )
    }

    /** USDT zum Marktkurs ohne USDT/USD am Stichtag: Zeile «Dollarkurs von USDT fehlt …» nach dem Kommentar (wie iOS). */
    @Test
    fun usdtApproximateLine() {
        val withText = texts.copy(usdtAssumed = "Dollarkurs von USDT fehlt – gerechnet als 1 US-Dollar")
        val rows = CutoffExport.rows(emptyList(), emptyMap(), BigDecimal("0.9"))
        val marked = CutoffExport.csv(LocalDate.ofEpochDay(day), "CHF", "2024-10-04", rows, withText, usdtApproximate = true)
            .removePrefix("\uFEFF").split("\r\n")
        assertEquals("Dollarkurs von USDT fehlt – gerechnet als 1 US-Dollar", marked[1])
        val plain = CutoffExport.csv(LocalDate.ofEpochDay(day), "CHF", "2024-10-04", rows, withText)
        assertTrue("Dollarkurs von USDT fehlt – gerechnet als 1 US-Dollar" !in plain)
        // Umgerechnet wird jede Währung ausser USDT selbst (auch USD)
        assertTrue(CutoffExport.convertsUsdt("CHF") && CutoffExport.convertsUsdt(" usd "))
        assertTrue(!CutoffExport.convertsUsdt("usdt"))
    }

    /**
     * Adressen nicht lesbar: Zeile nach dem Kommentar und Total «unvollständig», auch wenn alle übrigen Zeilen
     * vollständig sind — nie still eine zu tiefe Summe (wie iOS).
     */
    @Test
    fun unreadableAddressesMarkExportIncomplete() {
        val withText = texts.copy(addressesUnreadable = "Adressen nicht lesbar")
        val rows = CutoffExport.rows(listOf(CutoffHolding("BTC", BigDecimal("1"))), mapOf("BTC" to BigDecimal("100")), BigDecimal("0.9"))
        val marked = CutoffExport.csv(LocalDate.ofEpochDay(day), "CHF", "2024-10-04", rows, withText, addressesUnreadable = true)
            .removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }
        assertEquals("Adressen nicht lesbar", marked[1])
        assertEquals("Total;;;100.00;0.9;90.00;unvollständig", marked.last())
        val plain = CutoffExport.csv(LocalDate.ofEpochDay(day), "CHF", "2024-10-04", rows, withText)
            .removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }
        assertTrue(plain.none { it == "Adressen nicht lesbar" })
        assertEquals("Total;;;100.00;0.9;90.00;", plain.last())
    }

    /**
     * Zählende Adresse mit Einstand: Hinweiszeile «Umbuchungen evtl. doppelt/fehlend» nach dem Kommentar — nur dann
     * (ohne Posten, «Nur anzeigen» oder ohne Text keine Zeile); Zeilen und Total unverändert (wie iOS).
     */
    @Test
    fun addressLotsNoteOnlyWhenNeeded() {
        val lot = AddressCostLot(1, "BTC", t(day - 20), BigDecimal("0.4"), priceUsdt = BigDecimal("100"))
        val plainAddress = WatchedAddress(id = 1, chain = AddressChain.BTC, address = "bc1qexampleaddress", lastBalance = "0.5")
        val withLots = plainAddress.copy(costLots = listOf(lot))
        assertTrue(AddressCostLots.hasCountedLots(listOf(plainAddress, withLots)))
        assertTrue(!AddressCostLots.hasCountedLots(listOf(plainAddress)))
        assertTrue(!AddressCostLots.hasCountedLots(listOf(withLots.copy(countInPortfolio = false))))
        assertTrue(!AddressCostLots.hasCountedLots(emptyList()))

        val note = "Hinweis: Adressen mit Einstand"
        val withText = texts.copy(addressLots = note)
        val rows = CutoffExport.rows(listOf(CutoffHolding("BTC", BigDecimal("1"))), mapOf("BTC" to BigDecimal("100")), BigDecimal("0.9"))
        fun lines(t: CutoffCsvTexts, lots: Boolean) = CutoffExport.csv(LocalDate.ofEpochDay(day), "CHF", "2024-10-04", rows, t, addressLots = lots)
            .removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }
        val marked = lines(withText, true)
        assertEquals(note, marked[1])
        assertEquals(1, marked.count { it == note })
        val plain = lines(withText, false)
        assertTrue(plain.none { it == note })
        assertEquals(plain, marked.filter { it != note })
        assertEquals(lines(texts, false), lines(texts, true))
    }

    @Test
    fun todayUsesCurrentBalance() {
        // Stichtag heute: alle Beobachtungen bis jetzt — das aktuelle Guthaben, nichts fehlt
        val at = AddressBalanceHistory.balancesAt(histories, (day + 3) * 86_400_000L - 1)
        assertEquals(0, BigDecimal("0.7").compareTo(at.balances.getValue("BTC")))
        assertEquals(0, BigDecimal("2").compareTo(at.balances.getValue("ETH")))
        assertTrue(at.unobserved.isEmpty())
        val rows = CutoffExport.addressRows(at, mapOf("BTC" to BigDecimal("100"), "ETH" to BigDecimal("3000")), null)
        assertTrue(rows.all { it.fromAddress && !it.addressUnobserved })
        assertEquals(listOf("BTC", "ETH"), rows.map { it.coin })
    }
}
