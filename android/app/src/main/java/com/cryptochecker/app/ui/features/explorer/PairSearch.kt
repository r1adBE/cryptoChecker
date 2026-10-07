package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType

/** Ein Treffer der Suche über alle Börsen. */
data class SearchHit(val market: MarketInfo, val pair: CurrencyPairInfo)

/** Fortschritt beim Nachladen der Paarlisten für die Suche. */
data class SearchProgress(val done: Int, val total: Int)

/**
 * Sucht «BTC», «ETH USDT» oder «sol/usdc» in allen geladenen Paarlisten.
 * Exakter Coin vor Wortanfang, gängige Gegenwährungen zuerst, Spot vor Futures.
 */
object PairSearch {
    private val QUOTE_RANK = listOf("USDT", "USDC", "USD", "EUR", "CHF", "BTC")

    /**
     * Edelmetalle gibt es an Krypto-Börsen nur als goldgedeckte Token.
     * «Gold» bzw. «Silber» (auch in anderen Sprachen) findet deshalb diese Coins.
     */
    private val GOLD = listOf("PAXG", "XAUT")
    private val SILVER = listOf("KAG", "XAG")
    private val ALIASES: Map<String, List<String>> =
        listOf("GOLD", "ORO", "OURO", "GULD", "KULTA", "ZLATO", "ZŁOTO", "ARANY", "ALTIN", "EMAS", "VÀNG",
            "ЗОЛОТО", "ΧΡΥΣΟΣ", "ΧΡΥΣΌΣ", "זהב", "ذهب", "طلا", "सोना", "ทองคำ", "金", "黄金", "골드", "금")
            .associateWith { GOLD } +
        listOf("SILVER", "SILBER", "ARGENT", "ARGENTO", "PLATA", "PRATA", "ZILVER", "SØLV", "HOPEA", "STŘÍBRO", "SREBRO",
            "EZÜST", "ARGINT", "GÜMÜŞ", "PERAK", "BẠC", "СЕРЕБРО", "СРІБЛО", "ΑΣΗΜΙ", "כסף", "فضة", "نقره", "चाँदी",
            "เงิน", "銀", "银", "白银", "실버", "은")
            .associateWith { SILVER }

    fun find(
        query: String,
        markets: List<MarketInfo>,
        cache: Map<String, MarketPairsInfo>,
        limit: Int = 50,
    ): List<SearchHit> {
        val parts = query.uppercase().split(' ', '/', '-', ':').filter { it.isNotBlank() }
        val base = parts.getOrNull(0) ?: return emptyList()
        val quote = parts.getOrNull(1)
        val aliasBases = ALIASES[base].orEmpty()

        val hits = ArrayList<Pair<Int, SearchHit>>()
        for (market in markets) {
            val info = cache[market.key] ?: continue
            for (pair in info.pairs) {
                val b = pair.currencyBase.uppercase()
                val c = pair.currencyCounter.uppercase()
                val baseScore = when {
                    b == base -> 0
                    b in aliasBases -> 0
                    b.startsWith(base) -> 1
                    else -> continue
                }
                if (quote != null && !c.startsWith(quote)) continue
                val quoteRank = QUOTE_RANK.indexOf(c).let { if (it < 0) QUOTE_RANK.size else it }
                val futures = if (pair.contractType == FuturesContractType.NONE) 0 else 1
                hits += (baseScore * 1000 + quoteRank * 10 + futures) to SearchHit(market, pair)
            }
        }
        return hits
            .sortedWith(compareBy<Pair<Int, SearchHit>> { it.first }
                .thenBy { it.second.pair.currencyBase }
                .thenBy { it.second.market.name.lowercase() })
            // Manche Börsen führen ein Paar doppelt — Listen-Schlüssel müssen eindeutig sein.
            .distinctBy { (_, hit) ->
                listOf(hit.market.key, hit.pair.currencyBase, hit.pair.currencyCounter, hit.pair.contractType.name)
            }
            .take(limit)
            .map { it.second }
    }
}
