package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.Exchange
import com.cryptochecker.marketdata.model.ExchangeCategory
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.MultiResponseSource
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.ReferenceSource
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.EcbRates
import com.cryptochecker.marketdata.util.ReferenceRates

/**
 * Gemeinsamer Teil der Referenzkurse von Zentralbanken neben der EZB ([NbuReference], [NbpReference]): Abschnitt
 * «Referenzkurs» unter «Devisen» ([ExchangeCategory.FOREX]), keine Alarme, kein Handel. Paare = die Währungen, die
 * diese Quelle liefert und keine vorrangige ([ReferenceRates.assign]: EZB > NBU > NBP), je gegen USD und EUR, Kennung
 * «AEDUSD». Einzel- und Sammelkurs lesen dieselben verbundenen Antworten ([MultiResponseSource]): eine Abfrage je
 * Quelle und Aktualisierung, behalten bis zur nächsten Veröffentlichung. Keine Zeit der Börse — ein Referenzkurs ist
 * nie «veraltet» wie ein stehender Handel. iOS: `BankReference.swift`.
 */
abstract class BankReference(name: String, ttsName: String) : Exchange(name, ttsName), MultiResponseSource {
    /** Welche Zentralbank; bestimmt Heimwährung, Takt und Quellzeile. */
    abstract val source: ReferenceSource

    override val category = ExchangeCategory.FOREX
    override val allowsAlarms: Boolean get() = false
    override val priceIsDailyReference: Boolean get() = !source.weekly

    /**
     * Veröffentlichungen aus den Antworten [bodies] von [responseAddresses], aufsteigend (die jüngste zuletzt), je
     * Code → Kurs in der Heimwährung; null, wenn unlesbar.
     */
    protected abstract fun publications(bodies: List<String>): List<Map<String, Double>>?

    /** Adressen der Paarliste: zuerst die EZB (vorrangige Quelle), dann die Listen dieser und der übrigen Quellen. */
    protected abstract val pairListAddresses: List<String>

    /** Code-Mengen von EZB, NBU und NBP aus den Antworten [bodies] von [pairListAddresses]. */
    protected abstract fun listedCodes(bodies: List<String>): Triple<Set<String>, Set<String>, Set<String>>

    // ── Einzelkurs (dieselben Antworten wie die Sammelabfrage) ──

    override fun tickerAddress(call: Int, info: PairQuery): String {
        info.pairId?.takeIf(PAIR_ID::matches) ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        return responseAddresses(System.currentTimeMillis()).first()
    }

    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        val pairId = info.pairId?.takeIf(PAIR_ID::matches) ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        val published = publications(MultiResponseSource.split(body)).orEmpty()
        if (!ReferenceRates.copy(published, pairId.take(3), pairId.drop(3), ticker)) {
            throw ExchangeParseException(ExchangeParseException.NO_DATA)
        }
    }

    /** Die Zentralbanken liefern keinen Fehlertext. */
    override fun errorMessage(call: Int, body: String, info: PairQuery): String? = noErrorText()

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(call: Int): String? =
        responseAddresses(System.currentTimeMillis()).first().takeIf { call == 0 }

    /** Alle Währungen der jüngsten Veröffentlichung (ohne [ReferenceRates.EXCLUDED]) gegen USD und EUR. */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val published = publications(MultiResponseSource.split(body))?.takeIf { it.isNotEmpty() }
            ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        val codes = published.last().keys.filter { ReferenceRates.isCode(it) && it !in ReferenceRates.EXCLUDED }
        for (base in codes.sorted()) for (quote in ReferenceRates.QUOTES) {
            if (base == quote) continue
            bulkRow("$base$quote", tickers, failed) {
                if (!ReferenceRates.copy(published, base, quote, it)) throw ExchangeParseException(ExchangeParseException.NO_DATA)
            }
        }
    }

    // ── Paarliste: alle Listen zusammen, damit jede Währung nur aus einer Quelle kommt ──

    override val pairListCombined: Boolean get() = true

    override val pairListCallCount: Int get() = pairListAddresses.size

    override fun pairListAddress(call: Int): String? = pairListAddresses.getOrNull(call)

    override fun fillPairListCombined(bodies: List<String>, out: MutableList<ExchangePair>) {
        val (ecb, nbu, nbp) = listedCodes(bodies)
        if (ecb.isEmpty()) throw ExchangeParseException(ExchangeParseException.NO_DATA)
        out += ReferenceRates.pairs(ReferenceRates.assign(ecb, nbu, nbp), source)
    }

    protected companion object {
        val PAIR_ID = Regex("[A-Z]{6}")

        /** Codes des jüngsten EZB-Tags aus `eurofxref-daily.xml`; leer, wenn unlesbar. */
        fun ecbCodes(body: String?): Set<String> =
            body?.let(EcbRates::parseXml)?.let { days -> EcbRates.codes(days.getValue(days.keys.max())).toSet() }.orEmpty()
    }
}
