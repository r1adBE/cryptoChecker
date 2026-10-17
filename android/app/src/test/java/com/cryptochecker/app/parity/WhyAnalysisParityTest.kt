package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.activity.ActivityAnalyzer
import com.cryptochecker.app.domain.activity.BroadCoin
import com.cryptochecker.app.domain.activity.BroadMarket
import com.cryptochecker.app.domain.activity.BroadMarketSnapshot
import com.cryptochecker.app.domain.activity.FundingHistory
import com.cryptochecker.app.domain.activity.FundingPoint
import com.cryptochecker.app.domain.activity.FundingTrendRules
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.activity.OiSample
import com.cryptochecker.app.domain.activity.OiSeries
import com.cryptochecker.app.domain.activity.WhyBrief
import com.cryptochecker.app.domain.activity.WhyExtra
import com.cryptochecker.app.domain.activity.WhyFactors
import com.cryptochecker.app.domain.activity.WhyHeadline
import com.cryptochecker.app.domain.activity.WhyInput
import com.cryptochecker.app.domain.activity.WhyReport
import com.cryptochecker.app.domain.activity.WhySummary
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

/**
 * Gemeinsame Testfälle der «Warum?»-Auswertung (`testdata/parity/why_analysis.json`, Kopie in
 * iOS `Tests/Parity/`, gelesen von `WhyAnalysisParityTests.swift`): gleiche Eingaben → gleiche
 * Gründe, Faktoren, Lage, Funding-Verlauf, Open-Interest-Vergleich und breiter Markt.
 *
 * Kerzen werden aus kurzen Angaben gebaut ([WhyParityBuilder]), damit die Datei klein bleibt;
 * der Bau ist in Swift Schritt für Schritt gleich (gleiche Rechenoperationen → gleiche Doubles).
 */
class WhyAnalysisParityTest {

    private val data = ParityJson.load("why_analysis.json")
    private val now = (data["now"] as Double).toLong()

    @Test
    fun explain() {
        val cases = data["explain"].list()
        check(cases.isNotEmpty())
        cases.forEach { raw ->
            val c = raw.obj()
            val name = c["name"] as String
            val report = ActivityAnalyzer.explain(WhyParityBuilder.input(c["input"].obj(), now))
            assertEquals(name, WhyParityBuilder.normalize(c["expected"]), WhyParityBuilder.normalize(WhyParityBuilder.describe(report)))
        }
    }

    @Test
    fun oiSeries() {
        data["oiChange"].list().forEach { raw ->
            val c = raw.obj()
            val r = OiSeries.change(
                WhyParityBuilder.samples(c["series"], now), c["current"] as Double?, c["source"] as String?, now,
            )
            val actual = mapOf(
                "change" to r.changePercent, "minutes" to r.minutes?.toDouble(),
                "consistent" to r.consistent, "samples" to r.samples.toDouble(),
            )
            assertEquals(c["name"] as String, WhyParityBuilder.normalize(c["expected"]), WhyParityBuilder.normalize(actual))
        }
        data["oiAppend"].list().forEach { raw ->
            val c = raw.obj()
            val sample = WhyParityBuilder.samples(listOf(c["sample"]), now).single()
            val atNow = now + ((c["atMin"] as Double?) ?: 0.0).toLong() * 60_000L
            val out = OiSeries.append(WhyParityBuilder.samples(c["series"], now), sample, atNow)
            assertEquals(
                c["name"] as String,
                WhyParityBuilder.normalize(c["expected"]),
                WhyParityBuilder.normalize(out.map { WhyParityBuilder.sampleSpec(it, now) }),
            )
        }
    }

    @Test
    fun fundingTrend() {
        data["fundingTrend"].list().forEach { raw ->
            val c = raw.obj()
            val trend = FundingTrendRules.trend(
                WhyParityBuilder.history(c["history"], now), c["current"] as Double?, c["currentSource"] as String?, now,
            )
            val actual = trend?.let { mapOf("direction" to it.direction.name, "hours" to it.hours.toDouble()) }
            assertEquals(c["name"] as String, WhyParityBuilder.normalize(c["expected"]), WhyParityBuilder.normalize(actual))
        }
    }

    @Test
    fun broadMarket() {
        data["broadMarket"].list().forEach { raw ->
            val c = raw.obj()
            val coins = c["coins"].list().map {
                val e = it.list()
                BroadCoin(e[0] as String, e[1] as Double?, (e[2] as Double?)?.let { m -> now + m.toLong() * 60_000L })
            }
            val snap = BroadMarket.snapshot(coins, c["exclude"] as String?, now)
            val actual = snap?.let {
                mapOf("change24h" to it.change24h, "coins" to it.coins.toDouble(), "timeMin" to ((it.time - now) / 60_000L).toDouble())
            }
            assertEquals(c["name"] as String, WhyParityBuilder.normalize(c["expected"]), WhyParityBuilder.normalize(actual))
        }
    }

