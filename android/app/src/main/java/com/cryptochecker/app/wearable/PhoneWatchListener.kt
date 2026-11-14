package com.cryptochecker.app.wearable

import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Neue Wahl bzw. eigene Liste auf der Uhr: Einträge sofort aus dem Puffer lesen (das System gibt ihn nach dem Aufruf
 * frei), dann ohne den Aufruf zu blockieren im Prozess-Scope von [PhoneWatchSync] übernehmen. Uhr-App dazugekommen
 * (Fähigkeit [PhoneWatchSync.CAPABILITY] mit einer Uhr): den ganzen Stand ablegen — Wahl, Favoriten, Kursquelle,
 * Intervall ([PhoneWatchSync.republish]), Kurse und Portfolio-Stand ([PhoneWatchFeed.resend]); wie iOS
 * `sessionWatchStateDidChange`. Zähler der Uhr für «Akku & Aktivität» ([PhoneWatchUsage.PATH]) merkt sich
 * [PhoneWatchUsage].
 */
@AndroidEntryPoint
class PhoneWatchListener : WearableListenerService() {
    @Inject lateinit var sync: PhoneWatchSync
    @Inject lateinit var feed: PhoneWatchFeed
    @Inject lateinit var usage: PhoneWatchUsage

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val items = PhoneWatchSync.changed(dataEvents)
        if (items.isNotEmpty()) sync.takeFromWatch(items)
        // Nur eine kleine Datei: gleich hier, der Puffer gilt nur während des Aufrufs
        PhoneWatchUsage.changed(dataEvents)?.let(usage::take)
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        if (capabilityInfo.name != PhoneWatchSync.CAPABILITY || capabilityInfo.nodes.isEmpty()) return
        sync.republish()
        feed.resend()
    }
}
