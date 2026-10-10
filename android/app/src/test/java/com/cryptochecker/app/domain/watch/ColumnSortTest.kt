package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColumnSortTest {

    private data class Row(val name: String, val fav: Boolean, val chf: Double?, val change: Double?)

    // eigene Reihenfolge
    private val rows = listOf(
        Row("ETH/USDT", false, 2064.0, -0.02),
        Row("btc/USDT", true, 68643.0, 0.0),
        Row("SOL/CHF", false, 118.4, 3.85),
        Row("XRP/USDT", false, null, null),
        Row("ADA/USDT", true, 0.5, 1.2),
    )

    private fun names(sort: ColumnSort?) =
        ColumnSort.apply(rows, sort, { it.fav }, { it.name }, { it.chf }, { it.change }).map { it.name }

    @Test
    fun cycle() {
        val name = ColumnSort.next(null, SortKey.NAME)
        assertEquals(ColumnSort(SortKey.NAME, false), name)
        assertEquals(ColumnSort(SortKey.NAME, true), ColumnSort.next(name, SortKey.NAME))
        assertNull(ColumnSort.next(ColumnSort(SortKey.NAME, true), SortKey.NAME))
        val price = ColumnSort.next(name, SortKey.PRICE)
        assertEquals(ColumnSort(SortKey.PRICE, true), price)
        assertEquals(ColumnSort(SortKey.PRICE, false), ColumnSort.next(price, SortKey.PRICE))
        assertNull(ColumnSort.next(ColumnSort(SortKey.PRICE, false), SortKey.PRICE))
    }

    @Test
    fun ownOrderWithoutSort() {
        assertEquals(rows.map { it.name }, names(null))
    }

    @Test
    fun favoritesStayOnTopAndUnknownLast() {
        assertEquals(listOf("btc/USDT", "ADA/USDT", "ETH/USDT", "SOL/CHF", "XRP/USDT"), names(ColumnSort(SortKey.PRICE, true)))
        assertEquals(listOf("ADA/USDT", "btc/USDT", "SOL/CHF", "ETH/USDT", "XRP/USDT"), names(ColumnSort(SortKey.PRICE, false)))
        assertEquals(listOf("ADA/USDT", "btc/USDT", "SOL/CHF", "ETH/USDT", "XRP/USDT"), names(ColumnSort(SortKey.CHANGE, true)))
    }

    @Test
    fun nameIgnoresCase() {
        assertEquals(listOf("ADA/USDT", "btc/USDT", "ETH/USDT", "SOL/CHF", "XRP/USDT"), names(ColumnSort(SortKey.NAME, false)))
    }

    @Test
    fun encodeDecode() {
        for (key in SortKey.entries) for (d in listOf(true, false)) {
            val s = ColumnSort(key, d)
            assertEquals(s, ColumnSort.decode(s.encode()))
        }
        assertNull(ColumnSort.decode(null))
        assertNull(ColumnSort.decode("VOLUME_DESC"))
        assertNull(ColumnSort.decode("PRICE"))
    }
}
