package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.AddressServerProbe
import javax.inject.Inject

/** [AddressServerProbe] über [testBtcServer] und [clearServerPauses] von [WatchedAddressSource]. */
class WatchedAddressServerProbe @Inject constructor(private val source: WatchedAddressSource) : AddressServerProbe {
    override suspend fun testBtcServer(server: String): Long? = source.testBtcServer(server)

    override fun clearServerPauses(server: String) = source.clearServerPauses(server)
}
