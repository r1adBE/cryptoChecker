package com.cryptochecker.app.domain.live

import kotlin.math.min
import kotlin.math.roundToLong

/*
 * Live-Kurse per WebSocket (Runde 31): reine Logik ohne Android — Börsen, Abo-Nachrichten,
 * Parser, Planung der Verbindungen, Puffer und Regeln. Die Verbindungen selbst hält
 * `data/live/LivePriceStream`. Gleiche Logik in iOS `App/Services/LiveFeed.swift`;
 * gemeinsame Testfälle in `testdata/parity/live_feed.json`. Siehe DEVELOPMENT.md (Repository-Hauptordner), «Live prices».
 */

/**
 * Ein Kurs aus dem Datenstrom. [price] / [change24h] null = in dieser Nachricht nicht enthalten
 * (Bybit-Futures schicken nach dem ersten Stand nur geänderte Felder). [change24h] in Prozent
 * (1.5 = +1,5 %), nur gleitende 24 h. [time] in ms seit 1970; null = Empfangszeit nehmen.
 */
data class LiveTick(val symbol: String, val price: Double?, val change24h: Double?, val time: Long?)

/**
 * Letzter bekannter Live-Stand eines Paars (Watch-Id). [time] = Zeit des Takts mit diesem Kurs (Empfang bzw.
 * Ereigniszeit, gilt als Abrufzeit). [tradeTime] = belegte Handelszeit: nur gesetzt, wenn der Strom selbst einen
 * Kurswechsel gezeigt hat (zweiter Takt mit anderem Kurs) — der erste Takt nach dem Verbinden ist nur eine
 * Momentaufnahme (Binance «E», Bybit/OKX «ts»), kein Handel; null = keine Handelszeit bekannt.
 */
data class LiveQuote(val price: Double, val change24h: Double?, val time: Long, val tradeTime: Long? = null)

