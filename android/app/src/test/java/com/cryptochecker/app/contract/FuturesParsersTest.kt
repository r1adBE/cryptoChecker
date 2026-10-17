package com.cryptochecker.app.contract

import com.cryptochecker.app.data.remote.FuturesInfo
import com.cryptochecker.app.data.remote.FuturesParsers
import com.cryptochecker.app.data.remote.FuturesSources
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Funding/Open Interest der Börsen mit eigenen Futures-Daten: Antworten im Format der Börsen aus
 * `testdata/exchanges/futures_derivatives.json` laufen durch [FuturesParsers]; dazu die Herkunfts-
 * Einordnung [FuturesSources.note]. Gegenstück iOS: `Tests/FuturesParsersTests.swift` (dieselbe Datei).
 * `org.json` echt (testImplementation android-json), wie in [ExchangeContractTest].
 */
class FuturesParsersTest {

    private fun fixture(): Map<String, Any?> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testdata/exchanges/futures_derivatives.json")
            if (candidate.isFile) return ParityJson.parse(candidate.readText(Charsets.UTF_8)).obj()
            dir = dir.parentFile
        }
        throw IllegalStateException("testdata/exchanges/futures_derivatives.json not found")
    }

    private fun run(parser: String, b: Map<String, Any?>): FuturesInfo = when (parser) {
        "woo" -> FuturesParsers.woo(b["body"] as String)
        "bitget" -> FuturesParsers.bitget(b["ticker"] as String, b["fundingTime"] as String?)
        "mexc" -> FuturesParsers.mexc(b["ticker"] as String, b["detail"] as String?, b["fundingRate"] as String?, b["symbol"] as String)
        "htx" -> FuturesParsers.htx(b["funding"] as String, b["openInterest"] as String?)
        "hyperliquid" -> FuturesParsers.hyperliquid(b["body"] as String, b["coin"] as String)
        "deribit" -> FuturesParsers.deribit(b["body"] as String)
        "binanceCoinM" -> FuturesParsers.binanceCoinM(
            b["premium"] as String, b["openInterest"] as String?, b["symbol"] as String,
            (b["exchangeInfo"] as String?)?.let { FuturesParsers.binanceCoinMContractSizes(it) },
        )
        else -> throw IllegalArgumentException("unknown parser $parser")
    }

    private fun check(label: String, expected: Double?, actual: Double?) {
        if (expected == null) {
            assertEquals("$label: expected null", null, actual)
            return
        }
        if (actual == null) fail("$label: expected $expected, got null")
        val a = actual ?: return
        assertTrue("$label: expected $expected, got $a", abs(expected - a) <= max(abs(expected) * 1e-9, 1e-12))
    }

    @Test
    fun parsersMatchFixtures() {
        val cases = fixture()["cases"].list().map { it.obj() }
        assertTrue("futures_derivatives.json ohne Fälle", cases.size >= 10)
        for (c in cases) {
            val name = "${c["parser"]}: ${c["name"]}"
            val bodies = c["bodies"].obj()
            if (c["expectError"] == true) {
                val result = runCatching { run(c["parser"] as String, bodies) }
                assertTrue("$name: expected error, got ${result.getOrNull()}", result.isFailure)
                continue
            }
            val info = run(c["parser"] as String, bodies)
            val e = c["expected"].obj()
            check("$name funding", e["fundingRatePercent"].num(), info.fundingRatePercent)
            check("$name openInterest", e["openInterestUsd"].num(), info.openInterestUsd)
            assertEquals("$name nextFundingTime", e["nextFundingTime"].num()?.toLong(), info.nextFundingTime)
            assertEquals("$name source", e["source"], info.source)
            if (e.containsKey("fundingIntervalHours")) {
                assertEquals("$name fundingIntervalHours", e["fundingIntervalHours"].num()?.toInt(), info.fundingIntervalHours)
            }
        }
    }

    @Test
    fun fundingIntervalsMatchFixtures() {
        val f = fixture()["intervals"].obj()
        val binance = FuturesParsers.binanceFundingInfo(f["binanceFundingInfo"] as String)
        for (c in f["binance"].list().map { it.obj() }) {
            assertEquals("binance ${c["symbol"]}", c["expected"].num()?.toInt(), FuturesParsers.binanceIntervalHours(binance, c["symbol"] as String))
        }
        for (c in f["okx"].list().map { it.obj() }) {
            assertEquals(
                "okx ${c["name"]}", c["expected"].num()?.toInt(),
                FuturesParsers.okxIntervalHours(c["fundingTime"].num()?.toLong(), c["nextFundingTime"].num()?.toLong()),
            )
        }
        for (c in f["hours"].list().map { it.obj() }) {
            val v = c["value"]
            val value = (v as? Number)?.toDouble() ?: (v as? String)?.toDoubleOrNull()
            assertEquals("hours $v", c["expected"].num()?.toInt(), FuturesParsers.hours(value))
        }
    }

    @Test
    fun notesMatchFixtures() {
        for (c in fixture()["notes"].list().map { it.obj() }) {
            val note = FuturesSources.note(c["perpetual"] as Boolean, c["marketKey"] as String, c["source"] as String, c["pairId"] as String?)
            assertEquals("note ${c["marketKey"]} ${c["pairId"]} / ${c["source"]}", c["expected"], note?.name)
        }
    }

    /** Standard-Intervall je Quelle: nur Quellen mit einem festen Intervall haben eines, sonst unbekannt. */
    @Test
    fun sourceIntervalDefaultsMatchFixtures() {
        for (c in fixture()["intervals"].obj()["defaults"].list().map { it.obj() }) {
            assertEquals("default ${c["source"]}", c["expected"].num()?.toInt(), FuturesSources.fundingIntervalHours(c["source"] as String))
        }
    }
}
