package com.cryptochecker.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpUserAgentTest {

    @Test
    fun androidUserAgentCarriesTheVersionName() {
        assertEquals("cryptoChecker-Android/16.2.2", HttpUserAgent.android("16.2.2"))
        assertEquals("cryptoChecker-Android/16.2.2", HttpUserAgent.android(" 16.2.2 "))
        assertEquals("User-Agent", HttpUserAgent.HEADER)
    }

    @Test
    fun ownUserAgentOfARequestStays() {
        assertTrue(HttpUserAgent.shouldSet(null))
        assertTrue(HttpUserAgent.shouldSet(" "))
        assertFalse(HttpUserAgent.shouldSet("cryptoChecker-Android/16"))
    }
}
