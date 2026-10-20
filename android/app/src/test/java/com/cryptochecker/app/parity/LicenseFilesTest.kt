package com.cryptochecker.app.parity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lizenzdatei der App byte-gleich mit `LICENSE` (Android und iOS), der Herkunftshinweis in eigener Datei
 * `NOTICE-origin.txt` (beide Plattformen gleich) — so zeigt «Lizenzen» genau den Text der Projekt-Lizenz.
 */
class LicenseFilesTest {

    /** Ordner `android/` (über `testdata/parity`). */
    private val android: File = ParityJson.dir().parentFile.parentFile

    private fun bytes(path: String): ByteArray = File(android, path).readBytes()

    @Test
    fun appLicenseEqualsLicenseFile() {
        val license = bytes("LICENSE")
        assertArrayEquals(license, bytes("app/src/main/assets/licenses/MIT-cryptochecker.txt"))
        val ios = File(android.parentFile, "ios/App/Resources/Licenses/MIT-cryptochecker.txt")
        if (ios.exists()) assertArrayEquals(license, ios.readBytes())
    }

    @Test
    fun originNoticeIsItsOwnFile() {
        val origin = String(bytes("app/src/main/assets/licenses/NOTICE-origin.txt"), Charsets.UTF_8)
        assertTrue(origin.startsWith("Origin"))
        assertTrue(origin.contains("BitcoinChecker"))
        assertFalse(String(bytes("LICENSE"), Charsets.UTF_8).contains("\nOrigin\n"))
        val ios = File(android.parentFile, "ios/App/Resources/Licenses/NOTICE-origin.txt")
        if (ios.exists()) assertArrayEquals(origin.toByteArray(Charsets.UTF_8), ios.readBytes())
    }
}
