package com.cryptochecker.app.domain.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grösste Nachricht des Live-Stroms (seit Build 103): 1 MiB in UTF-8, wie iOS `LiveRules.maxMessageBytes`. */
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
