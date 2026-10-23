package com.cryptochecker.app.ui.features.alarms

import androidx.compose.runtime.saveable.SaverScope
import com.cryptochecker.app.data.local.model.AlarmCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
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

    @Test
    fun editorStateIsSaveableAndSaveButtonPinned() {
        val dir = findUp(listOf("", "app/", "android/app/").map { it + "src/main/java/com/cryptochecker/app/ui/features/alarms" })
        assertNotNull("alarms-Quellen nicht gefunden (Arbeitsverzeichnis?)", dir)
        val screen = File(dir, "AlarmsScreen.kt").readText()
        // ARC5-R01: offener Editor im ViewModel (SavedStateHandle), gleiche Liste wie AlarmDraftSaver.saver
        assertTrue(screen.contains("var draft by viewModel.editor"))
        assertFalse(screen.contains("var draft by rememberSaveable"))
        val state = File(dir, "AlarmEditorState.kt").readText()
        assertTrue(state.contains("handle[KEY] = value?.let { ArrayList(AlarmDraftSaver.save(it)) }"))
        assertTrue(state.contains("handle.get<List<*>>(KEY)?.let(AlarmDraftSaver::restore)"))
        assertTrue(File(dir, "AlarmsViewModel.kt").readText().contains("AlarmEditorState(savedStateHandle)"))
        assertTrue(screen.contains("var confirmFiresNow by rememberSaveable(stateSaver = AlarmDraftSaver.saver)"))
        val sheet = File(dir!!, "AlarmEditorSheet.kt").readText()
        assertFalse("Editor-Zustand mit remember statt rememberSaveable", Regex("""by remember \{""").containsMatchIn(sheet))
        // Knöpfe ausserhalb des scrollenden Inhalts, das Blatt endet über der Tastatur
        assertTrue(sheet.contains(".imePadding()"))
        val scroll = sheet.indexOf(".verticalScroll(")
        val pinned = sheet.indexOf("// Fest am unteren Rand")
        val save = sheet.indexOf("UiTestTags.ALARM_SAVE")
        assertTrue(scroll in 0 until pinned && pinned < save)
    }

    /** Sucht [candidates] vom Arbeitsverzeichnis aus aufwärts. */
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
