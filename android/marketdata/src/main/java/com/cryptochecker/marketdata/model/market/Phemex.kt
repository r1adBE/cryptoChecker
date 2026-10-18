package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.TradFi
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONObject

/** Produktliste (Paare und Faktoren); bei fehlenden Faktoren auch erste Anfrage der Spot-Kurse. */
private const val PHEMEX_PRODUCTS = "https://api.phemex.com/public/products"

private const val PHEMEX_MD = "https://api.phemex.com/md"

/**
 * Phemex Spot. Symbole beginnen mit «s» (sBTCUSDT). Die Börse liefert ganze Zahlen (Ep/Ev), die erst mit
 * [PhemexScales] zu Kursen werden: Preise (Ep) durch 10^`priceScale` des Produkts (ohne Angabe kein Kurs),
 * Menge (volumeEv, Basis) und Umsatz (turnoverEv, Kotierung) durch 10^`valueScale` der jeweiligen Währung aus
 * `/public/products` (meist 8; SHIB 2, VPAD 4). Doku: github.com/phemex/phemex-api-docs.
 * [PhemexFutures] bekommt Dezimaltexte (Rp) und braucht keine Faktoren.
 */
class Phemex : PhemexBase(
    name = "Phemex",
    tickerUrl = "$PHEMEX_MD/spot/ticker/24hr?symbol=%1\$s",
    contractType = FuturesContractType.NONE,
) {
    override val key = "Phemex"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String =
        info.pairId ?: "s${info.base}${info.quote}"

    /**
     * Fehlen die Faktoren, ist Anfrage 0 die Produktliste (diesmal ohne Kurs, ab der nächsten Abfrage mit — sonst
     * hinge ein Paar ohne Sammelabfrage für immer im Fehler). Veraltete Faktoren ([PhemexScales.isStale]) frischt
     * eine zweite Anfrage nach dem Kurs auf.
     */
    override fun tickerCallCount(info: PairQuery?): Int = callsFor(PhemexScales.current)

    override fun tickerAddress(call: Int, info: PairQuery): String {
        val products = when (call) {
            0 -> !PhemexScales.current.loaded
            1 -> true
            else -> false
        }
        return if (products) PHEMEX_PRODUCTS else super.tickerAddress(call, info)
    }

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val productData = productDataOf(json)
        if (productData == null) {
            super.fillTickerJson(call, json, ticker, info)
            return
        }
        // Antwort war die Produktliste: Faktoren übernehmen (und ablegen); als Anfrage 0 gibt es noch keinen Kurs
        PhemexScales.current = PhemexScales.parse(productData)
        if (call == 0) throw MarketParseException(PhemexScales.JUST_LOADED)
    }

    // ── Sammelabfrage ──

    /**
     * Ohne Faktoren (auch keine gespeicherten): erst `/public/products`, dann die Kurse. Veraltete Faktoren: die
     * Produktliste als zweite Anfrage nach den Kursen (scheitert sie, gelten die alten weiter — Gruppe 1).
     */
    override val bulkTickersNumOfRequests: Int
        get() = callsFor(PhemexScales.current)

    override fun getBulkTickersUrl(call: Int): String =
        if (isProductsCall(call)) PHEMEX_PRODUCTS else "$PHEMEX_MD/spot/ticker/24hr/all"

    /** Die Auffrischung (Anfrage 1) ist eine eigene Gruppe: Scheitert sie, bleiben die Kurse vollständig. */
    override fun bulkTickersRequestGroup(call: Int): Int =
        if (call == 1 && isProductsCall(1)) 1 else 0

    /** Spot ohne Faktoren: Diese Antwort ist die Produktliste — Faktoren übernehmen, keine Kurse. */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val productData = productDataOf(JSONObject(body))
        if (productData != null) {
            PhemexScales.current = PhemexScales.parse(productData)
            return
        }
        super.parseBulkTickers(call, body, tickers, failed)
    }

    // ── Lesen ──

    override fun copyRow(row: JSONObject, ticker: Ticker, symbol: String, base: String?, quote: String?) {
        // Ohne Faktoren aus der Produktliste (oder ihrer Kopie) kein Kurs: geraten läge er Zehnerpotenzen daneben
        val scales = PhemexScales.current
        if (!scales.loaded) throw MarketParseException(PhemexScales.NOT_LOADED)
        // Symbol ohne priceScale in der Liste (fehlt oder neu): ebenfalls kein Kurs statt geratener 10^8
        val priceDivisor = scales.priceDivisor(symbol)
            ?: throw MarketParseException(PhemexScales.UNKNOWN_SCALE.format(symbol))
        val (baseCode, quoteCode) = scales.currencies(symbol, base, quote)

        val last = row.getLong("lastEp") / priceDivisor
        ticker.last = last
        ticker.bid = row.scaled("bidEp", priceDivisor)
        ticker.ask = row.scaled("askEp", priceDivisor)
        ticker.high = row.scaled("highEp", priceDivisor)
        ticker.low = row.scaled("lowEp", priceDivisor)
        ticker.vol = row.scaled("volumeEv", scales.valueDivisor(baseCode))
        ticker.volQuote = row.scaled("turnoverEv", scales.valueDivisor(quoteCode))
        // «timestamp» (ns) = Momentaufnahme (≈ jetzt), kein Handel: Zeit der Börse bleibt offen.
        // «openEp» = Kurs vor 24 h
        ticker.change24hPercent = Change24h.fromOpen(last, row.scaled("openEp", priceDivisor))
    }

    /** `data` der Produktliste, falls [json] diese ist (statt eines Tickers); sonst null. */
    private fun productDataOf(json: JSONObject): JSONObject? = json.optJSONObject("data")?.takeIf { it.has("products") }

    private fun isProductsCall(call: Int): Boolean {
        val scales = PhemexScales.current
        return if (!scales.loaded) call == 0 else call == 1 && scales.isStale()
    }

    private fun callsFor(scales: PhemexScales): Int = if (scales.isStale()) 2 else 1

    /** Ganzzahlfeld [name] durch [divisor]; fehlt es oder ist es null: keine Daten. */
    private fun JSONObject.scaled(name: String, divisor: Double): Double =
        if (!has(name) || isNull(name)) Ticker.MISSING else optLong(name) / divisor
}

