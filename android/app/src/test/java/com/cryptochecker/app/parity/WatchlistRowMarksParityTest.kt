package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.ui.features.watchlist.RowMarks
import com.cryptochecker.app.ui.features.watchlist.rowShowsPeriod
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Marken der Merklisten-Zeile (`testdata/parity/watchlist_row_marks.json`): nur Abweichungen vom Normalfall, dazu der
 * Zeitraum an der Änderung (entfällt nur bei «seit letzt. Abruf» unter dem Spaltenkopf).
 * Gegenstück: `Tests/WatchlistRowMarksTests.swift`.
 */
class WatchlistRowMarksParityTest {

    private val data = ParityJson.load("watchlist_row_marks.json")

    @Test
    fun kindOnlyWhenNotToken() {
        val cases = data["kinds"].list().map { it.obj() }
        assertEquals(AssetKind.entries.map { it.name }.toSet(), cases.map { it["kind"] as String }.toSet())
        for (c in cases) {
            val kind = AssetKind.valueOf(c["kind"] as String)
            assertEquals(kind.name, c["showsKind"], RowMarks.showsKind(kind))
        }
    }

    @Test
    fun priceBellOnlyWhenOff() {
        for (c in data["priceBell"].list().map { it.obj() }) {
            val on = c["priceNotificationOn"] as Boolean
            assertEquals("on=$on", c["showsPriceBell"], RowMarks.showsPriceBell(on))
        }
    }

    @Test
    fun periodHiddenOnlyForSinceLastUnderHeader() {
        for (c in data["rowPeriod"].list().map { it.obj() }) {
            val basis = ChangeBasis.parse(c["basis"] as String)!!
            val header = c["periodInHeader"] as Boolean
            assertEquals("${c["basis"]} header=$header", c["showsPeriod"], rowShowsPeriod(basis, header))
        }
    }
}
