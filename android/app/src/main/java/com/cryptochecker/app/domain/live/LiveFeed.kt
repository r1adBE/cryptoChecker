package com.cryptochecker.app.domain.live

import kotlin.math.min

/*
 * Live-Kurse per WebSocket (Runde 31): reine Logik ohne Android — Börsen, Abo-Nachrichten,
 * Parser, Planung der Verbindungen, Puffer und Regeln. Die Verbindungen selbst hält
 * `data/live/LivePriceStream`. Gleiche Logik in iOS `App/Services/LiveFeed.swift`;
 * gemeinsame Testfälle in `testdata/parity/live_feed.json`. Siehe DEVELOPMENT.md, «Live prices».
 */

/**
 * Ein Kurs aus dem Datenstrom. [price] / [change24h] null = in dieser Nachricht nicht enthalten
 * (Bybit-Futures schicken nach dem ersten Stand nur geänderte Felder). [change24h] in Prozent
 * (1.5 = +1,5 %), nur gleitende 24 h. [time] in ms seit 1970; null = Empfangszeit nehmen.
 */
data class LiveTick(val symbol: String, val price: Double?, val change24h: Double?, val time: Long?)

/** Letzter bekannter Live-Stand eines Paars (Watch-Id). */
data class LiveQuote(val price: Double, val change24h: Double?, val time: Long)

/** Paar der sichtbaren Merkliste, soweit der Datenstrom es braucht. */
data class LivePair(
    val watchId: Long,
    val marketKey: String,
    val marketName: String,
    val pairId: String?,
    val base: String,
    val quote: String,
    /** Name von `FuturesContractType` (NONE, PERPETUAL, QUARTERLY …). */
    val contract: String,
    /** Wird nicht mehr gehandelt: kein Abo. */
    val notTraded: Boolean = false,
)

/**
 * Börsen mit öffentlichem WebSocket-Ticker. [marketKey] = Schlüssel des REST-Adapters
 * (`MarketsConfig`), [rollingChange] = der Strom liefert dieselbe gleitende 24-h-Veränderung
 * wie die REST-Abfrage (Kraken nicht: auch REST rechnet dort mit Kerzen).
 */
enum class LiveExchange(
    val marketKey: String,
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
            BINANCE -> id?.uppercase()
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

        fun fromMarketKey(key: String): LiveExchange? = entries.firstOrNull { it.marketKey == key }

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
    internal fun finite(value: Any?): Double? {
        val d = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
        return d?.takeIf { it.isFinite() }
    }

    private fun positive(value: Any?): Double? = finite(value)?.takeIf { it > 0.0 }

    private fun millis(value: Any?): Long? = finite(value)?.toLong()?.takeIf { it > 0 }

    /** Wie `Change24h.fromOpen`: (letzter − Eröffnung) / Eröffnung in %. */
    private fun fromOpen(last: Double?, open: Double?): Double? {
        if (last == null || open == null || last <= 0.0 || open <= 0.0) return null
        return (last - open) / open * 100.0
    }
}

/** Verbindungen eines Plans: eine Börse, ihre Symbole (sortiert, höchstens [LiveExchange.maxSymbolsPerConnection]). */
data class LiveConnectionSpec(val exchange: LiveExchange, val index: Int, val symbols: List<String>) {
    val key: String get() = "${exchange.name}#$index"
}

/** Ergebnis von [LivePlanner.plan]. */
data class LivePlan(
    val connections: List<LiveConnectionSpec>,
    /** Je Börse: Symbol → Watch-Ids (dasselbe Symbol kann mehrmals in der Merkliste stehen). */
    val watchIds: Map<LiveExchange, Map<String, List<Long>>>,
) {
    val isEmpty: Boolean get() = connections.isEmpty()
}

object LivePlanner {

    /** Mehr Verbindungen je Börse öffnet die App nicht; die übrigen Paare bleiben bei REST. */
    const val MAX_CONNECTIONS_PER_EXCHANGE = 3

    /**
     * Welche Verbindungen für die sichtbaren Paare? Ohne Börsen mit WebSocket, nicht mehr
     * gehandelte Paare und Börsen in [pausedMarketKeys] (ExchangeBackoff).
     */
    fun plan(pairs: List<LivePair>, pausedMarketKeys: Set<String> = emptySet()): LivePlan {
        val ids = LinkedHashMap<LiveExchange, MutableMap<String, MutableList<Long>>>()
        for (pair in pairs) {
            if (pair.notTraded || pair.marketKey in pausedMarketKeys) continue
            val exchange = LiveExchange.fromMarketKey(pair.marketKey) ?: continue
            val symbol = exchange.symbolFor(pair) ?: continue
            ids.getOrPut(exchange) { LinkedHashMap() }.getOrPut(symbol) { ArrayList() } += pair.watchId
        }
        val connections = ArrayList<LiveConnectionSpec>()
        val kept = LinkedHashMap<LiveExchange, Map<String, List<Long>>>()
        for (exchange in LiveExchange.entries) {
            val bySymbol = ids[exchange] ?: continue
            val chunks = bySymbol.keys.sorted().chunked(exchange.maxSymbolsPerConnection)
                .take(MAX_CONNECTIONS_PER_EXCHANGE)
            chunks.forEachIndexed { index, chunk -> connections += LiveConnectionSpec(exchange, index, chunk) }
            val subscribed = chunks.flatten().toSet()
            kept[exchange] = bySymbol.filterKeys { it in subscribed }
        }
        return LivePlan(connections, kept)
    }
}

