package com.cryptochecker.marketdata.model

import android.text.TextUtils
import com.cryptochecker.marketdata.model.currency.CurrencyPairsMap
import com.cryptochecker.marketdata.util.TimeUtils
import org.json.JSONObject

abstract class Market(
    val name: String,
    val ttsName: String,
    val currencyPairs: CurrencyPairsMap? = null
) {

	val key: String = this.javaClass.simpleName

    open val cautionResId: Int
        get() = 0

    // ====================
    // Parse Ticker
    // ====================
    open fun getNumOfRequests(checkerInfo: CheckerInfo?): Int {
        return 1
    }

    abstract fun getUrl(requestId: Int, checkerInfo: CheckerInfo): String

    // When the body is set, the HTTP POST request is used
    // By default is HTTP GET
    open fun getPostRequestInfo(requestId: Int, checkerInfo: CheckerInfo): PostRequestInfo? {
        return null
    }

    @Throws(Exception::class)
    fun parseTickerMain(requestId: Int, responseString: String, ticker: Ticker, checkerInfo: CheckerInfo): Ticker {
        parseTicker(requestId, responseString, ticker, checkerInfo)

        if (ticker.timestamp <= 0)
            ticker.timestamp = System.currentTimeMillis()
        else
            ticker.timestamp = TimeUtils.parseTimeToMillis(ticker.timestamp)

        return ticker
    }

    @Throws(Exception::class)
    protected open fun parseTicker(requestId: Int, responseString: String, ticker: Ticker, checkerInfo: CheckerInfo) {
        parseTickerFromJsonObject(requestId, JSONObject(responseString), ticker, checkerInfo)
    }

    @Throws(Exception::class)
    protected open fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        // do parsing
    }

    // ====================
    // Parse Ticker Error
    // ====================
    @Throws(Exception::class)
    fun parseErrorMain(requestId: Int, responseString: String, checkerInfo: CheckerInfo): String? {
        return parseError(requestId, responseString, checkerInfo)
    }

    @Throws(Exception::class)
    protected open fun parseError(requestId: Int, responseString: String, checkerInfo: CheckerInfo): String? {
        return parseErrorFromJsonObject(requestId, JSONObject(responseString), checkerInfo)
    }

    @Throws(Exception::class)
    protected open fun parseErrorFromJsonObject(requestId: Int, jsonObject: JSONObject, checkerInfo: CheckerInfo): String? {
        throw Exception()
    }

    // ====================
    // Massenabfrage aller Ticker (optional)
    // ====================

    /**
     * Zahl der Anfragen für eine Massenabfrage. 0 bedeutet: Die Börse
     * unterstützt das nicht, es wird weiter Paar für Paar abgefragt.
     */
    open val bulkTickersNumOfRequests: Int
        get() = 0

    open fun getBulkTickersUrl(requestId: Int): String? = null

    /**
     * true: Die (ungefilterte) Massenabfrage enthält alle handelbaren Paare.
     * Fehlt ein Paar darin, wird es nicht mehr gehandelt — eine Einzelabfrage
     * würde nur Zeit kosten und ebenfalls scheitern.
     */
    open val bulkTickersComplete: Boolean
        get() = false

    /**
     * Wie [getBulkTickersUrl], aber nur für die angegebenen Paar-Kennungen.
     * Börsen, die eine Auswahl unterstützen, liefern so wenige Kilobyte statt
     * mehrerer Megabyte. Standard: ungefilterte Massenabfrage.
     */
    open fun getBulkTickersUrl(requestId: Int, pairIds: Collection<String>): String? =
        getBulkTickersUrl(requestId)

    /**
     * Zahl der Anfragen für die gefilterte Massenabfrage dieser Paare — mehr als
     * [bulkTickersNumOfRequests], wenn die Liste auf mehrere URLs verteilt wird
     * (siehe [com.cryptochecker.marketdata.util.BulkPairChunks]). Die ungefilterte
     * Abfrage ([getBulkTickersUrl] ohne Paare) ist dann für jede Teilanfrage dieselbe.
     */
    open fun bulkTickersRequestCount(pairIds: Collection<String>): Int = bulkTickersNumOfRequests

    open fun getBulkTickersPostRequestInfo(requestId: Int): PostRequestInfo? = null

    /**
     * Liest alle Ticker einer Antwort. Schlüssel ist die Paar-Kennung, also
     * dasselbe [CurrencyPairInfo.currencyPairId], das beim Synchronisieren der
     * Handelspaare vergeben wurde — nur so lässt sich das Ergebnis später
     * einem beobachteten Paar zuordnen.
     */
    @Throws(Exception::class)
    fun parseBulkTickersMain(requestId: Int, responseString: String): Map<String, Ticker> {
        val tickers = LinkedHashMap<String, Ticker>()
        parseBulkTickers(requestId, responseString, tickers)

        for (ticker in tickers.values) {
            if (ticker.timestamp <= 0) ticker.timestamp = System.currentTimeMillis()
            else ticker.timestamp = TimeUtils.parseTimeToMillis(ticker.timestamp)
        }

        return tickers
    }

    @Throws(Exception::class)
    protected open fun parseBulkTickers(
        requestId: Int,
        responseString: String,
        tickers: MutableMap<String, Ticker>,
    ) {
        // Standard: keine Massenabfrage
    }

    // ====================
    // Parse currency pairs
    // ====================
    open val currencyPairsNumOfRequests: Int
        get() = 1

    open fun getCurrencyPairsUrl(requestId: Int): String? {
        return null
    }

    // If body defined than used HTTP POST request
    open fun getCurrencyPairsPostRequestInfo(requestId: Int): PostRequestInfo? {
        return null
    }

    @Throws(Exception::class)
    fun parseCurrencyPairsMain(requestId: Int, responseString: String, pairs: MutableList<CurrencyPairInfo>) {
        parseCurrencyPairs(requestId, responseString, pairs)
        for (i in pairs.indices.reversed()) {
            val currencyPairInfo = pairs[i]
            if (TextUtils.isEmpty(currencyPairInfo.currencyBase) || TextUtils.isEmpty(currencyPairInfo.currencyCounter)) pairs.removeAt(i)
        }
    }

    /**
     * true: Die Antworten aller Paar-Anfragen werden erst gesammelt und dann
     * gemeinsam in [parseCurrencyPairsCombined] ausgewertet — für Börsen, deren
     * Paarliste nur Kennungen enthält, die eine zweite Liste auflöst
     * (z. B. LATOKEN: Währungs-IDs → Kürzel). Scheitert eine Anfrage, scheitert
     * die ganze Synchronisierung.
     */
    open val currencyPairsCombined: Boolean
        get() = false

    @Throws(Exception::class)
    fun parseCurrencyPairsCombinedMain(responses: List<String>, pairs: MutableList<CurrencyPairInfo>) {
        parseCurrencyPairsCombined(responses, pairs)
        pairs.removeAll { TextUtils.isEmpty(it.currencyBase) || TextUtils.isEmpty(it.currencyCounter) }
    }

    @Throws(Exception::class)
    protected open fun parseCurrencyPairsCombined(responses: List<String>, pairs: MutableList<CurrencyPairInfo>) {
        // Standard: nicht verwendet
    }

    @Throws(Exception::class)
    protected open fun parseCurrencyPairs(requestId: Int, responseString: String, pairs: MutableList<CurrencyPairInfo>) {
        parseCurrencyPairsFromJsonObject(requestId, JSONObject(responseString), pairs)
    }

    @Throws(Exception::class)
    protected open fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        // do parsing
    }
}