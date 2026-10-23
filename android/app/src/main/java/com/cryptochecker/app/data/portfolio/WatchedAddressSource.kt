package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.remote.BlockedSources
import com.cryptochecker.app.data.remote.HttpUserAgent
import com.cryptochecker.app.data.remote.UserAgentInterceptor
import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.domain.exceptions.HttpMarketError
import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressServer
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
import com.cryptochecker.marketdata.model.PostRequestInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lädt Guthaben beobachteter Adressen ([AddressBalance]: Hosts, Antworten, Abstand) für
 * [WatchedAddressRefresher] (Portfolio-Tab, Hintergrund/Widget).
 *
 * Datenschutz: eigener Client ohne Mitschnitt (die Adresse landet nicht im Debug-Protokoll),
 * keine Cookies, neutraler User-Agent ([HttpUserAgent.NEUTRAL]), eigener Verbindungs-Pool und Dispatcher;
 * Protokolle nennen nur die gekürzte Adresse. Eigener Bitcoin-Server ([AddressServer]): nur dorthin.
 * Pausen: 451/403 → Host 6 h übersprungen ([BlockedSources]); 429/418 und wiederholte
 * Zeitüberschreitungen → Pause je Host nach [ExchangeBackoff] («Retry-After» gilt); dann der
 * nächste Host. Adressen der Reihe nach, nie parallel (schont die kostenlosen Anbieter).
 * ETH: mit «Tokens mitzählen» eine Stapel-Anfrage (ETH + ERC-20, [Erc20Tokens]). xpub: abgeleitete Adressen einzeln
 * mit kurzer Pause ([XpubScan], höchstens [XpubScan.MAX_REQUESTS] je Aktualisierung).
 */
