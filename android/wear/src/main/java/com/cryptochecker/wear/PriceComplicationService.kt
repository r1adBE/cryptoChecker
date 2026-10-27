package com.cryptochecker.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Komplikation (Build 154): der erste Favorit — kurz «BTC / +1.2%», lang «BTC 67’420.00 +1.2%». Sind die gespeicherten
 * Kurse älter als [MAX_AGE_MS], lädt sie neu (Zifferblatt fragt etwa alle 15 min, Manifest); ohne Netz die letzten.
 * Tippen öffnet die Uhr-App.
 */
class PriceComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        data(type, "BTC", WatchFormat.price(67420.0), WatchFormat.change(1.2), null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val store = WatchStore(this)
        val favorites = store.shown
        val coin = favorites.firstOrNull() ?: return NoDataComplicationData()
        var (quotes, time) = store.quotes()
        if (System.currentTimeMillis() - time > MAX_AGE_MS || coin !in quotes) {
            val fresh = runCatching { WatchPriceSource.fetch(favorites) }.getOrNull().orEmpty()
            if (fresh.isNotEmpty()) {
                time = System.currentTimeMillis()
                store.saveQuotes(fresh, time)
                quotes = fresh
            }
        }
        val q = quotes[coin] ?: return NoDataComplicationData()
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return data(request.complicationType, coin, WatchFormat.price(q.price), WatchFormat.change(q.change24h), tap)
    }

    private fun data(type: ComplicationType, coin: String, price: String, change: String, tap: PendingIntent?): ComplicationData? {
        val description = text(getString(R.string.a11y_watch_row, coin, price, change))
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(change), description)
                .setTitle(text(coin))
                .apply { tap?.let { setTapAction(it) } }
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("$price  $change"), description)
                .setTitle(text(coin))
                .apply { tap?.let { setTapAction(it) } }
                .build()
            else -> null
        }
    }

    private fun text(value: String): ComplicationText = PlainComplicationText.Builder(value).build()

    private companion object {
        const val MAX_AGE_MS = 10 * 60_000L
    }
}
