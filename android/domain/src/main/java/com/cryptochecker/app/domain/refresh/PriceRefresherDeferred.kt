// Späte Tages-Bezüge im Hintergrund nachtragen (Teil von `PriceRefresher`, gespiegelt in `PriceRefresher+Deferred.swift`).
// Erweiterungsfunktionen; Sperre und Abhängigkeiten bleiben in der Klasse.
package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.ChangeStamp
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.cryptochecker.app.domain.refresh.PriceRefresher.Companion.LATE_FILL_MILLIS

/**
 * Tages-Basen: Bezüge, die nach dem Warten ([DayReferences.awaitDayReferenceLoads]) noch kommen,
 * im Hintergrund nachtragen — nur die Veränderung, nur solange Kurs und Basis gleich
 * geblieben sind; danach die Widgets neu zeichnen. Bricht nichts ab und meldet nichts.
 */
fun PriceRefresher.fillLateDayChanges(missing: List<WatchEntity>, loads: List<Job>, stamp: ChangeStamp) {
    if (missing.isEmpty() || loads.all { it.isCompleted }) return
    lateFillScope.launch {
        runCatchingCancellable {
            withTimeoutOrNull(LATE_FILL_MILLIS) { loads.joinAll() }
            // Unter der Refresh-Sperre: kein Durchlauf schreibt dazwischen (neuere Kurse,
            // andere Basis); der Stempel muss noch derjenige dieses Durchlaufs sein
            val filled = refreshLock.withLock { lock -> fillLateDayChangesLocked(lock, missing, stamp) }
            if (filled > 0) liveWidgetGate.drawAll(deferWhenOff = false)
        }
    }
}

/** Späte Tages-Bezüge nachtragen (unter der Sperre, [lock] als Beweis); Anzahl nachgetragener Paare. */
suspend fun PriceRefresher.fillLateDayChangesLocked(
    @Suppress("UNUSED_PARAMETER") lock: RefreshLockScope,
    missing: List<WatchEntity>,
    stamp: ChangeStamp,
): Int {
    val settings = settingsRepository.current()
    if (ChangeBasisMath.stamp(settings.changeBasis, System.currentTimeMillis()) != stamp ||
        refreshStats.changeStamp.value != stamp
    ) return 0
    var count = 0
    for (watch in missing) {
        val price = watch.lastPrice ?: continue
        val change = dayReferences.candleChange(watch, price, stamp.dayStart)?.takeIf { it.isFinite() } ?: continue
        if (watchRepository.fillChange(watch.id, watch.lastUpdate, change)) count++
    }
    return count
}
