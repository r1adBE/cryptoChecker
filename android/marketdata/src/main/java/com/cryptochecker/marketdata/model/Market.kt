package com.cryptochecker.marketdata.model

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.util.BulkTickerParse
import com.cryptochecker.marketdata.util.BulkTickerRows
import com.cryptochecker.marketdata.util.TimeUtils
import org.json.JSONObject

/** Fest eingebaute Paare einer Börse ohne abrufbare Paarliste: Basis → mögliche Gegenwährungen. */
typealias CurrencyPairsMap = LinkedHashMap<String, Array<String>>

/**
 * Grundgerüst einer Börse: WIE mit ihr gesprochen wird — Adressen, POST-Inhalte, das Lesen der Antworten und
 * ihrer Fehlertexte. Netz, Speicher, Wiederholungen und Oberfläche gehören nicht hierher (MarketRemoteDataSource).
 *
 * Aufbau als Schablone: Die festen Einstiege `read…`/`parse…Main` erledigen das Gemeinsame (Zeitstempel, leere
 * Paare, Zeilenfehler) und rufen die überschreibbaren Teile `fill…`/`parseBulkTickers`. Eine Unterklasse
 * überschreibt nur, was bei ihr anders ist. Die Abschnitte folgen dem Ablauf: Einzelkurs · Sammelabfrage ·
 * Paarliste · Namen. Die übliche Börse mit einer Paarliste und einer Kursadresse erbt von
 * [com.cryptochecker.marketdata.model.market.generic.SimpleMarket]. Gegenstück auf iOS: `Market.swift`.
 *
 * Instanzen werden von gleichzeitigen Abfragen geteilt. [key] ist der Klassenname und steht so in der Datenbank —
 * nie umbenennen.
 */
