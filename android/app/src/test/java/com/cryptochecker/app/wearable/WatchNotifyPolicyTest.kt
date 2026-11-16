package com.cryptochecker.app.wearable

import com.cryptochecker.app.wearable.WatchNotifyPolicy.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Auf der Uhr zeigen»: wann die Uhr-App übernimmt und was an die Uhr geht. */
class WatchNotifyPolicyTest {

    @Test
    fun readyOnlyWithSwitchReachableWatchAndPermission() {
        assertTrue(WatchNotifyPolicy.ready(enabled = true, reachable = true, permitted = true))
        // Schalter aus, Uhr nicht erreichbar, Erlaubnis verweigert oder noch nichts gemeldet: das System leitet weiter
        assertFalse(WatchNotifyPolicy.ready(enabled = false, reachable = true, permitted = true))
        assertFalse(WatchNotifyPolicy.ready(enabled = true, reachable = false, permitted = true))
        assertFalse(WatchNotifyPolicy.ready(enabled = true, reachable = true, permitted = false))
        assertFalse(WatchNotifyPolicy.ready(enabled = true, reachable = null, permitted = true))
        assertFalse(WatchNotifyPolicy.ready(enabled = true, reachable = true, permitted = null))
    }

    @Test
    fun secretSendsNothing() {
        assertNull(WatchNotifyPolicy.payload(Visibility.SECRET, "Titel" to "Text", null, alarm = true))
    }

    @Test
    fun privateUsesThePublicVersion() {
        val p = WatchNotifyPolicy.payload(Visibility.PRIVATE, "BTC 50 000" to "Portfolio 12 345", "Alarm" to "Ziel erreicht", alarm = true)
        assertEquals(WatchNotifyPolicy.Payload("Alarm", "Ziel erreicht", alarm = true), p)
    }

    @Test
    fun privateWithoutPublicVersionUsesTheFullText() {
        val p = WatchNotifyPolicy.payload(Visibility.PRIVATE, "Titel" to "Text", null, alarm = false)
        assertEquals(WatchNotifyPolicy.Payload("Titel", "Text", alarm = false), p)
    }

    @Test
    fun publicIgnoresThePublicVersion() {
        val p = WatchNotifyPolicy.payload(Visibility.PUBLIC, "Titel" to "Text", "Öffentlich" to "Kurz", alarm = false)
        assertEquals("Titel", p?.title)
    }

    @Test
    fun emptyTitleAndTextSendNothing() {
        assertNull(WatchNotifyPolicy.payload(Visibility.PUBLIC, " " to null, null, alarm = false))
        assertEquals("Text", WatchNotifyPolicy.payload(Visibility.PUBLIC, null to "Text", null, alarm = false)?.text)
    }

    @Test
    fun longTextsAreCut() {
        val p = WatchNotifyPolicy.payload(Visibility.PUBLIC, "T".repeat(500) to "x".repeat(2000), null, alarm = false)
        assertEquals(WatchNotifyPolicy.MAX_TITLE, p?.title?.length)
        assertEquals(WatchNotifyPolicy.MAX_TEXT, p?.text?.length)
    }
}
