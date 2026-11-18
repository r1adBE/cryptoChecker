package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.util.SourceGuard
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Symbole erklärt»: die Liste nennt jedes Symbol der Zeile, in deren Reihenfolge — gleich wie iOS `WatchlistSymbol`. */
class WatchlistSymbolTest {

    @Test
    fun `legend lists the row symbols in row order`() {
        assertEquals(
            listOf(
                "FAVORITE", "TOKEN", "TRADFI", "ALPHA", "PRICE_NOTIFICATION_ON", "PRICE_NOTIFICATION_OFF", "ACTIVE_ALARMS",
                "NOTE", "WARNING",
            ),
            WatchlistSymbol.entries.map { it.name },
        )
    }

    @Test
    fun `every asset kind has its symbol and the bells have none`() {
        assertEquals(AssetKind.entries.toList(), WatchlistSymbol.entries.mapNotNull { it.kind })
        assertNull(WatchlistSymbol.PRICE_NOTIFICATION_ON.kind)
        assertNull(WatchlistSymbol.PRICE_NOTIFICATION_OFF.kind)
        assertNull(WatchlistSymbol.ACTIVE_ALARMS.kind)
        assertNull(WatchlistSymbol.FAVORITE.kind)
        assertNull(WatchlistSymbol.NOTE.kind)
        assertNull(WatchlistSymbol.WARNING.kind)
    }

    /** Gleiche Symbole wie iOS (Wert-Abgleich); dass das Menü die Liste öffnet, prüft die Regel `android-wiring`. */
    @Test
    fun `ios lists the same symbols`() {
        val ios = SourceGuard.ios(
            findUp(listOf("ios", "../ios", "../../ios").map { "$it/Shared/Models/WatchlistSymbol.swift" }),
            "ios/Shared/Models/WatchlistSymbol.swift",
        ).readText(Charsets.UTF_8)
        assertTrue(ios.contains("case " + WatchlistSymbol.entries.joinToString(", ") { it.name } + "\n"))
    }

    private fun findUp(candidates: List<String>): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in candidates) {
                val f = File(dir, rel)
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        return null
    }
}
