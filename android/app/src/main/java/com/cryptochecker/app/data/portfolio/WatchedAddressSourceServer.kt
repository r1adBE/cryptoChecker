// «Testen» des eigenen Bitcoin-Servers (Teil von `WatchedAddressSource`): gleicher Client wie die Adressabfragen
// (neutraler User-Agent, ohne Mitschnitt, eigene Verbindungen).
package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.domain.portfolio.AddressServer
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** Blockhöhe vom eigenen Server ([AddressServer.testUrl], nennt keine Adresse) oder null, wenn er nicht passend antwortet. */
internal suspend fun WatchedAddressSource.testBtcServer(server: String): Long? = try {
    AddressServer.parseTipHeight(client.callMarket(AddressServer.testUrl(server), null, maxBytes = TEST_MAX_BYTES))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Timber.d("Eigener Server: %s", e.javaClass.simpleName)
    null
}

/** Die Antwort ist eine Zahl; mehr braucht der Test nicht zu lesen. */
private const val TEST_MAX_BYTES = 1_024L
