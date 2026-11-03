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
import com.cryptochecker.marketdata.watch.WatchPortfolio
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Komplikation «Portfolio»: der Wert, den das Handy schickt (nur mit «Portfolio-Wert auf der Uhr»), ohne eigene
 * Abfrage — kurz «12.3K» mit der Währung als Titel, lang «CHF 12’345.67  +1.20 %» mit «Portfolio» als Titel; verborgen
 * auf dem Handy «•••». Ohne Wert (Schalter aus): keine Daten. Veraltet ([WatchFreshness]): lang mit Alter, kurz mit dem
 * Alter im Titel (vom Zifferblatt fortgezählt), wie [PriceComplicationService]. Neu gezeichnet bei jedem neuen Wert
 * ([WatchDataListener]). Tippen öffnet die Uhr-App. Wie die Apple Watch (`WatchPortfolioWidget`).
 */
class PortfolioComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        data(type, WatchPortfolio(12_345.67, 1.2, "USD", System.currentTimeMillis(), hidden = false), null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val p = WatchStore(this).portfolioFlow.value ?: return NoDataComplicationData()
        val tap = PendingIntent.getActivity(
            this, 1, Intent(this, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return data(request.complicationType, p, tap)
    }

    private fun data(type: ComplicationType, p: WatchPortfolio, tap: PendingIntent?): ComplicationData? {
        val now = System.currentTimeMillis()
        val outdated = WatchFreshness.isOutdated(p.time, now)
        val spoken = WatchPortfolioText.spoken(this, p) + if (outdated) ", ${WatchAge.spoken(this, p.time, now)}" else ""
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
                .setTitle(text(getString(R.string.watch_portfolio)))
                .apply { tap?.let { setTapAction(it) } }
                .build()
            else -> null
        }
    }

    private fun text(value: String): ComplicationText = PlainComplicationText.Builder(value).build()

    /** Titel mit Alter in einer Einheit («USD 47m»), vom Zifferblatt fortgezählt. */
    private fun aged(prefix: String, time: Long): ComplicationText =
        TimeDifferenceComplicationText.Builder(
            TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountUpTimeReference(Instant.ofEpochMilli(time)),
        )
            .setText("$prefix ^1")
            .setMinimumTimeUnit(TimeUnit.MINUTES)
            .build()
}
