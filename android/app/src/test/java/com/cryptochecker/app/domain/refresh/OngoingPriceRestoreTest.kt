package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.watch
import com.cryptochecker.marketdata.model.SimpleTicker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Nicht wegwischbar» nach einem Neustart: die Kurs-Mitteilungen sind weg, der Kurs bewegt sich unter der Melde-Schwelle
 * — der nächste Durchlauf zeigt sie wieder ([PriceUpdateRule], RESTORE), ohne den Bezug (Meldekurs) zu verschieben.
 * Ohne «Nicht wegwischbar» bleibt es beim bisherigen Verhalten.
 */
class OngoingPriceRestoreTest {

    private fun ticker(price: Double) = SimpleTicker().apply {
        last = price
        timestamp = System.currentTimeMillis()
    }

    private fun fixture(ongoing: Boolean): RefreshFixture = RefreshFixture(
        listOf(
            watch(id = 1, price = 100.0).copy(notificationEnabled = true, notifiedPrice = 100.0),
            watch(id = 2, price = 100.0).copy(notificationEnabled = true, notifiedPrice = 100.0),
        ),
    ).apply {
        settings.settings = settings.settings.copy(
            priceNotifications = true, notificationChangePercent = 5.0, ongoingNotifications = ongoing,
        )
        // Paar 1 nach dem Neustart weg, Paar 2 noch da
        notifier.priceVisible = { it != 1L }
    }

    private fun RefreshFixture.refresh() = runBlocking {
        lock.withLock { proof ->
            processor.process(
                proof, store.getWatches(), mapOf(1L to Fetched(ticker(102.0), null), 2L to Fetched(ticker(102.0), null)),
                settings.settings, dayStart = null,
            )
        }
    }

    @Test
    fun missingOngoingNotificationIsRestoredWithOldReference() {
        val f = fixture(ongoing = true)
        val processed = f.refresh()
        assertEquals(listOf(1L), f.notifier.pricePosts.toList())
        assertEquals(1, processed.notificationsShown)
        // Bezug bleibt 100 (kein neuer Meldekurs)
        assertTrue(f.store.notifiedPrices.isEmpty())
    }

    @Test
    fun withoutOngoingNothingIsRestored() {
        val f = fixture(ongoing = false)
        f.refresh()
        assertTrue(f.notifier.pricePosts.isEmpty())
        assertTrue(f.store.notifiedPrices.isEmpty())
    }
}
