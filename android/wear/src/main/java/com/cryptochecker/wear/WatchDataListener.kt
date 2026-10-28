package com.cryptochecker.wear

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.launch

/**
 * Neue Wahl bzw. neue Favoriten vom Handy: Einträge sofort aus dem Puffer lesen (das System gibt ihn nach dem Aufruf
 * frei), dann ohne den Aufruf zu blockieren in den gemeinsamen [WatchStore] übernehmen — die offene Uhr-App wechselt
 * die Liste und lädt neu — und die Komplikation neu zeichnen. Die Übernahme läuft in [WatchDataLayer.processScope]
 * weiter, auch wenn der Dienst schon beendet ist.
 */
class WatchDataListener : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val items = WatchDataLayer.changed(dataEvents)
        if (items.isEmpty()) return
        val context = applicationContext
        WatchDataLayer.processScope.launch {
            if (WatchDataLayer.take(context, WatchStore(context), items)) requestComplicationUpdate(context)
        }
    }
}