    private fun Any?.list(): List<Any?> = this as? List<Any?> ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>
}

/**
 * Bau der Eingaben und Beschreibung der Ergebnisse für `why_analysis.json` (auch vom Erzeuger
 * der erwarteten Werte genutzt). Zeiten in Minuten relativ zu «jetzt».
 */
internal object WhyParityBuilder {

    private const val H = ActivityAnalyzer.HOUR_MILLIS
    private const val DAY = ActivityAnalyzer.DAY_MILLIS

    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>
    private fun Any?.list(): List<Any?> = this as? List<Any?> ?: emptyList()
    private fun Any?.num(): Double? = (this as? Number)?.toDouble()

    /** Beginn der laufenden Stunde zu [now]. */
    fun runOpen(now: Long): Long = now - now % H

    /**
     * Stundenkerzen: {"count", "start", "pattern": [Renditen % im Wechsel], "tail": [[Rendite, Anzahl], …],
     * "volume", "volumeTail": [[Menge, Anzahl], …], "quote": true = Quote-Umsatz = Menge × Schluss}.
     * Die letzte Kerze ist die laufende Stunde.
     */
    fun candles(spec: Any?, now: Long): List<HourCandle>? {
        if (spec == null) return null
        val s = spec.obj()
        val count = s["count"].num()?.toInt() ?: 100
        val start = s["start"].num() ?: 100.0
        val pattern = s["pattern"].list().map { it.num() ?: 0.0 }.ifEmpty { listOf(0.0) }
        val tail = expand(s["tail"])
        val volume = s["volume"].num() ?: 100.0
        val volumeTail = expand(s["volumeTail"])
        val quote = s["quote"] as? Boolean ?: false
        val returns = count - 1
        val closes = ArrayList<Double>(count)
        closes += start
        for (i in 0 until returns) {
            val fromEnd = returns - i
            val r = if (fromEnd <= tail.size) tail[tail.size - fromEnd] else pattern[i % pattern.size]
            closes += closes[closes.size - 1] * (1.0 + r / 100.0)
        }
        val open0 = runOpen(now)
        return (0 until count).map { i ->
            val open = if (i == 0) closes[0] else closes[i - 1]
            val fromEnd = count - i
            val v = if (fromEnd <= volumeTail.size) volumeTail[volumeTail.size - fromEnd] else volume
            HourCandle(
                openTime = open0 - (count - 1 - i).toLong() * H,
                open = open,
                high = maxOf(open, closes[i]),
                low = minOf(open, closes[i]),
                close = closes[i],
                volume = v,
                quoteVolume = if (quote) v * closes[i] else null,
            )
        }
    }

    /** [[Wert, Anzahl], …] → Liste. */
    private fun expand(spec: Any?): List<Double> {
        val out = ArrayList<Double>()
        spec.list().forEach { pair ->
            val p = pair.list()
            val value = p[0].num() ?: 0.0
            repeat(p[1].num()?.toInt() ?: 1) { out += value }
        }
        return out
    }

    /** Tageskerzen: {"count", "close", "high", "low"} — alle Tage gleich, letzter = laufender Tag. */
    fun daily(spec: Any?, now: Long): List<HourCandle>? {
        if (spec == null) return null
        val s = spec.obj()
        val count = s["count"].num()?.toInt() ?: 31
        val close = s["close"].num() ?: 100.0
        val high = s["high"].num() ?: close
        val low = s["low"].num() ?: close
        val lastOpen = now - now % DAY
        return (0 until count).map { i ->
            HourCandle(lastOpen - (count - 1 - i).toLong() * DAY, close, high, low, close, 1.0)
        }
    }

    /** [[Minuten, Coins, Quelle|null], …] */
    fun samples(spec: Any?, now: Long): List<OiSample> = spec.list().map {
        val e = it.list()
        OiSample(units = e[1].num() ?: 0.0, time = now + (e[0].num() ?: 0.0).toLong() * 60_000L, source = e.getOrNull(2) as String?)
    }

    fun sampleSpec(s: OiSample, now: Long): List<Any?> = listOf(((s.time - now) / 60_000L).toDouble(), s.units, s.source)

    /** {"source", "points": [[Minuten, Satz %], …]} */
    fun history(spec: Any?, now: Long): FundingHistory? {
        if (spec == null) return null
        val s = spec.obj()
        return FundingHistory(
            s["points"].list().map {
                val e = it.list()
                FundingPoint(now + (e[0].num() ?: 0.0).toLong() * 60_000L, e[1].num() ?: 0.0)
            },
            s["source"] as String,
        )
    }

