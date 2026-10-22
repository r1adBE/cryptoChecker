package com.cryptochecker.app.data.live

import com.cryptochecker.app.domain.live.LiveRules
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Binäre Nachrichten des Live-Stroms (seit Build 104): bis [LiveRules.MAX_MESSAGE_BYTES] als UTF-8-Text wie iOS
 * (`.data`), grössere trennen die Verbindung ([LiveConnection.binaryText] = null).
 */
class LiveBinaryMessageTest {

    @Test
    fun kleineBinaerNachrichtWirdText() {
        assertEquals("""{"p":"1"}""", LiveConnection.binaryText("""{"p":"1"}""".encodeUtf8()))
        assertEquals("", LiveConnection.binaryText(ByteArray(0).toByteString()))
    }

    @Test
    fun grenzeInBytes() {
        val max = LiveRules.MAX_MESSAGE_BYTES
        assertEquals(max, LiveConnection.binaryText(ByteArray(max) { 'a'.code.toByte() }.toByteString())?.length)
        assertNull(LiveConnection.binaryText(ByteArray(max + 1) { 'a'.code.toByte() }.toByteString()))
    }
}
