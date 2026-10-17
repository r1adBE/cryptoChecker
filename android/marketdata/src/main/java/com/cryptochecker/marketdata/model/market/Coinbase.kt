package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.CurrencyPairsMap
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONObject

private const val COINBASE_API = "https://api.exchange.coinbase.com"

/** Paare vor der ersten Synchronisierung (Basis → Gegenwährungen), in dieser Reihenfolge. */
private val COINBASE_STARTER_PAIRS: CurrencyPairsMap = CurrencyPairsMap().apply {
    val btcEurGbpUsd = arrayOf("BTC", "EUR", "GBP", "USD")
    put("1INCH", btcEurGbpUsd.copyOf())
    put("AAVE", btcEurGbpUsd.copyOf())
    put("ADA", arrayOf("BTC", "ETH", "EUR", "GBP", "USD", "USDC"))
    put("ATOM", arrayOf("BTC", "USD"))
    put("BAT", arrayOf("BTC", "ETH", "EUR", "USD", "USDC"))
    put("BTC", arrayOf("EUR", "GBP", "USDC", "USD", "USDT"))
    put("DAI", arrayOf("USD", "USDC"))
    put("DASH", arrayOf("BTC", "USD"))
    put("DOGE", arrayOf("BTC", "EUR", "GBP", "USD", "USDT"))
    put("DOT", arrayOf("BTC", "EUR", "GBP", "USD", "USDT"))
    put("EOS", arrayOf("BTC", "EUR", "USD"))
    put("ETC", btcEurGbpUsd.copyOf())
    put("ETH", arrayOf("BTC", "DAI", "EUR", "GBP", "USD", "USDT", "USDC"))
    put("FIL", btcEurGbpUsd.copyOf())
    put("LINK", arrayOf("BTC", "ETH", "EUR", "GBP", "USD"))
    put("LTC", btcEurGbpUsd.copyOf())
    put("OMG", btcEurGbpUsd.copyOf())
    put("STORJ", arrayOf("BTC", "USD"))
    put("SUSHI", arrayOf("BTC", "ETH", "EUR", "GBP", "USD"))
    put("USDC", arrayOf("EUR", "GBP"))
    put("USDT", arrayOf("EUR", "GBP", "USD", "USDC"))
    put("XLM", arrayOf("BTC", "EUR", "USD"))
    put("ZEC", arrayOf("BTC", "USD", "USDC"))
}

/**
 * Coinbase Exchange (https://docs.cloud.coinbase.com/exchange/reference/). Kennung = Produkt-ID («BTC-USD»).
 *
 * Eine Anfrage je Paar: `/stats` liefert gleitend über 24 h open, high, low, last und volume — genug für
 * Merkliste, Alarme und Widgets. Geld/Brief aus `/ticker` zeigt die App nirgends dauerhaft (die Vorschau
 * beim Hinzufügen blendet fehlende Werte aus); die Zeit ist dann der Abruf.
 */
class Coinbase : SimpleMarket(
    name = "Coinbase",
    pairListUrl = "$COINBASE_API/products",
    tickerUrl = "$COINBASE_API/products/%1\$s/stats",
    errorField = "message",
    currencyPairs = COINBASE_STARTER_PAIRS,
) {

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base + "-" + info.quote)

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        // Unbekanntes oder stillgelegtes Produkt: {"message": …} statt Kursdaten — so melden statt
        // «No value for volume»
        val notice = json.optText("message")
        if (notice.isNotEmpty()) throw MarketParseException(notice)

        val last = json.getDouble("last")
        ticker.last = last
        ticker.high = json.getDouble("high")
        ticker.low = json.getDouble("low")
        // Volumen 0 ist ein ruhiges Paar (gültig); nur negative oder unlesbare Werte bleiben MISSING
        val volume = json.optDouble("volume")
        if (volume.isFinite() && volume >= 0) ticker.vol = volume
        // «open» = Kurs vor 24 h, gleitend
        ticker.change24hPercent = Change24h.fromOpen(last, json.optDouble("open"))
    }

    // ── Paarliste ──

    override fun fillPairList(call: Int, body: String, out: MutableList<MarketPair>) {
        JSONArray(body).forEachJSONObject { product ->
            if (product.optString("status") == "delisted") return@forEachJSONObject
            out += MarketPair(
                product.getString("base_currency"),
                product.getString("quote_currency"),
                product.getString("id"),
            )
        }
    }
}
