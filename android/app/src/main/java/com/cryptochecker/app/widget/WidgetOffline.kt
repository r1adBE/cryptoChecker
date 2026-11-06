package com.cryptochecker.app.widget

import android.content.Context
import com.cryptochecker.app.R
import com.cryptochecker.app.util.ConnectivityMonitor
import com.cryptochecker.app.work.WidgetReconnectWorker
import dagger.hilt.android.qualifiers.ApplicationContext
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
    /** Internet da? Wenn nicht, ist das Neuzeichnen bei Rückkehr des Netzes geplant (einmal, KEEP). */
    fun online(): Boolean {
        val online = connectivity.isOnlineNow()
        if (!online) WidgetReconnectWorker.enqueue(context)
        return online
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
