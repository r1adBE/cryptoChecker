package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.TradFi
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.forEachOptJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optStrings
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONObject
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val BINANCE_FAPI = "https://fapi.binance.com/fapi/v1"
private const val BINANCE_DAPI = "https://dapi.binance.com/dapi/v1"

/** 24-h-Ticker je Endpunkt: mit `symbol` ein Kontrakt, ohne Parameter alle. */
private const val BINANCE_FAPI_DAY_TICKER = "$BINANCE_FAPI/ticker/24hr"
private const val BINANCE_DAPI_DAY_TICKER = "$BINANCE_DAPI/ticker/24hr"

/** Vorsatz der COIN-M-Kennungen. */
private const val BINANCE_COIN_M_MARK = "2:"

/** Verfallstag im Symbol der Laufzeit-Kontrakte («251226»). */
private val BINANCE_EXPIRY_CODE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd", Locale.ROOT)

/**
 * Binance Futures: USDⓈ-M (fapi, Kennung = Symbol) und COIN-M (dapi, Kennung «2:» + Symbol). Zwei Endpunkte,
 * die ohne `symbol` je alle Kontrakte liefern — darum kein `SimpleMarket`.
 */
class BinanceFutures : Market("Binance Futures", "Binance Futures") {

    /**
     * Darf der Kontrakt gewählt werden? Nur «TRADING» — abgelaufene, abrechnende oder geschlossene
     * (DELIVERING, SETTLING, CLOSE …) nicht. USD-M nennt das Feld «status», COIN-M «contractStatus»;
     * fehlt beides, gilt er als wählbar.
     */
    fun isTradingContract(market: JSONObject): Boolean {
        val state = market.optText("status").ifEmpty { market.optText("contractStatus") }
        return state.isEmpty() || state == "TRADING"
    }

    // ── Einzelkurs ──

    override fun tickerAddress(call: Int, info: PairQuery): String {
        val pairId = pairIdOf(info)
        val endpoint = dayTickerOf(pairId)
        val symbol = pairId.removePrefix(BINANCE_COIN_M_MARK)
        // Laufzeit-Kontrakt: immer das Symbol der Paarliste («BTCUSDT_251226»). Der Kalender träfe nach der
        // Abrechnung (Freitag 08:00 UTC) den abgelaufenen Kontrakt — er dient nur alten Kennungen ohne Datum.
        if (FuturesContractType.deliveryCodeOf(symbol) != null) return "$endpoint?symbol=$symbol"
        val delivery = FuturesContractType.getDeliveryDate(info.contractType)
            ?: return "$endpoint?symbol=$symbol"
        val dated = info.base + info.quote + "_" + BINANCE_EXPIRY_CODE.format(delivery)
        return "$endpoint?symbol=$dated"
    }

    /** COIN-M antwortet mit einem Array aus einem Eintrag. */
    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        val row = if (isCoinM(pairIdOf(info))) JSONArray(body).getJSONObject(0) else JSONObject(body)
        copyRow(row, ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 2

    /** Ungefiltert enthält jede Antwort alle gehandelten Kontrakte. */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = if (call == 0) BINANCE_FAPI_DAY_TICKER else BINANCE_DAPI_DAY_TICKER

    /** USDⓈ-M (0) und COIN-M (1) je für sich «vollständig»: fällt dapi aus, bleibt fapi verlässlich. */
    override fun bulkTickersRequestGroup(call: Int): Int = if (call == 0) 0 else 1

    override fun bulkTickersPairGroup(pairId: String): Int = if (isCoinM(pairId)) 1 else 0

    /** Einträge, die keine Objekte sind, übergehen; unlesbare Zahlen treffen nur ihr Paar. */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val coinM = call > 0
        JSONArray(body).forEachOptJSONObject { row ->
            val symbol = row.optText("symbol")
            val pairId = if (coinM && symbol.isNotEmpty()) BINANCE_COIN_M_MARK + symbol else symbol
            bulkRowIfActive(pairId, row, "lastPrice", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override val pairListCallCount: Int
        get() = 2

    /** Ohne COIN-M (dapi) keine halbe Liste speichern: dann bleibt die bisherige. */
    override val pairListAllCallsRequired: Boolean
        get() = true

    override fun pairListAddress(call: Int): String =
        if (call == 0) "$BINANCE_FAPI/exchangeInfo" else "$BINANCE_DAPI/exchangeInfo"

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        val coinM = call > 0
        json.getJSONArray("symbols").forEachJSONObject { entry ->
            val kind = entry.getString("contractType")
            val type = contractKind(kind, coinM) ?: return@forEachJSONObject
            if (!isTradingContract(entry)) return@forEachJSONObject
            val symbol = entry.getString("symbol")
            out += MarketPair(
                entry.getString("baseAsset"),
                entry.getString("quoteAsset"),
                if (coinM) BINANCE_COIN_M_MARK + symbol else symbol,
                type,
                tradFi = TradFi.binance(kind, entry.optStrings("underlyingSubType")),
            )
        }
    }

    // ── Lesen ──

    /**
     * Ein 24-h-Eintrag in [ticker]. Unbekanntes Symbol: Binance schickt {"code":…,"msg":…} — als
     * Fehlertext melden statt «No value for volume». `closeTime` ist nur das Fensterende, kein Handel:
     * die Zeit der Börse bleibt unbekannt.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val problem = row.optText("msg")
        if (problem.isNotEmpty()) throw MarketParseException(problem)

        ticker.last = row.getDouble("lastPrice")
        ticker.high = row.getDouble("highPrice")
        ticker.low = row.getDouble("lowPrice")
        // COIN-M: «volume» zählt Kontrakte, «baseVolume» ist die Basismenge; USD-M kennt nur «volume» (Basis)
        ticker.vol = if (row.isNull("baseVolume")) row.getDouble("volume") else row.getDouble("baseVolume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        // gleitende 24 h, bereits Prozent
        ticker.change24hPercent = Change24h.percent(row.optDouble("priceChangePercent"))
    }

    /**
     * Vertragsart der Börse → App. TradFi-Perpetuals (Aktien, Rohstoffe, Devisen, Pre-IPO) laufen technisch
     * wie gewöhnliche Perpetuals. COIN-M rechnet in Coins ab: dort ist ein Perpetual invers. Unbekannt → null.
     */
    private fun contractKind(kind: String, coinM: Boolean): FuturesContractType? = when (kind) {
        "PERPETUAL", TradFi.BINANCE_TRADFI_PERPETUAL ->
            if (coinM) FuturesContractType.INVERSE_PERPETUAL else FuturesContractType.PERPETUAL
        "CURRENT_QUARTER" -> FuturesContractType.QUARTERLY
        "NEXT_QUARTER" -> FuturesContractType.BIQUARTERLY
        else -> null
    }

    /** Ohne Paar-Kennung gibt es keine Kursabfrage. */
    private fun pairIdOf(info: PairQuery): String =
        info.pairId ?: throw MarketParseException("Missing pair id")

    private fun dayTickerOf(pairId: String): String = if (isCoinM(pairId)) BINANCE_DAPI_DAY_TICKER else BINANCE_FAPI_DAY_TICKER

    private fun isCoinM(pairId: String): Boolean = pairId.startsWith(BINANCE_COIN_M_MARK)
}
