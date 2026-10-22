package com.cryptochecker.app.data.remote.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpBodyLimitTest {

    @Test
    fun readsUpToTheLimit() {
        val data = ByteArray(1000) { it.toByte() }
        assertArrayEquals(data, HttpBodyLimit.read(ByteArrayInputStream(data), -1, 1000))
        assertArrayEquals(data, HttpBodyLimit.read(ByteArrayInputStream(data), 1000, 1000))
    }

    @Test
    fun stopsAboveTheLimitWithoutContentLength() {
        val data = ByteArray(20_000)
        assertNull(HttpBodyLimit.read(ByteArrayInputStream(data), -1, 19_999))
    }

    @Test
    fun announcedLengthRejectsBeforeReading() {
        val input = ByteArrayInputStream(ByteArray(10))
        assertNull(HttpBodyLimit.read(input, 5_000_000, HttpBodyLimit.DEFAULT_MAX_BYTES))
        // Nichts gelesen
        assertEquals(10, input.available())
        assertTrue(HttpBodyLimit.exceeds(HttpBodyLimit.DEFAULT_MAX_BYTES + 1, HttpBodyLimit.DEFAULT_MAX_BYTES))
        assertFalse(HttpBodyLimit.exceeds(-1, HttpBodyLimit.DEFAULT_MAX_BYTES))
    }

    @Test
    fun startBufferIsCappedAt256KiB() {
        // Seit Build 102: angekündigte 16 MB belegen nicht sofort 16 MB
        assertEquals(256 * 1024, HttpBodyLimit.initialCapacity(HttpBodyLimit.BULK_MAX_BYTES, HttpBodyLimit.BULK_MAX_BYTES))
        assertEquals(1000, HttpBodyLimit.initialCapacity(1000, HttpBodyLimit.BULK_MAX_BYTES))
        assertEquals(8192, HttpBodyLimit.initialCapacity(-1, HttpBodyLimit.BULK_MAX_BYTES))
        assertEquals(8192, HttpBodyLimit.initialCapacity(0, HttpBodyLimit.BULK_MAX_BYTES))
        // Grössere Antworten wachsen beim Lesen nach
        val data = ByteArray(300 * 1024) { it.toByte() }
        assertArrayEquals(data, HttpBodyLimit.read(ByteArrayInputStream(data), data.size.toLong(), HttpBodyLimit.BULK_MAX_BYTES))
    }

    @Test
    fun decodesUtf8AndDropsBom() {
        val text = "{\"p\":\"98’450\"}"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()
        assertEquals(text, HttpBodyLimit.decode(bom))
        assertEquals(text, HttpBodyLimit.decode(text.toByteArray()))
    }

    /** Tiefe Verschachtelung erkennen, Klammern in Texten nicht mitzählen. */
    @Test
    fun detectsTooDeepJson() {
        assertFalse(HttpBodyLimit.tooDeep("""{"a":[{"b":[1,2]}]}"""))
        assertFalse(HttpBodyLimit.tooDeep("[".repeat(HttpBodyLimit.MAX_JSON_DEPTH)))
        assertTrue(HttpBodyLimit.tooDeep("[".repeat(HttpBodyLimit.MAX_JSON_DEPTH + 1)))
        assertFalse(HttpBodyLimit.tooDeep("""{"t":"${"[".repeat(1_000)}"}"""))
        // 16 MB «[[[[…»: wird geprüft, ohne dass ein Parser rekursiv absteigt
        assertTrue(HttpBodyLimit.tooDeep("[".repeat(HttpBodyLimit.BULK_MAX_BYTES.toInt())))
    }
}
