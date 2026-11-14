package com.cryptochecker.app.data

import android.content.res.Resources
import com.cryptochecker.app.data.local.ExchangeLocalDataSource
import com.cryptochecker.app.data.remote.ExchangeRemoteDataSource
import com.cryptochecker.app.domain.ExchangeRepository
import com.cryptochecker.app.domain.exceptions.UserFriendlyExchangeError
import com.cryptochecker.app.domain.exceptions.rethrowIfCritical
import com.cryptochecker.app.domain.exceptions.toExchangeFailure
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.app.domain.model.ExchangeTickerResult
import com.cryptochecker.app.domain.refresh.ExchangeFailure
import com.cryptochecker.app.domain.refresh.RefreshFailure
import com.cryptochecker.app.domain.refresh.RefreshReportLogic
import com.cryptochecker.app.domain.refresh.refreshFailure
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.domain.util.IoDispatcher
import com.cryptochecker.marketdata.config.ExchangeRetirement
import com.cryptochecker.marketdata.config.ExchangesConfig
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.Exchange
import com.cryptochecker.marketdata.model.exchange.BinanceAlpha
import com.cryptochecker.marketdata.model.exchange.DexPool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Börsen, Paarlisten und Kurse: gespeicherte Listen aus Room, alles Übrige über das Netz. */
class ExchangeRepositoryImpl @Inject constructor(
    private val local: ExchangeLocalDataSource,
    private val remote: ExchangeRemoteDataSource,
    private val settings: SettingsRepository,
    private val tokenIdentityStore: TokenIdentityStore,
    private val coinLogos: CoinLogoRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ExchangeRepository {

    override suspend fun getExchangeList(): List<ExchangeInfo> =
        ExchangesConfig.EXCHANGES.values.map { ExchangeInfo(key = it.key, name = it.name) }

    override fun isExchangeSupportsUpdatePairs(exchange: ExchangeInfo): Boolean {
        val source = exchangeOrNull(exchange.key) ?: return false
        return !source.pairListAddress(0).isNullOrEmpty()
    }

    /**
     * Paarliste einer Börse. TradFi-Futures (Aktien, Rohstoffe, Devisen, Pre-IPO, [ExchangePair.tradFi])
     * nur, wenn «Token & TradFi» (Einstellungen › Merkliste) sie erlaubt; gespeichert bleibt stets die
     * ganze Liste, damit ein Wechsel ohne neuen Abruf wirkt. Krypto-Paare bleiben hier immer drin
     * (Portfolio, Start-Auswahl, Info); «Nur TradFi» blendet sie erst in der Paar-Auswahl aus
     * ([com.cryptochecker.app.domain.settings.scoped]).
     */
    override suspend fun getExchangeCurrencyPairsInfo(exchange: ExchangeInfo, withAlpha: Boolean): ExchangePairsInfo {
        val stored = local.getExchangeData(exchange.key)
        if (stored == null) {
            // Noch nie abgeglichen: fest eingebaute Paare der Börse, falls sie welche hat
            val builtIn = exchangeOrNull(exchange.key)?.currencyPairs ?: return ExchangePairsInfo()
            return ExchangePairsInfo(lastSyncDate = 0, pairs = builtInPairs(builtIn))
        }

        val visible = when {
            stored.pairs.none { it.tradFi } -> stored
            settings.current().assetScope.allowsTradFi -> stored
            else -> stored.copy(pairs = stored.pairs.filterNot { it.tradFi })
        }
        return if (withAlpha) addAlphaPairs(exchange.key, visible) else visible
    }

    /**
     * Binance mit abgeglichener Spot-Liste: zusätzlich die Alpha-Token aus der gespeicherten Alpha-Liste
     * (kein eigener Abruf) als «SYMBOL/USDT» mit Kennung «ALPHA_<id>USDT». Spot-Paare bleiben, wie sie
     * sind; ein schon vergebenes Kürzel heisst beim Alpha-Token «SYMBOL_<Nummer>» ([BinanceAlpha.pairs]).
     */
    private suspend fun addAlphaPairs(exchangeKey: String, info: ExchangePairsInfo): ExchangePairsInfo {
        if (exchangeKey != BinanceAlpha.EXCHANGE_KEY || info.lastSyncDate <= 0L) return info
        val alphaPairs = BinanceAlpha.pairs(coinLogos.loadAlphaTokens(), info.pairs)
        if (alphaPairs.isEmpty()) return info
        return info.copy(pairs = (info.pairs + alphaPairs).sorted())
    }

    override suspend fun getExchangeTicker(exchange: ExchangeInfo, pairInfo: ExchangePair): ExchangeTickerResult {
        val info = pairInfo.asPairQuery()
        // Eine entfernte Börse betrifft nur diesen Eintrag, nicht den ganzen Durchlauf
        val source = exchangeOrNull(exchange.key)
            ?: return ExchangeTickerResult(MutableTicker(), info, UserFriendlyExchangeError.EXCHANGE_UNAVAILABLE)
        // Stillgelegt (ExchangeRetirement): keine Anfrage, der Marker nennt den Grund
        if (ExchangeRetirement.isRetired(exchange.key)) {
            return ExchangeTickerResult(MutableTicker(), info, ExchangeRetirement.marker(exchange.name))
        }

        return try {
            ExchangeTickerResult(remote.fetchExchangeTicker(source, info), info)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()
            ex.toExchangeFailure().let { failure -> ExchangeTickerResult(MutableTicker(), info, ex.textForUser(failure), failure) }
        }
    }

    override fun isExchangeSupportsBulkTickers(exchange: ExchangeInfo): Boolean {
        val source = exchangeOrNull(exchange.key) ?: return false
        return source.bulkTickersNumOfRequests > 0 && !ExchangeRetirement.isRetired(exchange.key)
    }

    override suspend fun getBulkTickers(exchange: ExchangeInfo, pairIds: Collection<String>): BulkTickers {
        return try {
            val source = exchangeOrNull(exchange.key) ?: return BulkTickers()
            if (ExchangeRetirement.isRetired(exchange.key)) return BulkTickers(error = ExchangeRetirement.marker(exchange.name))
            remote.fetchBulkTickers(source, pairIds)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()
            // Text weiterreichen: «zu viele Anfragen» pausiert die Börse (ExchangeBackoff)
            ex.toExchangeFailure().let { failure -> BulkTickers(error = ex.textForUser(failure), failure = failure) }
        }
    }

    override suspend fun searchDexPools(query: String): List<DexPool> = remote.searchDexPools(query)

    override suspend fun updateExchangeCurrencyPairs(exchange: ExchangeInfo) {
        // Stillgelegt: keine Paarliste mehr laden
        if (ExchangeRetirement.isRetired(exchange.key)) return
        val fresh = remote.fetchExchangeCurrencyPairsInfo(requireExchange(exchange.key))
        if (fresh.size <= 0) return
        // Paarliste und volle Coin-Namen («gleicher Token», aus derselben Paarliste) gehören zusammen: nach dem Abruf
        // beide speichern, auch wenn der Aufrufer gerade abbricht — sonst Paare neu, Namen alt. Die Namen schreiben
        // eine Datei, darum nicht im Kontext des Aufrufers (Explorer: Haupt-Thread).
        withContext(NonCancellable + ioDispatcher) {
            local.saveExchangeData(exchange.key, fresh)
            tokenIdentityStore.saveExchange(exchange.key, fresh.baseNames)
        }
    }
}

/** Börse zur Kennung; null, wenn diese App-Version sie nicht (mehr) kennt. */
private fun exchangeOrNull(exchangeKey: String): Exchange? = ExchangesConfig.EXCHANGES[exchangeKey]

/** Wie [exchangeOrNull], aber eine unbekannte Börse ist ein Fehler. */
private fun requireExchange(exchangeKey: String): Exchange =
    exchangeOrNull(exchangeKey) ?: throw Resources.NotFoundException("Exchange not found (key=$exchangeKey)")

/**
 * Gespeicherter Text: bei «keine Verbindung» (Art [ExchangeFailure.Offline]/[ExchangeFailure.Ssl]) der Marker
 * [RefreshReportLogic.OFFLINE_MARKER] — wie iOS `ConnectionErrors.describe`; sonst entschiede der Ausnahmetext
 * («SocketException: Software caused connection abort») und die Zeile zeigte «Etwas ist schiefgelaufen».
 */
private fun Exception.textForUser(failure: ExchangeFailure): String =
    if (failure.refreshFailure() == RefreshFailure.OFFLINE) RefreshReportLogic.OFFLINE_MARKER
    else message ?: javaClass.simpleName

private fun ExchangePair.asPairQuery(): PairQuery =
    PairQuery(base, quote, pairId, contractType)

/** Fest eingebaute Paare (Basis → Gegenwährungen) als Liste ohne Paar-Kennung. */
private fun builtInPairs(byBase: Map<String, Array<String>>): List<ExchangePair> {
    val result = ArrayList<ExchangePair>()
    for ((base, quotes) in byBase) {
        quotes.forEach { quote -> result += ExchangePair(base, quote, null) }
    }
    return result
}
