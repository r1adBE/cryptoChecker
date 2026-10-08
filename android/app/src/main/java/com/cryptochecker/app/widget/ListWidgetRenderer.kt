package com.cryptochecker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.cryptochecker.app.R
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.domain.refresh.OutdatedRule
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.util.PriceFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listen-Widget «Merkliste»: Kopf mit Uhrzeit und Aktualisieren, darunter die Zeilen ([ListWidgetRows]).
 * Ab Android 12 kommen die Zeilen im selben Schritt wie der Kopf ins Widget (RemoteCollectionItems) —
 * kein Dienst, der erst gebunden werden muss und hängen bleiben kann («Wird geladen …»). Darunter
 * und bei sehr langen Listen liefert sie [PriceWidgetService].
 */
@Singleton
class ListWidgetRenderer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val widgetPrefs: WidgetPrefs,
    private val refreshStats: RefreshStats,
    private val settingsRepository: SettingsRepository,
    private val toolkit: WidgetToolkit,
    private val rows: ListWidgetRows,
) {
    /** Zeichnet die Listen-Widgets [appWidgetIds]; false, wenn nichts zu zeichnen war. */
    suspend fun update(appWidgetIds: IntArray): Boolean {
        if (appWidgetIds.isEmpty()) return false
        val manager = AppWidgetManager.getInstance(context) ?: return false

        // Akzentfarbe wie in der App; Hell/Dunkel je Widget (System/Dunkel/Hell).
        val settings = settingsRepository.current()
        val accent = settings.accentColor
        val highContrast = HighContrast.isEffective(context, settings.highContrast)
        widgetPrefs.lastHighContrast = highContrast

        // Liegt die letzte erfolgreiche Aktualisierung zu weit zurück: «veraltet · 06:42» statt der Uhrzeit
        val lastRefresh = refreshStats.lastRefresh()
        val outdated = OutdatedRule.isOutdated(lastRefresh, System.currentTimeMillis(), WidgetOutdated.afterMillis(settings))

        // Widgets, deren Zeilen der Dienst liefert: danach neu laden lassen
        val viaService = ArrayList<Int>()
        for (appWidgetId in appWidgetIds) {
            val dark = widgetPrefs.isDark(appWidgetId)
            val colors = WidgetColors.of(accent, dark, settings.priceColorScheme, highContrast, settings.priceColorsInverted)
            val inline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) inlineRows(appWidgetId) else null
            if (inline == null) viaService += appWidgetId
            runCatching {
                manager.updateAppWidget(appWidgetId, render(manager, appWidgetId, colors, accent.logoRes(dark), lastRefresh, outdated, inline))
            }.onFailure { Timber.w(it, "Widget %d konnte nicht gezeichnet werden", appWidgetId) }
        }

        // Fordert die Liste an, ihre Daten neu zu laden (nur Zeilen aus dem Dienst).
        // Seit API 31 zugunsten von RemoteCollectionItems abgekündigt — die nutzen wir dort.
        if (viaService.isNotEmpty()) {
            @Suppress("DEPRECATION")
            runCatching { manager.notifyAppWidgetViewDataChanged(viaService.toIntArray(), R.id.widget_list) }
                .onFailure { Timber.w(it, "Widget-Liste konnte nicht aktualisiert werden") }
        }
        return true
    }

    private fun render(
        manager: AppWidgetManager,
        appWidgetId: Int,
        background: WidgetColors,
        logoRes: Int,
        lastRefresh: Long,
        outdated: Boolean,
        /** Zeilen direkt im Widget (ab Android 12); null = Dienst. */
        inline: RemoteViews.RemoteCollectionItems?,
    ): RemoteViews {
        val opacity = widgetPrefs.getOpacity(appWidgetId)
        val views = RemoteViews(context.packageName, R.layout.widget_list)

        toolkit.background(views, R.id.widget_bg, background, opacity)
        // Kopf: Logo · «Merkliste» links, rechts «Uhrzeit · Dauer» und der Aktualisieren-Knopf.
        // Knopf neutral: weiss im Dunkeln, schwarz im Hellen — nicht in der Akzentfarbe
        views.setInt(R.id.widget_refresh, "setColorFilter", background.textColor)
        views.setImageViewResource(R.id.widget_logo, logoRes)

        // Uhrzeit zuerst: Nur daran erkennt man, ob die Daten alt sind. Nie abgeschnitten:
        // erst fällt die Dauer weg, dann wird der Titel kleiner, dann fällt die Uhrzeit weg.
        // Veraltet: «veraltet · 06:42» ohne Dauer; eng nur «veraltet» (das Wort bleibt).
        val title = context.getString(R.string.tab_watchlist)
        val header = ListWidgetHeader.layout(
            widthDp = toolkit.sizeDp(manager, appWidgetId).first,
            time = if (outdated) WidgetOutdated.label(context, lastRefresh) else PriceFormat.time(lastRefresh).takeIf { it != "—" },
            duration = if (outdated) null else PriceFormat.duration(refreshStats.lastDuration()),
            titleWidthDp = { toolkit.textWidthDp(title, it) },
            statusWidthDp = { toolkit.textWidthDp(it, 11f, bold = false, tabular = true) },
            fallback = if (outdated) context.getString(R.string.widget_outdated) else null,
        )
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, header.titleSp)
        views.setTextColor(R.id.widget_title, background.textColor)
        views.setTextViewText(R.id.widget_duration, header.status)
        views.setTextColor(R.id.widget_duration, background.secondaryTextColor)
        // Screenreader: «veraltet, letzte Aktualisierung 06:42» statt der Kurzform
        views.setContentDescription(
            R.id.widget_duration,
            if (outdated && header.status.isNotEmpty()) WidgetOutdated.spoken(context, lastRefresh) else null
        )
        views.setTextViewText(R.id.widget_empty, context.getString(R.string.widget_empty))
        views.setTextColor(R.id.widget_empty, background.secondaryTextColor)

        if (inline != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setRemoteAdapter(R.id.widget_list, inline)
        } else {
            val adapterIntent = Intent(context, PriceWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                // Ohne eigene data-Uri teilen sich mehrere Widgets eine Fabrik.
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.widget_list, adapterIntent)
        }
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)

        views.setPendingIntentTemplate(R.id.widget_list, openAppTemplate())
        // Der Kopf (Logo, Titel, Uhrzeit) öffnet die App
        views.setOnClickPendingIntent(R.id.widget_header, toolkit.openApp())
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(appWidgetId))

        return views
    }

    /**
     * Zeilen für [appWidgetId] direkt im Widget (ab Android 12). Null unter Android 12, bei mehr als
     * [MAX_INLINE_ROWS] Paaren (alles geht in einem Binder-Aufruf mit) oder bei einem Fehler —
     * dann liefert sie [PriceWidgetService].
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun inlineRows(appWidgetId: Int): RemoteViews.RemoteCollectionItems? {
        return runCatching {
            val data = rows.load(appWidgetId)
            if (data.watches.size > MAX_INLINE_ROWS) return@runCatching null
            val builder = RemoteViews.RemoteCollectionItems.Builder()
                .setHasStableIds(true)
                .setViewTypeCount(1)
            data.watches.forEachIndexed { index, watch -> builder.addItem(watch.id, rows.row(data, index)) }
            builder.build()
        }.onFailure {
            if (it is CancellationException) throw it
            Timber.w(it, "Widget-Zeilen %d: weiter über den Dienst", appWidgetId)
        }.getOrNull()
    }

    /** Vorlage für die Zeilen; die Zeile ergänzt nur noch die Id ihres Paares. */
    private fun openAppTemplate(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Eigene Action: sonst gleiche Identität wie die allgemeine Mitteilung (Request-Code 0)
            action = ACTION_OPEN_WIDGET_ROW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun refreshIntent(appWidgetId: Int): PendingIntent {
        val intent = Intent(context, PriceWidgetProvider::class.java).apply {
            action = PriceWidgetProvider.ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val ACTION_OPEN_WIDGET_ROW = "com.cryptochecker.app.action.OPEN_FROM_WIDGET_ROW"

        /** Bis so viele Paare direkt im Widget; darüber über den Dienst (Grösse eines Binder-Aufrufs). */
        const val MAX_INLINE_ROWS = 200
    }
}
