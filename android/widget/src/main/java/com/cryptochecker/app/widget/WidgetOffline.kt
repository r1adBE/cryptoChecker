package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.RemoteViews
import com.cryptochecker.resources.R
import com.cryptochecker.app.data.util.ConnectivityMonitor
import com.cryptochecker.app.widget.WidgetReconnectWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Keine Verbindung» in den Widgets: Ohne Internet zeigt die Zeit-Zeile «Keine Verbindung · Stand 14:05» statt der
 * Uhrzeit bzw. «veraltet · …» ([WidgetStatusRule]) — derselbe Text wie die Statuszeile der Merkliste
 * (offline_status_since, in allen Sprachen vorhanden). Die Zeilen behalten ihre letzten Kurse.
 *
 * Erkennung wie die App ([ConnectivityMonitor]: Standardnetz mit bestätigtem Internet-Zugang), beim Zeichnen frisch
 * beim System nachgefragt. Zeichnet ein Widget offline, wartet [WidgetReconnectWorker] auf das Netz (WorkManager,
 * Bedingung CONNECTED, kein dauernder Dienst) und zeichnet dann neu und holt die Kurse.
 */
@Singleton
class WidgetConnectivity @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val connectivity: ConnectivityMonitor,
) {
    /** Abfragen von [online] seit dem Start (Gerätetest: eine je Zeichnen, nicht je Widget). */
    val queries = AtomicInteger()

    /** Internet da? Wenn nicht, ist das Neuzeichnen bei Rückkehr des Netzes geplant (einmal, KEEP). */
    fun online(): Boolean {
        queries.incrementAndGet()
        val online = connectivity.isOnlineNow()
        if (!online) WidgetReconnectWorker.enqueue(context)
        return online
    }
}

/**
 * Gezeichnete Widgets an den Launcher ([AppWidgetManager.updateAppWidget]). Gerätetests fangen sie mit [testSink] ab
 * und blähen sie selbst auf (`WidgetDrawDeviceTest`): Was im Widget steht, ist so ohne Launcher prüfbar.
 */
object WidgetPublish {
    /** Nur Gerätetests: statt an den Launcher hierhin; null = Launcher. */
    @Volatile
    var testSink: ((appWidgetId: Int, views: RemoteViews) -> Unit)? = null

    fun update(manager: AppWidgetManager, appWidgetId: Int, views: RemoteViews) {
        val sink = testSink
        if (sink != null) sink(appWidgetId, views) else manager.updateAppWidget(appWidgetId, views)
    }
}

/** Texte zu [WidgetStatusRule.Kind.OFFLINE] bzw. [WidgetStatusRule.Kind.OFFLINE_NO_TIME]. */
object WidgetOffline {

    /** «Keine Verbindung · Stand 14:05» (bzw. mit Tag, [WidgetOutdated.stamp]); ohne Zeit «Keine Verbindung». */
    fun label(context: Context, time: Long): String =
        if (time > 0) context.getString(R.string.offline_status_since, WidgetOutdated.stamp(context, time))
        else short(context)

    /** «Keine Verbindung» — enge Stufe, wenn die Zeit nicht mehr passt (das Wort bleibt). */
    fun short(context: Context): String = context.getString(R.string.offline_status)
}
