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
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.KEY_COINS_SOURCE
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.SOURCE_BINANCE
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.SOURCE_OTHER
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

        val binance = binanceCoins() ?: pairCacheCoins()
        val fresh = binance ?: coinbaseCoins()
        if (!fresh.isNullOrEmpty()) {
            val list = (fresh + STABLE).distinct().sorted()
            prefs.edit {
                putString(KEY_COINS, list.joinToString(","))
                putLong(KEY_COINS_AT, System.currentTimeMillis())
                // Herkunft für den Filter der Sammelabfrage ([binanceCoinList]): Coinbase führt z. B. kein IOTA
                putString(KEY_COINS_SOURCE, if (binance != null) SOURCE_BINANCE else SOURCE_OTHER)
            }
            coinList = list
            list
        } else {
            // Lieber eine ältere Liste als gar keine
            stored.also { if (it.isNotEmpty()) coinList = it }
        }
    }
}

/**
 * Coin-Liste für den Filter der Binance-Sammelabfrage, ohne Netz: nur wenn sie von Binance stammt (exchangeInfo oder
 * Paarliste «Binance», [KEY_COINS_SOURCE]) und höchstens [COINS_TTL_MILLIS] alt ist — aus dem Arbeitsspeicher, sonst
 * aus dem gemerkten Stand. Sonst null: ungefiltert (unbekannte Symbole findet das Halbieren bei HTTP 400).
 */
fun PortfolioPriceSource.binanceCoinList(): Set<String>? {
    if (prefs.getString(KEY_COINS_SOURCE, null) != SOURCE_BINANCE) return null
    if (System.currentTimeMillis() - prefs.getLong(KEY_COINS_AT, 0L) !in 0 until COINS_TTL_MILLIS) return null
    val list = coinList ?: prefs.getString(KEY_COINS, null)?.split(',')?.filter { it.isNotEmpty() }
    return list?.toHashSet()?.takeIf { it.isNotEmpty() }
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
