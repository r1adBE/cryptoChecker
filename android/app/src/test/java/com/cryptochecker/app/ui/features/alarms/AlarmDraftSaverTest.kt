package com.cryptochecker.app.ui.features.alarms

import androidx.compose.runtime.saveable.SaverScope
import com.cryptochecker.app.domain.model.AlarmCondition
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.Serializable

/**
 * Alarm-Editor übersteht Drehen: Entwurf als Bundle-taugliche Liste ([AlarmDraftSaver]) und Wächter auf die
 * Quellen (Zustand mit `rememberSaveable`, «Speichern» fest unten über der Tastatur statt im Scroll).
 */
class AlarmDraftSaverTest {

    private val full = AlarmDraft(
        id = 42L,
        condition = AlarmCondition.NEAR_HIGH,
        thresholdText = "2,5",
        repeating = true,
        sound = false,
        vibrate = false,
        speak = true,
        windowHours = 30,
        currency = "CHF",
        priceHint = 88_123.45,
        decimalSeparator = ',',
        newExtremeOnly = true,
        suggestedText = "2,5",
        blankStart = false,
    )

    @Test
    fun everyFieldSurvivesTheRoundTrip() {
        assertEquals(full, AlarmDraftSaver.restore(AlarmDraftSaver.save(full)))
        val blank = AlarmDraft(decimalSeparator = '.', blankStart = true)
        assertEquals(blank, AlarmDraftSaver.restore(AlarmDraftSaver.save(blank)))
    }

    @Test
    fun savedValuesFitIntoABundle() {
        val saved = AlarmDraftSaver.save(full)
        assertEquals(AlarmDraftSaver.FIELDS, saved.size)
        // Bundle nimmt Serializable: jedes Feld muss es sein (null zählt mit)
        saved.forEach { assertTrue("$it", it == null || it is Serializable) }
    }

    @Test
    fun foreignOrOldListGivesNoDraft() {
        assertNull(AlarmDraftSaver.restore(emptyList<Any?>()))
        assertNull(AlarmDraftSaver.restore(AlarmDraftSaver.save(full).dropLast(1)))
        val renamed = AlarmDraftSaver.save(full).toMutableList().also { it[1] = "GIBT_ES_NICHT" }
        assertNull(AlarmDraftSaver.restore(renamed))
    }

    @Test
    fun saverKeepsNullAsClosedEditor() {
        val scope = SaverScope { true }
        with(AlarmDraftSaver.saver) {
            assertNull(scope.save(null))
            val saved = scope.save(full)
            assertNotNull(saved)
            assertEquals(full, restore(saved!!))
        }
    }

    /**
     * Offener Editor im ViewModel ([AlarmEditorState] über [SavedStateHandle]): übersteht Prozess-Ende samt Eingaben;
     * zu = nichts gespeichert. Drehen mit offener Rückfrage auf dem Gerät:
     * `AlarmEditorRecreateTest`.
     */
    @Test
    fun openEditorSurvivesProcessDeath() {
        val handle = SavedStateHandle()
        AlarmEditorState(handle).draft = full
        // Neuer Prozess: nur die gespeicherten Werte bleiben
        fun newProcess(from: SavedStateHandle) = SavedStateHandle(from.keys().associateWith { from.get<Any>(it) })
        val restoredHandle = newProcess(handle)
        val restored = AlarmEditorState(restoredHandle)
        assertEquals(full, restored.draft)
        restored.close()
        assertNull("zu: nichts gespeichert", AlarmEditorState(newProcess(restoredHandle)).draft)
    }
}
