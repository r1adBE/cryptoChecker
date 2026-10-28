package com.cryptochecker.app.ui.features.alarms

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import com.cryptochecker.app.domain.alarm.NearExtreme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entwurf im Alarm-Editor ([AlarmDraft]): Wechsel zwischen Funding, Open Interest, Volumen und «Nahe am Hoch/Tief»
 * (Vorschlag und Zeitfenster), Vorzeichen umschalten, Währung umrechnen und die Formatierer im Begleitobjekt.
 * Ergänzt AlarmEditTest (Kursmarke «über» ↔ «unter»).
 */
class AlarmDraftConditionSwitchTest {

    private fun draft(
        condition: AlarmCondition,
        text: String,
        windowHours: Int = AlarmDraft.DEFAULT_WINDOW_HOURS,
        currency: String? = null,
    ) = AlarmDraft(
        condition = condition,
        thresholdText = text,
        windowHours = windowHours,
        currency = currency,
        decimalSeparator = '.',
    )

    // ---- Funding ----

    @Test
    fun switchToFundingSuggestsDefaultPercent() {
        val switched = draft(AlarmCondition.PRICE_ABOVE, "70000").withCondition(AlarmCondition.FUNDING_ABOVE)
        assertEquals(AlarmCondition.FUNDING_ABOVE, switched.condition)
        assertEquals("0.05", switched.thresholdText)
        assertEquals(AlarmDraft.DEFAULT_WINDOW_HOURS, switched.windowHours)
        assertTrue(switched.isValid)
    }

    @Test
    fun fundingToFundingKeepsTypedValue() {
        val switched = draft(AlarmCondition.FUNDING_ABOVE, "0.1").withCondition(AlarmCondition.FUNDING_BELOW)
        assertEquals(AlarmCondition.FUNDING_BELOW, switched.condition)
        assertEquals("0.1", switched.thresholdText)
    }

    @Test
    fun nearExtremeToFundingResetsWindowToHours() {
        val switched = draft(AlarmCondition.NEAR_HIGH, "2", windowHours = 30).withCondition(AlarmCondition.FUNDING_ABOVE)
        assertEquals(AlarmDraft.DEFAULT_WINDOW_HOURS, switched.windowHours)
        assertEquals("0.05", switched.thresholdText)
    }

    // ---- Open Interest ----

    @Test
    fun switchToOpenInterestSuggestsTenPercentAndSnapsWindow() {
        val four = draft(AlarmCondition.PRICE_ABOVE, "70000").withCondition(AlarmCondition.OI_UP)
        assertEquals(AlarmCondition.OI_UP, four.condition)
        assertEquals("10", four.thresholdText)
        assertEquals(4, four.windowHours)
        // Fenster auf 1, 4 oder 24 Stunden gerundet
        assertEquals(4, draft(AlarmCondition.PRICE_ABOVE, "1", windowHours = 2).withCondition(AlarmCondition.OI_UP).windowHours)
        assertEquals(24, draft(AlarmCondition.PRICE_ABOVE, "1", windowHours = 12).withCondition(AlarmCondition.OI_UP).windowHours)
        assertEquals(1, draft(AlarmCondition.PRICE_ABOVE, "1", windowHours = 1).withCondition(AlarmCondition.OI_DOWN).windowHours)
    }

    @Test
    fun openInterestToOpenInterestKeepsTypedValue() {
        val switched = draft(AlarmCondition.OI_UP, "25", windowHours = 24).withCondition(AlarmCondition.OI_DOWN)
        assertEquals("25", switched.thresholdText)
        assertEquals(24, switched.windowHours)
    }

    @Test
    fun nearExtremeToOpenInterestUsesDefaultOiWindow() {
        val switched = draft(AlarmCondition.NEAR_LOW, "2", windowHours = 30).withCondition(AlarmCondition.OI_UP)
        assertEquals(DerivativesAlarm.oiWindowHours(DerivativesAlarm.DEFAULT_OI_WINDOW_HOURS), switched.windowHours)
        assertEquals("10", switched.thresholdText)
    }

