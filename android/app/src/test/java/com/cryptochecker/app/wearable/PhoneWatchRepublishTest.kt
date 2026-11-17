package com.cryptochecker.app.wearable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Der Stand des Handys erreicht auch eine Uhr, deren App erst später dazukommt: die Fähigkeit der Uhr-App meldet sich
 * beim Handy ([PhoneWatchListener.onCapabilityChanged] → ganzer Stand samt Kursen und Portfolio-Stand), der Prozessstart
 * holt ein nicht abgelegtes Ablegen nach, und meldet die Uhr einen anderen Handy-Teil, geht alles noch einmal weg
 * (`WatchSettings.phoneSideDiffers`, Parität in `watch_link.json`). Wie iOS `sessionWatchStateDidChange`.
 */
class PhoneWatchRepublishTest {

    /** Manifest und Uhr-App melden dieselbe Fähigkeit (Ressourcen, nicht Quelltext). */
    @Test
    fun capabilityIsDeclaredOnBothSides() {
        val manifest = file("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("com.google.android.gms.wearable.CAPABILITY_CHANGED"))
        assertTrue(manifest.contains("android:path=\"/${PhoneWatchSync.CAPABILITY}\""))
        assertTrue(file("wear/src/main/res/values/wear.xml").contains("<item>${PhoneWatchSync.CAPABILITY}</item>"))
    }

    /** Uhr-App dazugekommen: Erreichbarkeit merken, ganzen Stand und Kurse samt Portfolio-Stand ablegen; ohne Uhr nichts. */
    @Test
    fun watchAppChangeRepublishesEverything() {
        val calls = mutableListOf<String>()
        fun change(name: String, reachable: Boolean) = PhoneWatchListener.watchAppChanged(
            name, reachable, { calls += "reachable=$it" }, republish = { calls += "republish" }, resend = { calls += "resend" },
        )
        change(PhoneWatchSync.CAPABILITY, reachable = true)
        assertEquals(listOf("reachable=true", "republish", "resend"), calls)
        calls.clear()
        change(PhoneWatchSync.CAPABILITY, reachable = false)
        assertEquals(listOf("reachable=false"), calls)
        calls.clear()
        change("other_capability", reachable = true)
        assertTrue(calls.isEmpty())
    }

    /** Start: nur ein nicht erledigter Stand wird abgelegt; ohne Uhr gilt er als erledigt, ein Fehler nicht. */
    @Test
    fun startRepublishesOnlyWhatIsNotSettled() = runBlocking<Unit> {
        assertTrue(PhoneWatchSync.republishAtStart(null, "{}"))
        assertTrue(PhoneWatchSync.republishAtStart("{\"a\":1}", "{}"))
        assertFalse(PhoneWatchSync.republishAtStart("{}", "{}"))
        var puts = 0
        assertEquals(PhoneWatchSync.PutResult.NO_WATCH, PhoneWatchSync.putOutcome({ 0 }) { puts++ })
        assertEquals("ohne Uhr nichts abgelegt", 0, puts)
        assertEquals(PhoneWatchSync.PutResult.PUBLISHED, PhoneWatchSync.putOutcome({ 1 }) { puts++ })
        assertEquals(1, puts)
        assertEquals(PhoneWatchSync.PutResult.FAILED, PhoneWatchSync.putOutcome({ error("keine Play-Dienste") }) { puts++ })
        assertEquals(PhoneWatchSync.PutResult.FAILED, PhoneWatchSync.putOutcome({ 1 }) { error("Frist") })
        assertTrue(PhoneWatchSync.PutResult.PUBLISHED.settled && PhoneWatchSync.PutResult.NO_WATCH.settled)
        assertFalse(PhoneWatchSync.PutResult.FAILED.settled)
    }

    /** Portfolio-Stand: gemerkt wird nur, was wirklich abgelegt ist; Gleiches geht nicht noch einmal weg. */
    @Test
    fun portfolioRemembersOnlyWhatWasPut() = runBlocking<Unit> {
        val sent = mutableListOf<String>()
        assertEquals(null, PhoneWatchFeed.sentIfNew("A", null) { false })
        assertEquals("A", PhoneWatchFeed.sentIfNew("A", null) { sent += it; true })
        assertEquals("A", PhoneWatchFeed.sentIfNew("A", "A") { sent += it; true })
        assertEquals(listOf("A"), sent)
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
