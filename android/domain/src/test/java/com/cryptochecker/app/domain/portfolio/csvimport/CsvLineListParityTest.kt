package com.cryptochecker.app.domain.portfolio.csvimport
import com.cryptochecker.app.domain.portfolio.csvimport.CsvLineList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj

/**
 * Zeilennummern der CSV-Vorschau (`testdata/parity/csv_line_list.json`): [CsvLineList.of] und [CsvLineList.moreAfter].
 * Gegenstück: `Tests/CsvLineListParityTests.swift`.
 */
class CsvLineListParityTest {

    private val data = ParityJson.load("csv_line_list.json")

    private fun Any?.ints(): List<Int> = list().map { (it.num() ?: -1.0).toInt() }

    @Test
    fun ofWieGemeinsameTestfaelle() {
        val cases = data["of"].list()
        assertTrue(cases.size >= 5)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val r = CsvLineList.of(c["lines"].ints(), shown = c["shown"].num()!!.toInt())
            assertEquals(name, c["count"].num()!!.toInt(), r.count)
            assertEquals(name, c["first"].ints(), r.shown)
            assertEquals(name, c["more"].num()!!.toInt(), r.more)
        }
    }

    @Test
    fun moreAfterWieGemeinsameTestfaelle() {
        val cases = data["moreAfter"].list()
        assertTrue(cases.isNotEmpty())
        for (raw in cases) {
            val c = raw.obj()
            assertEquals(
                c["name"] as String,
                c["expected"].num()!!.toInt(),
                CsvLineList.moreAfter(c["total"].num()!!.toInt(), c["shown"].num()!!.toInt()),
            )
        }
    }

    @Test
    fun standardZeigtFuenfNummern() {
        assertEquals(5, CsvLineList.SHOWN)
        assertEquals(listOf(1, 2, 3, 4, 5), CsvLineList.of((1..9).toList()).shown)
    }
}
