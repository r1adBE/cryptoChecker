package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.domain.alarm.AlarmTemplates.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmTemplatesTest {

    @Test
    fun percentTemplatesMeasureFromTheCurrentPrice() {
        val up = AlarmTemplates.definition(Template.UP_1, 60_000.0)!!
        assertEquals(AlarmCondition.CHANGE_PERCENT_UP, up.condition)
        assertEquals(1.0, up.threshold, 1e-9)
        assertEquals(60_000.0, up.referencePrice!!, 1e-9)
        assertFalse(up.repeating)

        val down = AlarmTemplates.definition(Template.DOWN_5, 2.5)!!
        assertEquals(AlarmCondition.CHANGE_PERCENT_DOWN, down.condition)
        assertEquals(5.0, down.threshold, 1e-9)
        assertEquals(2.5, down.referencePrice!!, 1e-9)

        assertEquals(5.0, AlarmTemplates.definition(Template.UP_5, 1.0)!!.threshold, 1e-9)
        assertEquals(AlarmCondition.CHANGE_PERCENT_DOWN, AlarmTemplates.definition(Template.DOWN_1, 1.0)!!.condition)
    }

    @Test
    fun percentTemplatesNeedAValidPrice() {
        assertNull(AlarmTemplates.definition(Template.UP_1, null))
        assertNull(AlarmTemplates.definition(Template.UP_1, 0.0))
        assertNull(AlarmTemplates.definition(Template.DOWN_1, -3.0))
        assertNull(AlarmTemplates.definition(Template.DOWN_5, Double.NaN))
        assertNull(AlarmTemplates.definition(Template.UP_5, Double.POSITIVE_INFINITY))
    }

    @Test
    fun newExtremeTemplatesUseNearExtremeWithDistanceZero() {
        val high = AlarmTemplates.definition(Template.NEW_HIGH_30, null)!!
        assertEquals(AlarmCondition.NEAR_HIGH, high.condition)
        assertTrue(NearExtreme.isNewOnly(high.threshold))
        assertEquals(30, high.windowHours)
        assertNull(high.referencePrice)

        val low = AlarmTemplates.definition(Template.NEW_LOW_30, 100.0)!!
        assertEquals(AlarmCondition.NEAR_LOW, low.condition)
        assertTrue(NearExtreme.isNewOnly(low.threshold))
        assertEquals(30, NearExtreme.windowDays(low.windowHours))
        assertNull(low.referencePrice)
    }

    @Test
    fun volumeTemplateUsesFactorThree() {
        val v = AlarmTemplates.definition(Template.VOLUME_X3, 5.0)!!
        assertEquals(AlarmCondition.VOLUME_SPIKE, v.condition)
        assertEquals(3.0, v.threshold, 1e-9)
        assertNull(v.referencePrice)
    }

    @Test
    fun availabilityHidesTemplatesWithoutData() {
        assertEquals(Template.entries.toList(), AlarmTemplates.available(hasPrice = true, hasDailyRange = true, hasHourlyVolume = true))
        // DEX-Paar ohne Kerzen: nur die Prozent-Vorlagen
        assertEquals(
            listOf(Template.UP_1, Template.UP_5, Template.DOWN_1, Template.DOWN_5),
            AlarmTemplates.available(hasPrice = true, hasDailyRange = false, hasHourlyVolume = false),
        )
        // Noch kein Kurs, aber Kerzen
        assertEquals(
            listOf(Template.NEW_HIGH_30, Template.NEW_LOW_30, Template.VOLUME_X3),
            AlarmTemplates.available(hasPrice = false, hasDailyRange = true, hasHourlyVolume = true),
        )
        assertEquals(listOf(Template.VOLUME_X3), AlarmTemplates.available(false, false, true))
        assertTrue(AlarmTemplates.available(false, false, false).isEmpty())
    }

    @Test
    fun templateOrderAndPercentSigns() {
        assertEquals(
            listOf("UP_1", "UP_5", "DOWN_1", "DOWN_5", "NEW_HIGH_30", "NEW_LOW_30", "VOLUME_X3"),
            Template.entries.map { it.name },
        )
        assertEquals(1.0, Template.UP_1.percent!!, 1e-9)
        assertEquals(-5.0, Template.DOWN_5.percent!!, 1e-9)
        assertNull(Template.VOLUME_X3.percent)
    }

    @Test
    fun suggestedThresholdRoundsToThreeSignificantDigits() {
        assertEquals("63400", AlarmTemplates.suggestedThresholdText(63_412.57))
        assertEquals("1.23", AlarmTemplates.suggestedThresholdText(1.2345))
        assertEquals("1,23", AlarmTemplates.suggestedThresholdText(1.2345, ','))
        assertEquals("0.000123", AlarmTemplates.suggestedThresholdText(0.00012345))
        assertEquals("187", AlarmTemplates.suggestedThresholdText(187.34))
        assertEquals("1", AlarmTemplates.suggestedThresholdText(1.0002))
        assertEquals("", AlarmTemplates.suggestedThresholdText(null))
        assertEquals("", AlarmTemplates.suggestedThresholdText(0.0))
        assertEquals("", AlarmTemplates.suggestedThresholdText(Double.NaN))
    }

    @Test
    fun suggestedThresholdReadsBackThroughTheParser() {
        for (price in listOf(63_412.57, 1.2345, 0.123456, 0.00012345, 187.34, 2_345.6, 98_765_432.1)) {
            for (sep in listOf('.', ',')) {
                val text = AlarmTemplates.suggestedThresholdText(price, sep)
                val parsed = ThresholdParser.parse(text, sep, priceHint = price)!!
                assertEquals(text.replace(',', '.').toDouble(), parsed, 1e-12)
                // Ohne Kurs-Hinweis ebenso (Dezimalzeichen der Region)
                assertEquals(parsed, ThresholdParser.parse(text, sep)!!, 1e-12)
            }
        }
    }

    @Test
    fun advancedOpensForEverythingButPlainPriceAlarms() {
        assertFalse(AlarmTemplates.opensAdvanced(AlarmCondition.PRICE_ABOVE, null))
        assertFalse(AlarmTemplates.opensAdvanced(AlarmCondition.PRICE_BELOW, ""))
        assertTrue(AlarmTemplates.opensAdvanced(AlarmCondition.PRICE_ABOVE, "CHF"))
        AlarmCondition.entries.filter { !it.isPriceThreshold }.forEach {
            assertTrue(AlarmTemplates.opensAdvanced(it, null))
        }
    }
}
