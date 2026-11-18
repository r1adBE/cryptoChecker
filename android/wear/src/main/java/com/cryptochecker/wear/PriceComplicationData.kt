package com.cryptochecker.wear

import android.app.PendingIntent
import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import com.cryptochecker.marketdata.watch.WatchPair
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Inhalt der Kurs-Komplikation ([PriceComplicationService]) ohne Dienst: leer, ohne Kurs und mit Kurs — mit einem
 * [Context] für die Texte, darum auf dem Emulator prüfbar (`PriceComplicationDataTest`). [tap] null = ohne Tipp-Ziel.
 */
internal object PriceComplicationData {
    /**
     * Keine Coins gewählt: sagt, was zu tun ist, und öffnet beim Tippen die Uhr-App (statt «--» ohne Tipp-Ziel).
     * «Wie Handy» ([phone]) ohne Favoriten: «Keine Favoriten auf dem Handy», eigene Liste: «Coins wählen».
     */
    fun empty(context: Context, type: ComplicationType, phone: Boolean, tap: PendingIntent?): ComplicationData? {
        val hint = context.getString(if (phone) R.string.watch_phone_empty else R.string.watch_choose)
        val title = text(context.getString(R.string.watch_complication_label))
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text("+"), text(hint))
                .setTitle(title)
                .apply { tap?.let { setTapAction(it) } }
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(hint), text(hint))
                .setTitle(title)
                .apply { tap?.let { setTapAction(it) } }
                .build()
            else -> null
        }
    }

    /**
     * Erster Coin ohne Kurs (Börse stillgelegt, noch nichts geladen): Paar mit «—», kurzer Grund in der langen Form
     * ([missingShort]), ausführlich für den Screenreader ([missingReason]); Tippen öffnet die Uhr-App. Wie iOS.
     */
    fun missing(context: Context, type: ComplicationType, key: String, tap: PendingIntent?): ComplicationData? {
        val label = WatchPair.label(key)
        val description = text("$label, ${missingReason(context, key)}")
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(NO_PRICE), description)
                .setTitle(text(label))
                .apply { tap?.let { setTapAction(it) } }
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text("$label • $NO_PRICE  ·  ${missingShort(context, key)}"),
                description,
            )
                .setTitle(text(label))
                .apply { tap?.let { setTapAction(it) } }
                .build()
            else -> null
        }
    }

    /** Alter veralteter Kurse: kurz für die lange Komplikation, ausgeschrieben für den Screenreader; [time] = Stand. */
    class Age(val short: String, val spoken: String, val time: Long)

    fun data(
        context: Context,
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
        val row = context.getString(R.string.a11y_watch_row, coin, WatchFormat.price(price), WatchFormat.change(change))
        val description = text(listOfNotNull(row, age?.spoken, source).joinToString(", "))
        // Wie die Zeile der Uhr-App: veraltet das Alter, sonst die Börse
        val detail = age?.short ?: source
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(WatchFormat.compactPrice(price)), description)
                .setTitle(age?.let { shortTitle(coin, it.time) } ?: text(coin))
                .apply { tap?.let { setTapAction(it) } }
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text(WatchFormat.priceLine(coin, price, quote, change, detail)),
                description,
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
            TimeDifferenceStyle.SHORT_SINGLE_UNIT,
            CountUpTimeReference(Instant.ofEpochMilli(time)),
        )
            .setText("$coin ^1")
            .setMinimumTimeUnit(TimeUnit.MINUTES)
            .build()
}

/** Platzhalter ohne Kurs (wie die Zeile der Uhr-App). */
private const val NO_PRICE = "—"
