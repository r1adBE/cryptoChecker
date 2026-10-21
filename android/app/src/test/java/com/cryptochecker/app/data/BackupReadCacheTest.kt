package com.cryptochecker.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Gewählte Sicherung einmal je Wiederherstellen lesen und entschlüsseln ([BackupReadCache]). */
class BackupReadCacheTest {
    private class File(val text: String)

    private var reads = 0
    private var decrypts = 0
    private val cache = BackupReadCache<String, File> { f, pw ->
        decrypts++
        if (pw == "falsch") throw IllegalArgumentException("falsches Passwort")
        f.text + "|" + pw
    }

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
}
