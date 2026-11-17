package com.cryptochecker.app.wearable

import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Neue Wahl bzw. eigene Liste auf der Uhr: Einträge sofort aus dem Puffer lesen (das System gibt ihn nach dem Aufruf
 * frei), dann ohne den Aufruf zu blockieren im Prozess-Scope von [PhoneWatchSync] übernehmen. Uhr-App dazugekommen
 * (Fähigkeit [PhoneWatchSync.CAPABILITY] mit einer Uhr): den ganzen Stand ablegen — Wahl, Favoriten, Kursquelle,
 * Intervall ([PhoneWatchSync.republish]), Kurse und Portfolio-Stand ([PhoneWatchFeed.resend]); wie iOS
 * `sessionWatchStateDidChange`. Zähler der Uhr für «Akku & Aktivität» ([PhoneWatchUsage.PATH]) merkt sich
 * [PhoneWatchUsage]. Für «Auf der Uhr zeigen» merkt sich [WatchNotifyBridge], ob die Uhr-App Mitteilungen zeigen darf
 * ([WatchNotifyBridge.STATE_PATH]) und ob eine Uhr mit der App erreichbar ist (Fähigkeit geändert).
 */
@AndroidEntryPoint
class PhoneWatchListener : WearableListenerService() {
    @Inject lateinit var sync: PhoneWatchSync

    @Inject lateinit var feed: PhoneWatchFeed

    @Inject lateinit var usage: PhoneWatchUsage

    @Inject lateinit var notify: WatchNotifyBridge

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val items = PhoneWatchSync.changed(dataEvents)
        if (items.isNotEmpty()) sync.takeFromWatch(items)
        // Nur eine kleine Datei: gleich hier, der Puffer gilt nur während des Aufrufs
        PhoneWatchUsage.changed(dataEvents)?.let(usage::take)
        dataEvents
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WatchNotifyBridge.STATE_PATH }
            .map { DataMapItem.fromDataItem(it.dataItem).dataMap.getBoolean(WatchNotifyBridge.STATE_KEY_PERMITTED, false) }
            .lastOrNull()
            ?.let(notify::takePermitted)
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) =
        watchAppChanged(
            capabilityInfo.name,
            capabilityInfo.nodes.isNotEmpty(),
            notify::takeReachable,
            republish = { sync.republish() },
            resend = { feed.resend() }
        )

    companion object {
        /**
         * Fähigkeit [name] geändert: nur die der Uhr-App ([PhoneWatchSync.CAPABILITY]) zählt. Erreichbarkeit immer
         * merken; mit einer Uhr ([reachable]) den ganzen Stand ([republish]) und Kurse samt Portfolio-Stand ([resend])
         * ablegen (`PhoneWatchRepublishTest`).
         */
        internal fun watchAppChanged(
            name: String,
            reachable: Boolean,
            takeReachable: (Boolean) -> Unit,
            republish: () -> Unit,
            resend: () -> Unit,
        ) {
            if (name != PhoneWatchSync.CAPABILITY) return
            takeReachable(reachable)
            if (!reachable) return
            republish()
            resend()
        }
    }
}