    @Test
    fun derivativesBackToPercentClearsValue() {
        assertEquals("", draft(AlarmCondition.OI_UP, "10").withCondition(AlarmCondition.CHANGE_PERCENT_UP).thresholdText)
        assertEquals("", draft(AlarmCondition.FUNDING_ABOVE, "0.05").withCondition(AlarmCondition.MOVE_PERCENT_WINDOW).thresholdText)
    }

    // ---- Volumen ----

    @Test
    fun switchToVolumeSpikeSuggestsDefaultFactor() {
        val switched = draft(AlarmCondition.CHANGE_PERCENT_UP, "5").withCondition(AlarmCondition.VOLUME_SPIKE)
        assertEquals(AlarmCondition.VOLUME_SPIKE, switched.condition)
        assertEquals(AlarmDraft.formatFactor(AlarmDraft.DEFAULT_VOLUME_FACTOR), switched.thresholdText)
        assertEquals("3", switched.thresholdText)
    }

    @Test
    fun volumeSpikeToPercentClearsFactor() {
        val switched = draft(AlarmCondition.VOLUME_SPIKE, "3").withCondition(AlarmCondition.CHANGE_PERCENT_DOWN)
        assertEquals(AlarmCondition.CHANGE_PERCENT_DOWN, switched.condition)
        assertEquals("", switched.thresholdText)
    }

    // ---- Nahe am Hoch/Tief ----

    @Test
    fun priceToNearExtremeSuggestsDistanceAndDays() {
        val switched = draft(AlarmCondition.PRICE_ABOVE, "70000").withCondition(AlarmCondition.NEAR_HIGH)
        assertEquals("2", switched.thresholdText)
        assertEquals(NearExtreme.DEFAULT_WINDOW_DAYS, switched.windowHours)
    }

    @Test
    fun percentToNearExtremeKeepsPercent() {
        val switched = draft(AlarmCondition.CHANGE_PERCENT_UP, "5").withCondition(AlarmCondition.NEAR_LOW)
        assertEquals("5", switched.thresholdText)
        assertEquals(NearExtreme.DEFAULT_WINDOW_DAYS, switched.windowHours)
    }

    @Test
    fun nearHighToNearLowKeepsDistanceAndDays() {
        val switched = draft(AlarmCondition.NEAR_HIGH, "3", windowHours = 90).withCondition(AlarmCondition.NEAR_LOW)
        assertEquals("3", switched.thresholdText)
        assertEquals(90, switched.windowHours)
    }

    @Test
    fun nearExtremeToPercentKeepsDistanceAndResetsWindow() {
        val switched = draft(AlarmCondition.NEAR_HIGH, "3", windowHours = 30).withCondition(AlarmCondition.CHANGE_PERCENT_UP)
        assertEquals("3", switched.thresholdText)
        assertEquals(AlarmDraft.DEFAULT_WINDOW_HOURS, switched.windowHours)
    }

    @Test
    fun newExtremeOnlyIsValidWithoutDistance() {
        val onlyNew = draft(AlarmCondition.NEAR_HIGH, "", windowHours = 30).copy(newExtremeOnly = true)
        assertEquals(NearExtreme.NEW_ONLY_DISTANCE, onlyNew.threshold!!, 0.0)
        assertTrue(onlyNew.isValid)
        assertNull(onlyNew.thresholdProblem)
    }

    // ---- Übrige Wechsel ----

    @Test
    fun sameConditionReturnsSameDraft() {
        val same = draft(AlarmCondition.CHANGE_PERCENT_UP, "5")
        assertSame(same, same.withCondition(AlarmCondition.CHANGE_PERCENT_UP))
    }

    @Test
    fun percentToPercentKeepsValue() {
        val switched = draft(AlarmCondition.CHANGE_PERCENT_UP, "5").withCondition(AlarmCondition.MOVE_PERCENT_WINDOW)
        assertEquals("5", switched.thresholdText)
    }

    @Test
    fun priceToPercentClearsPrice() {
        val switched = draft(AlarmCondition.PRICE_ABOVE, "70000").withCondition(AlarmCondition.CHANGE_PERCENT_UP)
        assertEquals("", switched.thresholdText)
        assertFalse(switched.isValid)
    }

    // ---- Vorzeichen ----

