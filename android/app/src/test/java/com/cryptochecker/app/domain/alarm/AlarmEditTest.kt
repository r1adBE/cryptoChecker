package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.ui.features.alarms.AlarmDraft
import com.cryptochecker.app.ui.features.alarms.ThresholdProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spiegel: ios/Tests/AlarmEditTests.swift. */
class AlarmEditTest {

    // MARK: firesNow

    @Test
    fun firesNowComparesLikeTheEvaluator() {
        assertTrue(AlarmEdit.firesNow(AlarmCondition.PRICE_ABOVE, 60_000.0, 60_000.0))
        assertTrue(AlarmEdit.firesNow(AlarmCondition.PRICE_ABOVE, 60_000.0, 61_000.0))
        assertFalse(AlarmEdit.firesNow(AlarmCondition.PRICE_ABOVE, 60_000.0, 59_999.0))
        assertTrue(AlarmEdit.firesNow(AlarmCondition.PRICE_BELOW, 60_000.0, 60_000.0))
        assertTrue(AlarmEdit.firesNow(AlarmCondition.PRICE_BELOW, 60_000.0, 59_000.0))
        assertFalse(AlarmEdit.firesNow(AlarmCondition.PRICE_BELOW, 60_000.0, 60_001.0))
    }

    @Test
    fun firesNowNeedsPriceAndPriceCondition() {
        assertFalse(AlarmEdit.firesNow(AlarmCondition.PRICE_ABOVE, 60_000.0, null))
        assertFalse(AlarmEdit.firesNow(AlarmCondition.PRICE_ABOVE, 60_000.0, Double.NaN))
        assertFalse(AlarmEdit.firesNow(AlarmCondition.PRICE_BELOW, 60_000.0, 0.0))
        assertFalse(AlarmEdit.firesNow(AlarmCondition.PRICE_BELOW, null, 1.0))
        assertFalse(AlarmEdit.firesNow(AlarmCondition.CHANGE_PERCENT_UP, 1.0, 60_000.0))
        assertFalse(AlarmEdit.firesNow(AlarmCondition.FUNDING_ABOVE, 0.01, 60_000.0))
    }

    // MARK: Speichern: neu scharf oder Zustand behalten

    /** Ausgelöster einmaliger Kursalarm (aus, gemeldet). */
    private val triggered = AlarmEntity(
        id = 7, watchId = 1, condition = AlarmCondition.PRICE_ABOVE, threshold = 60_000.0,
        enabled = false, repeating = false, sound = true, vibrate = true, speak = false,
        referencePrice = null, lastTriggeredAt = 1_000L, lastTriggeredPrice = 60_100.0,
        windowHours = 4, referenceAt = 1_000L, currency = null,
    )

    /** So speichert der Editor: Eingaben + Zustand «neu scharf». */
    private fun edited(from: AlarmEntity) = from.copy(
        enabled = true, lastTriggeredAt = 0L, lastTriggeredPrice = null, referenceAt = 0L,
    )

    @Test
    fun newAlarmIsSavedAsEdited() {
        val new = edited(triggered).copy(id = 0)
        assertTrue(AlarmEdit.rearms(null, new))
        assertSame(new, AlarmEdit.merged(null, new))
    }

    @Test
    fun soundVibrationSpeechKeepTheTriggeredState() {
        val input = edited(triggered).copy(sound = false, vibrate = false, speak = true)
        assertFalse(AlarmEdit.rearms(triggered, input))
        val saved = AlarmEdit.merged(triggered, input)
        assertFalse(saved.enabled)
        assertEquals(1_000L, saved.lastTriggeredAt)
        assertEquals(60_100.0, saved.lastTriggeredPrice!!, 0.0)
        assertEquals(1_000L, saved.referenceAt)
        assertFalse(saved.sound)
        assertFalse(saved.vibrate)
        assertTrue(saved.speak)
    }

