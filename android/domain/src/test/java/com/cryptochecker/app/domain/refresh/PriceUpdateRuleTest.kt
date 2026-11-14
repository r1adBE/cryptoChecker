package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.refresh.RefreshEffects.PriceUpdate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Kurs-Mitteilung nach einem neuen Kurs ([PriceUpdateRule]): «Nicht wegwischbar» an und die Mitteilung fehlt (Neustart,
 * App-Update, Wegwischen ab Android 14) → wieder zeigen, auch unter der Melde-Schwelle; sonst wie bisher.
 */
class PriceUpdateRuleTest {

    private fun decide(
        price: Double? = 102.0,
        reference: Double? = 100.0,
        threshold: Double = 5.0,
        ongoing: Boolean = true,
        shown: Boolean = true,
        notificationsOn: Boolean = true,
        pairOn: Boolean = true,
    ) = PriceUpdateRule.decide(notificationsOn, pairOn, price, reference, threshold, ongoing, shown)

    @Test
    fun ongoingAndMissingIsRestoredBelowTheThreshold() {
        assertEquals(PriceUpdate.RESTORE, decide(shown = false))
        // Noch da: bleibt
        assertEquals(PriceUpdate.NONE, decide(shown = true))
        // Nicht angeheftet: weggewischt bleibt weg, bis die Schwelle erreicht ist
        assertEquals(PriceUpdate.NONE, decide(ongoing = false, shown = false))
    }

    @Test
    fun thresholdReachedShowsWithNewReference() {
        assertEquals(PriceUpdate.SHOW, decide(price = 106.0, shown = false))
        assertEquals(PriceUpdate.SHOW, decide(price = 94.0))
        // Ohne Schwelle oder ohne Bezug: immer zeigen
        assertEquals(PriceUpdate.SHOW, decide(threshold = 0.0))
        assertEquals(PriceUpdate.SHOW, decide(reference = null, shown = false))
    }

    @Test
    fun offOrWithoutPriceCancels() {
        assertEquals(PriceUpdate.CANCEL, decide(notificationsOn = false, shown = false))
        assertEquals(PriceUpdate.CANCEL, decide(pairOn = false, shown = false))
        assertEquals(PriceUpdate.CANCEL, decide(price = null))
        assertEquals(PriceUpdate.CANCEL, decide(price = 0.0, shown = false))
    }
}
