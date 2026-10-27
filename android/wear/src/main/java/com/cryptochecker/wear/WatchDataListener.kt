package com.cryptochecker.wear

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/** Neue Wahl bzw. neue Favoriten vom Handy: übernehmen und die Komplikation neu zeichnen (Build 154). */
class WatchDataListener : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.release()
        val store = WatchStore(this)
        runBlocking { WatchDataLayer.pull(this@WatchDataListener, store) }
        requestComplicationUpdate(this)
    }
}