@Singleton
class WatchedAddressSource @Inject constructor(
    httpClient: OkHttpClient,
) {
    internal val client: OkHttpClient = httpClient.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
            // Neutraler User-Agent statt «okhttp/…» bzw. App-Kennung, gleich wie iOS
            addInterceptor(UserAgentInterceptor(HttpUserAgent.NEUTRAL))
        }
        // Eigene Verbindungen und Warteschlange: keine Wiederverwendung von Verbindungen der App-Anfragen
        .connectionPool(ConnectionPool())
        .dispatcher(Dispatcher())
        .callTimeout(AddressBalance.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .build()

    /** Pausen je Host (nur im Speicher; ein Neustart beginnt frisch). */
    private val backoff = ConcurrentHashMap<String, ExchangeBackoff.State>()
    /** Bitcoin-Hosts dieser Runde: eigener Server allein oder die öffentlichen ([AddressServer.btcEntries]). */
    @Volatile internal var btcEntries: List<String> = AddressBalance.BTC_HOSTS
    private val mutex = Mutex()

    /**
     * Ergebnis eines Abrufs. [tokens]: ERC-20-Guthaben (nur ETH mit «Tokens mitzählen»; null = nicht gelesen, das
     * letzte bleibt). [xpub]: neuer Zwischenspeicher nach einer xpub-Suche.
     */
    data class Fetched(
        val balance: String,
        val provider: String,
        val tokens: Map<String, String>? = null,
        val xpub: XpubCache? = null,
        /** 0x-Adresse, erste Suche: EVM-Netze mit Guthaben (Vorschlag der Wahl). */
        val networks: List<String>? = null,
        /** Solana: Token mit Guthaben ausserhalb der Liste. */
        val unknownTokens: Int? = null,
        /** Token, deren alter Wert mangels Antwort bleibt (TON: volle Jetton-Seite); null = unverändert. */
        val keptTokens: List<String>? = null,
    )

    /** Ergebnis je Adresse: entweder [fetched] oder [error] (technischer Text). */
    data class Update(val id: Long, val time: Long, val fetched: Fetched?, val error: String?)

    /**
     * Alle fälligen Adressen ([AddressBalance.isDue]) der Reihe nach laden.
     * @param force manuelles Aktualisieren (kürzerer Abstand).
     * @param budgetMillis Hintergrund: höchstens so lange insgesamt (je Anfrage höchstens
     *   [BACKGROUND_REQUEST_MILLIS]); läuft schon ein Abruf, wird nicht gewartet. Was nicht mehr
     *   drankommt, bleibt fällig (kein Fehler). null = Vordergrund, ohne Grenze.
     *   xpub im Hintergrund: die bekannten benutzten Adressen samt kurzem Vorausblick und nur ganz (sonst bleibt er
     *   fällig). Hintergrund: die am längsten nicht versuchten zuerst ([AddressBalance.dueOrder]) — sonst kämen bei
     *   knappem Budget immer nur die zuerst erfassten dran.
     * @param btcServer eigener Bitcoin-Server ([AddressServer]): Bitcoin nur dort, ohne Rückfall; null = öffentliche Knoten.
     */
    suspend fun refresh(
        addresses: List<WatchedAddress>,
        force: Boolean,
        now: Long = System.currentTimeMillis(),
        budgetMillis: Long? = null,
        btcServer: String? = null,
    ): List<Update> {
        val deadline = budgetMillis?.let { System.currentTimeMillis() + it }
        if (deadline == null) mutex.lock() else if (!mutex.tryLock()) return emptyList()
        try {
            btcEntries = AddressServer.btcEntries(btcServer)
            val updates = mutableListOf<Update>()
            for (a in AddressBalance.dueOrder(addresses, now, force, background = deadline != null)) {
                val time = System.currentTimeMillis()
                try {
                    val fetched = when {
                        a.isXpub -> fetchXpub(a, deadline) ?: continue
                        a.chain == AddressChain.ETH -> fetchEvm(a, deadline)
                        a.chain == AddressChain.TRX -> fetchTron(a, deadline)
                        a.chain == AddressChain.SOL -> fetchSolana(a, deadline)
                        a.chain == AddressChain.TON -> fetchTon(a, deadline)
                        else -> fetch(a.chain, a.address, deadline)
                    }
                    updates += Update(a.id, time, fetched, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfTime) {
                    break
                } catch (e: XpubScan.TooMany) {
                    // Eigener Hinweis «Zu viele Adressen» statt «Aktualisierung fehlgeschlagen» (wiederholen hilft nicht)
                    updates += Update(a.id, time, null, XpubScan.TOO_MANY_MARKER)
                } catch (e: Exception) {
                    Timber.d("Adresse %s: %s", WatchedAddressRules.shortAddress(a.address), e.message)
                    // Nur kurzer Code speichern (Ausnahmetexte enthalten Hosts/URLs, teils die Adresse); eigener Server: eigener Hinweis
                    val ownServer = btcServer != null && a.chain == AddressChain.BTC
                    updates += Update(a.id, time, null, if (ownServer) AddressServer.FAILED_MARKER else StoredError.sanitize(e.toString()))
                }
            }
            return updates
        } finally {
            mutex.unlock()
        }
    }

    /** Zeit um, bevor ein Host gefragt wurde (Adresse bleibt fällig). */
    private class OutOfTime : Exception("Zeit um")

    /**
     * Ein Guthaben (einzelne Adresse, ohne Token); wirft, wenn kein Host liefert.
     * @param deadline Endzeit (ms) im Hintergrund, null = ohne Grenze
     */
    suspend fun fetch(chain: AddressChain, address: String, deadline: Long? = null): Fetched = when (chain) {
        AddressChain.BTC -> firstWorking(btcEntries, deadline) { host ->
            AddressBalance.btcText(AddressBalance.parseBtc(client.callMarket(AddressServer.btcUrl(host, address), null), address))
        }
        AddressChain.ETH -> firstWorking(AddressBalance.ETH_RPCS, deadline) { rpc ->
            AddressBalance.ethText(AddressBalance.parseEthWei(client.callMarket(rpc, json(AddressBalance.ethRequest(address)))))
        }
        AddressChain.LTC -> firstWorking(ChainBalance.LTC_HOSTS, deadline) { host ->
            val stats = AddressBalance.parseBtc(client.callMarket(ChainBalance.ltcUrl(host, address), null), address, AddressChain.LTC)
            AddressBalance.unitsText(stats.totalSats.toString(), AddressChain.LTC.decimals)
        }
        AddressChain.XRP -> firstWorking(ChainBalance.XRP_RPCS, deadline) { rpc ->
            AddressBalance.unitsText(ChainBalance.parseXrp(client.callMarket(rpc, json(ChainBalance.xrpRequest(address)))), AddressChain.XRP.decimals)
        }
        AddressChain.TON -> firstWorking(ChainBalance.TON_HOSTS, deadline) { host ->
            AddressBalance.unitsText(ChainBalance.parseTon(client.callMarket(ChainBalance.tonUrl(host, address), null)), AddressChain.TON.decimals)
        }
        AddressChain.TRX -> firstWorking(ChainBalance.TRON_HOSTS, deadline) { host ->
            AddressBalance.unitsText(
                ChainBalance.parseTronAccount(client.callMarket(ChainBalance.tronAccountUrl(host), json(ChainBalance.tronAccountRequest(address)))),
                AddressChain.TRX.decimals,
            )
        }
        AddressChain.ADA -> firstWorking(ChainBalance.ADA_HOSTS, deadline) { host ->
            AddressBalance.unitsText(
                ChainBalance.parseAda(client.callMarket(ChainBalance.adaUrl(host), json(ChainBalance.adaRequest(address))), address),
                AddressChain.ADA.decimals,
            )
        }
        AddressChain.SOL -> firstWorking(ChainBalance.SOLANA_RPCS, deadline) { rpc ->
            AddressBalance.unitsText(
                ChainBalance.parseSolanaBalanceBody(client.callMarket(rpc, json(ChainBalance.solanaBalanceRequest(1, address)))),
                AddressChain.SOL.decimals,
            )
        }
    }.let { (balance, provider) -> Fetched(balance, provider) }

    /**
     * 0x-Adresse: Ethereum (mit «Tokens mitzählen» als Stapel mit ERC-20) und die gewählten weiteren EVM-Netze
     * ([ChainAssets.EVM], vor der ersten Suche alle) — je Netz eine Stapel-Anfrage (Netz-Guthaben + Token). Ein Netz,
     * das nicht antwortet, bleibt wie zuletzt; im Hintergrund endet die Runde, wenn die Zeit um ist. Die erste Wahl der
     * Netze nur im Vordergrund, stumme Netze bleiben darin ([WatchedAddressRules.discoveredNetworks]).
     */
    private suspend fun fetchEvm(a: WatchedAddress, deadline: Long?): Fetched {
        val ethereum = if (a.countTokens) fetchEthWithTokens(a.address, deadline) else fetch(AddressChain.ETH, a.address, deadline)
        val tokens = LinkedHashMap(ethereum.tokens ?: emptyMap())
        val found = ArrayList<String>()
        val unanswered = ArrayList<String>()
        for (id in a.networks ?: ChainAssets.EVM.map { it.id }) {
            val net = ChainAssets.network(id) ?: continue
            val list = if (a.countTokens) net.tokens else emptyList()
            try {
                val (batch, _) = firstWorking(net.rpcs, deadline) { rpc ->
                    Erc20Tokens.parseBatch(client.callMarket(rpc, json(Erc20Tokens.batchRequest(a.address, list))), list)
                }
                val native = AddressBalance.unitsText(batch.weiDigits, 18)
                tokens[ChainAssets.key(net.id, net.native)] = native
                for ((symbol, text) in batch.tokens) tokens[ChainAssets.key(net.id, symbol)] = text
                if (positive(native) || batch.tokens.values.any { positive(it) }) found += net.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfTime) {
                break
            } catch (e: Exception) {
                Timber.d("Netz %s: %s", net.id, e.message)
                unanswered += net.id
            }
        }
        val networks = if (a.networks == null) {
            WatchedAddressRules.discoveredNetworks(found, unanswered, background = deadline != null)
        } else {
            null
        }
        return ethereum.copy(tokens = tokens.ifEmpty { ethereum.tokens }, networks = networks)
    }

    /**
     * TON: Guthaben und (mit «Tokens mitzählen») Jettons über TON Center v3 — ohne Schlüssel höchstens 1 Anfrage/s, daher
     * eine Pause dazwischen. Jettons ohne Antwort bleiben wie zuletzt.
     */
    private suspend fun fetchTon(a: WatchedAddress, deadline: Long?): Fetched {
        val ton = fetch(AddressChain.TON, a.address, deadline)
        if (!a.countTokens) return ton
        return try {
            delay(ChainBalance.TON_PAUSE_MILLIS)
            val (page, _) = firstWorking(ChainBalance.TON_HOSTS, deadline) { host ->
                ChainBalance.parseTonJettonPage(client.callMarket(ChainBalance.tonJettonsUrl(host, a.address), null))
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

    private fun positive(text: String) = (PortfolioDecimal.parse(text)?.signum() ?: 0) > 0

    /** TRON: TRX und (mit «Tokens mitzählen») je TRC-20 eine Abfrage; ein Token ohne Antwort bleibt wie zuletzt. */
    private suspend fun fetchTron(a: WatchedAddress, deadline: Long?): Fetched {
        val trx = fetch(AddressChain.TRX, a.address, deadline)
        if (!a.countTokens) return trx
        val tokens = LinkedHashMap<String, String>()
        for (token in ChainAssets.TRON_TOKENS) {
            try {
                val (digits, _) = firstWorking(ChainBalance.TRON_HOSTS, deadline) { host ->
                    ChainBalance.parseTronCall(client.callMarket(ChainBalance.tronCallUrl(host), json(ChainBalance.tronTokenRequest(a.address, token))))
                }
                tokens[token.symbol] = AddressBalance.unitsText(digits, token.decimals)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfTime) {
                break
            } catch (e: Exception) {
                Timber.d("TRC-20 %s: %s", token.symbol, e.message)
            }
        }
        return trx.copy(tokens = tokens)
    }

    /**
     * Solana: SOL und (mit «Tokens mitzählen») Token-Konten beider Token-Programme in einer Stapel-Anfrage; ohne Stapel
     * drei Einzelanfragen. Unbekannte Mints zählen nicht ([ChainBalance.solana]).
     */
    private suspend fun fetchSolana(a: WatchedAddress, deadline: Long?): Fetched {
        if (!a.countTokens) return fetch(AddressChain.SOL, a.address, deadline)
        val (result, provider) = try {
            firstWorking(ChainBalance.SOLANA_RPCS, deadline) { rpc ->
                ChainBalance.parseSolanaBatch(client.callMarket(rpc, json(ChainBalance.solanaBatch(a.address))))
            }
        } catch (e: Erc20Tokens.BatchUnsupported) {
            firstWorking(ChainBalance.SOLANA_RPCS, deadline) { rpc ->
                val singles = ChainBalance.solanaSingles(a.address)
                val lamports = ChainBalance.parseSolanaBalanceBody(client.callMarket(rpc, json(singles[0])))
                val legacy = ChainBalance.parseSolanaTokensBody(client.callMarket(rpc, json(singles[1])))
                val t2022 = ChainBalance.parseSolanaTokensBody(client.callMarket(rpc, json(singles[2])))
                ChainBalance.solana(lamports, listOf(legacy, t2022))
            }
        }
        return Fetched(
            AddressBalance.unitsText(result.lamports, AddressChain.SOL.decimals), provider,
            tokens = result.tokens, unknownTokens = result.unknown,
        )
    }

    private fun json(body: String) = PostRequestInfo(body, mapOf("Content-Type" to "application/json"))

    /**
     * ETH + ERC-20 in einer Stapel-Anfrage ([Erc20Tokens.batchRequest]). Kann kein Knoten Stapel: ETH allein und
     * (nur im Vordergrund) die Token einzeln; scheitert der Stapel sonst: ETH allein, Token bleiben wie zuletzt.
     */
    private suspend fun fetchEthWithTokens(address: String, deadline: Long?): Fetched {
        try {
            val (batch, provider) = firstWorking(AddressBalance.ETH_RPCS, deadline) { rpc ->
                Erc20Tokens.parseBatch(client.callMarket(rpc, json(Erc20Tokens.batchRequest(address))))
            }
            return Fetched(AddressBalance.ethText(batch.weiDigits), provider, batch.tokens)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfTime) {
            throw e
        } catch (e: Exception) {
            val eth = fetch(AddressChain.ETH, address, deadline)
            if (e !is Erc20Tokens.BatchUnsupported || deadline != null) return eth
            val tokens = LinkedHashMap<String, String>()
            for (token in Erc20Tokens.LIST) {
                try {
                    tokens[token.symbol] = firstWorking(AddressBalance.ETH_RPCS, null) { rpc ->
                        Erc20Tokens.parseSingle(client.callMarket(rpc, json(Erc20Tokens.singleRequest(address, token))), token)
                    }.first
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    // Dieses Token bleibt wie zuletzt
                    Timber.d("Token %s: %s", token.symbol, e.message)
                }
            }
            return eth.copy(tokens = tokens)
        }
    }

    /** Erster Host, der liefert: Ergebnis und Anbieter-Name; wirft, wenn keiner liefert. */
    internal suspend fun <T> firstWorking(hosts: List<String>, deadline: Long?, load: suspend (String) -> T): Pair<T, String> {
        var lastError: Exception? = null
        for (entry in hosts) {
            val host = entry.removePrefix("https://").substringBefore('/').substringBefore(':')
            val now = System.currentTimeMillis()
            if (BlockedSources.isBlocked(host) || ExchangeBackoff.isPaused(backoff[host], now)) continue
            // Hintergrund: je Anfrage höchstens BACKGROUND_REQUEST_MILLIS, nie über die Endzeit
            val limit = deadline?.let { minOf(BACKGROUND_REQUEST_MILLIS, it - now) }
            if (limit != null && limit < MIN_REQUEST_MILLIS) throw lastError ?: OutOfTime()
            try {
                val result = if (limit == null) load(entry) else withTimeout(limit) { load(entry) }
                backoff.remove(host)
                return result to AddressBalance.providerName(host)
            } catch (e: TimeoutCancellationException) {
                note(host, ExchangeBackoff.Outcome.TIMEOUT, null)
                lastError = IllegalStateException("Zeitüberschreitung: $host")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BlockedSources.noteFailure(host, e)
                val outcome = when {
                    e is HttpMarketError && ExchangeBackoff.isRateLimitCode(e.httpCode) -> ExchangeBackoff.Outcome.RATE_LIMITED
                    e is InterruptedIOException -> ExchangeBackoff.Outcome.TIMEOUT
                    else -> ExchangeBackoff.Outcome.OTHER
                }
                note(host, outcome, (e as? HttpMarketError)?.retryAfterSeconds?.let { it * 1000L },
                    banned = (e as? HttpMarketError)?.httpCode == 418)
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Alle Anbieter pausiert")
    }

    private fun note(host: String, outcome: ExchangeBackoff.Outcome, retryAfterMillis: Long?, banned: Boolean = false) {
        val next = ExchangeBackoff.next(backoff[host], outcome, System.currentTimeMillis(), retryAfterMillis, banned = banned)
        if (next == null) backoff.remove(host) else backoff[host] = next
    }

    companion object {
        /** Hintergrund/Widget: alle Adressen zusammen höchstens so lange — Kurse und Alarme gehen vor. */
        const val BACKGROUND_TOTAL_MILLIS = 8_000L
        /** Hintergrund: eine Anfrage höchstens so lange (Vordergrund: [AddressBalance.TIMEOUT_MILLIS]). */
        const val BACKGROUND_REQUEST_MILLIS = 5_000L
        /** Weniger Restzeit lohnt keine Anfrage mehr. */
        private const val MIN_REQUEST_MILLIS = 1_000L
        /** xpub: Pause zwischen zwei Adressen (Vordergrund) — höflich zu den kostenlosen Anbietern. */
        const val XPUB_PAUSE_MILLIS = 200L
        /** xpub im Hintergrund (Zeitbudget, bekannte benutzte Adressen und kurzer Vorausblick). */
        const val XPUB_PAUSE_BACKGROUND_MILLIS = 50L
    }
}
