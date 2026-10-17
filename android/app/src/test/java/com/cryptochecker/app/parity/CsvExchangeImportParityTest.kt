package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.csvimport.CsvImport
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportMapping
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportParser
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportResult
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportSkipReason
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Börsen-Exporte (Binance, Coinbase, Bitpanda) aus den synthetischen Dateien `testdata/csv/` gegen die Erwartungen in
 * `csv_exchanges.json` (iOS: `CsvExchangeImportTests.swift`, Dateien in `Tests/Csv/`).
 */
class CsvExchangeImportParityTest {

    private val data = ParityJson.load("csv_exchanges.json")

    private fun parse(name: String): CsvImportResult =
        CsvImportParser.parse(File(ParityJson.dir().parentFile, "csv/$name").readText(Charsets.UTF_8))

    private fun text(v: BigDecimal?): String? = v?.let { CsvImport.text(it) }

    @Test
    fun files() {
        data["files"].list().map { it.obj() }.forEach { f ->
            val name = f["file"] as String
            val result = parse(name)
            assertEquals("$name format", f["format"], result.format.name)
            assertEquals("$name zone", f["zone"], result.zone)
            assertEquals("$name localTimes", f["localTimes"], result.localTimes)
            val expected = f["rows"].list().map { it.obj() }
            assertEquals("$name rows", expected.map { it["line"].num()!!.toInt() }, result.rows.map { it.line })
            expected.zip(result.rows).forEach { (e, r) ->
                val m = "$name line ${r.line}"
                assertEquals("$m kind", e["kind"], r.kind.name)
                assertEquals("$m rawKind", e["rawKind"], r.rawKind)
                assertEquals("$m coin", e["coin"], r.coin)
                assertEquals("$m amount", e["amount"], text(r.amount))
                assertEquals("$m price", e["price"], text(r.price))
                assertEquals("$m quote", e["quote"], r.quote)
                assertEquals("$m fee", e["fee"], text(r.fee))
                assertEquals("$m feeUnit", e["feeUnit"], r.feeUnit)
                assertEquals("$m time", Instant.parse(e["time"] as String).toEpochMilli(), r.timeMillis)
                assertEquals("$m externalId", e["externalId"], r.externalId)
                assertEquals("$m key", e["key"], r.key)
                assertEquals("$m toCoin", e["toCoin"], r.toCoin)
                assertEquals("$m toAmount", e["toAmount"], text(r.toAmount))
                assertEquals("$m swapValue", e["swapValue"], text(r.swapValue))
                assertEquals("$m stableSwap", e["stableSwap"] ?: false, r.stableSwap)
            }
            val skipped = f["skipped"].list().map { it.obj() }.map { it["line"].num()!!.toInt() to it["reason"] as String }
            assertEquals("$name skipped", skipped, result.skipped.map { it.line to it.reason.name })
        }
    }

    /** Zuordnung zu Transaktionen (Gebühr im Coin, im dritten Coin, in Fiat; Art) mit festem Tageskurs. */
    @Test
    fun mapping() {
        data["mapping"].list().map { it.obj() }.forEach { c ->
            val rates = c["fx"].obj().mapValues { BigDecimal(it.value as String) }
            val preview = CsvImportMapping.preview(parse(c["file"] as String), emptySet()) { currency, _ -> rates[currency] }
            c["transactions"].list().map { it.obj() }.forEach { e ->
                val line = e["line"].num()!!.toInt()
                val tx = preview.transactions.first { it.line == line }
                val m = "${c["file"]} line $line"
                assertEquals("$m type", e["type"], tx.type.name)
                assertEquals("$m priceUsdt", e["priceUsdt"], text(tx.priceUsdt))
                assertEquals("$m fee", e["fee"], text(tx.fee))
                assertEquals("$m feeUnit", e["feeUnit"], tx.feeUnit?.name)
                assertEquals("$m feeCoin", e["feeCoin"], tx.feeCoin)
                assertEquals("$m feeCoinAmount", e["feeCoinAmount"], text(tx.feeCoinAmount))
            }
        }
    }

    /** Beide Binance-Exporte nacheinander (beide Reihenfolgen): genau gleiche sind Doppelte, fast gleiche in der Prüfliste. */
    @Test
    fun binanceBothExportsNeverCountTwice() {
        data["bothExports"].list().map { it.obj() }.forEach { c ->
            val none: (String, Long) -> BigDecimal? = { _, _ -> null }
            val first = CsvImportMapping.preview(parse(c["first"] as String), emptySet(), fx = none)
            val second = CsvImportMapping.preview(parse(c["second"] as String), first.transactions.mapTo(HashSet()) { it.importKey }, fx = none)
            val m = "${c["first"]} → ${c["second"]}"
            assertEquals("$m duplicates", c["duplicates"].num()!!.toInt(), second.duplicates)
            assertEquals("$m possible duplicates", c["possibleDuplicates"].list().map { it.num()!!.toInt() },
                second.skipped.filter { it.reason == CsvImportSkipReason.POSSIBLE_DUPLICATE }.map { it.line })
            assertEquals("$m transactions", c["transactions"].list().map { it.num()!!.toInt() }, second.transactions.map { it.line })
        }
    }

    /** Dasselbe Geschäft aus Transaktions- und Handelsverlauf hat denselben Schlüssel (zweiter Import = doppelt). */
    @Test
    fun binanceLedgerAndTradeHistoryShareKeys() {
        val c = data["sharedKeys"].obj()
        val ledger = parse(c["ledger"] as String).rows.map { it.key }.toSet()
        val trades = parse(c["trades"] as String).rows.map { it.key }
        assertEquals(c["shared"].list(), trades.filter { it in ledger })
    }
}