    @Test
    fun toggledSignFlipsMinusPlusAndUnicodeMinus() {
        assertEquals("-5", draft(AlarmCondition.FUNDING_ABOVE, "5").withToggledSign().thresholdText)
        assertEquals("5", draft(AlarmCondition.FUNDING_ABOVE, "-5").withToggledSign().thresholdText)
        assertEquals("5", draft(AlarmCondition.FUNDING_ABOVE, "− 5").withToggledSign().thresholdText)
        assertEquals("-5", draft(AlarmCondition.FUNDING_ABOVE, "+5").withToggledSign().thresholdText)
        assertEquals("-0.1", draft(AlarmCondition.FUNDING_ABOVE, " 0.1 ").withToggledSign().thresholdText)
    }

    // ---- Währung ----

    @Test
    fun currencyToConversionMultipliesWithRate() {
        val converted = draft(AlarmCondition.PRICE_ABOVE, "100").withCurrency("EUR", "EUR", 0.9)
        assertEquals("EUR", converted.currency)
        assertEquals("90", converted.thresholdText)
    }

    @Test
    fun conversionBackToQuoteDividesByRate() {
        val back = draft(AlarmCondition.PRICE_ABOVE, "90", currency = "EUR").withCurrency(null, "EUR", 0.9)
        assertNull(back.currency)
        assertEquals("100", back.thresholdText)
    }

    @Test
    fun currencyWithoutRateKeepsText() {
        val noRate = draft(AlarmCondition.PRICE_ABOVE, "100").withCurrency("EUR", "EUR", null)
        assertEquals("EUR", noRate.currency)
        assertEquals("100", noRate.thresholdText)
        val badRate = draft(AlarmCondition.PRICE_ABOVE, "100").withCurrency("EUR", "EUR", 0.0)
        assertEquals("100", badRate.thresholdText)
        // Leerer Wert: nichts umzurechnen
        assertEquals("", draft(AlarmCondition.PRICE_ABOVE, "").withCurrency("EUR", "EUR", 0.9).thresholdText)
    }

    @Test
    fun currencyUnrelatedToOtherKeepsText() {
        val other = draft(AlarmCondition.PRICE_ABOVE, "100").withCurrency("CHF", "EUR", 0.9)
        assertEquals("CHF", other.currency)
        assertEquals("100", other.thresholdText)
    }

    @Test
    fun sameCurrencyReturnsSameDraft() {
        val same = draft(AlarmCondition.PRICE_ABOVE, "100", currency = "EUR")
        assertSame(same, same.withCurrency("EUR", "EUR", 0.9))
    }

    // ---- Formatierer ----

    @Test
    fun formatConvertedRoundsToSixDigitsWithSeparator() {
        assertEquals("1234.57", AlarmDraft.formatConverted(1234.56789))
        assertEquals("1234,57", AlarmDraft.formatConverted(1234.56789, ','))
        assertEquals("90", AlarmDraft.formatConverted(90.0))
    }

    @Test
    fun formatThresholdWithoutExponentOrTrailingZeros() {
        assertEquals("0.05", AlarmDraft.formatThreshold(0.05, '.'))
        assertEquals("0,05", AlarmDraft.formatThreshold(0.05, ','))
        assertEquals("65000", AlarmDraft.formatThreshold(65_000.0, '.'))
        assertEquals("0.00000712", AlarmDraft.formatThreshold(0.00000712, '.'))
    }

    @Test
    fun formatFactorDropsZeroFraction() {
        assertEquals("3", AlarmDraft.formatFactor(3.0))
        assertEquals("2.5", AlarmDraft.formatFactor(2.5))
        assertEquals("10", AlarmDraft.formatFactor(10.0))
    }

    @Test
    fun fallingPercentFlagCoversChangeDownAndOiDown() {
        assertTrue(AlarmCondition.CHANGE_PERCENT_DOWN.isFallingPercent)
        assertTrue(AlarmCondition.OI_DOWN.isFallingPercent)
        assertFalse(AlarmCondition.CHANGE_PERCENT_UP.isFallingPercent)
        assertFalse(AlarmCondition.PRICE_BELOW.isFallingPercent)
    }
}
