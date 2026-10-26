package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.MarketCategory
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.GeckoTerminalNetworks
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.forEachOptJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONObject
import java.net.URLEncoder
import java.util.SortedSet

private const val DEXSCREENER_API = "https://api.dexscreener.com"

/** Ausweg der Einzelkurse (Build 133): öffentliche Pool-Abfrage von GeckoTerminal, ohne Schlüssel. */
private const val GECKOTERMINAL_API = "https://api.geckoterminal.com/api/v2"

/** Ein DEX-Pool aus der Suche. */
data class DexPool(
    val chainId: String,
    val dexId: String,
    val pairAddress: String,
    val baseSymbol: String,
    val quoteSymbol: String,
    val priceUsd: Double?,
    val liquidityUsd: Double?,
    /** Vertragsadresse des Basis-Tokens («baseToken.address»); leer, wenn die Antwort keine nennt. */
    val baseAddress: String = "",
) {
    /** Kennung der Kursabfrage: «chain/pairAddress». */
    val pairId: String get() = "$chainId/$pairAddress"

    /**
     * Gegenwährung in der Merkliste: Kurse sind USD, die Chain steht dabei, damit gleichnamige Token
     * verschiedener Chains auseinanderzuhalten sind.
     */
    val watchQuote: String get() = "USD ($chainId)"

    /**
     * Gekürzte Vertragsadresse des Tokens («0x1234…abcd») für die Trefferzeile: Gleichnamige Token (zweimal «PEPE»)
     * sind sonst nur über Liquidität und Preis zu unterscheiden. Leer ohne Adresse.
     */
    val shortBaseAddress: String get() = DexScreener.shortAddress(baseAddress)

    /** Genug Liquidität für Alarme ([DexScreener.alarmCapable])? Sonst Hinweis «Geringe Liquidität – kein Alarm». */
    val alarmCapable: Boolean get() = DexScreener.alarmCapable(liquidityUsd)
}

/**
 * Kurse dezentraler Börsen (Uniswap, PancakeSwap, Raydium …) über DexScreener. Keine Paarliste (Pools findet die
 * Suche, [searchUrl]), darum kein `SimpleMarket`. Mehrere Pools derselben Chain kommen gesammelt
 * (`/latest/dex/pairs/{chain}/{a,b,…}`, höchstens [BULK_MAX_POOLS] je Anfrage), einzelne über dieselbe Adresse. Frei
 * und ohne Schlüssel; laut Anbieter rund 300 Abfragen pro Minute.
 *
 * Ausweg (Build 133): Scheitert ein Einzelkurs mit einem Serverfehler oder kennt DexScreener den Pool nicht
 * ([POOL_NOT_FOUND]), fragt die App denselben Pool bei GeckoTerminal ab ([tickerFallbackAddress],
 * [fillTickerFallback]) — nur einzeln, denn GeckoTerminal erlaubt ohne Schlüssel nur rund 30 Abfragen pro Minute.
 */
class DexScreener : Market("DexScreener", "Dex Screener") {
    override val key = KEY
    override val category = MarketCategory.DEX

    // ── Einzelkurs ──

    /** Kennung, die nicht unkodiert in den Pfad darf ([isSafePairId]): «nicht gefunden» ohne Anfrage. */
    override fun tickerAddress(call: Int, info: PairQuery): String {
        val pairId = info.pairId
        if (pairId == null || !isSafePairId(pairId)) throw MarketParseException(POOL_NOT_FOUND)
        return "$DEXSCREENER_API/latest/dex/pairs/$pairId"
    }

