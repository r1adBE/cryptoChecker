package com.cryptochecker.app.tts

import com.cryptochecker.app.tts.AnnouncementQueue.Item
import com.cryptochecker.app.tts.AnnouncementQueue.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementQueueTest {

    private fun alarm(text: String) = Item(text, 1f, Kind.ALARM)
    private fun price(key: Long, text: String) = Item(text, 1f, Kind.PRICE, key)

    private fun AnnouncementQueue.drain(): List<String> = generateSequence { poll() }.map { it.text }.toList()

    @Test
    fun `mehrere Alarme eines Durchlaufs bleiben alle erhalten`() {
        val q = AnnouncementQueue()
        q.offer(alarm("A1"))
        q.offer(alarm("A2"))
        q.offer(alarm("A3"))
        assertEquals(listOf("A1", "A2", "A3"), q.drain())
    }

    @Test
    fun `je Paar nur die neueste Kursansage`() {
        val q = AnnouncementQueue()
        assertFalse(q.offer(price(1, "BTC 1")))
        q.offer(price(2, "ETH 1"))
        assertTrue(q.offer(price(1, "BTC 2")))
        assertEquals(2, q.size)
        // BTC reiht sich mit dem neuen Kurs hinten ein
        assertEquals(listOf("ETH 1", "BTC 2"), q.drain())
    }

    @Test
    fun `Alarme vor wartenden Kursansagen und nie verworfen`() {
        val q = AnnouncementQueue()
        q.offer(price(1, "BTC 1"))
        q.offer(alarm("A1"))
        q.offer(price(1, "BTC 2"))
        q.offer(alarm("A2"))
        assertEquals(listOf("A1", "A2", "BTC 2"), q.drain())
    }

    @Test
    fun `Kursansage ohne Paar wird nicht zusammengefasst`() {
        val q = AnnouncementQueue()
        q.offer(Item("x", 1f, Kind.PRICE))
        q.offer(Item("y", 1f, Kind.PRICE))
        assertEquals(listOf("x", "y"), q.drain())
    }

    @Test
    fun `leer und clear`() {
        val q = AnnouncementQueue()
        assertNull(q.poll())
        q.offer(alarm("A"))
        q.offer(price(1, "p"))
        q.clear()
        assertTrue(q.isEmpty())
    }

    @Test
    fun `viele Live-Durchlaeufe stauen sich nicht`() {
        val q = AnnouncementQueue()
        repeat(100) { run -> (1L..5L).forEach { q.offer(price(it, "p$it-$run")) } }
        assertEquals(5, q.size)
        assertEquals((1..5).map { "p$it-99" }, q.drain())
    }
}
