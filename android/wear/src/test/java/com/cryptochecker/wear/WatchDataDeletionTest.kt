package com.cryptochecker.wear

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gelöschte Einträge des Handys (App zurückgesetzt oder entfernt): die Uhr liest sie mit ([WatchDataLayer.changed]
 * nimmt `TYPE_DELETED`) und vergisst, was davon stammt — Portfolio-Wert weg, Kurse nicht mehr vom Handy, Wahl wie ohne
 * Handy (`WatchSettings.forgetPhone`, Parität in `watch_link.json`). Fehlt ein Eintrag beim Öffnen ganz, gilt er als
 * gelöscht. Wie die Apple Watch ohne iPhone-App (`WatchModel.forgetPhone`).
 */
class WatchDataDeletionTest {

    @Test
    fun deletionsAreRead() {
        val layer = source("WatchDataLayer.kt")
        assertTrue(layer.contains("it.type == DataEvent.TYPE_CHANGED || it.type == DataEvent.TYPE_DELETED"))
        assertTrue(layer.contains("Item(uri.host, null, uri.path, deleted = true)"))
    }

    @Test
    fun deletionsClearWhatCameFromThePhone() {
        val layer = source("WatchDataLayer.kt")
        assertTrue(layer.contains("quotesItem.deleted -> store.forgetPhoneQuotes()"))
        assertTrue(layer.contains("if (it.deleted) WatchPortfolio.Message.Clear else WatchPortfolio.fromJson(it.json)"))
        assertTrue(layer.contains("if (gone) store.updateSettings { WatchSettings.forgetPhone(it) }"))
        // Beim Öffnen: kein Eintrag des Handys in der lesbaren Daten-Schicht → wie gelöscht
        assertTrue(layer.contains("if (found.none { it.node != local }) listOf(Item(null, null, path, deleted = true)) else found"))
        val store = source("WatchStore.kt")
        assertTrue(store.contains("remove(KEY_PHONE_COINS)"))
        assertTrue(store.contains("remove(KEY_PHONE_TIME)"))
        // Der Listener reagiert auf alle Pfade unter /cc/watch, auch auf Löschungen
        assertTrue(source("WatchDataListener.kt").contains("WatchDataLayer.changed(dataEvents)"))
    }

    private fun source(name: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in listOf("src/main/java/com/cryptochecker/wear/", "wear/src/main/java/com/cryptochecker/wear/",
                "android/wear/src/main/java/com/cryptochecker/wear/")) {
                val f = File(dir, rel + name)
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
        }
        throw AssertionError("$name nicht gefunden")
    }
}
