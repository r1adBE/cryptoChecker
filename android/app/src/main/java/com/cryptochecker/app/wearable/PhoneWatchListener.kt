package com.cryptochecker.app.wearable

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** Neue Wahl bzw. eigene Liste auf der Uhr: in [PhoneWatchSync] übernehmen (Build 154). */
@AndroidEntryPoint
class PhoneWatchListener : WearableListenerService() {
    @Inject lateinit var sync: PhoneWatchSync

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.release()
        runBlocking { sync.pullFromWatch() }
    }
}
