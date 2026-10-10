package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.alarm.ThresholdParser
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import java.util.Locale

/** Ein Treffer der Suche über alle Börsen. */
data class SearchHit(val market: MarketInfo, val pair: CurrencyPairInfo)

/** Fortschritt beim Nachladen der Paarlisten für die Suche. */
data class SearchProgress(val done: Int, val total: Int)

/**
 * Sucht «BTC», «ETH USDT», «sol/usdc» oder wie an der Börse «BTCUSDT», «BTCUSDT Qtly 1225»,
 * «BTCUSD_PERP» in allen geladenen Paarlisten. Exakter Coin vor Wortanfang, gängige
 * Gegenwährungen zuerst, Spot vor Perpetual vor Laufzeit-Futures (Quartal …).
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

    /** Laufzeit-Wörter wie bei den Börsen («BTCUSDT Qtly 1225», «BTCUSD_PERP», «Quartal»). */
    private val PERPETUAL_WORDS = setOf("PERP", "PERPETUAL", "SWAP")
    private val QUARTER_WORDS = setOf("QTLY", "QUARTERLY", "QUARTER", "QUARTAL", "1Q", "2Q", "CQ", "NQ")
    private val PERPETUALS = setOf(FuturesContractType.PERPETUAL, FuturesContractType.INVERSE_PERPETUAL)
    private val QUARTERS = setOf(FuturesContractType.QUARTERLY, FuturesContractType.BIQUARTERLY)

    /** Suchanfrage zerlegt: Coin, Gegenwährung, gewünschte Laufzeit und Verfallsdatum. */
    internal data class Query(
        val base: String,
        val quote: String?,
        val contracts: Set<FuturesContractType>?,
        /** «1225» (MMdd) oder «261225» (yyMMdd). */
        val date: String?,
    )

    internal fun parse(query: String): Query? {
        // Arabische/persische Ziffern wie 0–9 («BTC ١٢٢٥» = Verfallsdatum 1225), Richtungszeichen weg
        val tokens = ThresholdParser.latinDigits(query).uppercase()
            .split(' ', '/', '-', ':', '_', '.').filter { it.isNotBlank() }
        var contracts: Set<FuturesContractType>? = null
        var date: String? = null
        val words = ArrayList<String>()
        for (t in tokens) {
            when {
                t in PERPETUAL_WORDS -> contracts = PERPETUALS
                t in QUARTER_WORDS -> contracts = when (t) {
                    "1Q", "CQ" -> setOf(FuturesContractType.QUARTERLY)
                    "2Q", "NQ" -> setOf(FuturesContractType.BIQUARTERLY)
                    else -> QUARTERS
                }
                // Zahl nach dem Coin = Verfallsdatum (als erstes Wort bleibt es ein Coin, z. B. «1000»)
                words.isNotEmpty() && (t.length == 4 || t.length == 6) && t.all { it in '0'..'9' } -> date = t
                else -> words += t
            }
        }
        val base = words.getOrNull(0) ?: return null
        return Query(base, words.getOrNull(1), contracts, date)
    }

    /** Verfallsdatum als «yyMMdd»: aus der Kennung («BTCUSDT_261225»), sonst nach der Laufzeit berechnet. */
    internal fun deliveryCode(pair: CurrencyPairInfo): String? {
        pair.currencyPairId?.substringAfterLast('_', "")?.takeIf { it.length == 6 && it.all { c -> c in '0'..'9' } }
            ?.let { return it }
        val date = FuturesContractType.getDeliveryDate(pair.contractType) ?: return null
        // Locale.ROOT: Kennung mit lateinischen Ziffern, auch in arabischer/persischer App-Sprache
        return String.format(Locale.ROOT, "%02d%02d%02d", date.year % 100, date.monthValue, date.dayOfMonth)
    }

    /** Reihenfolge der Kontrakte: Spot, Perpetual, dann die Laufzeiten (nächste zuerst). */
    private fun contractRank(type: FuturesContractType): Int = when (type) {
        FuturesContractType.NONE -> 0
        FuturesContractType.PERPETUAL -> 1
        FuturesContractType.INVERSE_PERPETUAL -> 2
        else -> 3 + (type.value - FuturesContractType.WEEKLY.value).coerceIn(0, 6)
    }

    fun find(
        query: String,
        markets: List<MarketInfo>,
        cache: Map<String, MarketPairsInfo>,
        limit: Int = 50,
    ): List<SearchHit> {
        val q = parse(query) ?: return emptyList()
        val base = q.base
        val quote = q.quote
        val aliasBases = ALIASES[base].orEmpty()

        val hits = ArrayList<Pair<Int, SearchHit>>()
        for (market in markets) {
            val info = cache[market.key] ?: continue
            for (pair in info.pairs) {
                val b = pair.currencyBase.uppercase()
                val c = pair.currencyCounter.uppercase()
                // «BTCUSDT» wie an der Börse: Coin und Gegenwährung zusammengeschrieben
                val joined = quote == null && base == b + c
                val baseScore = when {
                    b == base || joined -> 0
                    b in aliasBases -> 0
                    b.startsWith(base) -> 1
                    else -> continue
                }
                if (quote != null && !c.startsWith(quote)) continue
                if (q.contracts != null && pair.contractType !in q.contracts) continue
                if (q.date != null) {
                    val code = deliveryCode(pair) ?: continue
                    if (!code.endsWith(q.date)) continue
                }
                val quoteRank = QUOTE_RANK.indexOf(c).let { if (it < 0) QUOTE_RANK.size else it }
                hits += (baseScore * 1000 + quoteRank * 10 + contractRank(pair.contractType)) to SearchHit(market, pair)
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
