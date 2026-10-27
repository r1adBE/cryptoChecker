// «Testen» des eigenen Bitcoin-Servers (Teil von `WatchedAddressSource`): gleicher Client wie die Adressabfragen
// (neutraler User-Agent, ohne Mitschnitt, eigene Verbindungen).
package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.remote.BlockedSources
import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.domain.portfolio.AddressServer
import com.cryptochecker.app.domain.portfolio.AddressServerRules
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** Blockhöhe vom eigenen Server ([AddressServer.testUrl], nennt keine Adresse) oder null, wenn er nicht passend antwortet. */
suspend fun WatchedAddressSource.testBtcServer(server: String): Long? = try {
    AddressServer.parseTipHeight(client.callMarket(AddressServer.testUrl(server), null, maxBytes = TEST_MAX_BYTES))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Timber.d("Eigener Server: %s", e.javaClass.simpleName)
    null
}

/** Speichern oder erfolgreicher Test des eigenen Servers: Sperre und Pause seines Hosts aufheben. */
fun WatchedAddressSource.clearServerPauses(server: String) {
    val host = AddressServerRules.hostKey(server)
    backoff.remove(host)
    BlockedSources.unblock(host)
}

/** Die Antwort ist eine Zahl; mehr braucht der Test nicht zu lesen. */
private const val TEST_MAX_BYTES = 1_024L
