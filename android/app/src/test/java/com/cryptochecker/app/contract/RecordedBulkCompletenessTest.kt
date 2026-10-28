package com.cryptochecker.app.contract

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import com.cryptochecker.marketdata.config.ExchangesConfig
import com.cryptochecker.marketdata.model.ExchangePair
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Börsen, deren `bulkTickersComplete` sich auf eine Aufzeichnung stützt: In `recorded_*.json` muss jedes Paar der
 * aufgezeichneten Paarliste auch in der aufgezeichneten Sammelabfrage stehen (gelesen oder als unlesbar gemeldet) —
 * sonst bestätigte die App ein gehandeltes Paar als «nicht mehr gehandelt». iOS: RecordedBulkCompletenessTests.swift.
 */
class RecordedBulkCompletenessTest {

    /** Börse → Aufzeichnung, die die Vollständigkeit belegt. */
    private val evidence = mapOf(
        "CoinDcx" to "recorded_coindcx.json",
        "Luno" to "recorded_luno.json",
        "Icrypex" to "recorded_icrypex.json",
        "HashKey" to "recorded_hashkey.json",
    )

    /**
     * Paare, die die Sammelabfrage zwar führt, aber mit Kurs 0 (nie gehandelt) — sie fallen dort weg wie bei Lighter
     * und gelten damit als nicht gehandelt (HashKey «SPICEUSDC», Aufzeichnung 19.10.2026: c/h/l/o/v alle «0»).
     */
    private val listedWithoutPrice = mapOf("HashKey" to setOf("SPICEUSDC"))

    private fun recordedDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testdata/exchanges/recorded")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw IllegalStateException("testdata/exchanges/recorded not found")
    }

    @Test
    fun recordedBulkListsEveryRecordedPair() {
        val failures = ArrayList<String>()
        for ((key, file) in evidence) {
            val exchange = ExchangesConfig.EXCHANGES[key] ?: throw AssertionError("unknown market $key")
            if (!exchange.bulkTickersComplete) failures += "$key: bulkTickersComplete is false"
            val cases = ParityJson.parse(File(recordedDir(), file).readText(Charsets.UTF_8)).obj()["cases"].list()
                .map { it.obj() }
            val pairs = ArrayList<ExchangePair>()
            val listed = HashSet<String>()
            for (c in cases) {
                val call = c["requestId"].num()?.toInt() ?: 0
                val body = c["body"] as String
                when (c["kind"]) {
                    "pairs" -> exchange.readPairList(call, body, pairs)
                    "bulk" -> exchange.parseBulkTickersDetailed(call, body).let { listed += it.tickers.keys; listed += it.failed }
                }
            }
            if (pairs.isEmpty() || listed.isEmpty()) failures += "$key: recording lacks pair list or bulk response"
            val zero = listedWithoutPrice[key].orEmpty()
            val missing = pairs.mapNotNull { it.pairId }.filter { it !in listed && it !in zero }
            if (missing.isNotEmpty()) failures += "$key: ${missing.size} recorded pair(s) missing in bulk: ${missing.take(10)}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
