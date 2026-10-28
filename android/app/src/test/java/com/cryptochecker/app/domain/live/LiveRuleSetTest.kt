package com.cryptochecker.app.domain.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus LiveBufferForgetTest

/** «Paar bearbeiten»: Der Live-Puffer vergisst den Stand des alten Paars ([LiveBuffer.forget]). */
class LiveBufferForgetTest {

    @Test
    fun forgetDropsQuoteAndUnsavedOfTheId() {
        val buffer = LiveBuffer()
        buffer.offer(listOf(1L, 2L), LiveTick("BTCUSDT", 100.0, 1.5, 10), now = 100)
        buffer.takeForUi()

        buffer.forget(listOf(1L))

        // Nichts mehr zu speichern für die vergessene Id, die andere bleibt
        assertEquals(setOf(2L), buffer.takeForDb().keys)
        // Die Anzeige erfährt davon (Kurs des alten Paars verschwindet)
        val ui = buffer.takeForUi()!!
        assertFalse(1L in ui)
        assertTrue(2L in ui)
        assertEquals(1, buffer.size)
    }

    @Test
    fun deltaAfterForgetDoesNotInheritTheOldChange() {
        val buffer = LiveBuffer()
        buffer.offer(listOf(1L), LiveTick("BTCUSDT", 100.0, 5.0, 10), now = 100)
        buffer.forget(listOf(1L))

        // Erster Takt des neuen Paars ohne 24-h-Feld: keine Veränderung vom alten Paar übernehmen
        buffer.offer(listOf(1L), LiveTick("ETHUSDT", 2_000.0, null, 200), now = 200)
        assertEquals(LiveQuote(2_000.0, null, 200), buffer.takeForDb()[1L])

        // Delta ohne Kurs nach dem Vergessen: nichts (kein alter Kurs als Ausweich-Wert)
        buffer.forget(listOf(1L))
        assertFalse(buffer.offer(listOf(1L), LiveTick("ETHUSDT", null, 1.0, 300), now = 300))
        assertTrue(buffer.takeForDb().isEmpty())
    }

    @Test
    fun forgetUnknownIdChangesNothing() {
        val buffer = LiveBuffer()
        buffer.offer(listOf(1L), LiveTick("BTCUSDT", 100.0, 1.5, 10), now = 100)
        buffer.takeForUi()
        buffer.forget(listOf(9L))
        assertNull(buffer.takeForUi())
        assertEquals(setOf(1L), buffer.takeForDb().keys)
    }
}

// ---- aus LiveJsonDepthTest

/** Tiefenlimit des Live-JSON-Lesers ([LiveJson.MAX_DEPTH]) — tiefe Nachrichten ergeben null statt Absturz. */
class LiveJsonDepthTest {

    private fun arrays(depth: Int) = "[".repeat(depth) + "]".repeat(depth)

    @Test
    fun tiefeBisZurGrenzeWirdGelesen() {
        assertNotNull(LiveJson.parse(arrays(LiveJson.MAX_DEPTH)))
        assertNull(LiveJson.parse(arrays(LiveJson.MAX_DEPTH + 1)))
    }

    @Test
    fun riesigeVerschachtelungIstUngueltigOhneStackOverflow() {
        // 1 MB «[[[[…»: kein StackOverflowError auf dem OkHttp-Thread
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

// ---- aus LiveMessageSizeTest

/** Grösste Nachricht des Live-Stroms: 1 MiB in UTF-8, wie iOS `LiveRules.maxMessageBytes`. */
class LiveMessageSizeTest {

    @Test
    fun grenzeIstEinMebibyteWieIos() {
        assertEquals(1_048_576, LiveRules.MAX_MESSAGE_BYTES)
    }

    @Test
    fun zaehltUtf8Bytes() {
        val max = LiveRules.MAX_MESSAGE_BYTES
        assertFalse(LiveRules.isMessageTooLarge(""))
        assertFalse(LiveRules.isMessageTooLarge("""{"p":"1"}"""))
        assertFalse(LiveRules.isMessageTooLarge("a".repeat(max)))
        assertTrue(LiveRules.isMessageTooLarge("a".repeat(max + 1)))
        // «ä» = 2 Byte: halb so viele Zeichen reichen
        assertFalse(LiveRules.isMessageTooLarge("ä".repeat(max / 2)))
        assertTrue(LiveRules.isMessageTooLarge("ä".repeat(max / 2) + "a"))
        // «€» = 3 Byte
        assertTrue(LiveRules.isMessageTooLarge("€".repeat(max / 3 + 1)))
        assertFalse(LiveRules.isMessageTooLarge("€".repeat(max / 3)))
    }
}
