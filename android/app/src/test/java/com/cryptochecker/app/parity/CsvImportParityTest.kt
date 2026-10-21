package com.cryptochecker.app.parity

import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.TaxExport
import com.cryptochecker.app.domain.portfolio.gainRows
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvDateOrder
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImport
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportMapping
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportParser
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportResult
import com.cryptochecker.app.domain.portfolio.csvimport.classify
import com.cryptochecker.app.domain.portfolio.csvimport.parseNumber
import com.cryptochecker.app.domain.portfolio.csvimport.parseTime
import com.cryptochecker.app.domain.portfolio.csvimport.tokenize
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Gemeinsame Testfälle `csv_import.json` (iOS: `CsvImportTests.swift`). */
class CsvImportParityTest {

    private val data = ParityJson.load("csv_import.json")

    /** ISO-UTC-Text oder Epoch-ms. */
    private fun millis(value: Any?): Long? = when (value) {
        null -> null
        is String -> Instant.parse(value).toEpochMilli()
        else -> value.num()!!.toLong()
    }

    private fun parse(name: String): CsvImportResult {
        val file = data["files"].list().map { it.obj() }.first { it["name"] == name }
        return CsvImportParser.parse(file["csv"] as String)
    }

    private fun text(v: BigDecimal?): String? = v?.let { CsvImport.text(it) }

