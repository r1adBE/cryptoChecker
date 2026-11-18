package com.cryptochecker.app.domain.portfolio

/** «Testen» des eigenen Bitcoin-Servers (Umsetzung in :data über `WatchedAddressSource`). */
interface AddressServerProbe {
    /** Blockhöhe vom eigenen Server oder null, wenn er nicht passend antwortet. */
    suspend fun testBtcServer(server: String): Long?

    /** Speichern oder erfolgreicher Test des eigenen Servers: Sperre und Pause seines Hosts aufheben. */
    fun clearServerPauses(server: String)
}
