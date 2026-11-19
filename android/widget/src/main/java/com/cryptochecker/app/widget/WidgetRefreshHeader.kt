package com.cryptochecker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.domain.widget.WidgetStatusRule
import com.cryptochecker.app.domain.widget.WidgetTextFit
import com.cryptochecker.resources.R

/**
 * Rechter Teil der Kopfzeile im Puls- und im Portfolio-Widget, einheitlich mit dem Listen-Widget: der Stand (Datum und
 * Uhrzeit, eng nur die Uhrzeit; veraltet bzw. ohne Netz mit Hinweis) und der Knopf «Aktualisieren»; während des Ladens
 * steht an seiner Stelle ein Kreisel (`busy`). Ids je Layout ([Ids]), Breiten aus widget_pulse.xml bzw.
 * widget_portfolio_header.xml. Wie iOS `WidgetHeaderStatus`.
 */
internal object WidgetRefreshHeader {

    /** Ids der drei Views einer Kopfzeile. */
    data class Ids(val status: Int, val refresh: Int, val busy: Int)

    val PULSE = Ids(R.id.pulse_status, R.id.pulse_refresh, R.id.pulse_refresh_busy)
    val PORTFOLIO = Ids(R.id.portfolio_status, R.id.portfolio_refresh, R.id.portfolio_refresh_busy)

    /** Feste Breiten (dp): Rand 2 × 12, Logo 16 + 4, Abstand vor dem Stand 4, Knopf 40 − 10 (negativer Rand). */
    const val FIXED_DP = 2 * 12f + 16f + 4f + 4f + 40f - 10f

    /** Schrift des Stands (sp, wie textSize in den Layouts). */
    const val STATUS_SP = 12f

    /**
     * Texte für den Stand, lang → kurz: «19. Nov., 10:12» / «10:12»; veraltet «veraltet · …» / «veraltet»; ohne Netz
     * «Keine Verbindung · Stand …» / «Keine Verbindung». Ohne Zeit (0) leer.
     */
    fun candidates(context: Context, time: Long, online: Boolean, outdated: Boolean): List<String> =
        when (WidgetStatusRule.kind(online, outdated, time)) {
            WidgetStatusRule.Kind.OFFLINE -> listOf(WidgetOffline.label(context, time), WidgetOffline.short(context))
            WidgetStatusRule.Kind.OFFLINE_NO_TIME -> listOf(WidgetOffline.short(context))
            WidgetStatusRule.Kind.OUTDATED ->
                listOf(WidgetOutdated.label(context, time), context.getString(R.string.widget_outdated))
            WidgetStatusRule.Kind.TIME -> if (time > 0L) listOf(dateTime(context, time), timeOnly(context, time)) else emptyList()
        }

    /** «19. Nov., 10:12» — mit Datum, damit klar ist, von wann der Stand ist. */
    fun dateTime(context: Context, time: Long): String = DateUtils.formatDateTime(
        context,
        time,
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
    )

    private fun timeOnly(context: Context, time: Long): String =
        DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_TIME)

    /**
     * Stand und Knopf setzen (Kreisel aus). Der Stand ist der erste der [candidates], der neben den Titel passt
     * ([widthDp] Breite des Widgets, [titleDp] Breite des Titels); sonst keiner — der Titel bleibt immer.
     * @return gezeigter Stand (leer = keiner)
     */
    fun bind(
        toolkit: WidgetToolkit,
        views: RemoteViews,
        ids: Ids,
        candidates: List<String>,
        widthDp: Int,
        titleDp: Float,
        colors: WidgetColors,
        refresh: PendingIntent,
        busy: Boolean = false,
    ): String {
        val available = if (widthDp > 0) widthDp - FIXED_DP - titleDp - WidgetTextFit.SAFETY_DP else null
        val text = candidates.firstOrNull {
            available == null ||
                toolkit.textWidthDp(it, STATUS_SP, bold = false, tabular = true) <= available
        }
            .orEmpty()
        views.setTextViewText(ids.status, text)
        views.setViewVisibility(ids.status, if (text.isEmpty()) View.GONE else View.VISIBLE)
        views.setTextColor(ids.status, colors.secondaryTextColor)
        // Knopf neutral wie im Listen-Widget: weiss im Dunkeln, schwarz im Hellen — nicht in der Akzentfarbe
        views.setInt(ids.refresh, "setColorFilter", colors.textColor)
        views.setViewVisibility(ids.refresh, if (busy) View.GONE else View.VISIBLE)
        views.setViewVisibility(ids.busy, if (busy) View.VISIBLE else View.GONE)
        views.setOnClickPendingIntent(ids.refresh, refresh)
        return text
    }

    /** Platz, den Stand und Knopf neben dem Titel brauchen (dp), für die Wahl der Titelgrösse. */
    fun usedDp(toolkit: WidgetToolkit, status: String): Float =
        (if (status.isEmpty()) 0f else 4f + toolkit.textWidthDp(status, STATUS_SP, bold = false, tabular = true)) + 40f - 10f

    /** Tipp auf «Aktualisieren»: Broadcast an [provider] mit [action] (eigener Request-Code je Widget). */
    fun intent(context: Context, provider: Class<*>, action: String, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, provider).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
