// Guthaben von TON- und Solana-Adressen abfragen — Teil von WatchedAddressSource.
package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.remote.HttpUserAgent
import com.cryptochecker.app.data.remote.UserAgentInterceptor
import com.cryptochecker.app.data.remote.callExchange
import com.cryptochecker.app.data.remote.util.CallStartInterceptor
import com.cryptochecker.app.domain.exceptions.HttpExchangeError
import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressServer
import com.cryptochecker.app.domain.portfolio.AddressServerRules
import com.cryptochecker.app.domain.portfolio.ChainAssets
import com.cryptochecker.app.domain.portfolio.ChainBalance
import com.cryptochecker.app.domain.portfolio.Erc20Tokens
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.domain.portfolio.XpubCache
import com.cryptochecker.app.domain.portfolio.XpubScan
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.refresh.StoredError
import com.cryptochecker.app.domain.remote.BlockedSources
import com.cryptochecker.marketdata.model.PostRequest
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * TON: Guthaben und (mit «Tokens mitzählen») Jettons über TON Center v3 — ohne Schlüssel höchstens 1 Anfrage/s, daher
 * eine Pause dazwischen. Jettons ohne Antwort bleiben wie zuletzt.
 */
internal suspend fun WatchedAddressSource.fetchTon(a: WatchedAddress, deadline: Long?): WatchedAddressSource.Fetched {
    val ton = fetch(AddressChain.TON, a.address, deadline)
    if (!a.countTokens) return ton
    return try {
        delay(ChainBalance.TON_PAUSE_MILLIS)
        val (page, _) = firstWorking(ChainBalance.TON_HOSTS, deadline) { host ->
            ChainBalance.parseTonJettonPage(client.callExchange(ChainBalance.tonJettonsUrl(host, a.address), null))
        }
        // Auf voller Seite fehlende bekannte Jettons: alter Wert bleibt, gilt aber als nicht bestätigt
        ton.copy(tokens = page.tokens, keptTokens = page.kept)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d("Jettons: %s", e.message)
        ton
    }
}

/**
 * Solana: SOL und (mit «Tokens mitzählen») Token-Konten beider Token-Programme in einer Stapel-Anfrage; ohne Stapel
 * drei Einzelanfragen. Unbekannte Mints zählen nicht ([ChainBalance.solana]).
 */
internal suspend fun WatchedAddressSource.fetchSolana(a: WatchedAddress, deadline: Long?): WatchedAddressSource.Fetched {
    if (!a.countTokens) return fetch(AddressChain.SOL, a.address, deadline)
    val (result, provider) = try {
        firstWorking(ChainBalance.SOLANA_RPCS, deadline) { rpc ->
            ChainBalance.parseSolanaBatch(client.callExchange(rpc, json(ChainBalance.solanaBatch(a.address))))
        }
    } catch (e: Erc20Tokens.BatchUnsupported) {
        firstWorking(ChainBalance.SOLANA_RPCS, deadline) { rpc ->
            val singles = ChainBalance.solanaSingles(a.address)
            val lamports = ChainBalance.parseSolanaBalanceBody(client.callExchange(rpc, json(singles[0])))
            val legacy = ChainBalance.parseSolanaTokensBody(client.callExchange(rpc, json(singles[1])))
            val t2022 = ChainBalance.parseSolanaTokensBody(client.callExchange(rpc, json(singles[2])))
            ChainBalance.solana(lamports, listOf(legacy, t2022))
        }
    }
    return WatchedAddressSource.Fetched(
        AddressBalance.unitsText(result.lamports, AddressChain.SOL.decimals),
        provider,
        tokens = result.tokens,
        unknownTokens = result.unknown,
    )
}
