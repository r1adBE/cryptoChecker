package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.watch.ChangeStamp
import kotlinx.coroutines.flow.StateFlow

/** Kennzahlen der Aktualisierung für die Oberfläche (Umsetzung: `RefreshStats`). */
interface RefreshStatsView {
    val lastReport: StateFlow<RefreshReport?>

    /** Ende der Pause je Börsen-Schlüssel. */
    val exchangePauses: StateFlow<Map<String, Long>>

    /** App-Start bis zum ersten Bild der Merkliste (zuletzt gemessen); null = noch nie. */
    val appStartMillis: StateFlow<Long?>
    val changeStamp: StateFlow<ChangeStamp?>

    fun setAppStartMillis(millis: Long)

    fun lastFullRefresh(): Long

    fun backoffStates(): Map<String, ExchangeBackoff.State>
}
