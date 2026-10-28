package com.cryptochecker.app.ui.features.alarms

import androidx.lifecycle.SavedStateHandle
import com.cryptochecker.app.data.local.model.AlarmCondition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus AlarmCreateGuardTest

/** Doppeltipp beim Anlegen eines Alarms: nur ein INSERT. */
class AlarmCreateGuardTest {

    @Test
    fun `zweiter Tipp waehrend das Anlegen laeuft wird ignoriert`() = runBlocking {
        val guard = AlarmCreateGuard()
        val release = CompletableDeferred<Unit>()
        var inserts = 0
        val first = guard.launch(this, guarded = true) { release.await(); inserts++ }
        val second = guard.launch(this, guarded = true) { inserts++ }
        assertNotNull(first)
        assertNull(second)
        release.complete(Unit)
        first!!.join()
        assertEquals(1, inserts)
        assertFalse(guard.busy)
        // Danach geht ein neues Anlegen wieder
        guard.launch(this, guarded = true) { inserts++ }!!.join()
        assertEquals(2, inserts)
    }

    /** Der Alarm-Editor sperrt auch beim Bearbeiten (IOS6-01, wie iOS `AlarmCreateGuard`): kein ungesperrter Start mehr. */
    @Test
    fun `Alarm-Editor sperrt auch beim Bearbeiten`() {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        var source: java.io.File? = null
        while (dir != null && source == null) {
            source = listOf("", "app/", "android/app/")
                .map { java.io.File(dir, it + "src/main/java/com/cryptochecker/app/ui/features/alarms/AlarmsViewModel.kt") }
                .firstOrNull { it.isFile }
            dir = dir.parentFile
        }
        assertNotNull("AlarmsViewModel.kt nicht gefunden (Arbeitsverzeichnis?)", source)
        val text = source!!.readText()
        val save = text.substringAfter("fun save(draft: AlarmDraft) {").substringBefore("\n    fun ")
        assertTrue(save.contains("createGuard.launch(viewModelScope, guarded = true)"))
        assertFalse(text.contains("guarded = draft.id == 0L"))
    }

    @Test
    fun `ungesperrter Start laeuft neben einem Anlegen`() = runBlocking {
        val guard = AlarmCreateGuard()
        val release = CompletableDeferred<Unit>()
        var saves = 0
        val creating = guard.launch(this, guarded = true) { release.await() }
        val edit = guard.launch(this, guarded = false) { saves++ }
        assertNotNull(edit)
        edit!!.join()
        assertEquals(1, saves)
        release.complete(Unit)
        creating!!.join()
    }

    @Test
    fun `Abbruch gibt den Waechter frei`() = runBlocking {
        val scope = CoroutineScope(Job())
        val guard = AlarmCreateGuard()
        val job = guard.launch(scope, guarded = true) { CompletableDeferred<Unit>().await() }
        yield()
        scope.cancel()
        job!!.join()
        assertFalse(guard.busy)
    }
}

// ---- aus AlarmDraftEnterAmountTest

/**
 * Hinweis «Betrag eingeben» unter dem gesperrten Knopf ([AlarmDraft.showsEnterAmountHint]): nur solange das Feld eines
 * neuen Alarms leer ist — nicht zusätzlich zum roten Fehlertext und nicht bei gültigem Wert. Wie iOS.
 */
class AlarmDraftEnterAmountTest {

    @Test
    fun `new alert with empty field shows the hint`() {
        val draft = AlarmDraft.blankFor('.')
        assertTrue(draft.showsEnterAmountHint)
        // Der graue Platzhalter (aktueller Kurs) ist kein Wert: Knopf bleibt gesperrt, Hinweis bleibt
        assertTrue(draft.copy(priceHint = 65_000.0).showsEnterAmountHint)
        assertFalse(draft.isValid)
    }

    @Test
    fun `typed value or visible error hides the hint`() {
        val blank = AlarmDraft.blankFor('.')
        assertFalse(blank.copy(thresholdText = "70000", blankStart = false).showsEnterAmountHint)
        // Gelöscht nach der ersten Eingabe: der Fehlertext unter dem Feld sagt es schon
        assertFalse(blank.copy(thresholdText = "", blankStart = false).showsEnterAmountHint)
        // Keine Zahl: eigener Fehlertext
        assertFalse(blank.copy(thresholdText = "abc", blankStart = false).showsEnterAmountHint)
    }

