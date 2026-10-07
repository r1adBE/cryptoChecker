package com.cryptochecker.app.widget

import androidx.core.content.edit
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Hintergrund eines Widgets: wie das Handy, immer dunkel oder immer hell. */
enum class WidgetTheme { SYSTEM, DARK, LIGHT }

/**
 * Einstellungen je Widget. Bewusst SharedPreferences: Die Werte werden auch
 * synchron im Broadcast-Empfänger und in der RemoteViews-Fabrik gebraucht.
 */
@Singleton
class WidgetPrefs @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs get() = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Pro Widget wählbar: Deckkraft und Hintergrund (System/Dunkel/Hell).
     * Die Akzentfarbe kommt aus der App-Einstellung.
     */
    fun setOpacity(appWidgetId: Int, opacityPercent: Int) {
        prefs.edit {
            putInt(opacityKey(appWidgetId), opacityPercent.coerceIn(0, 100))
            // Frühere Hintergrundwahl wird nicht mehr gebraucht.
            remove(backgroundKey(appWidgetId))
        }
    }

    /** Einzel-Widget: welches Paar es zeigt. */
    fun setWatchId(appWidgetId: Int, watchId: Long) {
        prefs.edit { putLong(watchKey(appWidgetId), watchId) }
    }

    fun getWatchId(appWidgetId: Int): Long? =
        prefs.getLong(watchKey(appWidgetId), -1L).takeIf { it > 0 }

    fun setTheme(appWidgetId: Int, theme: WidgetTheme) {
        prefs.edit { putString(themeKey(appWidgetId), theme.name) }
    }

    fun getTheme(appWidgetId: Int): WidgetTheme =
        prefs.getString(themeKey(appWidgetId), null)
            ?.let { name -> WidgetTheme.entries.firstOrNull { it.name == name } }
            ?: WidgetTheme.SYSTEM

    /** Dunkel für dieses Widget? «System» folgt dem Handy. */
    fun isDark(appWidgetId: Int): Boolean = when (getTheme(appWidgetId)) {
        WidgetTheme.SYSTEM -> com.cryptochecker.app.util.SystemTheme.isDark()
        WidgetTheme.DARK -> true
        WidgetTheme.LIGHT -> false
    }

    fun getOpacity(appWidgetId: Int): Int =
        prefs.getInt(opacityKey(appWidgetId), WidgetColors.DEFAULT_OPACITY)

    fun remove(appWidgetId: Int) {
        prefs.edit {
            remove(backgroundKey(appWidgetId))
            remove(opacityKey(appWidgetId))
            remove(themeKey(appWidgetId))
            remove(watchKey(appWidgetId))
            remove(chartRangeKey(appWidgetId))
            remove(chartTypeKey(appWidgetId))
            remove(groupKey(appWidgetId))
            remove(portfolioShowUsdtKey(appWidgetId))
        }
    }

    /**
     * Portfolio-Widget: zweite Zeile «≈ … USDT» unter dem Gesamtwert
     * («Umrechnung + USDT»). Ohne gespeicherten Wert (neue und bestehende Widgets): an.
     */
    fun setPortfolioShowUsdt(appWidgetId: Int, show: Boolean) {
        prefs.edit { putBoolean(portfolioShowUsdtKey(appWidgetId), show) }
    }

    fun getPortfolioShowUsdt(appWidgetId: Int): Boolean =
        prefs.getBoolean(portfolioShowUsdtKey(appWidgetId), true)

    private fun portfolioShowUsdtKey(appWidgetId: Int) = "portfolio_show_usdt_$appWidgetId"

    /** Einzel-Widget: Zeitraum des Mini-Charts (24 h / 7 Tage / 30 Tage). */
    fun setChartRange(appWidgetId: Int, range: WidgetChartRange) {
        prefs.edit { putString(chartRangeKey(appWidgetId), range.name) }
    }

    fun getChartRange(appWidgetId: Int): WidgetChartRange =
        prefs.getString(chartRangeKey(appWidgetId), null)
            ?.let { name -> WidgetChartRange.entries.firstOrNull { it.name == name } }
            ?: WidgetChartRange.DAY

    /** Einzel-Widget: Chart-Art (Kerzen / Linie). */
    fun setChartType(appWidgetId: Int, type: WidgetChartType) {
        prefs.edit { putString(chartTypeKey(appWidgetId), type.name) }
    }

    /** Ohne gespeicherten Wert (neue und bestehende Widgets): Kerzen. */
    fun getChartType(appWidgetId: Int): WidgetChartType =
        prefs.getString(chartTypeKey(appWidgetId), null)
            ?.let { name -> WidgetChartType.entries.firstOrNull { it.name == name } }
            ?: WidgetChartType.CANDLES

    /**
     * Wirksamer hoher Kontrast beim letzten Zeichnen der Widgets; null = noch nie
     * gezeichnet (oder vor diesem Update). Ändert sich der System-Kontrast, merkt
     * es die App beim nächsten Start und zeichnet neu.
     */
    var lastHighContrast: Boolean?
        get() = if (prefs.contains(KEY_LAST_HIGH_CONTRAST)) prefs.getBoolean(KEY_LAST_HIGH_CONTRAST, false) else null
        set(value) {
            if (value == null) prefs.edit { remove(KEY_LAST_HIGH_CONTRAST) }
            else prefs.edit { putBoolean(KEY_LAST_HIGH_CONTRAST, value) }
        }

    private fun chartRangeKey(appWidgetId: Int) = "widget_chart_range_$appWidgetId"
    private fun chartTypeKey(appWidgetId: Int) = "widget_chart_type_$appWidgetId"

    private fun backgroundKey(appWidgetId: Int) = "widget_bg_$appWidgetId"
    private fun opacityKey(appWidgetId: Int) = "widget_opacity_$appWidgetId"
    private fun themeKey(appWidgetId: Int) = "widget_theme_$appWidgetId"
    private fun watchKey(appWidgetId: Int) = "widget_watch_$appWidgetId"

    /** Listen-Widget: nur die Paare dieser Gruppe zeigen. null = «Alle». */
    fun setGroup(appWidgetId: Int, group: String?) {
        prefs.edit {
            if (group == null) remove(groupKey(appWidgetId))
            else putString(groupKey(appWidgetId), group)
        }
    }

    fun getGroup(appWidgetId: Int): String? = prefs.getString(groupKey(appWidgetId), null)

    private fun groupKey(appWidgetId: Int) = "$GROUP_PREFIX$appWidgetId"

    /**
     * Gruppe umbenannt ([new]) oder gelöscht (null): Listen-Widgets, die
     * [old] zeigen, folgen dem neuen Namen bzw. zeigen wieder «Alle».
     */
    fun replaceGroup(old: String, new: String?) {
        val current = prefs
        val keys = current.all.filter { (key, value) -> key.startsWith(GROUP_PREFIX) && value == old }.keys
        if (keys.isEmpty()) return
        current.edit {
            keys.forEach { key -> if (new == null) remove(key) else putString(key, new) }
        }
    }

    private companion object {
        const val FILE_NAME = "widgets"
        const val KEY_LAST_HIGH_CONTRAST = "last_high_contrast"
        const val GROUP_PREFIX = "widget_group_"
    }
}
