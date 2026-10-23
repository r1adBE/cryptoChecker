package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.market.GasFees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Netzwerkgebühren (`testdata/parity/gas_fees.json`): eth_feeHistory (Grundgebühr des nächsten Blocks plus
 * Median der Trinkgelder, unlesbare Einträge übersprungen), eth_gasPrice, mempool.space, Überweisungskosten und Anzeige.
 * Gegenstück: `Tests/GasFeesParityTests.swift`.
 */
class GasFeesParityTest {

    private val data = ParityJson.load("gas_fees.json")
    private val tolerance = data["tolerance"].num()!!

    /** Relativ verglichen: Werte von 1e-9 gwei (1 wei) bis 1e11 gwei. */
    private fun assertClose(name: String, expected: Double?, actual: Double?) {
        if (expected == null) {
            assertNull(name, actual)
            return
        }
        assertNotNull(name, actual)
        val scale = max(abs(expected), 1e-12)
        assertTrue("$name: erwartet $expected, war $actual", abs(expected - actual!!) <= tolerance * scale)
    }

    private fun <T> assertThrowsOrResult(name: String, case: Map<String, Any?>, parse: () -> T): T? {
        if (case["error"] == true) {
            val result = runCatching(parse)
            if (result.isSuccess) fail("$name: Fehler erwartet, war ${result.getOrNull()}")
            return null
        }
        return parse()
    }

    @Test
    fun feeHistoryWieGemeinsameTestfaelle() {
        val cases = data["feeHistory"].list()
        assertTrue(cases.size >= 20)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["description"] as String
            val fees = assertThrowsOrResult(name, c) { GasFees.parseFeeHistory(c["response"] as String) } ?: continue
            assertClose("$name langsam", c["slow"].num(), fees.first)
            assertClose("$name normal", c["normal"].num(), fees.second)
            assertClose("$name schnell", c["fast"].num(), fees.third)
        }
    }

    @Test
    fun gasPriceWieGemeinsameTestfaelle() {
        for (raw in data["gasPrice"].list()) {
            val c = raw.obj()
            val name = c["description"] as String
            val gwei = assertThrowsOrResult(name, c) { GasFees.parseGasPrice(c["response"] as String) } ?: continue
            assertClose(name, c["gwei"].num(), gwei)
        }
    }

    @Test
    fun mempoolWieGemeinsameTestfaelle() {
        for (raw in data["mempool"].list()) {
            val c = raw.obj()
            val name = c["description"] as String
            val fees = assertThrowsOrResult(name, c) { GasFees.parseMempool(c["response"] as String) } ?: continue
            assertClose("$name schnell", c["fast"].num(), fees.first)
            assertClose("$name normal", c["normal"].num(), fees.second)
            assertClose("$name langsam", c["slow"].num(), fees.third)
        }
    }

    @Test
    fun ueberweisungskostenWieGemeinsameTestfaelle() {
        for (raw in data["evmTransferUsd"].list()) {
            val c = raw.obj()
            val gwei = c["gwei"].num()!!
            assertClose("EVM $gwei gwei", c["usd"].num(), GasFees.evmTransferUsd(gwei, c["coinUsd"].num()))
        }
        for (raw in data["btcTransferUsd"].list()) {
            val c = raw.obj()
            val sat = c["satPerVb"].num()!!
            assertClose("BTC $sat sat/vB", c["usd"].num(), GasFees.btcTransferUsd(sat, c["btcUsd"].num()))
        }
    }

    @Test
    fun anzeigeWieGemeinsameTestfaelle() {
        val format = data["format"].obj()
        val locale = Locale.forLanguageTag((format["locale"] as String).replace('_', '-'))
        for (raw in format["gwei"].list()) {
            val c = raw.obj()
            assertEquals("gwei ${c["gwei"]}", c["expected"], GasFees.formatGwei(c["gwei"].num()!!, locale))
        }
        for (raw in format["usd"].list()) {
            val c = raw.obj()
            assertEquals("usd ${c["usd"]}", c["expected"], GasFees.formatUsd(c["usd"].num()!!, locale))
        }
    }
}