    @Test
    fun repeatingAlarmKeepsReportedStateWhenSwitchedToOnce() {
        val reported = triggered.copy(enabled = true, repeating = true)
        val input = edited(reported).copy(repeating = false)
        val saved = AlarmEdit.merged(reported, input)
        assertTrue(saved.enabled)
        assertFalse(saved.repeating)
        assertEquals(1_000L, saved.referenceAt)
        assertEquals(1_000L, saved.lastTriggeredAt)
    }

    @Test
    fun switchingTriggeredOnceAlarmToEveryTimeReEnablesIt() {
        val input = edited(triggered).copy(repeating = true)
        assertTrue(AlarmEdit.reenables(triggered, input))
        assertTrue(AlarmEdit.rearms(triggered, input))
        val saved = AlarmEdit.merged(triggered, input)
        assertTrue(saved.enabled)
        assertTrue(saved.repeating)
        assertEquals(0L, saved.referenceAt) // scharf wie mit dem Schalter
        assertEquals(1_000L, saved.lastTriggeredAt) // «zuletzt …» bleibt
    }

    @Test
    fun switchedOffNeverTriggeredAlarmStaysOffOnEveryTime() {
        val off = triggered.copy(lastTriggeredAt = 0L, lastTriggeredPrice = null, referenceAt = 0L)
        val input = edited(off).copy(repeating = true)
        assertFalse(AlarmEdit.reenables(off, input))
        assertFalse(AlarmEdit.merged(off, input).enabled)
    }

    @Test
    fun conditionThresholdCurrencyPairOrWindowReArm() {
        val base = edited(triggered)
        for (input in listOf(
            base.copy(condition = AlarmCondition.PRICE_BELOW),
            base.copy(threshold = 61_000.0),
            base.copy(currency = "CHF"),
            base.copy(watchId = 2),
        )) {
            assertTrue(AlarmEdit.conditionChanged(triggered, input))
            assertSame(input, AlarmEdit.merged(triggered, input))
        }
        // Fenster zählt nur, wo es zur Bedingung gehört
        assertFalse(AlarmEdit.conditionChanged(triggered, base.copy(windowHours = 1)))
        assertEquals(4, AlarmEdit.merged(triggered, base.copy(windowHours = 1)).windowHours)
        val move = triggered.copy(condition = AlarmCondition.MOVE_PERCENT_WINDOW, threshold = 3.0)
        assertTrue(AlarmEdit.conditionChanged(move, move.copy(windowHours = 12)))
        // Währung: Gross-/Kleinschreibung und Leer egal
        val chf = triggered.copy(currency = "CHF")
        assertFalse(AlarmEdit.conditionChanged(chf, chf.copy(currency = "chf")))
        assertFalse(AlarmEdit.conditionChanged(triggered, triggered.copy(currency = " ")))
        // Gleiche Schwelle, minimal anders zurückgelesen
        assertFalse(AlarmEdit.conditionChanged(triggered, triggered.copy(threshold = 60_000.0 * (1 + 1e-12))))
    }

    @Test
    fun percentAlarmKeepsReferenceWhenOnlyOptionsChange() {
        val percent = triggered.copy(
            condition = AlarmCondition.CHANGE_PERCENT_UP, threshold = 5.0, enabled = true, repeating = true,
            referencePrice = 58_000.0, referenceAt = 0L,
        )
        val saved = AlarmEdit.merged(percent, edited(percent).copy(referencePrice = 63_000.0, speak = true))
        assertEquals(58_000.0, saved.referencePrice!!, 0.0)
        assertTrue(saved.speak)
    }

    // MARK: Vorschlag im Editor folgt der Richtung

    @Test
    fun untouchedSuggestionFollowsDirection() {
        var draft = AlarmDraft.newFor(63_412.57, '.').copy(priceHint = 63_412.57)
        assertEquals("66600", draft.thresholdText)
        assertTrue(draft.isUntouchedSuggestion)
        draft = draft.withCondition(AlarmCondition.PRICE_BELOW)
        assertEquals("60200", draft.thresholdText)
        draft = draft.withCondition(AlarmCondition.PRICE_ABOVE)
        assertEquals("66600", draft.thresholdText)
    }

