package com.cryptochecker.app.ui.features.alarms

import com.cryptochecker.app.data.local.model.AlarmCondition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
