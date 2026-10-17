// Bild des Charts im Einzel-Widget (Teil von `SingleWidgetRenderer`) samt Schlüssel des zuletzt gezeichneten
// Bilds je Widget. Eigene Datei, weil das Zeichnen vom Laden und Binden getrennt lesbar sein soll.
package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import com.cryptochecker.app.util.PriceFormat

/**
 * Chart in der tatsächlichen Grösse der Bildfläche: Widget-Grösse aus den
 * Widget-Optionen (Hochformat: minWidth × maxHeight, Querformat: maxWidth × minHeight)
 * abzüglich Rand und Textzeilen von widget_single.xml. Ohne Angaben eine feste Grösse.
 */
internal fun SingleWidgetRenderer.drawChart(
    manager: AppWidgetManager,
    appWidgetId: Int,
    candles: List<WidgetCandle>,
    type: WidgetChartType,
    range: WidgetChartRange,
    colors: WidgetColors,
    highContrast: Boolean,
    currentPrice: Double?,
    /** Tages-/Stundenraster in der Chart-Zeitzone ([ChangeBasisMath.chartZone]). */
    zone: java.time.ZoneId,
    /** Stablecoin: grössere Mindesthöhe der Achse ([WidgetChartGeometry.STABLE_MIN_SPAN]). */
    stable: Boolean = false,
): android.graphics.Bitmap {
    val metrics = context.resources.displayMetrics
    val config = context.resources.configuration
    val density = metrics.density
    val fontScale = config.fontScale.takeIf { it > 0f } ?: 1f
    val options = runCatching { manager.getAppWidgetOptions(appWidgetId) }.getOrNull()
    val landscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val widgetW = options?.getInt(
        if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0
    ) ?: 0
    val widgetH = options?.getInt(
        if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0
    ) ?: 0
    // Feste Teile des Layouts: Rand 2 × 12 dp; Kopfzeile 14 sp, Börse 12 sp, Kurs 24 sp
    // (+4 dp), Chart-Abstand 6 dp, Uhrzeit 12 sp (+2 dp). Zeilenhöhe ≈ 1.35 × Schriftgrösse.
    val chartWDp = if (widgetW > 0) (widgetW - 24f) else DEFAULT_CHART_WIDTH_DP
    val chartHDp = if (widgetH > 0) (widgetH - 24f - 12f - (14f + 12f + 24f + 12f) * 1.35f * fontScale) else DEFAULT_CHART_HEIGHT_DP
    val wDp = chartWDp.coerceAtLeast(60f)
    val hDp = chartHDp.coerceAtLeast(32f)
    // Bitmap-Speicher begrenzen (RemoteViews-Grenze): höchstens ~1 MP; fitXY skaliert gleichmässig zurück.
    var scale = density
    val pixels = wDp * scale * hDp * scale
    if (pixels > MAX_CHART_PIXELS) scale *= kotlin.math.sqrt(MAX_CHART_PIXELS / pixels)
    val widthPx = (wDp * scale).toInt()
    val heightPx = (hDp * scale).toInt()
    val compact = WidgetChartGeometry.isCompact(wDp, hDp)

    // Alles, wovon das Bild abhängt; der Kurs nur so genau, wie er im Etikett steht
    val key = ChartBitmapKey(
        candles = candles,
        type = type,
        range = range,
        accentColor = colors.accentColor,
        onAccentColor = colors.onAccentColor,
        secondaryTextColor = colors.secondaryTextColor,
        upColor = colors.upColor,
        downColor = colors.downColor,
        highContrast = highContrast,
        widthPx = widthPx,
        heightPx = heightPx,
        density = scale,
        fontScale = fontScale,
        compact = compact,
        priceTag = PriceFormat.price(currentPrice?.takeIf { it > 0.0 } ?: candles.last().close),
        zone = zone.id,
        locale = config.locales.toLanguageTags(),
        stable = stable,
    )
    chartBitmaps.get(appWidgetId, key)?.let { return it }

    return WidgetChartRenderer.draw(
        candles = candles,
        type = type,
        range = range,
        colors = colors,
        highContrast = highContrast,
        widthPx = widthPx,
        heightPx = heightPx,
        density = scale,
        fontScale = fontScale,
        compact = compact,
        formatPrice = PriceFormat::price,
        currentPrice = currentPrice,
        zone = zone,
        stable = stable,
    ).also { chartBitmaps.put(appWidgetId, key, it) }
}

/** Wovon das Bild des Einzel-Widget-Charts abhängt (siehe [chartBitmaps]). */
internal data class ChartBitmapKey(
    // Die Kerzen selbst (nicht nur ihr Hash): keine Verwechslung bei gleichem Hash.
    // Meist dieselbe Liste aus sparkCache, der Vergleich ist dann sofort entschieden.
    val candles: List<WidgetCandle>,
    val type: WidgetChartType,
    val range: WidgetChartRange,
    val accentColor: Int,
    val onAccentColor: Int,
    val secondaryTextColor: Int,
    val upColor: Int,
    val downColor: Int,
    val highContrast: Boolean,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val fontScale: Float,
    val compact: Boolean,
    val priceTag: String,
    val zone: String,
    val locale: String,
    val stable: Boolean,
)