    @Test
    fun typedAmountStaysOnDirectionSwitch() {
        var draft = AlarmDraft.newFor(63_412.57, '.').copy(priceHint = 63_412.57)
        draft = draft.copy(thresholdText = "65000")
        assertFalse(draft.isUntouchedSuggestion)
        draft = draft.withCondition(AlarmCondition.PRICE_BELOW)
        assertEquals("65000", draft.thresholdText)
        // Bestehender Alarm: kein Vorschlag
        val existing = AlarmDraft.from(triggered).copy(priceHint = 63_412.57)
        assertNull(existing.suggestedText)
        assertEquals(existing.thresholdText, existing.withCondition(AlarmCondition.PRICE_BELOW).thresholdText)
    }

    @Test
    fun suggestionWithoutPriceIsKept() {
        var draft = AlarmDraft.newFor(63_412.57, '.')
        draft = draft.withCondition(AlarmCondition.PRICE_BELOW)
        assertEquals("66600", draft.thresholdText)
    }

    // MARK: Fehlertext unter dem Feld (warum «Speichern» gesperrt ist)

    @Test
    fun thresholdProblemFollowsValidation() {
        val price = AlarmDraft(condition = AlarmCondition.PRICE_ABOVE, decimalSeparator = '.')
        assertNull(price.copy(thresholdText = "65000").thresholdProblem)
        assertEquals(ThresholdProblem.EMPTY, price.copy(thresholdText = "  ").thresholdProblem)
        assertEquals(ThresholdProblem.INVALID, price.copy(thresholdText = "abc").thresholdProblem)
        assertEquals(ThresholdProblem.INVALID, price.copy(thresholdText = "0").thresholdProblem)
        val funding = AlarmDraft(condition = AlarmCondition.FUNDING_ABOVE, decimalSeparator = '.')
        assertNull(funding.copy(thresholdText = "-0.01").thresholdProblem)
        assertEquals(ThresholdProblem.FUNDING_RANGE, funding.copy(thresholdText = "12").thresholdProblem)
        assertEquals(ThresholdProblem.FUNDING_RANGE, funding.copy(thresholdText = "x").thresholdProblem)
        val oi = AlarmDraft(condition = AlarmCondition.OI_UP, decimalSeparator = '.')
        assertNull(oi.copy(thresholdText = "10").thresholdProblem)
        assertEquals(ThresholdProblem.OI_RANGE, oi.copy(thresholdText = "2000").thresholdProblem)
        assertEquals(ThresholdProblem.INVALID, oi.copy(thresholdText = "0").thresholdProblem)
        // Jeder Fehler sperrt, kein Fehler gibt frei
        for (d in listOf(price, funding, oi)) for (t in listOf("", "1", "abc", "5000")) {
            val draft = d.copy(thresholdText = t)
            assertEquals(draft.isValid, draft.thresholdProblem == null)
        }
    }

    // MARK: «Nicht geprüft seit …»

    @Test
    fun uncheckedKeepsFirstTimeAndLatestReason() {
        var map = emptyMap<Long, AlarmCheckStatus.Unchecked>()
        map = AlarmCheckStatus.unchecked(map, 1, AlarmCheckStatus.Reason.NO_RATE, 100)
        map = AlarmCheckStatus.unchecked(map, 1, AlarmCheckStatus.Reason.NO_CANDLES, 200)
        assertEquals(AlarmCheckStatus.Unchecked(AlarmCheckStatus.Reason.NO_CANDLES, 100), map[1])
        map = AlarmCheckStatus.checked(map, 1)
        assertNull(map[1])
        assertSame(map, AlarmCheckStatus.checked(map, 1))
    }

