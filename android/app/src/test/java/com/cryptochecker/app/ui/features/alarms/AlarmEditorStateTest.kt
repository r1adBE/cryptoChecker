package com.cryptochecker.app.ui.features.alarms

import androidx.lifecycle.SavedStateHandle
import com.cryptochecker.app.data.local.model.AlarmCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Offener Alarm-Editor übersteht das Prozess-Ende (Review ARC6 C1/TST6-R1): Der Zustand des [SavedStateHandle] wird wie
 * beim Prozess-Ende als einfache Map in einen NEUEN Handle übernommen (das System legt ihn aus dem Bundle neu an). Ein
 * neuer [AlarmEditorState] muss denselben Entwurf zeigen; nach `close()` keinen.
 */
class AlarmEditorStateTest {

    private val full = AlarmDraft(
        id = 7L,
        condition = AlarmCondition.PRICE_ABOVE,
        thresholdText = "200000",
        repeating = true,
        sound = true,
        vibrate = false,
        speak = false,
        windowHours = 24,
        currency = "USD",
        priceHint = 101_234.5,
        decimalSeparator = '.',
        newExtremeOnly = false,
        suggestedText = "",
        blankStart = false,
    )

    /** Wie nach dem Prozess-Ende: nur die gespeicherten Werte, keine Objekte des alten Prozesses. */
    private fun afterProcessDeath(handle: SavedStateHandle) =
        SavedStateHandle(handle.keys().associateWith { key -> handle.get<Any?>(key) })

    @Test
    fun openDraftComesBackAfterProcessDeath() {
        val handle = SavedStateHandle()
        AlarmEditorState(handle).draft = full
        assertEquals(full, AlarmEditorState(afterProcessDeath(handle)).draft)
    }

    @Test
    fun closedEditorStaysClosedAfterProcessDeath() {
        val handle = SavedStateHandle()
        val editor = AlarmEditorState(handle)
        editor.draft = full
        editor.close()
        assertNull(editor.draft)
        assertNull(AlarmEditorState(afterProcessDeath(handle)).draft)
    }

    @Test
    fun freshHandleGivesNoDraft() {
        assertNull(AlarmEditorState(SavedStateHandle()).draft)
    }
}
