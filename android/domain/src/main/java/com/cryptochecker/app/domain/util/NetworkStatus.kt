package com.cryptochecker.app.domain.util

import kotlinx.coroutines.flow.StateFlow

/** Netz da? Für Status-Pillen und Nachladen, sobald das Netz zurückkommt (Umsetzung: `ConnectivityMonitor`). */
interface NetworkStatus {
    val online: StateFlow<Boolean>

    /** Letzter bekannter Stand von [online]. */
    fun isOnline(): Boolean
}
