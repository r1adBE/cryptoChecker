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
import com.cryptochecker.marketdata.watch.WatchFreshness
import com.cryptochecker.marketdata.watch.WatchPortfolio
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Inhalt der Portfolio-Komplikation ([PortfolioComplicationService]) ohne Dienst: aus bzw. mit Wert — mit einem
 * [Context] für die Texte, darum auf dem Emulator prüfbar (`PortfolioComplicationDataTest`). [tap] null = ohne Tipp-Ziel.
 */
internal object PortfolioComplicationData {
    /** Kein Wert (Schalter «Portfolio-Wert auf der Uhr» aus): sagt es, statt leer zu bleiben; Tippen öffnet die Uhr-App. */
    fun off(context: Context, type: ComplicationType, tap: PendingIntent?): ComplicationData? {
        val title = context.getString(R.string.watch_portfolio)
        val hint = context.getString(R.string.watch_portfolio_turn_on)
        val description = text(WatchPortfolioText.offSpoken(title, hint))
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text(context.getString(R.string.watch_portfolio_off)),
                description
            )
                .setTitle(text(title))
                .apply { tap?.let { setTapAction(it) } }
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(WatchPortfolioText.offLine(title, hint)), description)
                .apply { tap?.let { setTapAction(it) } }
                .build()
            else -> null
        }
    }

    /** [interval] = Intervall des Handys ([WatchFreshness.isOutdated]); [now] = jetzt (Tests: fester Zeitpunkt). */
    fun data(
        context: Context,
        type: ComplicationType,
        p: WatchPortfolio,
        interval: Int,
        tap: PendingIntent?,
        now: Long = System.currentTimeMillis(),
    ): ComplicationData? {
        val outdated = WatchFreshness.isOutdated(p.time, now, interval)
        val spoken = WatchPortfolioText.spoken(context, p) + if (outdated) ", ${WatchAge.spoken(context, p.time, now)}" else ""
        val description = text(spoken)
        val change = if (p.hidden) "" else "  " + WatchFormat.change(p.changePercent)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(WatchPortfolioText.compact(p)), description)
                .setTitle(if (outdated) aged(p.currency, p.time) else text(p.currency))
                .apply { tap?.let { setTapAction(it) } }
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text(WatchPortfolioText.total(p) + change + if (outdated) "  ·  ${WatchAge.short(p.time, now)}" else ""),
                description,
            )
                .setTitle(text(context.getString(R.string.watch_portfolio)))
                .apply { tap?.let { setTapAction(it) } }
                .build()
            else -> null
        }
    }

    private fun text(value: String): ComplicationText = PlainComplicationText.Builder(value).build()

    /** Titel mit Alter in einer Einheit («USD 47m»), vom Zifferblatt fortgezählt. */
    private fun aged(prefix: String, time: Long): ComplicationText =
        TimeDifferenceComplicationText.Builder(
            TimeDifferenceStyle.SHORT_SINGLE_UNIT,
            CountUpTimeReference(Instant.ofEpochMilli(time)),
        )
            .setText("$prefix ^1")
            .setMinimumTimeUnit(TimeUnit.MINUTES)
            .build()
}
