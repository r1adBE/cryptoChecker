package com.cryptochecker.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.widget.WidgetPrefs
import com.cryptochecker.app.widget.widgetPairText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Einzel-Widget merkt das Paar, nicht nur die Id: steht unter der Id nach
 * einer eingespielten Sicherung ein anderes Paar, zeigt es «Paar wählen». Ältere Wahl ohne Paar merkt sich das Paar
 * beim ersten Zeichnen. Echte Widget-Einstellungen (SharedPreferences). Wie iOS `SingleWidgetChoiceTests.swift`.
 */
@RunWith(AndroidJUnit4::class)
class SingleWidgetChoiceDeviceTest {

    private val prefs = WidgetPrefs(InstrumentationRegistry.getInstrumentation().targetContext)
    private val widgetId = 987_654

    @After
    fun tearDown() = prefs.remove(widgetId)

    private fun watch(id: Long, base: String) =
        WatchEntity(id = id, exchangeKey = "Binance", exchangeName = "Binance", baseAsset = base, quoteAsset = "USDT")

    @Test
    fun samePairIsShownOtherPairAsksToChoose() {
        val btc = watch(5, "BTC")
        prefs.setWatchId(widgetId, 5, widgetPairText(btc))
        assertEquals("BTC", prefs.chosenWatch(widgetId, mapOf(5L to btc, 4L to watch(4, "ETH")))?.baseAsset)
        // Eingespielte Sicherung: Id 5 ist jetzt ETH
        assertNull(prefs.chosenWatch(widgetId, mapOf(5L to watch(5, "ETH"))))
        assertNull("Paar gelöscht", prefs.chosenWatch(widgetId, emptyMap()))
    }

    @Test
    fun olderChoiceWithoutPairRemembersItOnFirstDraw() {
        prefs.setWatchId(widgetId, 5)
        assertEquals("ETH", prefs.chosenWatch(widgetId, mapOf(5L to watch(5, "ETH")))?.baseAsset)
        // Jetzt gemerkt: ein anderes Paar unter derselben Id gilt nicht mehr
        assertNull(prefs.chosenWatch(widgetId, mapOf(5L to watch(5, "BTC"))))
    }
}
