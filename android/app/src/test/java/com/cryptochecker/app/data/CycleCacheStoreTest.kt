package com.cryptochecker.app.data

import com.cryptochecker.app.domain.market.CycleCachePolicy
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Zwischenspeicher des Markt-Tabs ([CycleCacheStore]): eine JSON-Datei je Bereich unter `cacheDir/cycle`, mit
 * Format-Version, Name, Zeitpunkt und Anbieter. Fremde, alte oder kaputte Dateien gelten als «nichts gespeichert».
 */
class CycleCacheStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val codec = CacheCodec<Pair<String, Int>>(
        encode = { JSONObject().put("s", it.first).put("n", it.second) },
        decode = { Pair(it.getString("s"), it.getInt("n")) },
    )

    private fun file(root: File, name: String) = File(root, "cache/cycle/" + CycleCachePolicy.fileName(name))

    @Test
    fun writtenValueIsReadBackWithTimeAndProvider() = runBlocking {
        val root = temp.newFolder()
        val store = CycleCacheStore(FolderContext(root))
        assertNull("noch nichts gespeichert", store.read("fng", codec))

        store.write("fng", "gier" to 72, savedAt = 1_700_000_000_000L, codec = codec, provider = "alternative.me")
        val read = store.read("fng", codec)!!
        assertEquals("gier" to 72, read.value)
        assertEquals(1_700_000_000_000L, read.savedAt)
        assertEquals("alternative.me", read.provider)
        assertTrue(file(root, "fng").isFile)
        assertFalse("Hilfsdatei umbenannt", File(file(root, "fng").path + ".tmp").exists())

        // Überschreiben ohne Anbieter: Anbieter unbekannt
        store.write("fng", "angst" to 20, savedAt = 1_700_000_100_000L, codec = codec)
        val again = CycleCacheStore(FolderContext(root)).read("fng", codec)!!
        assertEquals("angst" to 20, again.value)
        assertNull(again.provider)
    }

    @Test
    fun foreignOldOrBrokenFilesCountAsMissing() = runBlocking {
        val root = temp.newFolder()
        val store = CycleCacheStore(FolderContext(root))
        store.write("coin_ETH", "x" to 1, savedAt = 5L, codec = codec)
        val target = file(root, "coin_ETH")
        val good = JSONObject(target.readText())

        // Anderer Name im Inhalt (Datei eines anderen Bereichs umbenannt)
        target.writeText(JSONObject(good.toString()).put("name", "coin_BTC").toString())
        assertNull(store.read("coin_ETH", codec))
        // Andere oder fehlende Format-Version
        target.writeText(JSONObject(good.toString()).put("v", CycleCachePolicy.FORMAT_VERSION + 1).toString())
        assertNull(store.read("coin_ETH", codec))
        target.writeText(JSONObject(good.toString()).apply { remove("v") }.toString())
        assertNull(store.read("coin_ETH", codec))
        // Kein gültiger Zeitpunkt
        target.writeText(JSONObject(good.toString()).put("at", 0L).toString())
        assertNull(store.read("coin_ETH", codec))
        // Kaputtes JSON bzw. Inhalt, den der Codec ablehnt
        target.writeText("{nicht json")
        assertNull(store.read("coin_ETH", codec))
        target.writeText(JSONObject(good.toString()).put("data", JSONObject().put("s", "x")).toString())
        assertNull(store.read("coin_ETH", codec))
        // Gültige Datei mit leerem Anbieter: gelesen, Anbieter unbekannt
        target.writeText(JSONObject(good.toString()).put("src", " ").toString())
        val read = store.read("coin_ETH", codec)!!
        assertEquals("x" to 1, read.value)
        assertNull(read.provider)
    }

    @Test
    fun failingEncoderWritesNothing() = runBlocking {
        val root = temp.newFolder()
        val store = CycleCacheStore(FolderContext(root))
        val broken = CacheCodec<String>(encode = { error("kaputt") }, decode = { it.getString("s") })
        store.write("broken", "x", savedAt = 1L, codec = broken)
        assertFalse(file(root, "broken").exists())
        assertNull(store.read("broken", broken))
    }

    @Test
    fun blockedFolderIsNoCrash() = runBlocking {
        val root = temp.newFolder()
        // «cycle» ist eine Datei statt eines Ordners: nicht anlegbar, Schreiben entfällt still
        File(root, "cache").mkdirs()
        File(root, "cache/cycle").writeText("kein Ordner")
        val store = CycleCacheStore(FolderContext(root))
        store.write("fng", "x" to 1, savedAt = 1L, codec = codec)
        assertNull(store.read("fng", codec))
    }
}
