package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.Exchange
import com.cryptochecker.marketdata.model.ExchangeCategory
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.EcbRates

/**
 * Devisen als Referenzkurs: die täglich festgelegten Kurse der Europäischen Zentralbank, direkt von der EZB
 * ([EcbRates]), ohne Schlüssel — kein Handel, nicht live. Darum Abschnitt «Forex (Referenzkurs)»
 * ([ExchangeCategory.FOREX]), Label «Referenzkurs, täglich» ([priceIsDailyReference]) und keine Alarme ([allowsAlarms]).
 *
 * Paare: alle geordneten Paare der Währungen des jüngsten Tags in `eurofxref-daily.xml` samt EUR, Kennung «EURUSD».
 * Kurs = jüngster Tageswert aus `eurofxref-hist-90d.xml` innerhalb von [WINDOW_DAYS] Tagen vor dem jüngsten EZB-Tag,
 * Veränderung gegen den vorigen EZB-Tag (Wochenenden und Feiertage haben keinen); andere Basis als EUR als Kreuzkurs
 * ([EcbRates.cross]). Keine Zeit der Börse: ein Referenzkurs ist nie «veraltet» wie ein stehender Handel. Eine
 * Sammelabfrage für alle Paare. iOS: `EcbReference.swift`.
 */
class EcbReference : Exchange("ECB", "E C B") {
    override val key = KEY
    override val category = ExchangeCategory.FOREX
    override val allowsAlarms: Boolean get() = false
    override val priceIsDailyReference: Boolean get() = true

    // ── Einzelkurs ──

    override fun tickerAddress(call: Int, info: PairQuery): String {
        info.pairId?.takeIf(PAIR_ID::matches) ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        return EcbRates.HIST_90D_URL
    }

    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        val pairId = info.pairId?.takeIf(PAIR_ID::matches) ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        val window = EcbRates.parseXml(body)?.let { EcbRates.recent(it, WINDOW_DAYS) }.orEmpty()
        if (!copySeries(window, pairId.take(3), pairId.drop(3), ticker)) {
            throw ExchangeParseException(ExchangeParseException.NO_DATA)
        }
    }

    /** Die Dateien der EZB kennen keinen Fehlertext. */
    override fun errorMessage(call: Int, body: String, info: PairQuery): String? = noErrorText()

    // ── Sammelabfrage: eine Datei für alle Paare ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(call: Int): String? = EcbRates.HIST_90D_URL.takeIf { call == 0 }

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val window = EcbRates.parseXml(body)?.let { EcbRates.recent(it, WINDOW_DAYS) }
            ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        val codes = window.flatMap { EcbRates.codes(it) }.distinct().sorted()
        for (base in codes) for (quote in codes) {
            if (base != quote) bulkRow("$base$quote", tickers, failed) { copySeries(window, base, quote, it) }
        }
    }

    // ── Paarliste ──

    override fun pairListAddress(call: Int): String = EcbRates.DAILY_URL

    /** Jüngster Tag der EZB: alle geordneten Paare zweier verschiedener Codes (EUR eingeschlossen). */
    override fun fillPairList(call: Int, body: String, out: MutableList<ExchangePair>) {
        val days = EcbRates.parseXml(body) ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        val codes = EcbRates.codes(days.getValue(days.keys.max()))
        for (base in codes) for (quote in codes) if (base != quote) out += ExchangePair(base, quote, "$base$quote")
    }

    companion object {
        const val KEY = "EcbReference"

        /** Zeitfenster vor dem jüngsten EZB-Tag: genug für den vorigen EZB-Tag auch über Ostern oder Weihnachten. */
        const val WINDOW_DAYS = 10L

        private val PAIR_ID = Regex("[A-Z]{6}")

        /**
         * Kurs und Veränderung von [base] → [quote] aus den Tagen [window] (aufsteigend): jüngster Tag mit Wert,
         * Veränderung gegen den vorigen Tag mit Wert. false ohne Wert. Wie iOS `read`.
         */
        internal fun copySeries(window: List<Map<String, Double>>, base: String, quote: String, ticker: Ticker): Boolean {
            val values = window.mapNotNull { EcbRates.cross(it, base, quote) }
            val last = values.lastOrNull() ?: return false
            ticker.last = last
            ticker.change24hPercent = values.getOrNull(values.size - 2)?.let { Change24h.fromOpen(last, it) }
            return true
        }
    }
}
