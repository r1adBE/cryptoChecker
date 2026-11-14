package com.cryptochecker.app.wearable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Der Stand des Handys erreicht auch eine Uhr, deren App erst später dazukommt: die Fähigkeit der Uhr-App meldet sich
 * beim Handy ([PhoneWatchListener.onCapabilityChanged] → ganzer Stand samt Kursen und Portfolio-Stand), der Prozessstart
 * holt ein nicht abgelegtes Ablegen nach, und meldet die Uhr einen anderen Handy-Teil, geht alles noch einmal weg
 * (`WatchSettings.phoneSideDiffers`, Parität in `watch_link.json`). Wie iOS `sessionWatchStateDidChange`.
 */
class PhoneWatchRepublishTest {

    @Test
    fun capabilityChangeReachesTheListener() {
        val manifest = file("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("com.google.android.gms.wearable.CAPABILITY_CHANGED"))
        assertTrue(manifest.contains("android:path=\"/${PhoneWatchSync.CAPABILITY}\""))
        // Dieselbe Fähigkeit meldet die Uhr-App
        assertTrue(file("wear/src/main/res/values/wear.xml").contains("<item>${PhoneWatchSync.CAPABILITY}</item>"))
        val listener = file("app/src/main/java/com/cryptochecker/app/wearable/PhoneWatchListener.kt")
        assertTrue(listener.contains("override fun onCapabilityChanged(capabilityInfo: CapabilityInfo)"))
        assertTrue(listener.contains("sync.republish()"))
        assertTrue(listener.contains("feed.resend()"))
    }

    @Test
    fun startAndWatchStateRepublish() {
        val sync = file("app/src/main/java/com/cryptochecker/app/wearable/PhoneWatchSync.kt")
        assertTrue(sync.contains("if (prefs.getString(KEY_PUBLISHED, null) != current.toJson()) republish()"))
        assertTrue(sync.contains("if (WatchSettings.phoneSideDiffers(_state.value, remote)) republish()"))
        // Nur Gelungenes gilt als abgelegt
        assertTrue(sync.contains("if (put(PATH, json)) prefs.edit { putString(KEY_PUBLISHED, json) }"))
        assertTrue(sync.contains("if (nodes.isEmpty()) return@runCatchingCancellable false"))
        val feed = file("app/src/main/java/com/cryptochecker/app/wearable/PhoneWatchFeed.kt")
        assertTrue(feed.contains("if (sync.put(PORTFOLIO_PATH, json)) lastPortfolio = json"))
        assertEquals(1, Regex("lastPortfolio = json").findAll(feed).count())
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
