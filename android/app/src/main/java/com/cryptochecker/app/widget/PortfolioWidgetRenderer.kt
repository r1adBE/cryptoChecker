package com.cryptochecker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshotMath
import com.cryptochecker.app.domain.portfolio.PortfolioValuePoint
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSeries
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSize
import com.cryptochecker.app.domain.refresh.OutdatedRule
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.PriceFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Portfolio-Widget: Gesamtwert, Veränderung, Wertverlauf und die grössten Positionen aus der
 * letzten Momentaufnahme ([PortfolioSnapshotStore]) — ohne Netz.
 */
@Singleton
class PortfolioWidgetRenderer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val widgetPrefs: WidgetPrefs,
    private val settingsRepository: SettingsRepository,
    private val portfolioSnapshotStore: PortfolioSnapshotStore,
    private val toolkit: WidgetToolkit,
) {
    /**
     * Zuletzt gezeichneter Wertverlauf je Portfolio-Widget und Grösse (Schlüssel
     * Widget-Id × [MAX_PORTFOLIO_SIZES] + Grösse), beim Löschen entfernt ([forget]).
     */
    private val portfolioCharts = LatestPerWidget<PortfolioChartKey, android.graphics.Bitmap>()

    /** Stand der Momentaufnahme beim letzten Zeichnen der Portfolio-Widgets ([update]). */
    @Volatile
    var drawn: PortfolioDrawState? = null
        private set

    /**
     * Portfolio-Widgets aus der letzten Momentaufnahme ([PortfolioSnapshotStore]) zeichnen —
     * ohne Netz. Layout je Stufe ([PortfolioWidgetMath.size]): klein = Kopfzeile mit Pille,
     * Gesamtwert, Betrag über 24 h; schmal-hoch = dazu der Wertverlauf darunter; mittel = Werte
     * links, Wertverlauf rechts; gross = dazu die drei grössten Positionen. Ab Android 12 je
     * Grösse des Launchers ein eigenes Layout (RemoteViews mit Grössen-Zuordnung), sonst nach
     * der Grösse aus den Widget-Optionen. Mit Portfolio-Sperre nur Titel, Schloss und
     * «Gesperrt – in der App entsperren», ohne Werte.
     */
    suspend fun update(appWidgetIds: IntArray): Boolean {
        if (appWidgetIds.isEmpty()) return false
        val manager = AppWidgetManager.getInstance(context) ?: return false
        val settings = settingsRepository.current()
        val accent = settings.accentColor
        val highContrast = HighContrast.isEffective(context, settings.highContrast)
        widgetPrefs.lastHighContrast = highContrast
        val stored = portfolioSnapshotStore.snapshot()
        drawn = PortfolioDrawState(stored?.total, stored?.time)
        // %-Basis: Aufnahme mit anderer Basis oder von einem früheren Tag → Veränderung «—»
        // (bis zur nächsten Aufnahme); der Verlauf bleibt, beschriftet nach seiner eigenen Basis
        val snapshot = stored?.let {
            if (ChangeBasisMath.isCurrent(it.stamp, settings.changeBasis, System.currentTimeMillis())) it
            else it.copy(changeAmount = null, changePercent = null, positions = it.positions.map { p -> p.copy(change24hPercent = null) })
        }
        val fontScale = context.resources.configuration.fontScale.takeIf { it > 0f } ?: 1f
        val outdated = snapshot != null &&
            OutdatedRule.isOutdated(snapshot.time, System.currentTimeMillis(), WidgetOutdated.afterMillis(settings))
        val locked = PortfolioLockPolicy.widgetLocked(settings.appLock)

        for (appWidgetId in appWidgetIds) {
            val dark = widgetPrefs.isDark(appWidgetId)
            val style = PortfolioStyle(
                colors = WidgetColors.of(accent, dark, settings.priceColorScheme, highContrast, settings.priceColorsInverted),
                dark = dark,
                highContrast = highContrast,
                logoRes = accent.logoRes(dark),
                opacity = widgetPrefs.getOpacity(appWidgetId),
                basis = settings.changeBasis.storage,
                hideAmounts = settings.hidePortfolioAmounts,
            )
            val message: String? = when {
                locked -> context.getString(R.string.widget_portfolio_locked_hint)
                snapshot == null || snapshot.empty -> context.getString(R.string.widget_portfolio_empty)
                else -> null
            }
            val views = if (message != null || snapshot == null) {
                portfolioMessageViews(style, message.orEmpty(), locked)
            } else {
                val wantUsdt = snapshot.totalUsdt != null &&
                    PortfolioWidgetMath.showsUsdt(widgetPrefs.getPortfolioShowUsdt(appWidgetId), snapshot.currency)
                val sizes = portfolioSizesDp(manager, appWidgetId)
                if (sizes.size > 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Je Grösse (Hoch-/Querformat) ein Layout; der Launcher wählt ohne neuen Aufruf
                    RemoteViews(
                        sizes.mapIndexed { slot, (w, h) ->
                            SizeF(w.toFloat(), h.toFloat()) to
                                portfolioViews(appWidgetId, slot, w, h, snapshot, style, outdated, fontScale, wantUsdt)
                        }.toMap()
                    )
                } else {
                    val (w, h) = sizes.firstOrNull() ?: (0 to 0)
                    portfolioViews(appWidgetId, 0, w, h, snapshot, style, outdated, fontScale, wantUsdt)
                }
            }
            runCatching { manager.updateAppWidget(appWidgetId, views) }
                .onFailure { Timber.w(it, "Portfolio-Widget %d konnte nicht gezeichnet werden", appWidgetId) }
        }
        return true
    }

    /** Farben und Einstellungen eines Portfolio-Widgets. */
    private class PortfolioStyle(
        val colors: WidgetColors,
        val dark: Boolean,
        val highContrast: Boolean,
        val logoRes: Int,
        val opacity: Int,
        /** %-Basis (Zeitraum neben Betrag und Pille, Screenreader). */
        val basis: ChangeBasis = ChangeBasis.DEFAULT,
        /** «Beträge verbergen»: Gesamtwert und Betrag als «•••», ohne ≈ USDT; Prozente bleiben. */
        val hideAmounts: Boolean = false,
    )

    /** Hintergrund, Logo, Titel und Tipp (öffnet den Portfolio-Tab) — in jedem Layout gleich. */
    private fun portfolioFrame(views: RemoteViews, style: PortfolioStyle) {
        toolkit.background(views, R.id.portfolio_bg, style.colors, style.opacity)
        views.setImageViewResource(R.id.portfolio_logo, style.logoRes)
        views.setTextViewText(R.id.portfolio_title, context.getString(R.string.widget_portfolio_name))
        views.setTextColor(R.id.portfolio_title, style.colors.textColor)
        views.setOnClickPendingIntent(R.id.portfolio_root, openPortfolio())
    }

    /** Sperre oder «leer»: Titel, (Schloss,) Hinweis — keine Werte, kein Chart, keine Positionen. */
    private fun portfolioMessageViews(style: PortfolioStyle, message: String, locked: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_portfolio)
        portfolioFrame(views, style)
        views.setViewVisibility(R.id.portfolio_pill, View.GONE)
        views.setViewVisibility(R.id.portfolio_values, View.GONE)
        views.setViewVisibility(R.id.portfolio_lock_icon, if (locked) View.VISIBLE else View.GONE)
        if (locked) views.setInt(R.id.portfolio_lock_icon, "setColorFilter", style.colors.secondaryTextColor)
        views.setViewVisibility(R.id.portfolio_message, View.VISIBLE)
        views.setTextViewText(R.id.portfolio_message, message)
        views.setTextColor(R.id.portfolio_message, style.colors.secondaryTextColor)
        views.setContentDescription(
            R.id.portfolio_root,
            listOf(context.getString(R.string.widget_portfolio_name), message).joinToString(", ")
        )
        return views
    }

    /**
     * Ein Portfolio-Widget in der Grösse [widthDp] × [heightDp] (0 = unbekannt). [slot] trennt
     * die Chart-Bilder der Grössen eines Widgets im Zwischenspeicher ([portfolioCharts]).
     */
    private suspend fun portfolioViews(
        appWidgetId: Int,
        slot: Int,
        widthDp: Int,
        heightDp: Int,
        snapshot: PortfolioSnapshot,
        style: PortfolioStyle,
        outdated: Boolean,
        fontScale: Float,
        wantUsdt: Boolean,
    ): RemoteViews {
        val colors = style.colors
        val amount = snapshot.changeAmount?.takeIf { it.isFinite() }
        val percent = snapshot.changePercent?.takeIf { it.isFinite() }
        val layout = PortfolioWidgetMath.layout(
            widthDp = widthDp,
            heightDp = heightDp,
            fontScale = fontScale,
            hasChange = amount != null,
            wantUsdt = wantUsdt,
            positions = snapshot.positions.size,
        )
        val views = RemoteViews(
            context.packageName,
            when (layout.size) {
                PortfolioWidgetSize.MEDIUM -> R.layout.widget_portfolio_medium
                PortfolioWidgetSize.LARGE -> R.layout.widget_portfolio_large
                PortfolioWidgetSize.SMALL, PortfolioWidgetSize.TALL -> R.layout.widget_portfolio
            }
        )
        portfolioFrame(views, style)
        if (layout.size == PortfolioWidgetSize.SMALL || layout.size == PortfolioWidgetSize.TALL) {
            views.setViewVisibility(R.id.portfolio_lock_icon, View.GONE)
            views.setViewVisibility(R.id.portfolio_message, View.GONE)
        }
        views.setViewVisibility(R.id.portfolio_values, View.VISIBLE)

        // Richtung nach dem Vorzeichen des Betrags (nie nach dem Farbtausch): Farbe UND Pfeil
        val direction = amount?.let { PortfolioWidgetSeries.direction(it) }
        val priceColor = when (direction) {
            1 -> colors.upColor
            -1 -> colors.downColor
            else -> colors.neutralColor
        }
        val arrow = when (direction) {
            1 -> "▲"
            -1 -> "▼"
            else -> null
        }

        // Kopfzeile rechts: «▼ 2.31%» (24 h) als Pille in der Kursfarbe; wird es eng, fällt der
        // Titel weg (Logo und Pille bleiben), statt «Portf…»
        val pillText = if (percent != null && amount != null) {
            listOfNotNull(arrow, PortfolioSnapshotMath.unsignedPercent(percent)).joinToString(" ")
        } else null
        val title = context.getString(R.string.widget_portfolio_name)
        val titleFits = PortfolioWidgetMath.showsTitle(
            widthDp,
            toolkit.textWidthDp(title, 13f),
            pillText?.let { toolkit.textWidthDp(it, 11f, tabular = true) },
        )
        views.setTextViewText(R.id.portfolio_title, if (titleFits) title else "")
        if (pillText != null) {
            views.setViewVisibility(R.id.portfolio_pill, View.VISIBLE)
            views.setTextViewText(R.id.portfolio_pill_text, pillText)
            views.setTextColor(R.id.portfolio_pill_text, priceColor)
            toolkit.pill(views, R.id.portfolio_pill_bg, priceColor, style.dark, style.highContrast)
        } else {
            views.setViewVisibility(R.id.portfolio_pill, View.GONE)
        }

        // Gesamtwert (passt sich der Breite an)
        val total = PortfolioInsights.mask(PriceFormat.valueWithCurrency(snapshot.total, snapshot.currency), style.hideAmounts)
        views.setTextViewText(R.id.portfolio_total, total)
        views.setTextColor(R.id.portfolio_total, colors.textColor)

        // «▼ −1’968.40 CHF · 24h»; passt «· 24h» nicht in die Breite, ohne
        val textWidth = when {
            widthDp <= 0 -> null
            layout.size == PortfolioWidgetSize.MEDIUM -> (widthDp - 24f - 12f) / 2f
            else -> widthDp - 24f
        }
        if (layout.changeLine && amount != null) {
            val value = listOfNotNull(
                arrow,
                PortfolioInsights.mask(PortfolioSnapshotMath.signedAmount(amount, snapshot.currency), style.hideAmounts),
            ).joinToString(" ")
            val text = WidgetTextFit.firstFitting(
                listOf("$value · ${ChangeBasisText.shortLabel(context, style.basis)}", value),
                textWidth?.minus(WidgetTextFit.SAFETY_DP),
            ) { toolkit.textWidthDp(it, 12f, tabular = true) }
            views.setViewVisibility(R.id.portfolio_change, View.VISIBLE)
            views.setTextViewText(R.id.portfolio_change, text)
            views.setTextColor(R.id.portfolio_change, priceColor)
        } else {
            views.setViewVisibility(R.id.portfolio_change, View.GONE)
        }

        // «≈ 100’143.67 USDT» — je Widget wählbar; nicht, wenn die Anzeige schon USD ist
        val usdt = snapshot.totalUsdt?.takeIf { layout.usdt && !style.hideAmounts }?.let { PriceFormat.valueWithCurrency(it, USDT) }
        if (usdt != null) {
            views.setViewVisibility(R.id.portfolio_usdt, View.VISIBLE)
            views.setTextViewText(R.id.portfolio_usdt, "≈ $usdt")
            views.setTextColor(R.id.portfolio_usdt, colors.secondaryTextColor)
        } else {
            views.setViewVisibility(R.id.portfolio_usdt, View.GONE)
        }

        // Wertverlauf: Fläche ab genug Stundenwerten, sonst ruhig «Verlauf folgt». Tages-Basis:
        // feste Achse vom Tagesbeginn bis Tagesende, der Verlauf wächst ab dem ersten Wert über den Tag
        val points = snapshot.history.filter { it.value.isFinite() }
        // Verlauf seit Tagesbeginn: «heute» / «heute UTC»; sonst «24h» bzw. «48h» (ältere Aufnahmen)
        val chartBasis = snapshot.stamp?.basis ?: ChangeBasis.ROLLING_24H
        val dayAxis = snapshot.stamp?.takeIf { it.basis.isDay }
            ?.let { s -> ChangeBasisMath.dayEnd(s.basis, s.dayStart)?.let { end -> s.dayStart to end } }
        val drawable = layout.chart &&
            if (dayAxis != null) PortfolioWidgetSeries.drawableDay(points) else PortfolioWidgetSeries.drawable(points)
        val periodShort = if (drawable) {
            when {
                chartBasis.isDay -> ChangeBasisText.shortLabel(context, chartBasis)
                spanAtMostDay(points) -> context.getString(R.string.widget_range_short_24h)
                else -> context.getString(R.string.widget_portfolio_range_short_48h)
            }
        } else null
        val chartDescription = if (drawable) {
            val periodLong = when {
                chartBasis.isDay -> ChangeBasisText.longLabel(context, chartBasis)
                spanAtMostDay(points) -> context.getString(R.string.widget_range_24h)
                else -> context.getString(R.string.widget_portfolio_range_48h)
            }
            val hiddenSpoken = context.getString(R.string.a11y_amount_hidden)
            A11yText.chart(context, periodLong, points.map { it.value }) {
                if (style.hideAmounts) hiddenSpoken else PriceFormat.valueWithCurrency(it, snapshot.currency)
            }
        } else null
        if (layout.chart) {
            views.setViewVisibility(R.id.portfolio_chart_box, View.VISIBLE)
            val chartColor = when (direction ?: if (PortfolioWidgetMath.isUp(points)) 1 else -1) {
                1 -> colors.upColor
                -1 -> colors.downColor
                else -> colors.neutralColor
            }
            val (chartW, chartH) = PortfolioWidgetMath.chartSizeDp(layout, widthDp, heightDp, fontScale)
            val bitmap = if (drawable) {
                withContext(Dispatchers.Default) {
                    runCatching { drawPortfolioChart(appWidgetId, slot, points, chartColor, colors.secondaryTextColor, chartW, chartH, dayAxis) }
                        .onFailure { Timber.w(it, "Wertverlauf für Portfolio-Widget %d nicht gezeichnet", appWidgetId) }
                        .getOrNull()
                }
            } else null
            if (bitmap != null) {
                views.setImageViewBitmap(R.id.portfolio_chart, bitmap)
                views.setViewVisibility(R.id.portfolio_chart, View.VISIBLE)
                views.setViewVisibility(R.id.portfolio_chart_pending, View.GONE)
            } else {
                views.setViewVisibility(R.id.portfolio_chart, View.GONE)
                views.setViewVisibility(R.id.portfolio_chart_pending, View.VISIBLE)
                views.setTextColor(R.id.portfolio_chart_pending, colors.secondaryTextColor)
            }
        } else {
            views.setViewVisibility(R.id.portfolio_chart_box, View.GONE)
        }
        // Platzhalter hält die Fusszeile unten: klein und mittel (linke Spalte); sonst füllt der Chart
        views.setViewVisibility(
            R.id.portfolio_spacer,
            if (layout.size == PortfolioWidgetSize.SMALL || layout.size == PortfolioWidgetSize.MEDIUM) View.VISIBLE else View.GONE
        )

        // Gross: die drei grössten Positionen
        val positionsText = if (layout.size == PortfolioWidgetSize.LARGE) {
            PortfolioWidgetPositions.render(context, views, snapshot.positions.take(layout.rows), colors, style.basis)
        } else null

        // «Stand 15:19 · 24h»; alter Stand ausgeschrieben: «veraltet · 06:42»
        val stamp = WidgetOutdated.stamp(context, snapshot.time)
        val timeText = if (outdated) WidgetOutdated.label(context, snapshot.time)
        else context.getString(R.string.widget_portfolio_as_of, stamp)
        views.setViewVisibility(R.id.portfolio_time, if (layout.footer) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.portfolio_time, listOfNotNull(timeText, periodShort).joinToString(" · "))
        views.setTextColor(R.id.portfolio_time, colors.secondaryTextColor)

        // Screenreader: «Portfolio 83’170.32 CHF, gesunken um 2.31% in 24 Stunden, Stand 15:19» —
        // dann ≈ USDT, Verlauf und Positionen (nur was sichtbar ist); veraltet immer
        val change = when {
            percent != null -> ChangeBasisText.spoken(context, style.basis, percent)
            amount != null && direction == 0 ->
                ChangeBasisText.spokenPhrase(context, style.basis, context.getString(R.string.a11y_change_flat))
            amount != null -> ChangeBasisText.spokenPhrase(
                context,
                style.basis,
                context.getString(
                    if (amount > 0) R.string.a11y_change_up else R.string.a11y_change_down,
                    if (style.hideAmounts) context.getString(R.string.a11y_amount_hidden)
                    else PriceFormat.valueWithCurrency(kotlin.math.abs(amount), snapshot.currency)
                )
            )
            else -> null
        }
        views.setContentDescription(
            R.id.portfolio_root,
            listOfNotNull(
                "$title " + if (style.hideAmounts) context.getString(R.string.a11y_amount_hidden) else total,
                change,
                if (outdated) WidgetOutdated.spoken(context, snapshot.time)
                else context.getString(R.string.widget_portfolio_as_of, stamp),
                usdt?.let { context.getString(R.string.a11y_converted, it) },
                chartDescription,
                positionsText,
            ).joinToString(", ")
        )
        return views
    }

    /**
     * Grössen des Widgets in dp: ab Android 12 alle, die der Launcher meldet (Hoch- und
     * Querformat, höchstens [MAX_PORTFOLIO_SIZES]); sonst bzw. ohne Angabe die eine aus
     * [widgetSizeDp]. Leer nie — unbekannt ist (0, 0).
     */
    private fun portfolioSizesDp(manager: AppWidgetManager, appWidgetId: Int): List<Pair<Int, Int>> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val options = runCatching { manager.getAppWidgetOptions(appWidgetId) }.getOrNull()
            val sizes = options?.let {
                runCatching { BundleCompat.getParcelableArrayList(it, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java) }
                    .getOrNull()
            }.orEmpty()
                .map { it.width.roundToInt() to it.height.roundToInt() }
                .filter { (w, h) -> w > 0 && h > 0 }
                .distinct()
                .take(MAX_PORTFOLIO_SIZES)
            if (sizes.isNotEmpty()) return sizes
        }
        return listOf(toolkit.sizeDp(manager, appWidgetId))
    }

    /** Wertverlauf über höchstens rund einen Tag? (Dann «24h», sonst «48h».) */
    private fun spanAtMostDay(points: List<PortfolioValuePoint>): Boolean =
        points.last().time - points.first().time <= DAY_SPAN_MILLIS

    /**
     * Wertverlauf als Bitmap in der Grösse der Bildfläche ([wDp] × [hDp], aus
     * [PortfolioWidgetMath.chartSizeDp]); ohne Angaben eine feste Grösse. Gleiche Punkte,
     * Farben und Grösse ergeben dasselbe Bild — dann aus [portfolioCharts] statt neu gezeichnet.
     */
    private fun drawPortfolioChart(
        appWidgetId: Int,
        slot: Int,
        points: List<PortfolioValuePoint>,
        color: Int,
        baselineColor: Int,
        wDp: Float,
        hDp: Float,
        axis: Pair<Long, Long>?,
    ): android.graphics.Bitmap {
        val density = context.resources.displayMetrics.density
        val w = (if (wDp > 0f) wDp else DEFAULT_CHART_WIDTH_DP).coerceAtLeast(40f)
        val h = (if (hDp > 0f) hDp else DEFAULT_CHART_HEIGHT_DP).coerceAtLeast(PortfolioWidgetMath.CHART_MIN_HEIGHT_DP)
        var scale = density
        val pixels = w * scale * h * scale
        if (pixels > MAX_CHART_PIXELS) scale *= kotlin.math.sqrt(MAX_CHART_PIXELS / pixels)
        val widthPx = (w * scale).toInt()
        val heightPx = (h * scale).toInt()
        val key = PortfolioChartKey(points, color, baselineColor, widthPx, heightPx, scale, axis)
        val cacheId = appWidgetId * MAX_PORTFOLIO_SIZES + slot
        portfolioCharts.get(cacheId, key)?.let { return it }
        return WidgetChartRenderer.drawPortfolioArea(
            points = points,
            color = color,
            baselineColor = baselineColor,
            widthPx = widthPx,
            heightPx = heightPx,
            density = scale,
            axis = axis,
        ).also { portfolioCharts.put(cacheId, key, it) }
    }

    /** Gelöschte Portfolio-Widgets: zwischengespeicherte Charts aller Grössen freigeben. */
    fun forget(appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> repeat(MAX_PORTFOLIO_SIZES) { portfolioCharts.remove(id * MAX_PORTFOLIO_SIZES + it) } }
    }

    /** Öffnet die App im Portfolio-Tab (falls eingeschaltet, sonst die Merkliste). */
    private fun openPortfolio(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Eigene Action: Extras zählen nicht zur Identität eines PendingIntent — ohne sie
            // teilte er sich Request-Code 2 mit der Mitteilung von Paar-Id 2 (AppNotifier)
            // und FLAG_UPDATE_CURRENT überschriebe gegenseitig das Ziel.
            action = ACTION_OPEN_PORTFOLIO
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN, "portfolio")
        }
        return PendingIntent.getActivity(
            context,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Wovon das Bild des Portfolio-Wertverlaufs abhängt (siehe [portfolioCharts]). */
    private data class PortfolioChartKey(
        val points: List<PortfolioValuePoint>,
        val color: Int,
        val baselineColor: Int,
        val widthPx: Int,
        val heightPx: Int,
        val density: Float,
        val axis: Pair<Long, Long>?,
    )

    private companion object {
        /** Bis zu dieser Spanne heisst der Wertverlauf «24h», darüber «48h». */
        const val DAY_SPAN_MILLIS = 25 * 3_600_000L
        /** Höchstens so viele Grössen je Portfolio-Widget (Hoch-/Querformat, Faltgeräte). */
        const val MAX_PORTFOLIO_SIZES = 4
        const val ACTION_OPEN_PORTFOLIO = "com.cryptochecker.app.action.OPEN_PORTFOLIO"
    }
}
