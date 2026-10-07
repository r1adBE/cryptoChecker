package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CycleExtremesTest {

    /** Lineare Tageswerte zwischen Stützpunkten (Tag nach Halving → Kurs). */
    private fun interp(points: List<Pair<Int, Double>>): List<Pair<Int, Double>> {
        val out = ArrayList<Pair<Int, Double>>()
        for (k in 0 until points.size - 1) {
            val (d0, p0) = points[k]
            val (d1, p1) = points[k + 1]
            for (d in d0 until d1) out += d to p0 + (p1 - p0) * (d - d0) / (d1 - d0)
        }
        out += points.last()
        return out
    }

    @Test
    fun cycle2020HasDoubleTopAndDoubleBottomAndIgnoresMarch2024() {
        val daily = interp(listOf(
            0 to 8600.0, 338 to 63000.0, 436 to 29800.0, 548 to 67500.0, 768 to 19000.0,
            826 to 24400.0, 924 to 15800.0, 1403 to 73000.0, 1440 to 64000.0,
        ))
        val r = CycleExtremes.find(daily, LocalDate.of(2020, 5, 11), 8600.0)
        assertEquals(LocalDate.of(2021, 11, 10), r.top?.date)
        assertEquals(LocalDate.of(2021, 4, 14), r.secondTop?.date)
        assertEquals(LocalDate.of(2022, 11, 21), r.bottom?.date)
        assertEquals(LocalDate.of(2022, 6, 18), r.secondBottom?.date)
    }

    @Test
    fun cycle2016HasSingleTopAndBottom() {
        val daily = interp(listOf(
            0 to 650.0, 525 to 19500.0, 600 to 7000.0, 680 to 9800.0, 890 to 3200.0, 1060 to 12000.0, 1440 to 9500.0,
        ))
        val r = CycleExtremes.find(daily, LocalDate.of(2016, 7, 9), 650.0)
        assertEquals(525, r.top?.day)
        assertEquals(890, r.bottom?.day)
        assertNull(r.secondTop)
        assertNull(r.secondBottom)
    }
}
