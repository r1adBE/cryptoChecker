package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.alarm.ThresholdParser
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.TokenIdentity
import com.cryptochecker.app.settings.AssetScope
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import java.text.Normalizer
import java.util.Locale

/** Ein Treffer der Suche über alle Börsen; [alpha] = Alpha-Token («α», [AssetKind.ALPHA]). */
data class SearchHit(val market: MarketInfo, val pair: MarketPair, val alpha: Boolean = false)

/** Fortschritt beim Nachladen der Paarlisten für die Suche. */
data class SearchProgress(val done: Int, val total: Int)

/**
 * Suche über Namen («bitcoin» → BTC, «ether» → ETH) und Börsen («kraken») — wie `ExplorerNameMatch`
 * in iOS (`ExplorerPairSearch.swift`). Gross-/Kleinschreibung und Akzente egal.
 */
object NameMatch {
    /** Rang «Name beginnt mit» (ganzer Name oder ein Wort darin). */
    const val PREFIX = 0

    /** Rang «Name enthält». */
    const val CONTAINS = 1

    private val MARKS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    /** Klein geschrieben, ohne Akzente («Pépé» → «pepe»), getrimmt. */
    fun fold(text: String): String =
        Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(MARKS, "")

    /** Wörter eines (gefalteten) Namens: getrennt an allem, was weder Buchstabe noch Ziffer ist. */
    fun words(folded: String): List<String> = folded.split(SEPARATORS).filter { it.isNotEmpty() }

    /**
     * Trifft [query] den Namen? Beides schon [fold]. [PREFIX]: Name oder eines seiner Wörter beginnt
     * damit (ab 2 Zeichen); [CONTAINS]: steht irgendwo darin (ab 3 Zeichen); sonst null.
     */
    fun score(foldedName: String, query: String): Int? {
        if (query.length < 2 || foldedName.isEmpty()) return null
        if (foldedName.startsWith(query) || words(foldedName).any { it.startsWith(query) }) return PREFIX
        if (query.length >= 3 && foldedName.contains(query)) return CONTAINS
        return null
    }

    /**
     * Nennt das Wort [word] (gefaltet) die Börse [foldedMarketName]? Ab 3 Zeichen, Anfang eines
     * Worts des Namens oder des Namens ohne Trennzeichen («cryptocom» → «Crypto.com»).
     */
    fun matchesMarket(foldedMarketName: String, word: String): Boolean {
        if (word.length < 3) return false
        if (words(foldedMarketName).any { it.startsWith(word) }) return true
        return foldedMarketName.filter { it.isLetterOrDigit() }.startsWith(word)
    }
}

/**
 * Namen der Coins und TradFi-Paare, Schlüssel [CoinLogos.nameKey], samt gefalteter Form ([NameMatch.fold]) —
 * einmal je Namensliste gerechnet, nicht bei jedem Tastendruck. Die Coin-Suche ([CoinSearch]) geht zudem alle
 * Einträge durch, um Coins zu finden, deren Paarliste noch nicht geladen ist.
 */
class NameIndex(names: Map<String, String>) {
    /** Ein Name: [key] wie [CoinLogos.nameKey], [name] wie geliefert, [folded] für den Vergleich. */
    class Entry(val key: String, val name: String, val folded: String)

    private val byKey: Map<String, Entry> = HashMap<String, Entry>(names.size * 2).apply {
        for ((key, name) in names) {
            val f = NameMatch.fold(name)
            if (f.isNotEmpty()) put(key, Entry(key, name.trim(), f))
        }
    }

    val isEmpty: Boolean get() = byKey.isEmpty()

    val entries: Collection<Entry> get() = byKey.values

    fun folded(key: String): String? = byKey[key]?.folded

    fun name(key: String): String? = byKey[key]?.name

    companion object {
        val EMPTY = NameIndex(emptyMap())
    }
}

