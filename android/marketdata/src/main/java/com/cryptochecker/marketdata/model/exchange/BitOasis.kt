package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.forEachString
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONObject

private const val BITOASIS_API = "https://api.bitoasis.net/v1/exchange"

/**
 * BitOasis (VAE, AED-Paare), öffentliche API — Kennung wie «BTC-AED». Keine Sammelabfrage; die Paarliste steht je
 * Coin unter `tokens[].available_pairs` (nur `trade_enabled`). Fehler als `{"status": "error", "message"}`.
 */
class BitOasis : SimpleExchange(
    name = "BitOasis",
    pairListUrl = "$BITOASIS_API/markets",
    tickerUrl = "$BITOASIS_API/ticker/%1\$s",
    errorField = "message",
) {
    override val key = "BitOasis"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base.uppercase() + "-" + info.quote.uppercase())

    /**
     * `ticker`: `open_price` = Kurs vor 24 h (gleitend: er wandert stündlich mit, BTC-USDT gegen Binance geprüft),
     * Hoch/Tief über dasselbe Fenster. Kein Volumen, keine Handelszeit.
     */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val row = json.getJSONObject("ticker")
        val last = row.getDouble("last_price")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("daily_high")
        ticker.low = row.optDoubleOrMissing("daily_low")
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("open_price"))
    }

    // ── Paarliste ──

    /** Je handelbarem Coin seine Paare «BASE-QUOTE». */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONArray("tokens").forEachJSONObject { token ->
            if (!token.optBoolean("trade_enabled", false)) return@forEachJSONObject
            token.optJSONArray("available_pairs")?.forEachString { id ->
                val parts = id.split('-')
                if (parts.size == 2 && parts.all(String::isNotEmpty)) {
                    out += ExchangePair(parts[0].uppercase(), parts[1].uppercase(), id)
                }
            }
        }
    }
}
