package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import androidx.core.content.edit
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.STABLE
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.BINANCE_HOST
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.COINBASE_HOST
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.COINS_TTL_MILLIS
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.KEY_COINS
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.KEY_COINS_AT
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.isSymbol

// Coin-Liste für die Suche (Teil von [PortfolioPriceSource]): Binance-Spot-Paare, sonst Paar-Zwischenspeicher der
// Börse «Binance», sonst Coinbase; 1 Tag gemerkt. Erweiterungsfunktionen; der Zustand bleibt in der Klasse.

/** Alle wählbaren Coins (alphabetisch); leer, wenn keine Quelle antwortet. */
suspend fun PortfolioPriceSource.coins(): List<String> = withContext(Dispatchers.IO) {
    coinMutex.withLock {
        coinList?.let { list ->
            if (System.currentTimeMillis() - prefs.getLong(KEY_COINS_AT, 0L) in 0 until COINS_TTL_MILLIS) {
                return@withLock list
            }
        }
        val stored = prefs.getString(KEY_COINS, null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
        val storedAt = prefs.getLong(KEY_COINS_AT, 0L)
        if (stored.isNotEmpty() && System.currentTimeMillis() - storedAt in 0 until COINS_TTL_MILLIS) {
            coinList = stored
            return@withLock stored
        }

        val fresh = binanceCoins() ?: pairCacheCoins() ?: coinbaseCoins()
        if (!fresh.isNullOrEmpty()) {
            val list = (fresh + STABLE).distinct().sorted()
            prefs.edit {
                putString(KEY_COINS, list.joinToString(","))
                putLong(KEY_COINS_AT, System.currentTimeMillis())
            }
            coinList = list
            list
        } else {
            // Lieber eine ältere Liste als gar keine
            stored.also { if (it.isNotEmpty()) coinList = it }
        }
    }
}

private suspend fun PortfolioPriceSource.binanceCoins(): List<String>? {
    val url = "https://$BINANCE_HOST/api/v3/exchangeInfo?permissions=SPOT&showPermissionSets=false"
    val body = get(url, BINANCE_HOST, bulkClient) ?: return null
    return runCatchingCancellable {
        val symbols = JSONObject(body).getJSONArray("symbols")
        (0 until symbols.length()).mapNotNull { i ->
            val s = symbols.getJSONObject(i)
            s.optString("baseAsset").takeIf {
                s.optString("quoteAsset") == STABLE && s.optString("status") == "TRADING"
            }
        }.map { it.uppercase() }.filter { isSymbol(it) }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/** Paarliste der Börse «Binance» aus der App (lokal gespeichert oder mitgeliefert). */
private suspend fun PortfolioPriceSource.pairCacheCoins(): List<String>? = runCatchingCancellable {
    exchangeRepository.getExchangeCurrencyPairsInfo(ExchangeInfo(key = "Binance", name = "Binance"))
        .pairs
        .filter { it.quote == STABLE && it.contractType == FuturesContractType.NONE }
        .map { it.base.uppercase() }
        .filter { isSymbol(it) }
}.getOrNull()?.takeIf { it.isNotEmpty() }

private suspend fun PortfolioPriceSource.coinbaseCoins(): List<String>? {
    val body = get("https://api.exchange.coinbase.com/products", COINBASE_HOST, bulkClient) ?: return null
    return runCatchingCancellable {
        val array = JSONArray(body)
        (0 until array.length()).mapNotNull { i ->
            val p = array.getJSONObject(i)
            p.optString("base_currency").takeIf {
                p.optString("quote_currency") == "USD" && !p.optBoolean("trading_disabled", false)
            }
        }.map { it.uppercase() }.filter { isSymbol(it) }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