/** Paar der sichtbaren Merkliste, soweit der Datenstrom es braucht. */
data class LivePair(
    val watchId: Long,
    val exchangeKey: String,
    val exchangeName: String,
    val pairId: String?,
    val base: String,
    val quote: String,
    /** Name von `FuturesContractType` (NONE, PERPETUAL, QUARTERLY …). */
    val contract: String,
    /** Wird nicht mehr gehandelt: kein Abo. */
    val notTraded: Boolean = false,
)

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
     * gehandelte Paare und Börsen in [pausedExchangeKeys] (ExchangeBackoff).
     */
    fun plan(pairs: List<LivePair>, pausedExchangeKeys: Set<String> = emptySet()): LivePlan {
        val ids = LinkedHashMap<LiveExchange, MutableMap<String, MutableList<Long>>>()
        for (pair in pairs) {
            if (pair.notTraded || pair.exchangeKey in pausedExchangeKeys) continue
            val exchange = LiveExchange.fromExchangeKey(pair.exchangeKey) ?: continue
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

    /**
     * Erst eine Verbindung, die so lange hielt und Kurse brachte, setzt die Pause zurück. Sonst
     * verbände eine Börse, die nach dem ersten Stand trennt, jede Sekunde neu.
     */
    const val STABLE_MILLIS = 60_000L

    /** Streuung der Pause (±20 %), damit nicht alle Verbindungen im selben Takt neu verbinden. */
    const val JITTER = 0.2

    /** Grundpause ohne Streuung (gemeinsame Testfälle). */
    fun delayMillis(attempt: Int, rateLimited: Boolean): Long {
        if (rateLimited) return RATE_LIMITED_MILLIS
        val step = attempt.coerceIn(0, 6)
        return min(MAX_MILLIS, 1_000L shl step)
    }

    /**
     * Zähler für den nächsten Versuch: 0 nur, wenn die Verbindung Kurse brachte ([receivedData]) und
     * mindestens [STABLE_MILLIS] bestand ([connectedMillis] = Dauer seit dem Verbindungsaufbau).
     */
    fun nextAttempt(attempt: Int, receivedData: Boolean, connectedMillis: Long): Int =
        if (receivedData && connectedMillis >= STABLE_MILLIS) 0 else min(attempt, MAX_ATTEMPT) + 1

    /**
     * Pause vor dem nächsten Versuch: Grundpause ±[JITTER], [random] in 0..1 (0,5 = ohne Streuung).
     * Abgewiesen (429/418): fest 5 min, nie kürzer als von der Börse verlangt.
     */
    fun reconnectDelayMillis(attempt: Int, rateLimited: Boolean, random: Double): Long {
        val base = delayMillis(attempt, rateLimited)
        if (rateLimited) return base
        val r = if (random.isFinite()) random.coerceIn(0.0, 1.0) else 0.5
        return (base * (1.0 + JITTER * (2.0 * r - 1.0))).roundToLong()
    }

    /** Obergrenze des Zählers (die Pause wächst ab 6 ohnehin nicht mehr). */
    private const val MAX_ATTEMPT = 100
}

/**
 * Regeln: Anzeige höchstens zweimal je Sekunde, Datenbank alle 10 s, Widgets höchstens jede
 * Minute; welche 24-h-Veränderung gilt; wann ein Paar die REST-Abfrage auslassen darf.
 */
object LiveRules {

    /**
     * Zeit eines neuen Kurses: die der Börse, ausser sie fehlt oder liegt mehr als 5 Minuten in der Zukunft
     * (falsch gelesen, Ortszeit als Epoche) – dann jetzt. Wie die REST-Abfrage (`Exchange.markFetched`): eine Zeit
     * aus der Zukunft liesse einen stehenden Kurs lange frisch aussehen.
     */
    fun tickTime(reported: Long?, now: Long): Long =
        reported?.takeIf { it > 0 && it <= now + com.cryptochecker.marketdata.model.Exchange.EXCHANGE_TIME_FUTURE_TOLERANCE_MS } ?: now
    const val UI_INTERVAL_MILLIS = 500L
    const val DB_INTERVAL_MILLIS = 10_000L
    const val WIDGET_INTERVAL_MILLIS = 60_000L

    /** So lange gilt ein Paar nach dem letzten Kurs als live; danach wieder REST. */
    const val FRESH_MILLIS = 30_000L

    /** Kein Lebenszeichen der Verbindung so lange: neu verbinden. */
    const val STALE_CONNECTION_MILLIS = 60_000L

    /** Grösste Nachricht des Stroms (1 MiB, wie iOS `maximumMessageSize`); grössere trennen die Verbindung. */
    const val MAX_MESSAGE_BYTES = 1_048_576

    /** Nachricht grösser als [MAX_MESSAGE_BYTES] (UTF-8)? Jede UTF-16-Einheit zählt 1 bis 3 Byte. */
    fun isMessageTooLarge(text: String): Boolean = when {
        text.length > MAX_MESSAGE_BYTES -> true
        text.length.toLong() * 3 <= MAX_MESSAGE_BYTES -> false
        else -> text.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES
    }

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

    /**
     * Zeit laut Börse (`exchangeTime`), die ein Live-Kurs hinterlässt: die belegte Handelszeit des Stroms
     * ([LiveQuote.tradeTime]); ohne sie bei gleichem Kurs die bisherige (null bleibt null), bei anderem Kurs als
     * dem gespeicherten null — gehandelt wurde dann irgendwann seit dem letzten Abruf, die alte Zeit wäre falsch.
     * Die Takt- bzw. Abrufzeit ist nie Börsenzeit (sie steckt in `lastUpdate`).
     */
    fun exchangeTime(quote: LiveQuote, storedPrice: Double?, storedExchangeTime: Long?): Long? =
        quote.tradeTime?.takeIf { it > 0 } ?: if (storedPrice == quote.price) storedExchangeTime else null

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
            // Neue Zeit nur mit geändertem Kurs: Ein Delta ohne lastPrice (z. B. Bybit, nur 24-h-Wert
            // oder Volumen geändert) behält den alten Kurs — und damit dessen Zeit. Ebenso ein Takt
            // mit gleichem Kurs (Binance schickt «c» jede Sekunde mit neuer Ereigniszeit «E»).
            // Sonst wirkte ein stehender Kurs frisch und «veraltet» käme nie.
            // Handelszeit nur mit einem im Strom gesehenen Kurswechsel; der erste Takt (previous null) belegt keinen
            // Handel — sonst sähe ein seit Stunden stehendes Buch frisch gehandelt aus («Unverändert seit» fiele weg)
            val time: Long
            val tradeTime: Long?
            if (previous != null && (tick.price == null || tick.price == previous.price)) {
                time = previous.time
                tradeTime = previous.tradeTime
            } else {
                time = LiveRules.tickTime(tick.time, now)
                tradeTime = if (previous == null) null else time
            }
            val quote = LiveQuote(
                price = price,
                change24h = tick.change24h ?: previous?.change24h,
                time = time,
                tradeTime = tradeTime,
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

    /**
     * Stand dieser Paare vergessen («Paar bearbeiten»: gleiche Id, anderes Paar). Sonst bliebe der
     * Kurs des alten Paars stehen — und mit ihm, als Ausweich-Wert eines Deltas ohne 24-h-Feld in
     * [offer], dessen 24-h-Veränderung.
     */
    fun forget(ids: Collection<Long>) {
        for (id in ids) {
            if (latest.remove(id) != null) uiDirty = true
            unsaved.remove(id)
        }
    }

    fun clear() {
        if (latest.isNotEmpty()) uiDirty = true
        latest.clear()
        unsaved.clear()
    }

    val size: Int get() = latest.size
}
