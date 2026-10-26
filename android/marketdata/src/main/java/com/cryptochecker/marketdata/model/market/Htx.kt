package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.MarketCategory
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.TradFi
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optStrings
import com.cryptochecker.marketdata.util.optText
import org.json.JSONObject

private const val HTX_SPOT_API = "https://api.huobi.pro"
private const val HTX_SWAP_API = "https://api.hbdm.com"

/** HTX (vormals Huobi) Spot. Kennung = `sc` («btcusdt»). */
class Htx : HtxBase(
    name = "HTX",
    pairListUrl = "$HTX_SPOT_API/v2/settings/common/symbols",
    tickerUrl = "$HTX_SPOT_API/market/detail/merged?symbol=%1\$s",
    errorField = "err-msg",
    allTickersUrl = "$HTX_SPOT_API/market/tickers",
    rowsField = "data",
    idField = "symbol",
    turnoverField = "vol",
    singleHighLowRolling = true,
) {
    override val key = "Htx"

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONArray("data").forEachJSONObject { entry ->
            if (entry.optString("state") != "online") return@forEachJSONObject
            out += MarketPair(entry.getString("bc").uppercase(), entry.getString("qc").uppercase(), entry.getString("sc"))
        }
    }
}

/** HTX USDT-Perpetuals (linear swap). Kennung = `contract_code` («BTC-USDT»). */
class HtxFutures : HtxBase(
    name = "HTX Futures",
    pairListUrl = "$HTX_SWAP_API/linear-swap-api/v1/swap_contract_info?business_type=swap",
    tickerUrl = "$HTX_SWAP_API/linear-swap-ex/market/detail/merged?contract_code=%1\$s",
    errorField = "err_msg",
    allTickersUrl = "$HTX_SWAP_API/linear-swap-ex/market/detail/batch_merged?business_type=swap",
    rowsField = "ticks",
    idField = "contract_code",
    turnoverField = "trade_turnover",
    singleHighLowRolling = false,
) {
    override val key = "HtxFutures"
    override val category = MarketCategory.FUTURES

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONArray("data").forEachJSONObject { entry ->
            // contract_status 1 = gelistet und handelbar
            if (entry.optInt("contract_status", -1) != 1) return@forEachJSONObject
            val code = entry.getString("contract_code")
            val sides = code.split('-')
            if (sides.size != 2) return@forEachJSONObject
            out += MarketPair(sides[0], sides[1], code, FuturesContractType.PERPETUAL, TradFi.htx(tradFiLabels(entry)))
        }
    }

    // ── Lesen ──

    /** `tradfi_labels` kommt als Liste oder als Text; nicht leer = Aktie, Rohstoff … (TradFi). */
    private fun tradFiLabels(entry: JSONObject): List<String> =
        if (entry.optJSONArray("tradfi_labels") == null) listOf(entry.optText("tradfi_labels"))
        else entry.optStrings("tradfi_labels")
}

/**
 * Gemeinsames von Spot und Swap: gleiche Ticker-Felder unter `tick`, Sammelabfrage als Liste [rowsField] mit der
 * Kennung in [idField]. Den Umsatz in der Quote nennt [turnoverField]: Spot `vol`, Swap `trade_turnover` — beim
 * Swap ist `vol` die Zahl der Kontrakte, also nie Ersatz. Die Paarliste liest jede Unterklasse selbst.
 * Hoch/Tief: Die Sammelabfragen (Spot `market/tickers`, Swap `batch_merged`) und der Swap-Einzelkurs zählen sie seit
 * Tagesbeginn UTC+8, nicht über 24 h — nur der Spot-Einzelkurs (`detail/merged`, rollend) liefert sie
 * ([singleHighLowRolling]).
 */
abstract class HtxBase(
    name: String,
    pairListUrl: String,
    tickerUrl: String,
    errorField: String,
    private val allTickersUrl: String,
    private val rowsField: String,
    private val idField: String,
    private val turnoverField: String,
    private val singleHighLowRolling: Boolean,
) : SimpleMarket(name = name, pairListUrl = pairListUrl, tickerUrl = tickerUrl, errorField = errorField) {

    // ── Einzelkurs ──

    /** `ts` der Hülle ist die Antwortzeit, kein Handel → Zeit der Börse unbekannt. */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json.getJSONObject("tick"), ticker, withHighLow = singleHighLowRolling)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = allTickersUrl

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray(rowsField).forEachJSONObject { row ->
            bulkRowIfActive(row.optString(idField), row, "close", tickers, failed) { copyRow(row, it, withHighLow = false) }
        }
    }

    // ── Lesen ──

    /**
     * Keine 24-h-Veränderung: «open» ist laut Doku der Sammelabfrage die Eröffnung des Kalendertags (Singapur) —
     * Einzel- und Sammelabruf wären sonst uneinheitlich. `ts` ist die Momentaufnahme, nicht der letzte Handel.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker, withHighLow: Boolean) {
        ticker.last = row.getDouble("close")
        // Hoch/Tief seit Tagesbeginn (UTC+8) gehörten zu einem anderen Zeitraum als die 24-h-Werte: nicht übernommen
        if (withHighLow) {
            ticker.high = row.optDoubleOrMissing("high")
            ticker.low = row.optDoubleOrMissing("low")
        }
        ticker.vol = row.optDoubleOrMissing("amount")
        ticker.volQuote = row.optDoubleOrMissing(turnoverField)
        ticker.bid = bestPrice(row, "bid")
        ticker.ask = bestPrice(row, "ask")
    }

    /** Geld/Brief kommen als Zahl oder als [Preis, Menge]. */
    private fun bestPrice(row: JSONObject, field: String): Double {
        val level = row.optJSONArray(field) ?: return row.optDoubleOrMissing(field)
        return level.optDouble(0, Ticker.MISSING)
    }
}