/** Neuer Verbindungsversuch nach einem Abbruch: 1, 2, 4 … höchstens 60 s; abgewiesen (429/418) 5 min. */
object LiveBackoff {
    const val MAX_MILLIS = 60_000L
    const val RATE_LIMITED_MILLIS = 300_000L

    fun delayMillis(attempt: Int, rateLimited: Boolean): Long {
        if (rateLimited) return RATE_LIMITED_MILLIS
        val step = attempt.coerceIn(0, 6)
        return min(MAX_MILLIS, 1_000L shl step)
    }
}

/**
 * Regeln: Anzeige höchstens zweimal je Sekunde, Datenbank alle 10 s, Widgets höchstens jede
 * Minute; welche 24-h-Veränderung gilt; wann ein Paar die REST-Abfrage auslassen darf.
 */
object LiveRules {
    const val UI_INTERVAL_MILLIS = 500L
    const val DB_INTERVAL_MILLIS = 10_000L
    const val WIDGET_INTERVAL_MILLIS = 60_000L

    /** So lange gilt ein Paar nach dem letzten Kurs als live; danach wieder REST. */
    const val FRESH_MILLIS = 30_000L

    /** Kein Lebenszeichen der Verbindung so lange: neu verbinden. */
    const val STALE_CONNECTION_MILLIS = 60_000L

    /**
     * Veränderung, die mit dem Live-Kurs gespeichert bzw. gezeigt wird: die des Stroms nur bei
     * Basis «Letzte 24 Std.», passendem Stempel und Börse mit gleitendem Wert — sonst bleibt
     * die bisherige (sie wird bei der nächsten REST-Abfrage neu gerechnet).
     */
    fun chooseChange(
        rollingBasis: Boolean,
        stampCurrent: Boolean,
        exchangeRolling: Boolean,
        live: Double?,
        existing: Double?,
    ): Double? = if (rollingBasis && stampCurrent && exchangeRolling && live != null && live.isFinite()) live else existing

    /** Kurs vor höchstens [FRESH_MILLIS] aus dem Strom? */
    fun isFresh(lastTickAt: Long?, now: Long): Boolean =
        lastTickAt != null && lastTickAt > 0 && now - lastTickAt in 0..FRESH_MILLIS

    /**
     * Darf die REST-Abfrage dieses Paar auslassen? Nur mit frischem Live-Kurs, rollender Basis
     * (Tages-Basen brauchen Kerzen), unverändertem Stempel und gleitendem Wert der Börse.
     */
    fun skipRest(lastTickAt: Long?, now: Long, rollingBasis: Boolean, stampUnchanged: Boolean, exchangeRolling: Boolean): Boolean =
        rollingBasis && stampUnchanged && exchangeRolling && isFresh(lastTickAt, now)
}

/**
 * Puffer der Live-Kurse je Watch-Id (nicht threadsicher — der Aufrufer sperrt). Merkt sich,
 * was die Anzeige noch nicht gesehen hat und was noch nicht gespeichert ist.
 */
class LiveBuffer {
    private val latest = HashMap<Long, LiveQuote>()
    private val unsaved = LinkedHashSet<Long>()
    private var uiDirty = false

    /** Kurs übernehmen; fehlende Felder (Delta) vom letzten Stand. false = nichts geändert. */
    fun offer(watchIds: List<Long>, tick: LiveTick, now: Long): Boolean {
        var changed = false
        for (id in watchIds) {
            val previous = latest[id]
            val price = tick.price ?: previous?.price ?: continue
            val quote = LiveQuote(
                price = price,
                change24h = tick.change24h ?: previous?.change24h,
                time = tick.time?.takeIf { it > 0 } ?: now,
            )
            if (quote == previous) continue
            latest[id] = quote
            // Nur ein neuer Kurs muss gespeichert werden (nicht jede neue Zeit)
            if (previous == null || previous.price != quote.price || previous.change24h != quote.change24h) unsaved += id
            uiDirty = true
            changed = true
        }
        return changed
    }

    /** Stand für die Anzeige, falls sich seit dem letzten Aufruf etwas geändert hat; sonst null. */
    fun takeForUi(): Map<Long, LiveQuote>? {
        if (!uiDirty) return null
        uiDirty = false
        return HashMap(latest)
    }

    /** Ungespeicherte Kurse; danach gelten sie als gespeichert. */
    fun takeForDb(): Map<Long, LiveQuote> {
        if (unsaved.isEmpty()) return emptyMap()
        val batch = unsaved.mapNotNull { id -> latest[id]?.let { id to it } }.toMap()
        unsaved.clear()
        return batch
    }

    /** Speichern ging nicht (Aktualisierung lief): beim nächsten Mal nochmals. */
    fun markUnsaved(ids: Collection<Long>) {
        ids.filterTo(unsaved) { it in latest }
    }

    /** Nur noch diese Paare behalten (andere Gruppe gewählt). */
    fun retain(ids: Set<Long>) {
        if (latest.keys.retainAll(ids)) uiDirty = true
        unsaved.retainAll(ids)
    }

    fun clear() {
        if (latest.isNotEmpty()) uiDirty = true
        latest.clear()
        unsaved.clear()
    }

    val size: Int get() = latest.size
}
