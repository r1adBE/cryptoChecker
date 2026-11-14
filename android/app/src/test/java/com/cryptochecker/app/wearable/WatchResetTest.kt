package com.cryptochecker.app.wearable

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «App zurücksetzen» räumt auch die Uhr auf: die Einträge des Handys liegen bei den Play-Diensten, nicht im App-Speicher
 * — sie werden vor `clearApplicationUserData()` gelöscht ([PhoneWatchSync.clearWatch]), die Uhr vergisst dann
 * Portfolio-Wert, Favoriten und Kurse vom Handy (`WatchDataDeletionTest` in `:wear`). Wie iOS `PhoneWatchLink.reset`.
 */
class WatchResetTest {

    @Test
    fun resetDeletesWatchEntriesFirst() {
        val screen = file("app/src/main/java/com/cryptochecker/app/ui/features/settings/SettingsScreen.kt")
        val clear = screen.indexOf("PhoneWatchSync.clearWatch(resetContext)")
        val wipe = screen.indexOf("clearApplicationUserData()")
        assertTrue(clear > 0)
        assertTrue("Uhr vor dem Leeren des Speichers", clear < wipe)
    }

    @Test
    fun clearWatchDeletesAllOwnEntries() {
        val sync = file("app/src/main/java/com/cryptochecker/app/wearable/PhoneWatchSync.kt")
        // Eigener Knoten, Präfix /cc/watch: Wahl, Kurse und Portfolio-Wert
        assertTrue(sync.contains("val uri = \"\${PutDataRequest.WEAR_URI_SCHEME}://\$local\$PATH\".toUri()"))
        assertTrue(sync.contains("deleteDataItems(uri, DataClient.FILTER_PREFIX)"))
        assertTrue(PhoneWatchFeed.QUOTES_PATH.startsWith(PhoneWatchSync.PATH))
        assertTrue(PhoneWatchFeed.PORTFOLIO_PATH.startsWith(PhoneWatchSync.PATH))
    }

    private fun file(path: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "android/")) {
                val f = File(dir, prefix + path)
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
        }
        throw AssertionError("$path nicht gefunden")
    }
}
