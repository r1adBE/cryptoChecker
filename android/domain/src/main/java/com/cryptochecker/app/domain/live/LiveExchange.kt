// Börsen mit Live-Strom und Parser ihrer Nachrichten (Teil von `LiveFeed.kt`, gespiegelt in iOS `LiveExchange.swift`).
package com.cryptochecker.app.domain.live

import com.cryptochecker.marketdata.model.exchange.BinanceAlpha
import com.cryptochecker.marketdata.util.EpochTime

/**
 * Börsen mit öffentlichem WebSocket-Ticker. [exchangeKey] = Schlüssel des REST-Adapters
 * (`ExchangesConfig`), [rollingChange] = der Strom liefert dieselbe gleitende 24-h-Veränderung
 * wie die REST-Abfrage (Kraken nicht: auch REST rechnet dort mit Kerzen).
 */
enum class LiveExchange(
    val exchangeKey: String,
    val url: String,
    /** Höchstens so viele Symbole je Verbindung; weitere bekommen eine eigene. */
    val maxSymbolsPerConnection: Int,
    /** Höchstens so viele Symbole je Abo-Nachricht (Bybit Spot: 10). */
    val maxSymbolsPerMessage: Int,
    /** Ping als Textnachricht (Protokoll der Börse); null = nur WebSocket-Ping-Frames. */
    val pingText: String?,
    val pingIntervalMillis: Long,
    val rollingChange: Boolean,
) {
    BINANCE("Binance", "wss://stream.binance.com:9443/ws", 200, 200, null, 0, true),
    BINANCE_FUTURES("BinanceFutures", "wss://fstream.binance.com/ws", 200, 200, null, 0, true),
    BYBIT("Bybit", "wss://stream.bybit.com/v5/public/spot", 200, 10, """{"op":"ping"}""", 20_000, true),
    BYBIT_FUTURES("BybitFutures", "wss://stream.bybit.com/v5/public/linear", 200, 10, """{"op":"ping"}""", 20_000, true),
    OKX("Okex", "wss://ws.okx.com:8443/ws/v5/public", 200, 50, "ping", 25_000, true),
    OKX_SWAP("OkexFutures", "wss://ws.okx.com:8443/ws/v5/public", 200, 50, "ping", 25_000, true),
    COINBASE("Coinbase", "wss://ws-feed.exchange.coinbase.com", 200, 200, null, 0, true),
    KRAKEN("Kraken", "wss://ws.kraken.com/v2", 200, 200, """{"method":"ping"}""", 30_000, false);

    /** Symbol im Datenstrom für ein Paar; null = dieses Paar nicht live (z. B. Laufzeit-Futures). */
    fun symbolFor(pair: LivePair): String? {
        val id = pair.pairId?.trim()?.takeIf { it.isNotEmpty() }
        return when (this) {
            // Alpha-Paare («ALPHA_175USDT») haben keinen Spot-Datenstrom: Kurse per Abfrage
            BINANCE -> id?.takeIf { !BinanceAlpha.isPairId(it) }?.uppercase()
            // Nur USDⓈ-M-Perpetuals; COIN-M («2:…») läuft über einen anderen Server
            BINANCE_FUTURES -> id?.takeIf { pair.contract == PERPETUAL && !it.startsWith("2:") }?.uppercase()
            BYBIT -> id
            BYBIT_FUTURES -> id?.takeIf { pair.contract == PERPETUAL }
            OKX -> id ?: "${pair.base}-${pair.quote}".uppercase()
            OKX_SWAP -> id?.takeIf { it.endsWith("-SWAP") }
            COINBASE -> id ?: "${pair.base}-${pair.quote}".uppercase()
            // v2 nennt Paare «BTC/USD» (nicht die REST-Kennung XXBTZUSD); Basis und Quote sind normalisiert
            KRAKEN -> if (pair.base.isBlank() || pair.quote.isBlank()) null
            else "${pair.base.trim().uppercase()}/${pair.quote.trim().uppercase()}"
        }
    }

    /** Abo-Nachrichten für [symbols], je höchstens [maxSymbolsPerMessage]. */
    fun subscribeMessages(symbols: List<String>): List<String> =
        symbols.chunked(maxSymbolsPerMessage).mapIndexed { index, chunk ->
            when (this) {
                BINANCE, BINANCE_FUTURES ->
                    """{"method":"SUBSCRIBE","params":[${chunk.joinToString(",") { quoted(it.lowercase() + "@ticker") }}],"id":${index + 1}}"""
                BYBIT, BYBIT_FUTURES ->
                    """{"op":"subscribe","args":[${chunk.joinToString(",") { quoted("tickers.$it") }}]}"""
                OKX, OKX_SWAP ->
                    """{"op":"subscribe","args":[${chunk.joinToString(",") { """{"channel":"tickers","instId":${quoted(it)}}""" }}]}"""
                COINBASE ->
                    """{"type":"subscribe","product_ids":[${chunk.joinToString(",") { quoted(it) }}],"channels":["ticker"]}"""
                KRAKEN ->
                    """{"method":"subscribe","params":{"channel":"ticker","symbol":[${chunk.joinToString(",") { quoted(it) }}]}}"""
            }
        }

    companion object {
        private const val PERPETUAL = "PERPETUAL"

        fun fromExchangeKey(key: String): LiveExchange? = entries.firstOrNull { it.exchangeKey == key }

        private fun quoted(text: String): String = buildString {
            append('"')
            for (c in text) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append(' ')
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}

/**
 * Liest Kurs-Nachrichten der Börsen. Bestätigungen, Pongs, Herzschläge und Unbekanntes
 * ergeben eine leere Liste. Feldnamen wie in den REST-Adaptern (Binance «c»/«P»,
 * Bybit «lastPrice»/«price24hPcnt» als Bruchteil, OKX «last»/«open24h», Coinbase
 * «price»/«open_24h», Kraken «last»).
 */
object LiveParser {

    fun parse(exchange: LiveExchange, text: String): List<LiveTick> {
        // OKX antwortet auf «ping» mit dem Text «pong» (kein JSON)
        if (text.isEmpty() || text[0] != '{' && text[0] != '[') return emptyList()
        val root = LiveJson.parse(text) ?: return emptyList()
        return when (exchange) {
            LiveExchange.BINANCE, LiveExchange.BINANCE_FUTURES -> binance(root)
            LiveExchange.BYBIT, LiveExchange.BYBIT_FUTURES -> bybit(root)
            LiveExchange.OKX, LiveExchange.OKX_SWAP -> okx(root)
            LiveExchange.COINBASE -> coinbase(root)
            LiveExchange.KRAKEN -> kraken(root)
        }
    }

    /** 24hrTicker: «s» Symbol, «c» letzter Kurs, «P» Veränderung in % (gleitend), «E» Ereigniszeit. */
    private fun binance(root: Any?): List<LiveTick> {
        val obj = root as? Map<*, *> ?: return emptyList()
        // Kombinierter Strom: {"stream":…,"data":{…}}
        val data = obj["data"] as? Map<*, *> ?: obj
        if (data["e"] != "24hrTicker") return emptyList()
        val symbol = (data["s"] as? String)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return listOf(LiveTick(symbol, positive(data["c"]), finite(data["P"]), millis(data["E"])))
    }

    /** tickers.SYMBOL: «lastPrice», «price24hPcnt» (Bruchteil, 0.0123 = +1,23 %), «ts» oben. */
    private fun bybit(root: Any?): List<LiveTick> {
        val obj = root as? Map<*, *> ?: return emptyList()
        val topic = obj["topic"] as? String ?: return emptyList()
        if (!topic.startsWith("tickers.")) return emptyList()
        val data = obj["data"] as? Map<*, *> ?: return emptyList()
        val symbol = (data["symbol"] as? String)?.takeIf { it.isNotEmpty() } ?: topic.removePrefix("tickers.")
        val change = finite(data["price24hPcnt"])?.let { it * 100.0 }
        return listOf(LiveTick(symbol, positive(data["lastPrice"]), change, millis(obj["ts"])))
    }

    /** tickers: je Eintrag «instId», «last», «open24h» (Kurs vor 24 h), «ts». */
    private fun okx(root: Any?): List<LiveTick> {
        val obj = root as? Map<*, *> ?: return emptyList()
        val arg = obj["arg"] as? Map<*, *> ?: return emptyList()
        if (arg["channel"] != "tickers" || obj["event"] != null) return emptyList()
        val data = obj["data"] as? List<*> ?: return emptyList()
        return data.mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            val symbol = (item["instId"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val last = positive(item["last"])
            LiveTick(symbol, last, fromOpen(last, positive(item["open24h"])), millis(item["ts"]))
        }
    }

    /** ticker: «product_id», «price», «open_24h» (Kurs vor 24 h); Zeit = Empfang. */
    private fun coinbase(root: Any?): List<LiveTick> {
        val obj = root as? Map<*, *> ?: return emptyList()
        if (obj["type"] != "ticker") return emptyList()
        val symbol = (obj["product_id"] as? String)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val last = positive(obj["price"])
        return listOf(LiveTick(symbol, last, fromOpen(last, positive(obj["open_24h"])), null))
    }

    /**
     * v2 ticker: je Eintrag «symbol», «last». «change_pct» bleibt weg: wie bei REST rechnet die
     * App für Kraken mit Kerzen (Bezug nicht eindeutig als gleitende 24 h dokumentiert).
     */
    private fun kraken(root: Any?): List<LiveTick> {
        val obj = root as? Map<*, *> ?: return emptyList()
        if (obj["channel"] != "ticker") return emptyList()
        val data = obj["data"] as? List<*> ?: return emptyList()
        return data.mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            val symbol = (item["symbol"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            LiveTick(symbol, positive(item["last"]), null, null)
        }
    }

    /** Zahl oder Zahl als Text; NaN/∞ → null. */
    fun finite(value: Any?): Double? {
        val d = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
        return d?.takeIf { it.isFinite() }
    }

    private fun positive(value: Any?): Double? = finite(value)?.takeIf { it > 0.0 }

    /**
     * Zeit der Börse in ms, egal ob sie in s, ms, µs oder ns kommt ([EpochTime.parseTimeToMillis], wie REST). Was nicht
     * in einen Long passt (z. B. «1e30»), ist keine Zeit.
     */
    private fun millis(value: Any?): Long? = finite(value)
        ?.takeIf { it >= 1 && it < Long.MAX_VALUE.toDouble() }
        ?.let { EpochTime.parseTimeToMillis(it.toLong()) }

    /** Wie `Change24h.fromOpen`: (letzter − Eröffnung) / Eröffnung in %. */
    private fun fromOpen(last: Double?, open: Double?): Double? {
        if (last == null || open == null || last <= 0.0 || open <= 0.0) return null
        return (last - open) / open * 100.0
    }
}
