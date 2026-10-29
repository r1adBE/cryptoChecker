package com.cryptochecker.app.ui.components

import com.cryptochecker.app.ui.components.NotificationPrePrompt.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Erklärung vor der Mitteilungs-Abfrage: höchstens einmal bei neuen Paaren, nach «Später» noch einmal beim ersten
 * Alarm — wie `NotificationPrePromptTests.swift`. Auch wenn mehrere Bildschirme fragen oder die Erklärung ohne
 * Antwort verschwindet (Zurück, Navigation, Prozess beendet).
 */
class NotificationPrePromptTest {

    private var stage = NotificationPrePrompt.STAGE_NEW
    private val slot = PrePromptSlot(readStage = { stage }, writeStage = { stage = it })

    @Test
    fun ruleTable() {
        val all = listOf(
            NotificationPrePrompt.STAGE_NEW, NotificationPrePrompt.STAGE_LATER_ONCE,
            NotificationPrePrompt.STAGE_DONE, NotificationPrePrompt.STAGE_CONTINUED,
        )
        for (s in all) for (r in NotificationAsk.entries) {
            // Würde das System nicht fragen oder ausdrücklich: immer gleich die System-Abfrage (fragt dann nicht)
            assertEquals(Action.SYSTEM, NotificationPrePrompt.action(notDetermined = false, stage = s, reason = r))
        }
        for (s in all) assertEquals(Action.SYSTEM, NotificationPrePrompt.action(true, s, NotificationAsk.DIRECT))
        val ask = { s: Int, r: NotificationAsk -> NotificationPrePrompt.action(true, s, r) }
        assertEquals(Action.EXPLAIN, ask(NotificationPrePrompt.STAGE_NEW, NotificationAsk.PAIRS_ADDED))
        assertEquals(Action.EXPLAIN, ask(NotificationPrePrompt.STAGE_NEW, NotificationAsk.ALARM_CREATED))
        assertEquals(Action.NOTHING, ask(NotificationPrePrompt.STAGE_LATER_ONCE, NotificationAsk.PAIRS_ADDED))
        assertEquals(Action.EXPLAIN, ask(NotificationPrePrompt.STAGE_LATER_ONCE, NotificationAsk.ALARM_CREATED))
        assertEquals(Action.NOTHING, ask(NotificationPrePrompt.STAGE_DONE, NotificationAsk.PAIRS_ADDED))
        assertEquals(Action.NOTHING, ask(NotificationPrePrompt.STAGE_DONE, NotificationAsk.ALARM_CREATED))
        assertEquals(Action.SYSTEM, ask(NotificationPrePrompt.STAGE_CONTINUED, NotificationAsk.PAIRS_ADDED))
    }

    @Test
    fun pairsAddedExplainsOnceEvenWithoutAnswer() {
        assertEquals(Action.EXPLAIN, slot.request(true, NotificationAsk.PAIRS_ADDED))
        // Schon beim Zeigen weiter: verschwindet sie ohne Antwort (Prozess beendet), zählt das als «Später»
        assertEquals(NotificationPrePrompt.STAGE_LATER_ONCE, stage)
        val afterRestart = PrePromptSlot(readStage = { stage }, writeStage = { stage = it })
        assertEquals(Action.NOTHING, afterRestart.request(true, NotificationAsk.PAIRS_ADDED))
        assertFalse(afterRestart.showing.value)
    }

    @Test
    fun secondRequestWhileShowingDoesNothing() {
        assertEquals(Action.EXPLAIN, slot.request(true, NotificationAsk.PAIRS_ADDED))
        // Ein anderer Bildschirm (Explorer, Merkliste, Alarm) fragt, während sie steht: keine zweite, keine Stufe
        assertEquals(Action.NOTHING, slot.request(true, NotificationAsk.PAIRS_ADDED))
        assertEquals(Action.NOTHING, slot.request(true, NotificationAsk.ALARM_CREATED))
        assertEquals(NotificationPrePrompt.STAGE_LATER_ONCE, stage)
        assertTrue(slot.showing.value)
    }

    @Test
    fun laterThenFirstAlarmOnceMoreThenNever() {
        slot.request(true, NotificationAsk.PAIRS_ADDED)
        assertTrue(slot.answer(proceed = false))
        // Zurück-Taste nach der Antwort: zählt nicht doppelt
        assertFalse(slot.answer(proceed = false))
        assertEquals(Action.NOTHING, slot.request(true, NotificationAsk.PAIRS_ADDED))
        assertEquals(Action.EXPLAIN, slot.request(true, NotificationAsk.ALARM_CREATED))
        assertEquals(NotificationPrePrompt.STAGE_DONE, stage)
        slot.answer(proceed = false)
        assertEquals(Action.NOTHING, slot.request(true, NotificationAsk.ALARM_CREATED))
        assertEquals(Action.NOTHING, slot.request(true, NotificationAsk.PAIRS_ADDED))
    }

    @Test
    fun continueAsksSystemFromThenOn() {
        slot.request(true, NotificationAsk.PAIRS_ADDED)
        assertTrue(slot.answer(proceed = true))
        assertEquals(NotificationPrePrompt.STAGE_CONTINUED, stage)
        assertFalse(slot.showing.value)
        assertEquals(Action.SYSTEM, slot.request(true, NotificationAsk.PAIRS_ADDED))
    }

    @Test
    fun noExplanationWhenSystemWouldNotAsk() {
        assertEquals(Action.SYSTEM, slot.request(false, NotificationAsk.PAIRS_ADDED))
        assertEquals(NotificationPrePrompt.STAGE_NEW, stage)
        assertFalse(slot.showing.value)
    }
}
