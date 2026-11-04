package com.cryptochecker.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.cryptochecker.marketdata.watch.WatchFreshness
import com.cryptochecker.marketdata.watch.WatchPair
import com.cryptochecker.marketdata.watch.WatchPhoneQuotes
import com.cryptochecker.marketdata.watch.WatchSettings
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Komplikation: der erste Favorit — kurz «BTC / +1.2%», lang «BTC 67’420.00 +1.2%». Das Zifferblatt fragt etwa alle
 * 15 min (Manifest, fest); geladen wird nur, was nach [WatchPhoneQuotes.selfFetch] fällig ist: nichts, solange das
 * Handy frische Kurse liefert, sonst ab dem Intervall des Handys weniger 5 Min. ([WatchInterval]); ohne Netz oder ohne
 * neuen Kurs für diesen Coin der letzte. Lang mit der Börse, die den Kurs geliefert hat («… +1.2%  ·  Binance Futures»,
 * wie die Zeile der Uhr-App), veraltet ([WatchFreshness], 45 Min. ab dem Stand seines Abrufs) statt ihrer mit dem Alter
 * «… +1.2%  ·  vor 47 Min.», kurz mit dem Alter im Titel «BTC 47m» (vom Zifferblatt fortgezählt, [shortTitle]); die
 * Beschreibung für den Screenreader nennt Alter und Börse immer. Farben bestimmt das Zifferblatt (kein Abblassen möglich). Tippen
 * öffnet die Uhr-App.
 */
class PriceComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        data(type, "BTC", WatchFormat.price(67420.0), WatchFormat.change(1.2), "Binance", null, null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val store = WatchStore(this)
        val settings = store.settings
        val favorites = settings.shown
        val coin = favorites.firstOrNull() ?: return NoDataComplicationData()
        var cached = store.quotes()[coin]
        val usePhone = settings.source == WatchSettings.Source.PHONE && WatchDataLayer.phoneConnected(this)
        val need = WatchPhoneQuotes.selfFetch(favorites, store.quotes().mapValues { it.value.time }, store.phoneCoins,
            usePhone, settings.interval, System.currentTimeMillis(), force = false)
        if (need.isNotEmpty()) {
            val startedAt = System.currentTimeMillis()
            // Abbruch des Aufrufs nicht schlucken; höchstens [WatchPriceSource.FETCH_DEADLINE_MS], danach der gespeicherte Kurs
            val fresh = try {
                WatchPriceSource.fetch(need)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
            // Je Coin zusammengeführt: fehlt der erste Coin im neuen Ergebnis oder hat ein später gestarteter Abruf
            // schon geschrieben, bleibt der gespeicherte Kurs samt Zeit
            if (fresh.isNotEmpty()) cached = store.mergeQuotes(fresh, startedAt)[coin] ?: cached
        }
        val shown = cached ?: return NoDataComplicationData()
        val q = shown.quote
        val time = shown.time
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val now = System.currentTimeMillis()
        val age = if (WatchFreshness.isOutdated(time, now)) {
            Age(WatchAge.short(time, now), WatchAge.spoken(this, time, now), time)
        } else {
            null
        }
        // Paar des Handys wie dort angezeigt («BANK Perp»), Kürzel der eigenen Liste unverändert
        // Börse, die den Kurs geliefert hat (wie die Zeile der Uhr-App), nicht nur das Paar
        return data(request.complicationType, WatchPair.label(coin), WatchFormat.price(q.price), WatchFormat.change(q.change24h),
            q.source.takeIf { it.isNotBlank() }, age, tap)
    }

    /** Alter veralteter Kurse: kurz für die lange Komplikation, ausgeschrieben für den Screenreader; [time] = Stand. */
    private class Age(val short: String, val spoken: String, val time: Long)

    private fun data(
        type: ComplicationType,
        coin: String,
        price: String,
        change: String,
        source: String?,
        age: Age?,
        tap: PendingIntent?,
    ): ComplicationData? {
        val row = getString(R.string.a11y_watch_row, coin, price, change)
        val description = text(listOfNotNull(row, age?.spoken, source).joinToString(", "))
        // Wie die Zeile der Uhr-App: veraltet das Alter, sonst die Börse
        val detail = age?.short ?: source
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(change), description)
                .setTitle(age?.let { shortTitle(coin, it.time) } ?: text(coin))
                .apply { tap?.let { setTapAction(it) } }
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text("$price  $change" + (detail?.let { "  ·  $it" } ?: "")), description,
            )
                .setTitle(text(coin))
                .apply { tap?.let { setTapAction(it) } }
                .build()
            else -> null
        }
    }

    private fun text(value: String): ComplicationText = PlainComplicationText.Builder(value).build()

    /**
     * Titel der kurzen Komplikation bei veraltetem Kurs: Coin und Alter in einer Einheit («BTC 47m», vom System
     * übersetzt und vom Zifferblatt fortgezählt) — sichtbar wie in der langen Form, nicht nur im Screenreader.
     */
    private fun shortTitle(coin: String, time: Long): ComplicationText =
        TimeDifferenceComplicationText.Builder(
            TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountUpTimeReference(Instant.ofEpochMilli(time)),
        )
            .setText("$coin ^1")
            .setMinimumTimeUnit(TimeUnit.MINUTES)
            .build()

}
