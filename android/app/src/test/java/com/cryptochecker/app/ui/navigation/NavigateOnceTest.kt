package com.cryptochecker.app.ui.navigation

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Doppel-Tipp legt Alarm-, «Alle Alarme»- und «Paar hinzufügen»-Seite nur einmal auf den Stapel. */
class NavigateOnceTest {

    @Test
    fun nurDieVordersteSeiteDarfNavigieren() {
        assertTrue(NavigateOnce.allowed(Lifecycle.State.RESUMED))
        // Nach dem ersten Tipp steht die alte Seite während der Überblendung auf STARTED
        assertFalse(NavigateOnce.allowed(Lifecycle.State.STARTED))
        assertFalse(NavigateOnce.allowed(Lifecycle.State.CREATED))
        assertFalse(NavigateOnce.allowed(Lifecycle.State.DESTROYED))
    }

    @Test
    fun zweiterTippWaehrendDerUeberblendungErzeugtKeinenZweitenEintrag() {
        // Nachbau des Ablaufs: der erste Tipp navigiert und stuft die Seite auf STARTED herab
        var state = Lifecycle.State.RESUMED
        val backStack = mutableListOf<String>()
        repeat(2) {
            if (NavigateOnce.allowed(state)) {
                backStack += "alarms"
                state = Lifecycle.State.STARTED
            }
        }
        assertEquals(1, backStack.size)
    }

    @Test
    fun alarmSeitenWerdenNurUeberDenSchutzGeoeffnet() {
        val dir = listOf(
            File("src/main/java/com/cryptochecker/app/ui/navigation"),
            File("app/src/main/java/com/cryptochecker/app/ui/navigation"),
        ).first { it.isDirectory }
        val unguarded = Regex("""navigation\.navigate\(ScreenRoute\.(Alarms\(|AlarmsOverview\))""")
        for (name in listOf("AppNavHost.kt", "AppNavDestinations.kt")) {
            val source = File(dir, name).readText()
            assertFalse("$name öffnet Alarm-Seiten ohne navigateOnce", unguarded.containsMatchIn(source))
            assertFalse("$name öffnet «Paar hinzufügen» ohne Schutz", source.contains("navigation.openExplorer()"))
        }
    }
}
