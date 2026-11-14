package com.cryptochecker.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.cryptochecker.marketdata.usage.UsageMeter
import com.cryptochecker.marketdata.watch.WatchFreshness
import com.cryptochecker.marketdata.watch.WatchPair
import com.cryptochecker.marketdata.watch.WatchPhoneQuotes
import com.cryptochecker.marketdata.watch.WatchSettings
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Komplikation: der erste Favorit — kurz «BTC / 84’450» (Kurs knapp, [WatchFormat.compactPrice]; die Änderung nennt die
 * Beschreibung für den Screenreader), lang «BTC • 84’450.00 USDT  +1.2%» mit der Gegenwährung des Paars
 * ([WatchFormat.priceLine]). Das Zifferblatt fragt etwa alle
 * 15 min (Manifest, fest); geladen wird nur, was nach [WatchPhoneQuotes.selfFetch] fällig ist: nichts, solange das
 * Handy frische Kurse liefert, sonst ab dem Intervall des Handys weniger 5 Min. ([WatchInterval]); ohne Netz oder ohne
 * neuen Kurs für diesen Coin der letzte. Lang mit der Börse, die den Kurs geliefert hat («… +1.2%  ·  Binance Futures»,
 * wie die Zeile der Uhr-App), veraltet ([WatchFreshness], 3 × Intervall des Handys ab dem Stand seines Abrufs) statt
 * ihrer mit dem Alter «… +1.2%  ·  vor 47 Min.», kurz mit dem Alter im Titel «BTC 47m» (vom Zifferblatt fortgezählt, [shortTitle]); die
 * Beschreibung für den Screenreader nennt Alter und Börse immer. Farben bestimmt das Zifferblatt (kein Abblassen möglich). Tippen
 * öffnet die Uhr-App. Ohne gewählte Coins statt «--» ein Hinweis mit Tipp-Ziel ([empty]): lang «Coins wählen» bzw. «Keine
 * Favoriten auf dem Handy», kurz «Kurs / +» — wie watchOS, das dann die Uhr-App öffnet.
 */
class PriceComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        data(type, "BTC", WatchPair.USDT, 84_450.0, 1.2, "Binance", null, null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val store = WatchStore(this)
        val settings = store.settings
        val favorites = settings.shown
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val coin = favorites.firstOrNull()
            ?: return empty(request.complicationType, settings.mode == WatchSettings.Mode.PHONE, tap)
        var cached = store.quotes()[coin]
        val usePhone = settings.source == WatchSettings.Source.PHONE && WatchPhoneQuotes.usePhone(
            settings.source, WatchDataLayer.phoneConnected(this), store.phoneTime, System.currentTimeMillis(), settings.interval,
        )
        val need = WatchPhoneQuotes.selfFetch(favorites, store.quotes().mapValues { it.value.time }, store.phoneCoins,
            usePhone, settings.interval, System.currentTimeMillis(), force = false)
        if (need.isNotEmpty()) {
            // Komplikation ruft selbst ab: zählt für «Akku & Aktivität» als Hintergrund-Lauf
            WatchUsage.attach(this)
            UsageMeter.shared.countRun()
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
            UsageMeter.shared.flush()
            WatchUsage.publish(this)
        }
        // Börse des Paars stillgelegt (ExchangeRetirement) bzw. noch ohne Kurs: kein alter Kurs, sondern «Paar / —» mit
        // dem Grund und Tipp-Ziel (wie die Zeile der Uhr-App) statt «keine Daten»
        val retired = WatchPair.parse(coin)?.let { WatchPairQuotes.retired(it) } == true
        val shown = cached?.takeIf { !retired } ?: return missing(request.complicationType, coin, tap)
        val q = shown.quote
        val time = shown.time
        val now = System.currentTimeMillis()
        val age = if (WatchFreshness.isOutdated(time, now, settings.interval)) {
            Age(WatchAge.short(time, now), WatchAge.spoken(this, time, now), time)
        } else {
            null
        }
        // Paar des Handys wie dort angezeigt («BANK Perp»), Kürzel der eigenen Liste unverändert (gegen USDT)
        // Börse, die den Kurs geliefert hat (wie die Zeile der Uhr-App), nicht nur das Paar
        val quote = WatchPair.parse(coin)?.quote ?: WatchPair.USDT
        return data(request.complicationType, WatchPair.label(coin), quote, q.price, q.change24h,
            q.source.takeIf { it.isNotBlank() }, age, tap)
    }

    /**
     * Keine Coins gewählt: sagt, was zu tun ist, und öffnet beim Tippen die Uhr-App (statt «--» ohne Tipp-Ziel).
     * «Wie Handy» ([phone]) ohne Favoriten: «Keine Favoriten auf dem Handy», eigene Liste: «Coins wählen».
     */
    private fun empty(type: ComplicationType, phone: Boolean, tap: PendingIntent): ComplicationData? {
        val hint = getString(if (phone) R.string.watch_phone_empty else R.string.watch_choose)
        val title = text(getString(R.string.watch_complication_label))
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text("+"), text(hint))
                .setTitle(title)
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(hint), text(hint))
                .setTitle(title)
                .setTapAction(tap)
                .build()
            else -> null
        }
    }

    /**
     * Erster Coin ohne Kurs (Börse stillgelegt, noch nichts geladen): Paar mit «—», kurzer Grund in der langen Form
     * ([missingShort]), ausführlich für den Screenreader ([missingReason]); Tippen öffnet die Uhr-App. Wie iOS.
     */
    private fun missing(type: ComplicationType, key: String, tap: PendingIntent): ComplicationData? {
        val label = WatchPair.label(key)
        val description = text("$label, ${missingReason(this, key)}")
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(NO_PRICE), description)
                .setTitle(text(label))
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text("$label • $NO_PRICE  ·  ${missingShort(this, key)}"), description,
            )
                .setTitle(text(label))
                .setTapAction(tap)
                .build()
            else -> null
        }
    }

    /** Alter veralteter Kurse: kurz für die lange Komplikation, ausgeschrieben für den Screenreader; [time] = Stand. */
    private class Age(val short: String, val spoken: String, val time: Long)

    private fun data(
        type: ComplicationType,
        coin: String,
        quote: String,
        price: Double,
        change: Double?,
        source: String?,
        age: Age?,
        tap: PendingIntent?,
    ): ComplicationData? {
        // Screenreader mit vollem Kurs und Änderung (kurz steht nur der Kurs)
        val row = getString(R.string.a11y_watch_row, coin, WatchFormat.price(price), WatchFormat.change(change))
        val description = text(listOfNotNull(row, age?.spoken, source).joinToString(", "))
        // Wie die Zeile der Uhr-App: veraltet das Alter, sonst die Börse
        val detail = age?.short ?: source
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(WatchFormat.compactPrice(price)), description)
                .setTitle(age?.let { shortTitle(coin, it.time) } ?: text(coin))
                .apply { tap?.let { setTapAction(it) } }
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text(WatchFormat.priceLine(coin, price, quote, change, detail)), description,
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

/** Platzhalter ohne Kurs (wie die Zeile der Uhr-App). */
private const val NO_PRICE = "—"