    /**
     * Nur der gemerkte Pool zählt: Liefert die API einen anderen zuerst (oder nur einen anderen), gilt das als
     * «nicht gefunden» — nie die Kurse eines fremden Pools.
     */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val pool = poolsIn(json).firstOrNull { isPool(it.optString("chainId"), it.optString("pairAddress"), info.pairId) }
            ?: throw MarketParseException(POOL_NOT_FOUND)
        copyPool(pool, ticker)
    }

    // ── Ausweg: GeckoTerminal ──

    /** Derselbe Pool bei GeckoTerminal (`/networks/{netz}/pools/{adresse}`); ungültige Kennung → kein Ausweg. */
    override fun tickerFallbackAddress(call: Int, info: PairQuery): String? {
        val pairId = info.pairId?.trim() ?: return null
        if (!isSafePairId(pairId)) return null
        val cut = pairId.indexOf('/')
        return geckoTerminalUrl(pairId.substring(0, cut), pairId.substring(cut + 1))
    }

    /** «Pool nicht gefunden» bei DexScreener: GeckoTerminal versuchen. */
    override fun tickerFallbackOnParseError(call: Int, error: Exception): Boolean =
        error is MarketParseException && error.message == POOL_NOT_FOUND

    /**
     * Antwort von GeckoTerminal: {data: {attributes: {address, base_token_price_usd, price_change_percentage.h24,
     * volume_usd.h24, reserve_in_usd}}}. Nur der gemerkte Pool zählt (Adresse ohne Gross-/Kleinschreibung).
     */
    override fun fillTickerFallback(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        val attributes = JSONObject(body).optJSONObject("data")?.optJSONObject("attributes")
            ?: throw MarketParseException(POOL_NOT_FOUND)
        val wanted = info.pairId?.trim().orEmpty()
        val address = wanted.substringAfter('/', "")
        if (address.isEmpty() || !attributes.optString("address").equals(address, ignoreCase = true)) {
            throw MarketParseException(POOL_NOT_FOUND)
        }
        ticker.last = attributes.getString("base_token_price_usd").toDouble()
        attributes.optJSONObject("volume_usd")?.let { ticker.volQuote = it.optDoubleOrMissing("h24") }
        ticker.change24hPercent = attributes.optJSONObject("price_change_percentage")
            ?.let { Change24h.percent(it.optDouble("h24")) }
        ticker.liquidityUsd = attributes.optDouble("reserve_in_usd").takeIf { it.isFinite() && it >= 0.0 }
    }

    // ── Sammelabfrage: Pools derselben Chain gemeinsam ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    // Ohne Auswahl gibt es keine Sammelabfrage (keine ungefilterte Adresse); ein fehlender Pool geht in die
    // Einzelabfrage, darum nicht «vollständig»
    override fun getBulkTickersUrl(call: Int, pairIds: Collection<String>): String? =
        bulkGroups(pairIds).getOrNull(call)?.let { bulkUrl(it) }

    override fun bulkTickersRequestCount(pairIds: Collection<String>): Int = bulkGroups(pairIds).size

    /**
     * Jeder Pool der Antwort unter «chain/pairAddress», so wie die Suche die Kennung bildet ([DexPool.pairId]); mit
     * eigener Liquidität, die Alarm-Sperre unter [DEX_MIN_ALARM_LIQUIDITY_USD] gilt je Pool. Pools ohne Preis werden
     * übersprungen (dann Einzelabfrage).
     */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        for (pool in poolsIn(JSONObject(body))) {
            val chain = pool.optString("chainId")
            val address = pool.optString("pairAddress")
            if (chain.isEmpty() || address.isEmpty()) continue
            bulkRowIfActive("$chain/$address", pool, "priceUsd", tickers, failed) { copyPool(pool, it) }
        }
    }

    // ── Lesen ──

    /** Kurs, Volumen, 24-h-Veränderung und Liquidität eines Pools (Einzel- und Sammelabfrage gleich). */
    private fun copyPool(pool: JSONObject, ticker: Ticker) {
        ticker.last = pool.getString("priceUsd").toDouble()
        pool.optJSONObject("volume")?.let { ticker.volQuote = it.optDoubleOrMissing("h24") }
        // «priceChange.h24»: gleitende 24 h in Prozent
        ticker.change24hPercent = pool.optJSONObject("priceChange")?.let { Change24h.percent(it.optDouble("h24")) }
        // Liquidität bei jeder Abfrage: Alarme pausieren, solange der Pool dünn ist (alarmCapable)
        ticker.liquidityUsd = liquidityOf(pool)
    }

    /** Alle Pools der Antwort: zuerst das Array `pairs` (nur Objekte), dann ein einzelnes `pair`. */
    private fun poolsIn(json: JSONObject): List<JSONObject> {
        val found = ArrayList<JSONObject>()
        json.optJSONArray("pairs")?.forEachOptJSONObject(found::add)
        json.optJSONObject("pair")?.let(found::add)
        return found
    }

    companion object {
        /** Schlüssel der Börse (Sicherung, Merkliste). */
        const val KEY = "DexScreener"

        /** Lesefehler «Pool nicht gefunden» (Einzelkurs, ungültige Kennung). */
        const val POOL_NOT_FOUND = "Pool not found"

        /**
         * Kennung genau «chain/adresse», beide Teile nur aus Zeichen, die unkodiert im Pfad stehen
         * dürfen ([SAFE_PART]; keine Leerzeichen, kein zweites «/», kein «?», «#», «%»). Gilt für Einzelkurs, Suchtreffer
         * und Sicherung. Wie iOS `DexScreener.isSafePairId` (`testdata/parity/dex_pair_id.json`).
         * SEC3-R1: kein Teil nur aus Punkten («.», «..» würden den Pfad beim Parsen verlassen).
         */
        fun isSafePairId(pairId: String): Boolean {
            val cut = pairId.indexOf('/')
            if (cut <= 0 || cut == pairId.lastIndex) return false
            return isSafePart(pairId.substring(0, cut)) && isSafePart(pairId.substring(cut + 1))
        }

        // Ein Teil der Kennung: nur Pfad-Zeichen und nicht nur Punkte
        private fun isSafePart(part: String): Boolean = SAFE_PART.matches(part) && part.any { it != '.' }

        /**
         * Mindest-Liquidität (USD) eines Pools für Alarme. Darunter (oder unbekannt) bewegen schon kleine Beträge den
         * Kurs — ein Alarm wäre leicht auszulösen bzw. zu manipulieren. Wie iOS `DexScreener.minAlarmLiquidityUsd`.
         */
        const val DEX_MIN_ALARM_LIQUIDITY_USD = 10_000.0

        /** Alarmfähig: bekannte Liquidität von mindestens [DEX_MIN_ALARM_LIQUIDITY_USD]. */
        fun alarmCapable(liquidityUsd: Double?): Boolean =
            liquidityUsd != null && liquidityUsd.isFinite() && liquidityUsd >= DEX_MIN_ALARM_LIQUIDITY_USD

        /** «liquidity.usd» eines Pools; fehlend, NaN oder negativ = unbekannt (null). */
        internal fun liquidityOf(entry: JSONObject): Double? =
            entry.optJSONObject("liquidity")?.optDouble("usd")?.takeIf { it.isFinite() && it >= 0.0 }

        /**
         * Gehört ([chainId], [pairAddress]) zum gemerkten Pool [pairId] («chain/pairAddress»)? Die Adresse
         * vergleicht ohne Gross-/Kleinschreibung (EVM-Prüfsummen). Wie `DexScreener.isPool` (iOS).
         */
        fun isPool(chainId: String, pairAddress: String, pairId: String?): Boolean {
            val wanted = pairId?.trim().orEmpty()
            val cut = wanted.indexOf('/')
            if (cut <= 0 || cut == wanted.lastIndex) return false
            val wantedChain = wanted.substring(0, cut)
            val wantedAddress = wanted.substring(cut + 1)
            if (pairAddress.isEmpty()) return false
            return chainId.equals(wantedChain, ignoreCase = true) && pairAddress.equals(wantedAddress, ignoreCase = true)
        }

        /** Höchstzahl Pool-Adressen je Sammelabfrage (Vorgabe von DexScreener). */
        const val BULK_MAX_POOLS = 30

        // Zeichen, die unkodiert im Pfad stehen dürfen (Chain-Namen, EVM-/Solana-Adressen)
        private val SAFE_PART = Regex("[A-Za-z0-9._~-]+")

        /**
         * Pools für die Sammelabfrage: je Chain (sortiert) die Adressen ohne Doppelte, sortiert, in Gruppen von
         * höchstens [BULK_MAX_POOLS]. Kennungen ohne «chain/adresse» oder mit Zeichen, die kodiert werden müssten,
         * fehlen — sie laufen über die Einzelabfrage. Wie `DexScreener.bulkGroups` (iOS).
         */
        fun bulkGroups(pairIds: Collection<String>): List<Pair<String, List<String>>> {
            val byChain = sortedMapOf<String, SortedSet<String>>()
            for (raw in pairIds) {
                val id = raw.trim()
                val cut = id.indexOf('/')
                if (cut <= 0 || cut == id.lastIndex) continue
                val chain = id.substring(0, cut)
                val address = id.substring(cut + 1)
                if (!isSafePart(chain) || !isSafePart(address)) continue
                byChain.getOrPut(chain) { sortedSetOf() }.add(address)
            }
            return byChain.flatMap { (chain, addresses) ->
                addresses.toList().chunked(BULK_MAX_POOLS).map { chain to it }
            }
        }

        /** Adresse einer Gruppe: `/latest/dex/pairs/{chain}/{a,b,…}`. */
        fun bulkUrl(group: Pair<String, List<String>>): String =
            "$DEXSCREENER_API/latest/dex/pairs/${group.first}/${group.second.joinToString(",")}"

        /** Pool-Abfrage bei GeckoTerminal; das Netz folgt [GeckoTerminalNetworks]. Wie iOS. */
        fun geckoTerminalUrl(chainId: String, address: String): String =
            "$GECKOTERMINAL_API/networks/${GeckoTerminalNetworks.network(chainId)}/pools/$address"

        fun searchUrl(query: String): String {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            return "$DEXSCREENER_API/latest/dex/search?q=$encoded"
        }

        /**
         * Gekürzte Adresse: die ersten 6 und die letzten 4 Zeichen («0x1234…abcd»); kurze Adressen bleiben ganz. Wie
         * `DexScreener.shortAddress` (iOS).
         */
        fun shortAddress(address: String): String {
            val trimmed = address.trim()
            return if (trimmed.length <= 12) trimmed else trimmed.take(6) + "…" + trimmed.takeLast(4)
        }

        /**
         * Treffer der Suche, nach Liquidität absteigend (meist steht der relevante Pool vorn). Ist [query] die
         * Vertragsadresse eines Tokens, stehen dessen Pools (als Basis) vor allen anderen — sonst stünde ein
         * liquiderer Pool mit dem Token nur als Gegenwährung (z. B. WETH/PEPE) oben.
         */
        fun parseSearch(body: String, query: String = ""): List<DexPool> {
            val list = JSONObject(body).optJSONArray("pairs") ?: return emptyList()
            val pools = ArrayList<DexPool>()
            list.forEachJSONObject { entry ->
                val base = entry.optJSONObject("baseToken") ?: return@forEachJSONObject
                val quote = entry.optJSONObject("quoteToken") ?: return@forEachJSONObject
                pools += DexPool(
                    chainId = entry.optString("chainId"),
                    dexId = entry.optString("dexId"),
                    pairAddress = entry.optString("pairAddress"),
                    baseSymbol = base.optString("symbol"),
                    quoteSymbol = quote.optString("symbol"),
                    priceUsd = entry.optString("priceUsd").toDoubleOrNull(),
                    liquidityUsd = liquidityOf(entry),
                    baseAddress = base.optString("address"),
                )
            }
            // Nur Pools, deren Kennung unkodiert in den Pfad darf (isSafePairId) — sonst nie übernehmbar
            val usable = pools.filter { pool ->
                pool.chainId.isNotEmpty() && pool.pairAddress.isNotEmpty() &&
                    pool.baseSymbol.isNotEmpty() && pool.priceUsd != null && isSafePairId(pool.pairId)
            }
            // sortedByDescending ist stabil: gleiche Liquidität behält die Reihenfolge der Antwort
            val ranked = usable.sortedByDescending { it.liquidityUsd ?: 0.0 }
            val wanted = query.trim()
            if (wanted.isEmpty()) return ranked
            val (exact, rest) = ranked.partition { it.baseAddress.equals(wanted, ignoreCase = true) }
            return exact + rest
        }
    }
}
