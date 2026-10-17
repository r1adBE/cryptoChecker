package com.cryptochecker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cryptochecker.app.R
import timber.log.Timber

/** Die Widgets der App, wie sie die App zum Hinzufügen anbietet (Einstellungen › Widgets). */
enum class WidgetKind(
    val provider: Class<out AppWidgetProvider>,
    val nameRes: Int,
    val descriptionRes: Int,
    /** Vorschaubild (dasselbe wie in der Widget-Auswahl des Startbildschirms). */
    val previewRes: Int,
) {
    LIST(PriceWidgetProvider::class.java, R.string.tab_watchlist, R.string.widget_description, R.drawable.widget_preview_list_img),
    SINGLE(SingleWidgetProvider::class.java, R.string.widget_kind_single, R.string.single_widget_description, R.drawable.widget_preview_single_img),
    PORTFOLIO(PortfolioWidgetProvider::class.java, R.string.widget_portfolio_name, R.string.widget_portfolio_description, R.drawable.widget_preview_portfolio_img),
    PULSE(PulseWidgetProvider::class.java, R.string.pulse_now_title, R.string.widget_pulse_description, R.drawable.widget_preview_pulse_img),
}

/**
 * Widget direkt aus der App auf den Startbildschirm legen
 * ([AppWidgetManager.requestPinAppWidget], ab API 26). Der Startbildschirm zeigt dazu seinen
 * eigenen Dialog; danach meldet er die neue Widget-Id an [WidgetPinReceiver] — so lässt sich
 * ein Einzel-Widget gleich mit dem gewünschten Paar einrichten.
 */
object WidgetPinner {

    /** Kann der Startbildschirm Widgets aus der App annehmen? (Manche Launcher nicht.) */
    fun isSupported(context: Context): Boolean =
        runCatching { AppWidgetManager.getInstance(context)?.isRequestPinAppWidgetSupported == true }
            .getOrDefault(false)

    /**
     * Anfrage stellen. @param watchId nur beim Einzel-Widget: dieses Paar gleich einstellen
     * (ohne öffnet sich nach dem Hinzufügen das Einrichten).
     * @return false, wenn der Startbildschirm das nicht kann — dann eine Anleitung zeigen
     */
    fun request(context: Context, kind: WidgetKind, watchId: Long? = null): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        if (!isSupported(context)) return false
        val callback = Intent(context, WidgetPinReceiver::class.java).apply {
            action = WidgetPinReceiver.ACTION_PINNED
            // Eigene Uri je Art und Paar: Extras zählen nicht zur Identität eines PendingIntent
            data = Uri.parse("cryptochecker://widget-pin/${kind.name}/${watchId ?: "none"}")
            putExtra(WidgetPinReceiver.EXTRA_KIND, kind.name)
            if (watchId != null) putExtra(WidgetPinReceiver.EXTRA_WATCH_ID, watchId)
        }
        // Veränderbar: Das System trägt die neue Widget-Id (EXTRA_APPWIDGET_ID) ein.
        // Ausdrückliches Ziel (eigener Empfänger), daher erlaubt.
        val success = PendingIntent.getBroadcast(
            context,
            0,
            callback,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return runCatching { manager.requestPinAppWidget(ComponentName(context, kind.provider), null, success) }
            .onFailure { Timber.w(it, "Widget %s konnte nicht angefragt werden", kind) }
            .getOrDefault(false)
    }
}
