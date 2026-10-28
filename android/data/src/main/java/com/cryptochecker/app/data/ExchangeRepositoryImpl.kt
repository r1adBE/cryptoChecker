package com.cryptochecker.app.data

import android.content.res.Resources
import com.cryptochecker.app.data.local.ExchangeLocalDataSource
import com.cryptochecker.app.data.remote.ExchangeRemoteDataSource
import com.cryptochecker.app.domain.exceptions.UserFriendlyExchangeError
import com.cryptochecker.app.domain.exceptions.rethrowIfCritical
import com.cryptochecker.app.domain.exceptions.toExchangeFailure
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.app.domain.model.ExchangeTickerResult
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.marketdata.config.ExchangesConfig
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.Exchange
import com.cryptochecker.marketdata.model.exchange.BinanceAlpha
import com.cryptochecker.marketdata.model.exchange.DexPool
import kotlinx.coroutines.Dispatchers
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
     * ([com.cryptochecker.app.settings.scoped]).
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

        return try {
            ExchangeTickerResult(remote.fetchExchangeTicker(source, info), info)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()
            ExchangeTickerResult(MutableTicker(), info, ex.textForUser(), ex.toExchangeFailure())
        }
    }

    override fun isExchangeSupportsBulkTickers(exchange: ExchangeInfo): Boolean {
        val source = exchangeOrNull(exchange.key) ?: return false
        return source.bulkTickersNumOfRequests > 0
    }

    override suspend fun getBulkTickers(exchange: ExchangeInfo, pairIds: Collection<String>): BulkTickers {
        return try {
            val source = exchangeOrNull(exchange.key) ?: return BulkTickers()
            remote.fetchBulkTickers(source, pairIds)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()
            // Text weiterreichen: «zu viele Anfragen» pausiert die Börse (ExchangeBackoff)
            BulkTickers(error = ex.textForUser(), failure = ex.toExchangeFailure())
        }
    }

    override suspend fun searchDexPools(query: String): List<DexPool> = remote.searchDexPools(query)

    override suspend fun updateExchangeCurrencyPairs(exchange: ExchangeInfo) {
        val fresh = remote.fetchExchangeCurrencyPairsInfo(requireExchange(exchange.key))
        if (fresh.size <= 0) return
        // Paarliste und volle Coin-Namen («gleicher Token», aus derselben Paarliste) gehören zusammen: nach dem Abruf
        // beide speichern, auch wenn der Aufrufer gerade abbricht — sonst Paare neu, Namen alt. Die Namen schreiben
        // eine Datei, darum nicht im Kontext des Aufrufers (Explorer: Haupt-Thread).
        withContext(NonCancellable + Dispatchers.IO) {
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

private fun Exception.textForUser(): String = message ?: javaClass.simpleName

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
