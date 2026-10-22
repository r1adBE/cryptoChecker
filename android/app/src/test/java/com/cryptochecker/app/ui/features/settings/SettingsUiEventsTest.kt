package com.cryptochecker.app.ui.features.settings

import com.cryptochecker.app.ui.components.ScreenEvents
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Einstellungen › Kursmitteilungen (Review ARC6-R01/UX6-R1): Erlaubnis-Abfrage nur nach «Trotzdem» und nur beim Einschalten,
 * als Ereignis für den aktuellen Bildschirm statt als Rückruf in die alte Komposition.
 */
class SettingsUiEventsTest {

    @Test
    fun proceedWhileSwitchingOnAsksExactlyOnce() = runTest {
        val events = ScreenEvents<Unit>()
        askPermissionAfterWrite(events, enabled = true)(true)
        assertEquals(listOf(Unit), events.events.take(1).toList())
        assertNull(withTimeoutOrNull(50) { events.events.first() })
    }

    @Test
    fun cancelLockedOrSwitchingOffAsksNothing() = runTest {
        val events = ScreenEvents<Unit>()
        // «Abbrechen» bzw. gesperrt: nicht geschrieben
        askPermissionAfterWrite(events, enabled = true)(false)
        // Ausschalten: nie fragen
        askPermissionAfterWrite(events, enabled = false)(true)
        askPermissionAfterWrite(events, enabled = false)(false)
        assertNull(withTimeoutOrNull(50) { events.events.first() })
    }

    @Test
    fun pairsPageSendsEventsInsteadOfCallingTheLauncher() {
        val dir = findUp(listOf("", "app/", "android/app/").map { it + "src/main/java/com/cryptochecker/app/ui/features/settings" })
        assertNotNull("settings-Quellen nicht gefunden (Arbeitsverzeichnis?)", dir)
        val page = File(dir, "SettingsAlarmPages.kt").readText()
        // Kein Rückruf nach F-08, der den Launcher der Komposition beim Tippen aufruft
        assertFalse(Regex("""written\s*->[^}]*requestNotifications\(\)""").containsMatchIn(page))
        assertEquals(2, Regex("""askPermissionAfterWrite\(uiEvents\.requestNotifications""").findAll(page).count())
        assertEquals(1, Regex("""CollectScreenEvents\(uiEvents\.requestNotifications\)""").findAll(page).count())
    }

    private fun findUp(candidates: List<String>): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in candidates) {
                val f = File(dir, rel)
                if (f.isDirectory) return f
            }
            dir = dir.parentFile
        }
        return null
    }
}
