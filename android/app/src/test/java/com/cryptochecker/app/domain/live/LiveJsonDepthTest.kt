package com.cryptochecker.app.domain.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Seit Build 102: Tiefenlimit des Live-JSON-Lesers ([LiveJson.MAX_DEPTH]) — tiefe Nachrichten ergeben null statt Absturz. */
class LiveJsonDepthTest {

    private fun arrays(depth: Int) = "[".repeat(depth) + "]".repeat(depth)

    @Test
    fun tiefeBisZurGrenzeWirdGelesen() {
        assertNotNull(LiveJson.parse(arrays(LiveJson.MAX_DEPTH)))
        assertNull(LiveJson.parse(arrays(LiveJson.MAX_DEPTH + 1)))
    }

    @Test
    fun riesigeVerschachtelungIstUngueltigOhneStackOverflow() {
        // 1 MB «[[[[…»: bis Build 101 ein StackOverflowError auf dem OkHttp-Thread
        assertNull(LiveJson.parse("[".repeat(1_000_000)))
        assertNull(LiveJson.parse("{\"a\":".repeat(200_000) + "1" + "}".repeat(200_000)))
    }

    @Test
    fun gemischteTiefeZaehltObjekteUndArrays() {
        val ok = "{\"a\":".repeat(64) + "[".repeat(64) + "1" + "]".repeat(64) + "}".repeat(64)
        assertNotNull(LiveJson.parse(ok))
        val tooDeep = "{\"a\":".repeat(64) + "[".repeat(65) + "1" + "]".repeat(65) + "}".repeat(64)
        assertNull(LiveJson.parse(tooDeep))
    }

    @Test
    fun geschwisterZaehlenNichtAlsTiefe() {
        // Viele Einträge nebeneinander sind flach: Tiefe wird beim Verlassen wieder abgezogen
        val wide = "[" + (1..500).joinToString(",") { "[[1]]" } + "]"
        assertEquals(500, (LiveJson.parse(wide) as List<*>).size)
    }
}
