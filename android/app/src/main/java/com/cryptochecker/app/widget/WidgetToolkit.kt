package com.cryptochecker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.RemoteViews
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeView
import com.cryptochecker.app.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Was alle Startbildschirm-Widgets teilen: Ids je Widget-Art, Grösse aus den Widget-Optionen,
 * Textbreite wie im Widget, Hintergrund, grauer Zeitraum, %-Basis und «App öffnen».
 */
@Singleton
class WidgetToolkit @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val refreshStats: RefreshStats,
) {
    /** Ids der Widgets einer Art ([provider]); ohne AppWidgetManager leer. */
    fun ids(provider: Class<*>): IntArray {
        val manager = AppWidgetManager.getInstance(context) ?: return IntArray(0)
        return runCatching {
            manager.getAppWidgetIds(ComponentName(context, provider))
        }.getOrNull() ?: IntArray(0)
    }

    /**
     * %-Basis der Widgets und ob die gespeicherten Veränderungen noch dazu passen (Stempel des
     * letzten Durchlaufs); sonst «—» bis zur nächsten Aktualisierung.
     */
    fun changeView(basis: ChangeBasis, now: Long = System.currentTimeMillis(), showPeriod: Boolean = true): ChangeView =
        ChangeView.of(refreshStats.changeStamp.value, basis, now, showPeriod = showPeriod)

    /**
     * Hintergrund eines Widgets: Die Fläche mit runden Ecken (drawable/widget_background) liegt
     * als ImageView unter dem Inhalt; Farbe per setColorFilter, Deckkraft per setImageAlpha.
     * Beides geht in RemoteViews ab API 26 — eine Tönung des View-Hintergrunds
     * (setBackgroundTintList) erst ab API 31, setBackgroundColor verlöre die runden Ecken.
     */
    fun background(views: RemoteViews, layerId: Int, colors: WidgetColors, opacityPercent: Int) {
        val color = colors.colorWithOpacity(opacityPercent)
        views.setInt(layerId, "setColorFilter", color or 0xFF000000.toInt())
        views.setInt(layerId, "setImageAlpha", Color.alpha(color))
    }

    /**
     * Breite eines Textes in dp, gemessen wie im Widget: [sp] mit der Schriftgrösse des
     * Systems, fett (wie die Kopfzeilen) und auf Wunsch mit Tabellenziffern.
     */
    fun textWidthDp(text: String, sp: Float, bold: Boolean = true, tabular: Boolean = false): Float {
        if (text.isEmpty()) return 0f
        val metrics = context.resources.displayMetrics
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (tabular) fontFeatureSettings = "tnum"
        }
        return paint.measureText(text) / metrics.density
    }

    /**
     * [text] mit kleiner, grauer Währung [quote] ([WidgetQuoteText]): RelativeSizeSpan und
     * ForegroundColorSpan gehen in RemoteViews (parcelbar) und wirken auch mit autoSizeTextType.
     * Ohne Währung im Text der Text unverändert.
     */
    fun quoteText(text: String, quote: String, priceSp: Float, quoteColor: Int): CharSequence {
        val range = WidgetQuoteText.quoteRange(text, quote) ?: return text
        val end = range.last + 1
        return android.text.SpannableString(text).apply {
            setSpan(android.text.style.RelativeSizeSpan(WidgetQuoteText.scale(priceSp)), range.first, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.ForegroundColorSpan(quoteColor), range.first, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Breite wie [textWidthDp], die Währung aber verkleinert wie in [quoteText] ([WidgetQuoteText.width]). */
    fun quoteTextWidthDp(text: String, quote: String, sp: Float, bold: Boolean = true, tabular: Boolean = false): Float =
        WidgetQuoteText.width(text, quote, sp) { part, size -> textWidthDp(part, size, bold, tabular) }

    /**
     * Zeitraum am Ende einer Veränderung grau («↘ −1’968.40 CHF  −2.31% · 24h»): ab [start]
     * ([WidgetTextFit.periodStart]) die Sekundärfarbe [grey] — der Zeitraum ist nie rot/grün. Ohne
     * [start] der Text unverändert. Bestehende Spans (kleine Währung) bleiben.
     */
    fun periodGrey(text: CharSequence, start: Int?, grey: Int): CharSequence {
        if (start == null || start !in 0 until text.length) return text
        return android.text.SpannableString(text).apply {
            setSpan(android.text.style.ForegroundColorSpan(grey), start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Widget-Grösse in dp aus den Widget-Optionen wie beim Einzel-Widget
     * (Hochformat: minWidth × maxHeight, Querformat: maxWidth × minHeight); 0 = unbekannt.
     */
    fun sizeDp(manager: AppWidgetManager, appWidgetId: Int): Pair<Int, Int> {
        val options = runCatching { manager.getAppWidgetOptions(appWidgetId) }.getOrNull() ?: return 0 to 0
        val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val width = options.getInt(
            if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0
        )
        val height = options.getInt(
            if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0
        )
        return width to height
    }

    /** Tippen öffnet die App (Kopf des Listen-Widgets, Einzel-Widget mit Paar). */
    fun openApp(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Eigene Action: sonst gleiche Identität wie die Mitteilung von Paar-Id 1 (Request-Code 1)
            action = ACTION_OPEN_WIDGET
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val ACTION_OPEN_WIDGET = "com.cryptochecker.app.action.OPEN_FROM_WIDGET"
    }
}

/** Bildfläche der Charts ohne Widget-Optionen (dp), etwa die bisherige Mindestgrösse. */
internal const val DEFAULT_CHART_WIDTH_DP = 160f
internal const val DEFAULT_CHART_HEIGHT_DP = 56f

/** Höchstens so viele Pixel je Chart-Bild (RemoteViews-Grenze). */
internal const val MAX_CHART_PIXELS = 1_000_000f

internal const val USDT = "USDT"
