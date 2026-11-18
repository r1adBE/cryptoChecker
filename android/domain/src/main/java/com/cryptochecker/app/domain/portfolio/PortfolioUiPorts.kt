package com.cryptochecker.app.domain.portfolio

import kotlinx.coroutines.flow.StateFlow

/** Guthaben der beobachteten Adressen neu lesen (Umsetzung: `WatchedAddressRefresher`). */
interface AddressRefresh {
    /** [force] = auch frische Guthaben neu lesen; [background] = mit Zeitgrenze der Hintergrundprüfung. */
    suspend fun refresh(force: Boolean, background: Boolean = false): Boolean
}

/** «Unbekannt lassen» bei Einträgen ohne Einstand, nur Ids, nur dieses Gerät (Umsetzung: `PortfolioCostGapStore`). */
interface CostGapAcks {
    val acknowledged: StateFlow<Set<Long>>

    fun acknowledge(id: Long)

    /** Wieder als offen zählen (z. B. «Einstand erfassen» nach «Unbekannt lassen»). */
    fun reopen(id: Long)

    /** Nur noch [keep] behalten (Lücken, die es noch gibt). */
    fun prune(keep: Set<Long>)
}
