package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.Exchange
import com.cryptochecker.marketdata.model.ExchangeCategory
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.Change24h
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset

private const val FRANKFURTER_API = "https://api.frankfurter.dev/v1"

/**
 * Devisen als Referenzkurs: die täglich festgelegten Kurse der Europäischen Zentralbank über Frankfurter,
 * ohne Schlüssel — kein Handel, nicht live. Darum Abschnitt «Forex (Referenzkurs)» ([ExchangeCategory.FOREX]), Label
 * «Referenzkurs, täglich» ([priceIsDailyReference]) und keine Alarme ([allowsAlarms]).
 *
 * Paare: alle geordneten Paare der Währungen aus `/currencies`, Kennung «EURUSD». Kurs = jüngster Tageswert der
 * Zeitreihe der letzten [WINDOW_DAYS] Tage, Veränderung gegen den vorigen EZB-Tag (Wochenenden und Feiertage
 * haben keinen). Keine Zeit der Börse: ein Referenzkurs ist nie «veraltet» wie ein stehender Handel.
 * Sammelabfrage je Basiswährung. iOS: `EcbReference.swift`.
 */
class EcbReference : Exchange("ECB (Frankfurter)", "E C B") {
    override val key = KEY
    override val category = ExchangeCategory.FOREX
    override val allowsAlarms: Boolean get() = false
    override val priceIsDailyReference: Boolean get() = true

    // ── Einzelkurs ──

    override fun tickerAddress(call: Int, info: PairQuery): String {
        val pairId = info.pairId?.takeIf(PAIR_ID::matches) ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        return seriesUrl(pairId.take(3), listOf(pairId.drop(3)), today())
    }

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val quote = info.pairId?.takeIf(PAIR_ID::matches)?.drop(3)
            ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        if (!copySeries(json, quote, ticker)) throw ExchangeParseException(ExchangeParseException.NO_DATA)
    }

    /** Fehlertext von Frankfurter: {"message": "not found"}. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? =
        json.optString("message").takeIf { it.isNotEmpty() } ?: noErrorText()

    // ── Sammelabfrage: je Basiswährung eine Zeitreihe ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(call: Int, pairIds: Collection<String>): String? =
        bulkGroups(pairIds).getOrNull(call)?.let { (base, quotes) -> seriesUrl(base, quotes, today()) }

    override fun bulkTickersRequestCount(pairIds: Collection<String>): Int = bulkGroups(pairIds).size

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val json = JSONObject(body)
        val base = json.getString("base")
        val quotes = sortedSetOf<String>()
        val rates = json.getJSONObject("rates")
        rates.keys().forEach { day -> rates.optJSONObject(day)?.keys()?.forEach { quotes.add(it) } }
        for (quote in quotes) bulkRow("$base$quote", tickers, failed) { copySeries(json, quote, it) }
    }

    // ── Paarliste ──

    override fun pairListAddress(call: Int): String = "$FRANKFURTER_API/currencies"

    /** {"AUD": "Australian Dollar", …}: alle geordneten Paare zweier verschiedener Codes. */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        val codes = json.keys().asSequence().filter(CODE::matches).toSortedSet()
        for (base in codes) for (quote in codes) if (base != quote) out += ExchangePair(base, quote, "$base$quote")
    }

    private fun today(): LocalDate = LocalDate.now(ZoneOffset.UTC)

    companion object {
        const val KEY = "EcbReference"

        /** Zeitfenster der Zeitreihe: genug für den vorigen EZB-Tag auch über Ostern oder Weihnachten. */
        const val WINDOW_DAYS = 10L

        private val CODE = Regex("[A-Z]{3}")
        private val PAIR_ID = Regex("[A-Z]{6}")

        /** Zeitreihe ab [today] − [WINDOW_DAYS]: `/{start}..?base=B&symbols=Q1,Q2`. Wie iOS `seriesURL`. */
        fun seriesUrl(base: String, quotes: List<String>, today: LocalDate): String =
            "$FRANKFURTER_API/${today.minusDays(WINDOW_DAYS)}..?base=$base&symbols=${quotes.joinToString(",")}"

        /** Gültige Kennungen je Basis (sortiert), Gegenwährungen sortiert ohne Doppelte. Wie iOS `bulkGroups`. */
        fun bulkGroups(pairIds: Collection<String>): List<Pair<String, List<String>>> =
            pairIds.map(String::trim).filter(PAIR_ID::matches)
                .groupBy({ it.take(3) }, { it.drop(3) })
                .toSortedMap()
                .map { (base, quotes) -> base to quotes.distinct().sorted() }

        /**
         * Kurs und Veränderung von [quote] aus {"rates": {"2026-10-23": {"USD": 1.12}, …}}: jüngster Tag mit Wert,
         * Veränderung gegen den vorigen Tag mit Wert. false ohne Wert.
         */
        internal fun copySeries(json: JSONObject, quote: String, ticker: Ticker): Boolean {
            val rates = json.optJSONObject("rates") ?: return false
            val values = rates.keys().asSequence().sorted()
                .mapNotNull { day -> rates.optJSONObject(day)?.optDouble(quote)?.takeIf { it.isFinite() && it > 0.0 } }
                .toList()
            val last = values.lastOrNull() ?: return false
            ticker.last = last
            ticker.change24hPercent = values.getOrNull(values.size - 2)?.let { Change24h.fromOpen(last, it) }
            return true
        }
    }
}
