package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.MutableTicker
import com.cryptochecker.app.data.remote.util.HttpBodyLimit
import com.cryptochecker.app.domain.exceptions.UserFriendlyExchangeError
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.Exchange
import com.cryptochecker.marketdata.model.MultiResponseSource
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Kurse einer [MultiResponseSource] (Referenzkurse von NBU und NBP): alle Antworten zusammen holen — eine Anfrage je
 * Adresse, egal wie viele Paare —, bis [MultiResponseSource.validUntil] im Speicher behalten (NBP: bis zur nächsten
 * Tabelle B am Mittwoch) und verbunden als Antwort von Anfrage 0 lesen. Einzel- und Sammelkurs teilen sich die
 * Antworten. Nur im Speicher, nach dem Neustart einmal neu. Wie iOS `MultiResponses`.
 */
object MultiResponses {
    private class Entry(val addresses: List<String>, val body: String, val until: Long)

    /** Börsen-Schlüssel → zuletzt gelesene Antworten. */
    private val cache = ConcurrentHashMap<String, Entry>()

    /** Verbundene Antworten von [source]: aus dem Speicher, solange gültig und für dieselben Adressen, sonst neu. */
    suspend fun body(
        client: OkHttpClient,
        exchange: Exchange,
        source: MultiResponseSource,
        now: Long = System.currentTimeMillis(),
    ): String {
        val addresses = source.responseAddresses(now)
        cache[exchange.key]?.takeIf { it.addresses == addresses && now < it.until }?.let { return it.body }
        val bodies = addresses.map { client.callExchange(it, null, HttpBodyLimit.BULK_MAX_BYTES) }
        val joined = MultiResponseSource.join(bodies)
        val until = source.validUntil(bodies, now)
        if (until > now) cache[exchange.key] = Entry(addresses, joined, until) else cache.remove(exchange.key)
        return joined
    }

    /** Sammelabfrage: alle Kurse aus den verbundenen Antworten (gelesen wie Anfrage 0). */
    suspend fun bulk(client: OkHttpClient, exchange: Exchange, source: MultiResponseSource): BulkTickers {
        val parsed = exchange.parseBulkTickersDetailed(0, body(client, exchange, source))
        return BulkTickers(tickers = parsed.tickers, failed = parsed.failed - parsed.tickers.keys)
    }

    /** Einzelkurs aus denselben Antworten; ohne gültigen Kurs ein Lesefehler wie bei jeder Börse. */
    suspend fun ticker(client: OkHttpClient, exchange: Exchange, source: MultiResponseSource, info: PairQuery): Ticker {
        val ticker = MutableTicker()
        exchange.readTicker(0, body(client, exchange, source), ticker, info)
        if (!ticker.last.isFinite() || ticker.last <= 0.0) {
            throw ExchangeParseException(UserFriendlyExchangeError.NO_TICKER_DATA)
        }
        return ticker
    }

    /** Für Tests: Speicher leeren. */
    fun clear() = cache.clear()
}