    @Test
    fun `editing an existing alert never shows the hint`() {
        val existing = AlarmDraft(id = 7, condition = AlarmCondition.PRICE_ABOVE, thresholdText = "70000", decimalSeparator = '.')
        assertFalse(existing.showsEnterAmountHint)
    }

    @Test
    fun `sentence card and hint never both`() {
        val blank = AlarmDraft.blankFor('.')
        assertFalse("leeres Feld: nur der Hinweis", blank.showsSentence)
        assertTrue(blank.copy(thresholdText = "70000", blankStart = false).showsSentence)
        // Fehler sichtbar: Karte bleibt (der Hinweis fehlt dann)
        assertTrue(blank.copy(thresholdText = "", blankStart = false).showsSentence)
        listOf(blank, blank.copy(thresholdText = "1", blankStart = false), blank.copy(thresholdText = "x", blankStart = false))
            .forEach { assertTrue(it.showsSentence != it.showsEnterAmountHint) }
    }
}

// ---- aus AlarmEditorStateTest

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

// ---- aus AlarmUndoOfferTest

/**
 * «Rückgängig» nach einem Schnell-Alarm ([AlarmUndoOffer]): bleibt offen, bis es gezeigt und erledigt ist — auch über
 * «Prüfen» im Dialog nach dem allerersten Alarm hinweg —, aber nicht ewig ([PendingAlarmUndo.isFresh]).
 */
class AlarmUndoOfferTest {

    @Test
    fun staysUntilDone() {
        val offer = AlarmUndoOffer()
        val undo = PendingAlarmUndo(alarmId = 5, text = "Alarm gespeichert", createdAt = 1_000)
        offer.offer(undo)
        // Bildschirm verlassen und zurück: das Angebot ist noch da
        assertEquals(undo, offer.pending.value)
        offer.done(5)
        assertNull(offer.pending.value)
    }

    @Test
    fun doneOfOlderOfferKeepsNewerOne() {
        val offer = AlarmUndoOffer()
        offer.offer(PendingAlarmUndo(5, "a", 1_000))
        val newer = PendingAlarmUndo(6, "b", 2_000)
        offer.offer(newer)
        offer.done(5)
        assertEquals(newer, offer.pending.value)
    }

    @Test
    fun freshForTenMinutesOnly() {
        val undo = PendingAlarmUndo(5, "a", createdAt = 1_000)
        assertTrue(undo.isFresh(1_000))
        assertTrue(undo.isFresh(1_000 + PendingAlarmUndo.MAX_AGE_MILLIS))
        assertFalse(undo.isFresh(1_001 + PendingAlarmUndo.MAX_AGE_MILLIS))
        // Uhr zurückgestellt: nicht mehr anbieten
        assertFalse(undo.isFresh(999))
    }
}

// ---- aus AlarmUserChangeTest

/**
 * [AlarmUserChange]: Ausschalten, Bearbeiten und Löschen entfernen die gemeldete Mitteilung — ihr «1 h später» schaltete den
 * Alarm sonst wieder ein, obwohl der Nutzer ihn eben ausgeschaltet hat.
 */
class AlarmUserChangeTest {

    @Test
    fun disablingAndEditingRemoveTheNotification() {
        assertTrue(AlarmUserChange.cancelsNotification(enabled = false, storedKept = false))
        // Bearbeitet (Bedingung, Ton …)
        assertTrue(AlarmUserChange.cancelsNotification(enabled = null, storedKept = false))
        // Einschalten lässt eine Mitteilung stehen
        assertFalse(AlarmUserChange.cancelsNotification(enabled = true, storedKept = false))
        // Merkliste nur leer im Arbeitsspeicher: Die Id kann die eines gespeicherten Alarms sein
        assertFalse(AlarmUserChange.cancelsNotification(enabled = false, storedKept = true))
    }

    @Test
    fun deletingRemovesNotificationAndLogUnlessOnlyInMemory() {
        assertEquals(AlarmUserChange.Cleanup(cancelNotification = true, removeLog = true), AlarmUserChange.afterDelete(false))
        assertEquals(AlarmUserChange.Cleanup(cancelNotification = false, removeLog = false), AlarmUserChange.afterDelete(true))
    }
}