    @Test
    fun files() {
        data["files"].list().map { it.obj() }.forEach { f ->
            val name = f["name"] as String
            val result = CsvImportParser.parse(f["csv"] as String)
            assertEquals("$name format", f["format"], result.format.name)
            val expected = f["rows"].list().map { it.obj() }
            assertEquals("$name rows", expected.map { (it["line"].num()!!).toInt() }, result.rows.map { it.line })
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
                assertEquals("$m time", millis(e["time"]), r.timeMillis)
                assertEquals("$m externalId", e["externalId"], r.externalId)
                assertEquals("$m key", e["key"], r.key)
                // Tausch (fehlt im Fixture = null)
                assertEquals("$m toCoin", e["toCoin"], r.toCoin)
                assertEquals("$m toAmount", e["toAmount"], text(r.toAmount))
                assertEquals("$m swapValue", e["swapValue"], text(r.swapValue))
                // Kauf/Verkauf gegen Stablecoin als Tausch (fehlt = false)
                assertEquals("$m stableSwap", e["stableSwap"] ?: false, r.stableSwap)
            }
            val skipped = f["skipped"].list().map { it.obj() }.map { (it["line"].num()!!).toInt() to it["reason"] as String }
            assertEquals("$name skipped", skipped, result.skipped.map { it.line to it.reason.name })
        }
    }

    @Test
    fun numbers() {
        data["numbers"].list().map { it.obj() }.forEach { c ->
            val hint = (c["hint"] as String?)?.single()
            assertEquals("number: $c", c["expected"], text(CsvImportParser.parseNumber(c["text"] as String, hint)))
        }
    }

    @Test
    fun dates() {
        data["dates"].list().map { it.obj() }.forEach { c ->
            assertEquals("date: $c", millis(c["expected"]), CsvImportParser.parseTime(c["text"] as String))
        }
    }

    @Test
    fun classify() {
        data["classify"].list().map { it.obj() }.forEach { c ->
            assertEquals("classify: $c", c["expected"], CsvImportParser.classify(c["text"] as String)?.name)
        }
    }

    /** Zeitzone ohne Versatz: gewählt vor Swissquote (Europe/Zurich) vor Gerät; die Vorschau übernimmt sie. */
    @Test
    fun zones() {
        data["zones"].list().map { it.obj() }.forEach { c ->
            val file = data["files"].list().map { it.obj() }.first { it["name"] == c["file"] }
            val result = CsvImportParser.parse(file["csv"] as String, c["deviceZone"] as String, c["override"] as String?)
            val m = "zones $c"
            assertEquals("$m zone", c["zone"], result.zone)
            assertEquals("$m localTimes", c["localTimes"], result.localTimes)
            assertEquals("$m time", millis(c["time"]), result.rows[c["row"].num()!!.toInt()].timeMillis)
            val preview = CsvImportMapping.preview(result, emptySet()) { _, _ -> null }
            assertEquals("$m preview zone", result.zone, preview.zone)
            assertEquals("$m preview localTimes", result.localTimes, preview.localTimes)
        }
        // Unstaking wird eine Umbuchung ohne Preis, mit der Art als Notiz
        val csv = "Timestamp,Type,Asset,Amount,Price,Currency\n2024-03-01T00:00:00Z,Unstaking,ETH,1,3000,USD\n"
        val tx = CsvImportMapping.preview(CsvImportParser.parse(csv), emptySet()) { _, _ -> null }.transactions.single()
        assertEquals(PortfolioTxType.TRANSFER, tx.type)
        assertNull(tx.priceUsdt)
        assertEquals("Unstaking", tx.note)
    }

    @Test
    fun fx() {
        data["fx"].list().map { it.obj() }.forEach { c ->
            val rates = c["rates"].obj().mapValues { (_, byDay) ->
                byDay.obj().entries.associate { LocalDate.parse(it.key).toEpochDay() to BigDecimal(it.value as String) }
            }
            // USDT/USD-Tagesschlüsse (Datum → Schluss); fehlt im Fixture = null (USD gilt 1:1 als USDT)
            val usdtUsd = (c["usdtUsd"] as Map<*, *>?)?.entries
                ?.associate { LocalDate.parse(it.key as String).toEpochDay() to BigDecimal(it.value as String) }
            val priced = CsvImport.priceInUsdt(parse(c["file"] as String).rows, usdtUsd = usdtUsd) { cur, day -> rates[cur]?.get(day) }
            val expected = c["expected"].list().map { it.obj() }
            assertEquals(expected.map { it["line"].num()!!.toInt() }, priced.map { it.row.line })
            expected.zip(priced).forEach { (e, p) ->
                val m = "${c["file"]} line ${p.row.line}"
                assertEquals("$m priceUsdt", e["priceUsdt"], text(p.priceUsdt))
                assertEquals("$m feeUsdt", e["feeUsdt"], text(p.feeUsdt))
                assertEquals("$m needsFx", e["needsFx"], p.needsFx)
                assertEquals("$m missingPrice", e["missingPrice"], p.missingPrice)
                assertEquals("$m swapValueUsdt", e["swapValueUsdt"], text(p.swapValueUsdt))
                assertEquals("$m usdtApproximate", e["usdtApproximate"] ?: false, p.usdtApproximate)
            }
        }
    }

    /**
     * USDT zum Marktkurs, Rundlauf Import → Steuer-Export: USD/Fiat → USDT mit dem USDT/USD-Schluss DES Tags, der Export
     * rechnet mit demselben Schluss zurück — ein USD-Kauf über 10 000 ergibt 10 000 USD Einstand, CHF ohne Scheingewinn.
     */
    @Test
    fun usdtRoundTrip() {
        data["usdtRoundTrip"].list().map { it.obj() }.forEach { c ->
            val name = c["name"] as String
            fun byDay(raw: Any?) = raw.obj().entries.associate { LocalDate.parse(it.key).toEpochDay() to BigDecimal(it.value as String) }
            val rates = c["rates"].obj().mapValues { (_, v) -> byDay(v) }
            val usdtUsd = byDay(c["usdtUsd"])
            val currency = c["currency"] as String
            val result = CsvImportParser.parse(c["csv"] as String, "UTC")
            val preview = CsvImportMapping.preview(result, emptySet(), usdtUsd = usdtUsd) { cur, day -> rates[cur]?.get(day) }
            assertEquals("$name priceUsdt", c["priceUsdt"], preview.transactions.map { text(it.priceUsdt) })
            assertEquals("$name usdtApproximate", c["usdtApproximate"].list().map { it.num()!!.toInt() }, preview.usdtApproximate)
            val trades = preview.transactions.mapIndexed { i, t ->
                PortfolioTrade(
                    id = i + 1L, coin = t.coin, type = t.type, amount = t.amount, priceUsdt = t.priceUsdt, time = t.time,
                    fee = t.fee, feeUnit = t.feeUnit, feeCoin = t.feeCoin, feeCoinAmount = t.feeCoinAmount,
                )
            }
            val rows = TaxExport.gainRows(
                trades, TaxMethod.of(CostMethod.FIFO), TaxTemplate.NEUTRAL.period(c["year"].num()!!.toInt()),
                java.time.ZoneId.of("UTC"), currency, rates[currency] ?: emptyMap(), usdtUsd = usdtUsd,
            )
            val expected = c["gains"].list().map { it.obj() }
            assertEquals("$name rows", expected.size, rows.size)
            expected.zip(rows).forEach { (e, r) ->
                assertEquals("$name coin", e["coin"], r.coin)
                assertEquals("$name cost", e["costTarget"], CutoffExport.money(r.costTarget!!))
                assertEquals("$name proceeds", e["proceedsTarget"], CutoffExport.money(r.proceedsTarget!!))
                assertEquals("$name gain", e["gainTarget"], CutoffExport.money(r.gainTarget!!))
                assertEquals("$name usdtApproximate", e["usdtApproximate"], r.usdtApproximate)
            }
        }
    }

    @Test
    fun dedupe() {
        val d = data["dedupe"].obj()
        val rows = parse(d["file"] as String).rows
        val split = CsvImport.splitDuplicates(rows, d["existing"].list().map { it as String }.toSet())
        assertEquals(d["freshLines"].list().map { it.num()!!.toInt() }, split.fresh.map { it.line })
        assertEquals(d["duplicateLines"].list().map { it.num()!!.toInt() }, split.duplicates.map { it.line })
        // Gleiche Datei zweimal: alles Duplikate
        val again = CsvImport.splitDuplicates(rows, rows.map { it.key }.toSet())
        assertEquals(0, again.fresh.size)
    }

    /** Schlüssel ohne Versatz unabhängig von der Zeitzone; frühere Schlüssel (je Zeitzone) als legacyKeys. */
    @Test
    fun zoneKeys() {
        data["zoneKeys"].list().map { it.obj() }.forEach { c ->
            val file = data["files"].list().map { it.obj() }.first { it["name"] == c["file"] }
            val result = CsvImportParser.parse(file["csv"] as String, c["deviceZone"] as String, c["override"] as String?)
            val row = result.rows[c["row"].num()!!.toInt()]
            val m = "zoneKeys $c"
            assertEquals("$m key", c["key"], row.key)
            assertEquals("$m legacyKeys", c["legacyKeys"].list().map { it as String }.toSet(), row.legacyKeys.toSet())
        }
    }

    /** Zweiter Import derselben Datei mit anderer Zeitzone: keine Doppel (auch gegen Schlüssel bis Build 45). */
    @Test
    fun zoneDedupe() {
        data["zoneDedupe"].list().map { it.obj() }.forEach { c ->
            val file = data["files"].list().map { it.obj() }.first { it["name"] == c["file"] }
            val result = CsvImportParser.parse(file["csv"] as String, c["deviceZone"] as String, c["override"] as String?)
            val split = CsvImport.splitDuplicates(result.rows, c["existing"].list().map { it as String }.toSet())
            assertEquals(c["name"] as String, c["duplicateLines"].list().map { it.num()!!.toInt() }, split.duplicates.map { it.line })
        }
    }

    /** Tag/Monat je Datei; nur mehrdeutige Daten → Auswahl der Vorschau. */
    @Test
    fun dateOrder() {
        data["dateOrder"].list().map { it.obj() }.forEach { c ->
            val name = c["name"] as String
            val chosen = (c["dateOrder"] as String?)?.let { CsvDateOrder.valueOf(it) }
            val result = CsvImportParser.parse(c["csv"] as String, "UTC", null, chosen)
            assertEquals("$name order", c["order"], result.dateOrder.name)
            assertEquals("$name ambiguous", c["ambiguous"], result.dateOrderAmbiguous)
            assertEquals("$name times", c["times"].list().map { millis(it) }, result.rows.map { it.timeMillis })
            val preview = CsvImportMapping.preview(result, emptySet()) { _, _ -> null }
            assertEquals("$name preview order", result.dateOrder, preview.dateOrder)
            assertEquals("$name preview ambiguous", result.dateOrderAmbiguous, preview.dateOrderAmbiguous)
        }
    }

    /** Dezimalzeichen: «0.125» verrät es; mehrdeutige Zahl ohne Hinweis → Auswahl der Vorschau. */
    @Test
    fun decimalMark() {
        data["decimalMark"].list().map { it.obj() }.forEach { c ->
            val name = c["name"] as String
            val chosen = (c["decimalMark"] as String?)?.first()
            val result = CsvImportParser.parse(c["csv"] as String, "UTC", null, null, chosen)
            assertEquals("$name sample", c["sample"], result.decimalSample)
            assertEquals("$name mark", (c["mark"] as String?)?.first(), result.decimalMark)
            assertEquals("$name ambiguous", c["sample"] != null, result.decimalAmbiguous)
            assertEquals("$name amounts", c["amounts"], result.rows.map { text(it.amount) })
            assertEquals("$name prices", c["prices"], result.rows.map { text(it.price) })
            val preview = CsvImportMapping.preview(result, emptySet()) { _, _ -> null }
            assertEquals("$name preview sample", result.decimalSample, preview.decimalSample)
            assertEquals("$name preview mark", result.decimalMark, preview.decimalMark)
            assertEquals("$name preview columns", result.columns, preview.columns)
        }
        // Auswahl der Vorschau: beide Lesarten der mehrdeutigen Zahl
        assertEquals("42000", text(CsvImportParser.sampleValue("42,000", '.')))
        assertEquals("42", text(CsvImportParser.sampleValue("42,000", ',')))
        assertEquals("42", text(CsvImportParser.sampleValue("42.000", '.')))
        assertEquals("42000", text(CsvImportParser.sampleValue("42.000", ',')))
    }

    /** Zeichensatz: UTF-8 (BOM weg), sonst Windows-1252; erkannte Spalten. */
    @Test
    fun decode() {
        data["decode"].list().map { it.obj() }.forEach { c ->
            val name = c["name"] as String
            val hex = c["hex"] as String
            val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val text = CsvImportParser.decode(bytes)
            assertEquals("$name text", c["text"], text)
            val columns = c["columns"] ?: return@forEach
            val result = CsvImportParser.parse(text, "UTC")
            assertEquals("$name columns", columns, result.columns)
            c["rows"].list().map { it.obj() }.zip(result.rows).forEach { (e, r) ->
                assertEquals("$name amount", e["amount"], text(r.amount))
                assertEquals("$name price", e["price"], text(r.price))
                assertEquals("$name quote", e["quote"], r.quote)
                assertEquals("$name fee", e["fee"], text(r.fee))
                assertEquals("$name feeUnit", e["feeUnit"], r.feeUnit)
            }
            assertEquals("$name row count", c["rows"].list().size, result.rows.size)
        }
    }

    /** Vorschau: Hinweis bei Kauf/Verkauf gegen Stablecoin (als Tausch gebucht) und die zugeordneten Transaktionen. */
    @Test
    fun stableSwaps() {
        data["stableSwaps"].list().map { it.obj() }.forEach { c ->
            val name = c["file"] as String
            val preview = CsvImportMapping.preview(parse(name), emptySet()) { _, _ -> null }
            assertEquals("$name stableSwaps", c["stableSwaps"], preview.stableSwaps)
            c["txs"].list().map { it.obj() }.forEach { e ->
                val tx = preview.transactions.first { it.line == e["line"].num()!!.toInt() }
                val m = "$name line ${tx.line}"
                assertEquals("$m type", e["type"], tx.type.name)
                assertEquals("$m coin", e["coin"], tx.coin)
                assertEquals("$m toCoin", e["toCoin"], tx.toCoin)
                assertEquals("$m toAmount", e["toAmount"], text(tx.toAmount))
                assertEquals("$m swapValueUsdt", e["swapValueUsdt"], text(tx.swapValueUsdt))
                // Gebühr (Build 48; fehlt im Fixture = nicht geprüft)
                if ("fee" in e) assertEquals("$m fee", e["fee"], text(tx.fee))
                if ("feeUnit" in e) assertEquals("$m feeUnit", e["feeUnit"], tx.feeUnit?.name)
                if ("feeCoin" in e) assertEquals("$m feeCoin", e["feeCoin"], tx.feeCoin)
                if ("feeCoinAmount" in e) assertEquals("$m feeCoinAmount", e["feeCoinAmount"], text(tx.feeCoinAmount))
            }
            // Bestand nach allen Transaktionen der Vorschau (z. B. Kraken-Saldo)
            (c["balances"] as Map<String, Any?>?)?.let { balances ->
                val trades = preview.transactions.mapIndexed { i, t ->
                    PortfolioTrade(
                        id = i + 1L, coin = t.coin, type = t.type, amount = t.amount, priceUsdt = t.priceUsdt, time = t.time,
                        fee = t.fee, feeUnit = t.feeUnit, feeCoin = t.feeCoin, feeCoinAmount = t.feeCoinAmount,
                        toCoin = t.toCoin, toAmount = t.toAmount, swapValueUsdt = t.swapValueUsdt, imported = true,
                        feeRow = t.feeRow, interestIncome = t.interest,
                    )
                }
                val held = PortfolioCalculator.holdingsAt(trades)
                balances.forEach { (coin, v) -> assertEquals("$name balance $coin", v, text(held[coin])) }
            }
        }
        // Schon importiert (alte Schlüssel als Kauf/Verkauf): kein Hinweis
        val rows = parse("generic_stable").rows
        val all = rows.flatMap { it.legacyKeys + it.key }.toSet()
        assertEquals(false, CsvImportMapping.preview(parse("generic_stable"), all) { _, _ -> null }.stableSwaps)
    }

    /** Tageskurs-Tag des Imports = Ortstag des Geräts wie im Steuer-Export ([CsvImportResult.fxZone]). */
    @Test
    fun fxDay() {
        val c = data["fxDay"].obj()
        val file = data["files"].list().map { it.obj() }.first { it["name"] == c["file"] }
        val deviceZone = c["deviceZone"] as String
        val result = CsvImportParser.parse(file["csv"] as String, deviceZone)
        assertEquals(c["zone"], result.zone)
        assertEquals(deviceZone, result.fxZone)
        val rates = c["rates"].obj().mapValues { (_, byDay) ->
            byDay.obj().entries.associate { LocalDate.parse(it.key).toEpochDay() to BigDecimal(it.value as String) }
        }
        val preview = CsvImportMapping.preview(result, emptySet()) { cur, day -> rates[cur]?.get(day) }
        val tx = preview.transactions.first { it.line == c["line"].num()!!.toInt() }
        assertEquals(c["priceUsdt"], text(tx.priceUsdt))
        // Derselbe Tag wie im Export
        assertEquals(
            TaxExport.localDate(tx.time, java.time.ZoneId.of(deviceZone)).toEpochDay(),
            CsvImport.epochDay(tx.time, result.fxZone),
        )
    }

    /** Fiat-Liste des Imports = Währungen mit Devisenkurs (eine Quelle der Wahrheit, Build 48 doppelt). */
    @Test
    fun fiatListMatchesFxCurrencies() {
        assertEquals(FxRateSource.CURRENCIES.toSet(), CsvImport.FX_FIAT)
        assertEquals(FxRateSource.CURRENCIES.size, CsvImport.FX_FIAT.size)
    }

    @Test
    fun epochDay() {
        data["epochDay"].list().map { it.obj() }.forEach { c ->
            assertEquals("epochDay: $c", LocalDate.parse(c["expected"] as String).toEpochDay(), CsvImport.epochDay(millis(c["time"])!!))
        }
    }

    @Test
    fun occurrenceAndTokenizer() {
        assertEquals(listOf("a", "b", "a#2", "a#3"), CsvImport.withOccurrence(listOf("a", "b", "a", "a")))
        // «""» in Anführungszeichen = «"»
        val cells = CsvImportParser.tokenize("x,\"a \"\"b\"\", c\",y\n", ',').single().cells
        assertEquals(listOf("x", "a \"b\", c", "y"), cells)
        assertNull(CsvImportParser.parseTime("2024-02-29T25:00:00Z"))
    }

    /**
     * Build 93: Fiat-Gebühr in einer anderen Währung als die Preiswährung zum Tageskurs ihrer Währung, ohne Kurs
     * missingFx (nie still weg); Tausch aus zwei Seiten ohne Fiat-Wert mit EUR-Gebühr ebenso; Belohnung mit Fiat-Betrag
     * bekommt Preis = Betrag / Menge, der Schlüssel bis Build 92 (ohne Preis) erkennt den früheren Import weiter.
     */
    @Test
    fun fiatFeeReward() {
        val c = data["fiatFeeReward"].obj()
        val rates = c["rates"].obj().mapValues { (_, byDay) ->
            byDay.obj().entries.associate { LocalDate.parse(it.key).toEpochDay() to BigDecimal(it.value as String) }
        }
        val result = parse(c["file"] as String)
        val fx: (String, Long) -> BigDecimal? = { cur, day -> rates[cur]?.get(day) }
        val priced = CsvImport.priceInUsdt(result.rows, fx = fx)
        val expected = c["priced"].list().map { it.obj() }
        assertEquals(expected.map { it["line"].num()!!.toInt() }, priced.map { it.row.line })
        expected.zip(priced).forEach { (e, p) ->
            val m = "fiatFeeReward line ${p.row.line}"
            assertEquals("$m priceUsdt", e["priceUsdt"], text(p.priceUsdt))
            assertEquals("$m feeUsdt", e["feeUsdt"], text(p.feeUsdt))
            assertEquals("$m needsFx", e["needsFx"], p.needsFx)
        }
        val preview = CsvImportMapping.preview(result, emptySet(), fx = fx)
        assertEquals(c["missingFx"].list().map { it.num()!!.toInt() }, preview.missingFx)
        c["txs"].list().map { it.obj() }.forEach { e ->
            val line = e["line"].num()!!.toInt()
            val tx = preview.transactions.single { it.line == line }
            assertEquals("tx $line fee", e["fee"], text(tx.fee))
            assertEquals("tx $line feeUnit", e["feeUnit"], tx.feeUnit?.name)
            assertEquals("tx $line feeCoin", e["feeCoin"], tx.feeCoin)
        }
        // Tageskurse: Gebühr in jeder Fiat-Währung (GBP neben der Preiswährung USD)
        assertEquals(c["fxNeeds"].list(), CsvImportMapping.fxNeeds(result.rows).keys.sorted())
        // Belohnung mit Preis: früherer Schlüssel ohne Preis bleibt erkannt
        val reward = result.rows.single { it.line == c["legacyLine"].num()!!.toInt() }
        assertEquals(c["key"], reward.key)
        assertEquals(true, c["legacyKey"] in reward.legacyKeys)
        assertEquals(listOf(reward), CsvImport.splitDuplicates(result.rows, setOf(c["legacyKey"] as String)).duplicates)
    }
}
