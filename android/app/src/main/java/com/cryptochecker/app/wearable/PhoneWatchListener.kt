package com.cryptochecker.app.wearable

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Neue Wahl bzw. eigene Liste auf der Uhr: Einträge sofort aus dem Puffer lesen (das System gibt ihn nach dem Aufruf
 * frei), dann ohne den Aufruf zu blockieren im Prozess-Scope von [PhoneWatchSync] übernehmen.
 */
@AndroidEntryPoint
class PhoneWatchListener : WearableListenerService() {
    @Inject lateinit var sync: PhoneWatchSync

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val items = PhoneWatchSync.changed(dataEvents)
        if (items.isNotEmpty()) sync.takeFromWatch(items)
    }
}
