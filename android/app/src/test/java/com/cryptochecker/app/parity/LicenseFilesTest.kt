package com.cryptochecker.app.parity

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lizenzdatei der App byte-gleich mit `LICENSE` (Android und iOS), die Hinweise zu Dritten in eigener Datei
 * `NOTICE-thirdparty.txt` (beide Plattformen gleich) — so zeigt «Lizenzen» genau den Text der Projekt-Lizenz.
 */
class LicenseFilesTest {

    /** Ordner `android/` (über `testdata/parity`). */
    private val android: File = ParityJson.dir().parentFile!!.parentFile!!

    private fun bytes(path: String): ByteArray = File(android, path).readBytes()

    /**
     * iOS-Gegenstück; fehlt es, nicht still grün (SEC4-R1/TST4-R1): reiner Android-Checkout übersprungen,
     * mit `REQUIRE_IOS_SOURCES=true` Fehler ([SourceGuard]).
     */
    private fun iosBytes(path: String): ByteArray =
        SourceGuard.ios(File(android.parentFile, path).takeIf { it.isFile }?.readBytes(), path)

    @Test
    fun appLicenseEqualsLicenseFile() {
        val license = bytes("LICENSE")
        assertArrayEquals(license, bytes("app/src/main/assets/licenses/MIT-cryptochecker.txt"))
        assertArrayEquals(license, iosBytes("ios/App/Resources/Licenses/MIT-cryptochecker.txt"))
    }

    @Test
    fun thirdPartyNoticeIsItsOwnFile() {
        val notice = String(bytes("app/src/main/assets/licenses/NOTICE-thirdparty.txt"), Charsets.UTF_8)
        assertTrue(notice.startsWith("Third-party names, logos and data"))
        assertFalse(String(bytes("LICENSE"), Charsets.UTF_8).contains("Third-party names"))
        assertArrayEquals(notice.toByteArray(Charsets.UTF_8), iosBytes("ios/App/Resources/Licenses/NOTICE-thirdparty.txt"))
    }
}