    fun input(s: Map<String, Any?>, now: Long): WhyInput {
        val broad = s["broad"]?.obj()?.let {
            BroadMarketSnapshot(it["change24h"].num() ?: 0.0, it["coins"].num()?.toInt() ?: 0, now + (it["timeMin"].num() ?: 0.0).toLong() * 60_000L)
        }
        return WhyInput(
            baseAsset = s["base"] as String,
            candles = candles(s["candles"], now),
            referenceCandles = candles(s["reference"], now),
            fundingPercent = s["funding"].num(),
            openInterestChangePercent = s["oi"].num(),
            fearGreed = s["fng"].num()?.toInt(),
            fearGreedYesterday = s["fngYesterday"].num()?.toInt(),
            now = now,
            tickerChange24h = s["ticker24h"].num(),
            marketLive = s["live"] as? Boolean ?: true,
            dailyCandles = daily(s["daily"], now),
            quoteAsset = s["quote"] as String?,
            ethCandles = candles(s["eth"], now),
            broadMarket = broad,
            openInterestMinutes = s["oiMinutes"].num()?.toInt(),
            openInterestConsistent = s["oiConsistent"] as? Boolean ?: true,
            fundingHistory = history(s["fundingHistory"], now),
            fundingSource = s["fundingSource"] as String?,
        )
    }

    /** Schlüssel von «Kurz gesagt» wie `WhySummary.keys` in Swift. */
    fun briefKeys(brief: WhyBrief): List<String> {
        val out = ArrayList<String>()
        brief.headline?.let {
            out += when (it) {
                WhyHeadline.CALM -> "why_summary_calm"
                WhyHeadline.COIN_VOLUME -> "why_summary_coin_volume"
                WhyHeadline.COIN -> "why_summary_coin"
                WhyHeadline.AGAINST -> "why_summary_against"
                WhyHeadline.MARKET_VOLUME -> "why_summary_market_volume"
                WhyHeadline.MARKET -> "why_summary_market"
                WhyHeadline.PEG_NEAR -> "why_summary_peg_near"
                WhyHeadline.PEG_OFF -> "why_summary_peg_off"
            }
        }
        brief.extras.forEach {
            out += when (it) {
                WhyExtra.THIN -> "why_summary_thin"
                WhyExtra.LONGS -> "why_summary_longs"
                WhyExtra.SHORTS -> "why_summary_shorts"
            }
        }
        return out
    }

    /** Ergebnis als JSON-artige Struktur (Namen = Kotlin-Enum-Namen). */
    fun describe(r: WhyReport): Map<String, Any?> {
        return linkedMapOf(
            "hasMarketData" to r.hasMarketData,
            "stable" to r.stable,
            "price" to r.price,
            "change1h" to r.change1h,
            "change24h" to r.change24h,
            "high30d" to r.high30d,
            "low30d" to r.low30d,
            "btcLink" to r.btcLink?.name,
            "oiMinutes" to r.oiMinutes?.toDouble(),
            "candleTimeMin" to r.candleTime?.let { ((it - r.dataTime) / 60_000L).toDouble() },
            "reasons" to r.reasons.map {
                linkedMapOf(
                    "kind" to it.kind.name, "tone" to it.tone.name, "value" to it.value, "secondary" to it.secondary,
                    "strong" to it.strong, "ref" to it.ref?.name, "threshold" to it.threshold, "approximate" to it.approximate,
                    "similarOnly" to WhySummary.isSimilarOnly(it), "mark" to WhySummary.mark(it).name,
                )
            },
            "position3d" to r.position3d?.let { linkedMapOf("kind" to it.kind.name, "percent" to it.percent, "threshold" to it.threshold) },
            "fundingTrend" to r.fundingTrend?.let { linkedMapOf("direction" to it.direction.name, "hours" to it.hours.toDouble()) },
            "factors" to WhyFactors.rank(r).map {
                linkedMapOf(
                    "kind" to it.kind.name, "note" to it.note.name, "direction" to it.direction.name,
                    "value" to it.value, "secondary" to it.secondary, "strength" to it.strength, "neutral" to it.neutral,
                    "ref" to it.ref?.name, "approximate" to it.approximate,
                    "trend" to it.trend?.let { t -> "${t.direction.name}/${t.hours}" },
                )
            },
            "brief" to briefKeys(WhySummary.brief(r.reasons)),
        )
    }

    /** Zahlen als ganze Millionstel (Text), damit der Vergleich robust gegen Rundung ist. */
    fun normalize(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to normalize(v) }.toSortedMap()
        is List<*> -> value.map { normalize(it) }
        is Number -> {
            // Millionstel, halbe kaufmännisch vom Nullpunkt weg gerundet (in Swift gleich gerechnet)
            val d = value.toDouble()
            val micro = floor(abs(d) * 1e6 + 0.5).toLong()
            if (micro == 0L) "0" else (if (d < 0) "-" else "") + micro.toString()
        }
        else -> value
    }
}
