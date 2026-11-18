package com.cryptochecker.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import com.cryptochecker.marketdata.watch.WatchPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Kurs-Komplikation auf der Uhr: leer, ohne Kurs, frisch und veraltet — die Texte, die das Zifferblatt zeichnet. */
@RunWith(AndroidJUnit4::class)
class PriceComplicationDataTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun emptyOwnListAsksToChooseCoins() {
        val short = PriceComplicationData.empty(context, ComplicationType.SHORT_TEXT, phone = false, tap = null)
            as ShortTextComplicationData
        assertEquals("+", short.text.shown(context))
        assertEquals(context.getString(R.string.watch_choose), short.contentDescription?.shown(context))
        assertEquals(context.getString(R.string.watch_complication_label), short.title?.shown(context))
    }

    @Test
    fun emptyPhoneModeSaysNoFavoritesOnThePhone() {
        val long = PriceComplicationData.empty(context, ComplicationType.LONG_TEXT, phone = true, tap = null)
            as LongTextComplicationData
        assertEquals(context.getString(R.string.watch_phone_empty), long.text.shown(context))
    }

    @Test
    fun freshPriceShowsCompactPriceAndSource() {
        val short = PriceComplicationData.data(
            context, ComplicationType.SHORT_TEXT, "BTC", WatchPair.USDT, 84_450.0, 1.2,
            "Binance", null, null
        ) as ShortTextComplicationData
        assertEquals(WatchFormat.compactPrice(84_450.0), short.text.shown(context))
        assertEquals("BTC", short.title?.shown(context))
        val long = PriceComplicationData.data(
            context, ComplicationType.LONG_TEXT, "BTC", WatchPair.USDT, 84_450.0, 1.2,
            "Binance", null, null
        ) as LongTextComplicationData
        assertEquals(WatchFormat.priceLine("BTC", 84_450.0, WatchPair.USDT, 1.2, "Binance"), long.text.shown(context))
        assertTrue(long.contentDescription!!.shown(context).endsWith(", Binance"))
    }

    @Test
    fun outdatedPriceShowsAgeInsteadOfSource() {
        val now = System.currentTimeMillis()
        val time = now - 47 * 60_000L
        val age = PriceComplicationData.Age(WatchAge.short(time, now), WatchAge.spoken(context, time, now), time)
        val long = PriceComplicationData.data(
            context, ComplicationType.LONG_TEXT, "ETH", WatchPair.USDT, 3_100.0, -0.5,
            "Kraken", age, null
        ) as LongTextComplicationData
        assertEquals(WatchFormat.priceLine("ETH", 3_100.0, WatchPair.USDT, -0.5, age.short), long.text.shown(context))
        val spoken = long.contentDescription!!.shown(context)
        assertTrue(spoken, spoken.contains(age.spoken) && spoken.endsWith("Kraken"))
        // Kurz: Alter im Titel, vom Zifferblatt fortgezählt
        val short = PriceComplicationData.data(
            context, ComplicationType.SHORT_TEXT, "ETH", WatchPair.USDT, 3_100.0, -0.5,
            "Kraken", age, null
        ) as ShortTextComplicationData
        val title = short.title!!.shown(context)
        assertTrue(title, title.startsWith("ETH ") && title != "ETH ")
    }

    @Test
    fun missingPriceShowsDashWithPair() {
        val short = PriceComplicationData.missing(context, ComplicationType.SHORT_TEXT, "BTC", tap = null)
            as ShortTextComplicationData
        assertEquals("—", short.text.shown(context))
        assertEquals(WatchPair.label("BTC"), short.title?.shown(context))
    }

    @Test
    fun otherComplicationTypesGetNothing() {
        assertNull(PriceComplicationData.empty(context, ComplicationType.RANGED_VALUE, phone = false, tap = null))
        assertNull(
            PriceComplicationData.data(
                context, ComplicationType.SMALL_IMAGE, "BTC", WatchPair.USDT, 1.0, null,
                null, null, null
            )
        )
    }
}