/** Phemex USDT-Perpetuals: Dezimaltexte (Rp/Rq/Rv), ohne Geld-/Briefkurs. */
class PhemexFutures : PhemexBase(
    name = "Phemex Futures",
    tickerUrl = "$PHEMEX_MD/v2/ticker/24hr?symbol=%1\$s",
    contractType = FuturesContractType.PERPETUAL,
) {
    override val key = "PhemexFutures"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: "${info.base}${info.quote}"

    // ── Sammelabfrage ──

    override fun getBulkTickersUrl(call: Int): String = "$PHEMEX_MD/v2/ticker/24hr/all"

    // ── Lesen ──

    override fun copyRow(row: JSONObject, ticker: Ticker, symbol: String, base: String?, quote: String?) {
        val close = row.getDouble("closeRp")
        ticker.last = close
        ticker.high = row.optDoubleOrMissing("highRp")
        ticker.low = row.optDoubleOrMissing("lowRp")
        ticker.vol = row.optDoubleOrMissing("volumeRq")
        ticker.volQuote = row.optDoubleOrMissing("turnoverRv")
        // «timestamp» (ns): Momentaufnahme wie bei Spot. «openRp» = Kurs vor 24 h
        ticker.change24hPercent = Change24h.fromOpen(close, row.optDouble("openRp"))
    }
}

/** Gemeinsames von Spot und Perpetuals: Paarliste aus `/public/products`, Fehlerformat, Sammelabfrage. */
abstract class PhemexBase(
    name: String,
    tickerUrl: String,
    private val contractType: FuturesContractType,
) : SimpleMarket(name = name, pairListUrl = PHEMEX_PRODUCTS, tickerUrl = tickerUrl, ttsName = "Phemex") {

    private val isSpot: Boolean
        get() = contractType == FuturesContractType.NONE

    /** Liest einen Ticker; [symbol] = Paar-Kennung, [base]/[quote] wenn bekannt (Einzelabfrage). */
    protected abstract fun copyRow(row: JSONObject, ticker: Ticker, symbol: String, base: String?, quote: String?)

    // ── Einzelkurs ──

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val symbol = pairId(info).orEmpty()
        copyRow(json.getJSONObject("result"), ticker, symbol, info.base, info.quote)
    }

    /** Fehlerformen: {"error":{…}} oder {"code":…,"msg":"…"}. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        val nested = json.optJSONObject("error")
        if (nested != null) return nested.optText("message").ifEmpty { nested.toString() }
        return json.optText("msg").ifEmpty { noErrorText() }
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Eine Fehlerantwort statt Liste scheitert mit dem Text der Börse, statt still leer zu bleiben (wie iOS). */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val root = JSONObject(body)
        val rows = root.optJSONArray("result") ?: throw MarketParseException(bulkFailureText(root))
        val priceField = if (isSpot) "lastEp" else "closeRp"
        rows.forEachJSONObject { row ->
            val symbol = row.optString("symbol")
            bulkRowIfActive(symbol, row, priceField, tickers, failed) { copyRow(row, it, symbol, null, null) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        val data = json.getJSONObject("data")
        if (isSpot) collectSpot(data, out) else collectPerpetuals(data, out)
    }

    // ── Lesen ──

    private fun collectSpot(data: JSONObject, out: MutableList<MarketPair>) {
        // Mit der Paarliste kommen die Faktoren (Ep/Ev): gleich übernehmen
        PhemexScales.current = PhemexScales.parse(data)
        data.getJSONArray("products").forEachJSONObject { product ->
            if (product.optString("type") != "Spot" || product.optString("status") != "Listed") return@forEachJSONObject
            out += MarketPair(
                product.getString("baseCurrency"),
                product.getString("quoteCurrency"),
                product.getString("symbol"),
            )
        }
    }

    private fun collectPerpetuals(data: JSONObject, out: MutableList<MarketPair>) {
        val list = data.optJSONArray("perpProductsV2") ?: return
        list.forEachJSONObject { product ->
            if (product.optString("status") != "Listed") return@forEachJSONObject
            val kind = product.optString("type")
            if (kind.isNotEmpty() && !kind.contains("Perpetual", ignoreCase = true)) return@forEachJSONObject
            out += MarketPair(
                product.getString("baseCurrency"),
                product.getString("quoteCurrency"),
                product.getString("symbol"),
                contractType,
                // perpProductSubType «TradFi» (Aktien, Rohstoffe …) wie bei Binance/Bybit markieren
                TradFi.phemex(product.optText("perpProductSubType")),
            )
        }
    }

    private fun bulkFailureText(root: JSONObject): String {
        val nested = root.optJSONObject("error")?.optText("message")
        if (!nested.isNullOrEmpty()) return nested
        return root.optText("msg").ifEmpty { "No result" }
    }
}
