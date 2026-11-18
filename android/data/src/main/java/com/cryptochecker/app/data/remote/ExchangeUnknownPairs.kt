// Sammelabfrage ohne unbekannte Paare wiederholen — Teil von ExchangeRemoteDataSource.
package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.remote.util.HttpBodyLimit
import com.cryptochecker.app.data.remote.util.RequestKey
import com.cryptochecker.app.domain.exceptions.ExchangeError
import com.cryptochecker.app.domain.exceptions.HttpExchangeError
import com.cryptochecker.app.domain.exceptions.ParseError
import com.cryptochecker.app.domain.exceptions.UserFriendlyExchangeError
import com.cryptochecker.app.domain.exceptions.rethrowIfCritical
import com.cryptochecker.app.domain.exceptions.toExchangeFailure
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.app.domain.model.MutableTicker
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.refresh.ExchangeFailure
import com.cryptochecker.app.domain.refresh.RateLimitSignal
import com.cryptochecker.app.domain.refresh.pairListCallAborts
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.Exchange
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.MultiResponseSource
import com.cryptochecker.marketdata.model.PairListPaging
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.PostRequest
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.BinanceAlpha
import com.cryptochecker.marketdata.model.exchange.DexPool
import com.cryptochecker.marketdata.model.exchange.DexScreener
import com.cryptochecker.marketdata.model.pairListPaging
import com.cryptochecker.marketdata.util.BulkCompleteness
import com.cryptochecker.marketdata.util.BulkTickerParse
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Gefilterte Anfrage ohne ungefilterten Ersatz abgelehnt ([Exchange.getBulkKnownPairsUrl], Bithumb):
 * Paarliste holen, angefragte Paare, die dort fehlen, weglassen und ohne sie nochmals fragen. Die
 * weggelassenen fehlen dann in der (vollständigen) Antwort → die Einzelabfrage bestätigt «nicht mehr
 * gehandelt», BulkDelisted lässt sie künftig weg. null = geht nicht oder kein Paar unbekannt.
 */
internal suspend fun ExchangeRemoteDataSource.withoutUnknownPairs(
    client: OkHttpClient,
    exchange: Exchange,
    call: Int,
    pairIds: Collection<String>,
    postBody: PostRequest?,
    original: Exception,
): BulkTickerParse? {
    val listAddress = exchange.getBulkKnownPairsUrl(call)
    if (listAddress.isNullOrEmpty()) return null

    val listed = ArrayList<ExchangePair>()
    exchange.readPairList(0, client.callExchange(listAddress, null, HttpBodyLimit.BULK_MAX_BYTES), listed)
    val knownIds = listed.mapNotNullTo(HashSet()) { it.pairId }
    val unknown = BulkCompleteness.unknownPairs(pairIds, knownIds) ?: return null

    Timber.w(original, "Sammelabfrage ohne %d unbekannte Paare erneut: %s", unknown.size, exchange.key)
    val remaining = pairIds.filterNot { it in unknown }
    if (remaining.isEmpty()) return BulkTickerParse(emptyMap(), emptySet())
    val address = exchange.getBulkTickersUrl(call, remaining)
    if (address.isNullOrEmpty()) return null
    return loadBulk(client, exchange, call, address, postBody)
}
