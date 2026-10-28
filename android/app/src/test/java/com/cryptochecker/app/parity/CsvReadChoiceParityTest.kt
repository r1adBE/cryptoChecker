package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.csvimport.CsvDateOrder
import com.cryptochecker.app.domain.portfolio.csvimport.CsvReadChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Importieren» nicht während des Neu-Lesens und nur mit der Vorschau der aktuellen
 * Lesart (`csv_import.json` › «confirmGate»). Gegenstück: `Tests/CsvReadChoiceTests`.
 */
class CsvReadChoiceParityTest {

    private fun choice(raw: Any?): CsvReadChoice? = raw?.obj()?.let { o ->
        CsvReadChoice(
            zone = o["zone"] as String?,
            dateOrder = (o["dateOrder"] as String?)?.let { CsvDateOrder.valueOf(it) },
            decimalMark = (o["decimalMark"] as String?)?.single(),
        )
    }

    @Test
    fun knopfWieGemeinsameTestfaelle() {
        val cases = ParityJson.load("csv_import.json")["confirmGate"].list()
        assertTrue(cases.size >= 7)
        for (raw in cases) {
            val c = raw.obj()
            val actual = CsvReadChoice.canConfirm(c["rereading"] as Boolean, choice(c["builtWith"]), choice(c["chosen"])!!)
            assertEquals(c["name"] as String, c["expected"] as Boolean, actual)
        }
    }
}
