package com.cryptochecker.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gewählte Sicherung einmal je Wiederherstellen lesen und entschlüsseln ([BackupReadCache]). */
class BackupReadCacheTest {
    private class File(val text: String)

    private var reads = 0
    private var decrypts = 0
    private var now = 1_000L
    private val cache = BackupReadCache<String, File>(
        decrypt = { f, pw ->
            decrypts++
            if (pw == "falsch") throw IllegalArgumentException("falsches Passwort")
            f.text + "|" + pw
        },
        clock = { now },
        plainTtlMillis = 60_000L,
    )

    private fun read(text: String = "inhalt"): () -> File = { reads++; File(text) }

    @Test
    fun wholeFlowReadsAndDecryptsOnce() {
        // Passwort nötig? → Passwort prüfen (ungültige Einträge) → Wiederherstellen
        cache.file("a", read())
        cache.plain("a", "pw", read())
        val (_, text) = cache.take("a", "pw", read())
        assertEquals("inhalt|pw", text)
        assertEquals(1, reads)
        assertEquals(1, decrypts)
    }

    @Test
    fun takeForgetsEverything() {
        cache.take("a", null, read())
        cache.file("a", read())
        assertEquals(2, reads)
    }

    @Test
    fun takeForgetsAlsoOnFailure() {
        assertThrows(IllegalArgumentException::class.java) { cache.take("a", "falsch", read()) }
        cache.file("a", read())
        assertEquals(2, reads)
    }

    @Test
    fun wrongPasswordIsNotKeptAndOtherPasswordDecryptsAgain() {
        assertThrows(IllegalArgumentException::class.java) { cache.plain("a", "falsch", read()) }
        assertEquals("inhalt|pw", cache.plain("a", "pw", read()))
        assertEquals("inhalt|pw2", cache.plain("a", "pw2", read()))
        // Datei nur einmal gelesen, jedes Passwort einmal entschlüsselt
        assertEquals(1, reads)
        assertEquals(3, decrypts)
    }

    @Test
    fun otherFileOrClearReadsAgain() {
        val first = cache.file("a", read("eins"))
        assertSame(first, cache.file("a", read("zwei")))
        assertEquals("zwei|null", cache.plain("b", null, read("zwei")))
        cache.clear()
        assertEquals("drei|null", cache.plain("b", null, read("drei")))
        assertEquals(3, reads)
    }

    @Test
    fun clearDuringReadDoesNotKeepTheResult() {
        // Abbrechen, während noch gelesen wird: das Ergebnis gilt für den Aufrufer, wird aber nicht gemerkt
        cache.file("a") { reads++; cache.clear(); File("alt") }
        cache.file("a", read())
        assertEquals(2, reads)
    }

    @Test
    fun klartextVerfaelltNachDerFrist() {
        // Klartext nur zwischen Passwort prüfen und Wiederherstellen, danach neu entschlüsseln
        cache.plain("a", "pw", read())
        now += 59_000L
        cache.plain("a", "pw", read())
        assertEquals(1, decrypts)
        now += 2_000L
        assertEquals("inhalt|pw", cache.plain("a", "pw", read()))
        assertEquals(2, decrypts)
        // Die Datei selbst bleibt gemerkt (kein zweites Lesen)
        assertEquals(1, reads)
    }

    @Test
    fun abgelaufenerKlartextWirdBeimNaechstenZugriffVerworfen() {
        cache.plain("a", "falsch2", read())
        assertTrue(cache.holdsPlain())
        now += 61_000L
        // Anderes Passwort, scheitert: der alte Klartext ist trotzdem weg
        assertThrows(IllegalArgumentException::class.java) { cache.plain("a", "falsch", read()) }
        assertFalse(cache.holdsPlain())
    }

    @Test
    fun passwortVergleichUnterscheidetNullUndText() {
        assertEquals("inhalt|null", cache.plain("a", null, read()))
        assertEquals("inhalt|pw", cache.plain("a", "pw", read()))
        assertEquals("inhalt|pw", cache.plain("a", "pw", read()))
        assertEquals(2, decrypts)
    }

    @Test
    fun keinPasswortImKlartextGemerkt() {
        // Kein Feld der Klasse hält das Passwort selbst (nur einen gesalzenen Fingerabdruck)
        cache.plain("a", "geheimesPasswort", read())
        val fields = BackupReadCache::class.java.declaredFields.onEach { it.isAccessible = true }.map { it.get(cache) }
        assertFalse(fields.any { it == "geheimesPasswort" })
    }
}