/**
 * Sucht «BTC», «ETH USDT», «sol/usdc» oder wie an der Börse «BTCUSDT», «BTCUSDT Qtly 1225»,
 * «BTCUSD_PERP» in allen geladenen Paarlisten, dazu über den Namen («bitcoin», «ether usdt»)
 * und die Börse («kraken», «btc kraken»). Rang: exakter Coin, Coin-Anfang, Name-Anfang, Name
 * enthält, nur Börse; darin gängige Gegenwährungen zuerst, Spot vor Perpetual vor
 * Laufzeit-Futures (Quartal …).
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

    /** Coins, die das Wort [word] (gross) meint: «GOLD» → PAXG, XAUT; sonst keine. */
    internal fun aliasesOf(word: String): List<String> = ALIASES[word].orEmpty()

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
        /** Alle Wörter ohne Laufzeit und Datum (Coin, Gegenwährung, Name, Börse). */
        val words: List<String> = listOfNotNull(base, quote),
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
        return Query(base, words.getOrNull(1), contracts, date, words)
    }

    /** Verfallsdatum als «yyMMdd»: aus der Kennung («BTCUSDT_261225»), sonst nach der Laufzeit berechnet. */
    internal fun deliveryCode(pair: MarketPair): String? {
        pair.pairId?.substringAfterLast('_', "")?.takeIf { it.length == 6 && it.all { c -> c in '0'..'9' } }
            ?.let { return it }
        val date = FuturesContractType.getDeliveryDate(pair.contractType) ?: return null
        // Locale.ROOT: Kennung mit lateinischen Ziffern, auch in arabischer/persischer App-Sprache
        return String.format(Locale.ROOT, "%02d%02d%02d", date.year % 100, date.monthValue, date.dayOfMonth)
    }

    /** Reihenfolge der Kontrakte: Spot, Perpetual, dann die Laufzeiten (nächste zuerst). */
    internal fun contractRank(type: FuturesContractType): Int = when (type) {
        FuturesContractType.NONE -> 0
        FuturesContractType.PERPETUAL -> 1
        FuturesContractType.INVERSE_PERPETUAL -> 2
        else -> 3 + (type.value - FuturesContractType.WEEKLY.value).coerceIn(0, 6)
    }

    /** Ränge (× 1000): Coin exakt, Coin-Anfang, Name-Anfang, Name enthält, nur die Börse. */
    private const val NAME_TIER = 2
    private const val EXCHANGE_TIER = 4

    /**
     * Coin- und Namensteil der Anfrage: erstes Wort Coin bzw. Name, zweites Gegenwährung; mehrere
     * Wörter zusammen dürfen auch ein Name sein («shiba inu»). Merkt sich die Namens-Ränge je Coin.
     */
    private class Terms(val words: List<String>, private val names: NameIndex) {
        private val base = words.firstOrNull().orEmpty()
        private val quote = words.getOrNull(1)
        private val aliasBases = ALIASES[base].orEmpty()
        private val foldedBase = NameMatch.fold(base)
        private val foldedAll = if (words.size > 1) NameMatch.fold(words.joinToString(" ")) else null
        private val memoAll = HashMap<String, Int>()
        private val memoBase = HashMap<String, Int>()

        /** Rang (0–3) für Coin [b] / Gegenwährung [c] (beide gross); null = kein Treffer. */
        fun tier(b: String, c: String, tradFi: Boolean, withNames: Boolean): Int? {
            // «BTCUSDT» wie an der Börse: Coin und Gegenwährung zusammengeschrieben
            val joined = quote == null && base == b + c
            val symbol = when {
                b == base || joined || b in aliasBases -> 0
                b.startsWith(base) -> 1
                else -> null
            }
            if (symbol != null) {
                return if (quote != null && !c.startsWith(quote)) null else symbol
            }
            if (!withNames || names.isEmpty) return null
            val memoKey = if (tradFi) "\u0001$b" else b
            if (foldedAll != null) {
                nameTier(memoAll, memoKey, b, tradFi, foldedAll)?.let { return it }
            }
            if (quote != null && !c.startsWith(quote)) return null
            return nameTier(memoBase, memoKey, b, tradFi, foldedBase)
        }

        private fun nameTier(memo: HashMap<String, Int>, memoKey: String, b: String, tradFi: Boolean, q: String): Int? {
            val tier = memo.getOrPut(memoKey) {
                val name = names.folded(CoinLogos.nameKey(b, tradFi))
                val score = name?.let { NameMatch.score(it, q) }
                if (score == null) -1 else NAME_TIER + score
            }
            return tier.takeIf { it >= 0 }
        }
    }

    fun find(
        query: String,
        markets: List<MarketInfo>,
        cache: Map<String, MarketPairsInfo>,
        limit: Int = 50,
        /** «Token & TradFi»: nur Paare, die die Auswahl zeigt. */
        scope: AssetScope = AssetScope.BOTH,
        /** Namen der Coins und TradFi-Paare für die Suche über den Namen. */
        names: NameIndex = NameIndex.EMPTY,
        /** «inkl. Alpha-Tokens» aus: diese Alpha-Kürzel nicht zeigen ([AssetScope.allows]). */
        hiddenAlpha: Set<String> = emptySet(),
        /** Alle Alpha-Kürzel: Treffer mit «α» kennzeichnen ([AssetKind.of]). */
        alphaSymbols: Set<String> = emptySet(),
        /** Namen der Alpha-Token je Kürzel ([AssetKind.of]). */
        alphaNames: Map<String, String> = emptyMap(),
        /** Namen der Coins je Börse (Börsen-Schlüssel → Kürzel gross → Name; [AssetKind.of]). */
        pairNames: Map<String, Map<String, String>> = emptyMap(),
    ): List<SearchHit> {
        val q = parse(query) ?: return emptyList()
        val primary = Terms(q.words, names)
        val foldedWords = q.words.map { NameMatch.fold(it) }

        val hits = ArrayList<Pair<Int, SearchHit>>()
        for (market in markets) {
            val info = cache[market.key] ?: continue
            // Keine Namen für DEX-Pools: dort darf ein fremder Token «BTC» heissen
            val withNames = CoinLogos.allowedFor(market.key)
            // Wörter, die diese Börse nennen («btc kraken»): ohne sie wird der Rest gesucht
            val marketName = NameMatch.fold(market.name)
            val exchangeWords = foldedWords.indices.filter { NameMatch.matchesMarket(marketName, foldedWords[it]) }
            val rest = if (exchangeWords.isEmpty()) {
                null
            } else {
                Terms(q.words.filterIndexed { i, _ -> i !in exchangeWords }, names)
            }
            val names = pairNames[market.key].orEmpty()
            for (pair in info.pairs) {
                if (!scope.allows(pair, market.key, hiddenAlpha, names, alphaNames)) continue
                val b = pair.base.uppercase()
                val c = pair.quote.uppercase()
                val tier = primary.tier(b, c, pair.tradFi, withNames)
                    ?: rest?.let { r -> if (r.words.isEmpty()) EXCHANGE_TIER else r.tier(b, c, pair.tradFi, withNames) }
                    ?: continue
                if (q.contracts != null && pair.contractType !in q.contracts) continue
                if (q.date != null) {
                    val code = deliveryCode(pair) ?: continue
                    if (!code.endsWith(q.date)) continue
                }
                val quoteRank = QUOTE_RANK.indexOf(c).let { if (it < 0) QUOTE_RANK.size else it }
                val alpha = AssetKind.of(
                    market.key, pair.base, pair.tradFi, alphaSymbols, pair.pairId,
                    names[TokenIdentity.key(pair.base)], alphaNames,
                ) == AssetKind.ALPHA
                hits += (tier * 1000 + quoteRank * 10 + contractRank(pair.contractType)) to SearchHit(market, pair, alpha)
            }
        }
        return hits
            .sortedWith(compareBy<Pair<Int, SearchHit>> { it.first }
                .thenBy { it.second.pair.base }
                .thenBy { it.second.market.name.lowercase() })
            // Manche Börsen führen ein Paar doppelt — Listen-Schlüssel müssen eindeutig sein.
            .distinctBy { (_, hit) ->
                listOf(hit.market.key, hit.pair.base, hit.pair.quote, hit.pair.contractType.name)
            }
            .take(limit)
            .map { it.second }
    }
}
