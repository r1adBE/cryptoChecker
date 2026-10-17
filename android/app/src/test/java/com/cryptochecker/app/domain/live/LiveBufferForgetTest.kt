package com.cryptochecker.app.domain.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