abstract class Market(
    val name: String,
    val ttsName: String,
    val currencyPairs: CurrencyPairsMap? = null,
) {

    val key: String = javaClass.simpleName

    /**
     * true: Der gelieferte «Kurs» ist ein Mittelkurs (aus Geld/Brief oder Mark) statt des letzten Handels. Das
     * Aktionsblatt beschriftet ihn dann als «Mittelkurs»; eine Zeit der Börse gibt es dazu nicht, «Aktualisiert»
     * ist der Abruf. iOS: `priceIsMid`.
     */
    open val priceIsMid: Boolean get() = false

    /**
     * true: Die 24-h-Veränderung bezieht sich auf den Mark-Preis (Hyperliquid `markPx` gegen `prevDayPx`), nicht
     * auf den angezeigten Kurs. Zeile und Aktionsblatt schreiben dann «Mark» dazu (`ChangeBasisMath.showsMark`).
     * iOS: `changeIsMark`.
     */
    open val changeIsMark: Boolean get() = false

    // ── Einzelkurs ───────────────────────────────────────────────────────────────

    /** Wie viele Anfragen ein Kurs braucht; Nummer `call` läuft von 0 bis Anzahl − 1. */
    open fun tickerCallCount(info: PairQuery?): Int = 1

    abstract fun tickerAddress(call: Int, info: PairQuery): String

    /** Inhalt für POST; null heisst: einfache GET-Anfrage. */
    open fun tickerPostBody(call: Int, info: PairQuery): PostRequestInfo? = null

    /**
     * Liest eine Kursantwort in [ticker] und gibt ihn zurück. Danach gilt: [Ticker.timestamp] ist der Moment des
     * Abrufs, [Ticker.exchangeTime] die Zeit laut Börse (oder null).
     *
     * Warum der Abruf zählt: «veraltet» (OutdatedRule, lastUpdate) fragt, ob die Börse das Paar noch führt — und
     * die Antwort kam eben jetzt. Ein ruhiges Paar mit altem letzten Handel (Upbit `trade_timestamp`, LATOKEN
     * `updateTimestamp`) ist darum nicht veraltet. Scheitert der Abruf, bleibt lastUpdate stehen und das Paar
     * altert wie gewohnt; ein gestrichenes Paar fehlt in der Liste (NotTraded). Die Börsenzeit dient nur der
     * Anzeige (PriceFreshness), damit ein stehender Kurs nicht frisch aussieht. Wie `Market.swift`.
     */
    @Throws(Exception::class)
    fun readTicker(call: Int, body: String, ticker: Ticker, info: PairQuery): Ticker {
        fillTicker(call, body, ticker, info)
        markFetched(ticker)
        return ticker
    }

    /** Standard: Antwort als JSON-Objekt an [fillTickerJson]. Börsen mit Listen-Antwort überschreiben dies. */
    protected open fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        fillTickerJson(call, JSONObject(body), ticker, info)
    }

    /** Liest die Felder einer Objekt-Antwort; ohne Überschreibung bleibt der Ticker leer (→ «kein Kurs»). */
    protected open fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) = Unit

    /**
     * Fehlertext der Börse zu einer Kursantwort, die sich nicht lesen liess. Wirft, wenn die Antwort keinen enthält;
     * der Aufrufer meldet dann den ursprünglichen Lesefehler.
     */
    @Throws(Exception::class)
    fun readError(call: Int, body: String, info: PairQuery): String? = errorMessage(call, body, info)

    /** Standard: Antwort als JSON-Objekt an [errorMessageJson]. */
    protected open fun errorMessage(call: Int, body: String, info: PairQuery): String? =
        errorMessageJson(call, JSONObject(body), info)

    /** Ohne eigene Regel kennt die Börse keinen Fehlertext. */
    protected open fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? = noErrorText()

    /** Für [errorMessage]/[errorMessageJson]: Die Antwort enthält keinen Fehlertext der Börse. */
    protected fun noErrorText(): Nothing = throw MarketParseException(NO_ERROR_TEXT)

    /**
     * Die Leser schreiben eine allfällige Börsenzeit (s, ms, µs oder ns) in [Ticker.timestamp]. Hier wird sie in
     * ms nach [Ticker.exchangeTime] verschoben (≤ 0 = keine) und [Ticker.timestamp] auf jetzt gesetzt.
     */
    private fun markFetched(ticker: Ticker) {
        val reported = ticker.timestamp
        ticker.exchangeTime = reported.takeIf { it > 0 }?.let(TimeUtils::parseTimeToMillis)
        ticker.timestamp = System.currentTimeMillis()
    }

    // ── Sammelabfrage aller Kurse (freiwillig) ───────────────────────────────────

    /** Anfragen einer Sammelabfrage; 0 = gibt es nicht, die Paare werden einzeln abgefragt. */
    open val bulkTickersNumOfRequests: Int get() = 0

    /**
     * true: Die ungefilterte Sammelabfrage führt jedes handelbare Paar. Fehlt ein Paar, ist es nicht mehr im
     * Handel; eine Einzelabfrage würde ebenso scheitern und nur Zeit kosten.
     */
    open val bulkTickersComplete: Boolean get() = false

    /** Ungefilterte Adresse der Teilanfrage [call]; null = diese Teilanfrage gibt es ungefiltert nicht. */
    open fun getBulkTickersUrl(call: Int): String? = null

    /**
     * Gefilterte Sammelabfrage nur für [pairIds] — einige Kilobyte statt mehrerer Megabyte, wo die Börse eine
     * Auswahl erlaubt. Standard: dieselbe Adresse wie ungefiltert.
     */
    open fun getBulkTickersUrl(call: Int, pairIds: Collection<String>): String? = getBulkTickersUrl(call)

    /**
     * Anfragen für die gefilterte Abfrage dieser Paare. Mehr als [bulkTickersNumOfRequests], wenn die Kennungen
     * auf mehrere Adressen verteilt werden ([com.cryptochecker.marketdata.util.BulkPairChunks]); ungefiltert ist
     * dann jede Teilanfrage gleich.
     */
    open fun bulkTickersRequestCount(pairIds: Collection<String>): Int = bulkTickersNumOfRequests

    /** Inhalt für POST; null heisst GET. */
    open fun getBulkTickersPostRequestInfo(call: Int): PostRequestInfo? = null

    /**
     * Gruppe einer Teilanfrage (Binance: Spot 0, Alpha 1). Scheitert eine, verliert nur ihre Gruppe
     * ([bulkTickersPairGroup]) die Aussage «nicht mehr gehandelt»; die übrigen Paare bleiben vollständig.
     * Standard: alles eine Gruppe.
     */
    open fun bulkTickersRequestGroup(call: Int): Int = 0

    /** Gruppe eines Paars, passend zu [bulkTickersRequestGroup]. */
    open fun bulkTickersPairGroup(pairId: String): Int = 0

    /**
     * Für Börsen ohne ungefilterte Sammelabfrage, die eine Auswahl mit einem unbekannten Paar als Ganzes
     * ablehnen, ohne es zu nennen (Bithumb «Code not found»): Adresse der Paarliste ([pairListAddress], GET).
     * Damit werden die unbekannten Paare gefunden, aus der Anfrage genommen und als nicht gehandelt gemeldet
     * (NotTraded, BulkDelisted). null = trifft nicht zu.
     */
    open fun getBulkKnownPairsUrl(call: Int): String? = null

    /**
     * Alle Kurse einer Sammelantwort, geschlüsselt nach [MarketPair.pairId] — derselben Kennung wie
     * beim Abgleich der Paarliste, sonst liessen sie sich keinem beobachteten Paar zuordnen.
     */
    @Throws(Exception::class)
    fun parseBulkTickersMain(call: Int, body: String): Map<String, Ticker> =
        parseBulkTickersDetailed(call, body).tickers

    /**
     * Wie [parseBulkTickersMain], zusätzlich die Paare mit unlesbarer Zeile ([BulkTickerParse.failed]). Überwiegen
     * die unlesbaren Zeilen, wirft es [MarketParseException] mit [BulkTickerRows.FORMAT_ERROR].
     */
    @Throws(Exception::class)
    fun parseBulkTickersDetailed(call: Int, body: String): BulkTickerParse {
        val good = LinkedHashMap<String, Ticker>()
        val broken = LinkedHashSet<String>()
        parseBulkTickers(call, body, good, broken)
        // Ein Paar, das doch noch gelesen wurde, gilt nicht als Fehler
        broken -= good.keys

        if (BulkTickerRows.isFormatFailure(good.size, broken.size)) {
            throw MarketParseException(BulkTickerRows.formatErrorText(good.size, broken.size))
        }
        good.values.forEach(::markFetched)
        return BulkTickerParse(good, broken)
    }

    /**
     * Liest eine Sammelantwort: lesbare Zeilen nach [tickers], vorhandene, aber unlesbare nach [failed] — am
     * einfachsten über [bulkRowIfActive] bzw. [bulkRow]. Standard: keine Sammelabfrage.
     */
    protected open fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) = Unit

    /**
     * Die übliche Zeile einer Sammelantwort: ohne Kennung oder mit leerem Kurs in [priceField] (ruhendes Paar,
     * [BulkTickerRows.inactivePrice]) still übergangen, sonst über [bulkRow] gelesen.
     */
    protected fun bulkRowIfActive(
        pairId: String,
        row: JSONObject,
        priceField: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
        read: (Ticker) -> Unit,
    ) {
        if (pairId.isEmpty() || BulkTickerRows.inactivePrice(row, priceField)) return
        bulkRow(pairId, tickers, failed, read)
    }

    /**
     * Liest eine Zeile der Sammelantwort mit [read] in einen frischen Ticker. Wirft [read] (Feld fehlt oder heisst
     * anders) oder fehlt ein brauchbarer letzter Kurs ([BulkTickerRows.usable]), landet [pairId] in [failed] —
     * das Paar zeigt dann einen Fehler, statt still «unverändert» zu bleiben. Darum wird jede Ausnahme hier
     * bewusst in diesen Eintrag übersetzt und nicht weitergeworfen.
     */
    protected fun bulkRow(
        pairId: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
        read: (Ticker) -> Unit,
    ) {
        val row = SimpleTicker()
        val readable = try {
            read(row)
            BulkTickerRows.usable(row)
        } catch (_: Exception) {
            false
        }
        if (readable) {
            tickers[pairId] = row
        } else {
            failed.add(pairId)
        }
    }

    // ── Paarliste ────────────────────────────────────────────────────────────────

    open val pairListCallCount: Int get() = 1

    /**
     * true: Erst alle Antworten der Paarliste sammeln, dann gemeinsam in [fillPairListCombined] lesen — für
     * Börsen, deren Liste nur Kennungen enthält, die eine zweite Liste auflöst (LATOKEN: Währungs-ID → Kürzel).
     * Eine gescheiterte Anfrage lässt dann den ganzen Abgleich scheitern.
     */
    open val pairListCombined: Boolean get() = false

    /**
     * true: Bei einzeln gelesenen Anfragen muss jede gelingen (nicht nur die erste) — für Börsen, deren
     * Anfragen je eine eigene Hälfte des Angebots liefern (Binance Futures: USDⓈ-M und COIN-M). Sonst
     * würde eine halbe Liste gespeichert und die andere Hälfte verschwände bis zum nächsten Abgleich;
     * so bleibt die bisherige Liste stehen. iOS: `pairListAllCallsRequired`.
     */
    open val pairListAllCallsRequired: Boolean get() = false

    /** Adresse der Paarliste für Anfrage [call]; null = die Börse hat keine (feste Paare oder Suche). */
    open fun pairListAddress(call: Int): String? = null

    /** Inhalt für POST; null heisst GET. */
    open fun pairListPostBody(call: Int): PostRequestInfo? = null

    /**
     * Für Börsen, die ihre Paarliste über einen Cursor blättern (Bybit `nextPageCursor`): Adresse der Folgeseite
     * nach [body], null auf der letzten Seite. Sie wird per GET geholt und mit derselben Nummer [call] gelesen,
     * höchstens [MAX_PAIR_LIST_PAGES] Seiten je Anfrage. Eine unlesbare Antwort wirft (nie still aufhören).
     * iOS: `pairListNextPage(call:body:)`.
     */
    open fun pairListNextPage(call: Int, body: String): String? = null

    /** Liest eine Antwort der Paarliste nach [out] und wirft Paare ohne Basis oder Gegenwährung wieder hinaus. */
    @Throws(Exception::class)
    fun readPairList(call: Int, body: String, out: MutableList<MarketPair>) {
        fillPairList(call, body, out)
        dropIncomplete(out)
    }

    /** Wie [readPairList] für Börsen mit [pairListCombined], über alle gesammelten Antworten. */
    @Throws(Exception::class)
    fun readPairListCombined(bodies: List<String>, out: MutableList<MarketPair>) {
        fillPairListCombined(bodies, out)
        dropIncomplete(out)
    }

    /** Standard: Antwort als JSON-Objekt an [fillPairListJson]. Börsen mit Listen-Antwort überschreiben dies. */
    protected open fun fillPairList(call: Int, body: String, out: MutableList<MarketPair>) {
        fillPairListJson(call, JSONObject(body), out)
    }

    /** Liest die Paare einer Objekt-Antwort; ohne Überschreibung keine. */
    protected open fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) = Unit

    /** Nur für Börsen mit [pairListCombined]. */
    protected open fun fillPairListCombined(bodies: List<String>, out: MutableList<MarketPair>) = Unit

    // Reines Kotlin (kein TextUtils), damit die Vertragstests der Paarlisten auf der JVM laufen
    private fun dropIncomplete(out: MutableList<MarketPair>) {
        out.removeAll { pair -> pair.base.isEmpty() || pair.quote.isEmpty() }
    }

    // ── Volle Coin-Namen (Identität «gleicher Token», K-8) ───────────────────────

    /**
     * Kürzel → voller Name (z. B. Gate `base_name`), aus derselben Antwort wie die Paarliste, ohne weitere
     * Anfrage. Standard: keine Namen. iOS: `parseBaseNames`.
     */
    @Throws(Exception::class)
    open fun parseBaseNames(call: Int, body: String): Map<String, String> = emptyMap()

    /** Gegenstück zu [parseBaseNames] für Börsen mit [pairListCombined]. */
    @Throws(Exception::class)
    open fun parseBaseNamesCombined(bodies: List<String>): Map<String, String> = emptyMap()

    companion object {
        /** Höchstzahl Seiten je Paarlisten-Anfrage, falls ein Cursor nie endet. */
        const val MAX_PAIR_LIST_PAGES = 10

        /** Grund von [noErrorText]; erreicht nie die Oberfläche (dort steht dann der Lesefehler). */
        const val NO_ERROR_TEXT = "No error text"
    }
}
