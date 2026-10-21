package com.cryptochecker.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    /**
     * Kein fest verdrahteter User-Agent neben [HttpUserAgent] (seit Build 93: Logos sandten «/16» statt der Version):
     * Jede Abfrage nimmt [HttpUserAgent.android] mit dem versionName.
     */
    @Test
    fun noHardcodedUserAgentInSources() {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var root: File? = null
        while (dir != null && root == null) {
            root = listOf("", "app/", "android/app/").map { File(dir, it + "src/main/java/com/cryptochecker/app") }
                .firstOrNull { it.isDirectory }
            dir = dir.parentFile
        }
        assertNotNull("android/app/src/main/java nicht gefunden (Arbeitsverzeichnis?)", root)
        val hits = root!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "HttpUserAgent.kt" }
            .filter { "\"cryptoChecker-Android/" in it.readText(Charsets.UTF_8) }
            .map { it.name }
            .toList()
        assertTrue("fester User-Agent statt HttpUserAgent.android: $hits", hits.isEmpty())
    }
}