    @Test
    fun checkStatusStoreMarksAndClears() {
        val store = AlarmCheckStatus()
        store.markUnchecked(5, AlarmCheckStatus.Reason.NO_RATE, 10)
        store.markUnchecked(6, AlarmCheckStatus.Reason.NO_CANDLES, 20)
        store.markChecked(5)
        assertNull(store.state.value[5])
        assertEquals(AlarmCheckStatus.Reason.NO_CANDLES, store.state.value[6]?.reason)
    }

    // MARK: Bezug neuer Prozentalarme nur aus frischem Kurs

    @Test
    fun freshReferenceOnlyFromFreshPrice() {
        val now = 1_700_000_000_000L
        val stale = 45 * 60_000L
        assertEquals(100.0, AlarmEdit.freshReference(100.0, now - 60_000L, now, stale)!!, 0.0)
        assertEquals(100.0, AlarmEdit.freshReference(100.0, now - stale, now, stale)!!, 0.0)
        assertNull(AlarmEdit.freshReference(100.0, now - stale - 1, now, stale))
        assertNull(AlarmEdit.freshReference(100.0, 0L, now, stale))
        assertNull(AlarmEdit.freshReference(null, now, now, stale))
        assertNull(AlarmEdit.freshReference(0.0, now, now, stale))
    }

    // MARK: Neuer Alarm: leeres Feld, Kurs als Platzhalter

    @Test
    fun blankDraftStartsEmptyWithPricePlaceholder() {
        val draft = AlarmDraft.blankFor('.').copy(priceHint = 63_412.5734)
        assertEquals("", draft.thresholdText)
        assertEquals("63412.6", draft.pricePlaceholder)
        // Leer, aber nicht rot; Knopf bleibt gesperrt
        assertEquals(ThresholdProblem.EMPTY, draft.thresholdProblem)
        assertNull(draft.visibleProblem)
        assertFalse(draft.isValid)
        // Kein «löst sofort aus» ohne getippten Betrag
        assertFalse(AlarmEdit.firesNow(draft.condition, draft.threshold, draft.priceHint))
        // Nach dem ersten Tippen: Platzhalter weg, «leer» wieder ein Fehler
        val typed = draft.copy(thresholdText = "6", blankStart = false).copy(thresholdText = "")
        assertNull(typed.pricePlaceholder)
        assertEquals(ThresholdProblem.EMPTY, typed.visibleProblem)
        // Ohne Kurs kein Platzhalter; Komma als Dezimalzeichen
        assertNull(AlarmDraft.blankFor('.').pricePlaceholder)
        assertEquals("0,0000123457", AlarmDraft.blankFor(',').copy(priceHint = 0.00001234567).pricePlaceholder)
    }

    /** Eigene Alarmwährung nur, wenn die Quote umrechenbar ist — nie bei Fiat ohne Devisenkurs (NGN). Wie iOS. */
    @Test
    fun alarmCurrencyOfferedOnlyForConvertibleQuotes() {
        val fiat = listOf("CHF", "EUR", "USD", "JPY")
        assertTrue(AlarmEdit.offersCurrency(true, "USDT", "CHF", fiat))
        assertTrue(AlarmEdit.offersCurrency(true, "EUR", "CHF", fiat))
        assertTrue(AlarmEdit.offersCurrency(true, "BTC", "CHF", fiat))
        // Fiat ohne Devisenkurs: nie umrechenbar
        assertFalse(AlarmEdit.offersCurrency(true, "NGN", "CHF", fiat))
        assertFalse(AlarmEdit.offersCurrency(true, "ars", "USD", fiat))
        // Ungültig, leer, gleiche Währung oder keine Kursmarke
        assertFalse(AlarmEdit.offersCurrency(true, "B-T", "CHF", fiat))
        assertFalse(AlarmEdit.offersCurrency(true, "", "CHF", fiat))
        assertFalse(AlarmEdit.offersCurrency(true, "chf", "CHF", fiat))
        assertFalse(AlarmEdit.offersCurrency(false, "USDT", "CHF", fiat))
    }
}
