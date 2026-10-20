package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.LegacyStorageMigration
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImport
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportMapping
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportParser
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportRow
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportKind
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportTx
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportedRef
import com.cryptochecker.app.domain.portfolio.csvimport.CsvRestoreState
import com.cryptochecker.app.domain.portfolio.csvimport.CsvStoredMove
import com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferLink
import com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferMatch
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gemeinsame Testfälle `csv_transfers.json` (iOS: `CsvTransferMatchTests.swift`). */
class CsvTransferMatchParityTest {

    private val data = ParityJson.load("csv_transfers.json")

    private fun millis(v: Any?): Long = Instant.parse(v as String).toEpochMilli()
    private fun dec(v: Any?): BigDecimal? = (v as String?)?.let { BigDecimal(it) }
    private fun text(v: BigDecimal?): String? = v?.let { CsvImport.text(it) }
    private fun unit(v: Any?): PortfolioFeeUnit? = (v as String?)?.let { PortfolioFeeUnit.valueOf(it) }

    private fun tx(o: Map<String, Any?>) = CsvImportTx(
        line = o["line"].num()!!.toInt(),
        coin = o["coin"] as String,
        type = PortfolioTxType.valueOf(o["type"] as String),
        amount = dec(o["amount"])!!,
        priceUsdt = null,
        time = millis(o["time"]),
        fee = dec(o["fee"]),
        feeUnit = unit(o["feeUnit"]),
        feeCoin = o["feeCoin"] as String?,
        feeCoinAmount = dec(o["feeCoinAmount"]),
        note = null,
        importKey = o["key"] as String,
    )

    private fun stored(o: Map<String, Any?>) = CsvStoredMove(
        id = o["id"].num()!!.toLong(),
        coin = o["coin"] as String,
        type = PortfolioTxType.valueOf(o["type"] as String),
        amount = dec(o["amount"])!!,
        fee = dec(o["fee"]),
        feeUnit = unit(o["feeUnit"]),
        feeCoin = o["feeCoin"] as String?,
        feeCoinAmount = dec(o["feeCoinAmount"]),
        time = millis(o["time"]),
        importKey = o["importKey"] as String,
        transferFrom = o["transferFrom"] as String?,
        transferTo = o["transferTo"] as String?,
        priceUsdt = dec(o["priceUsdt"]),
        acquiredTime = o["acquiredTime"]?.num()?.toLong(),
        exchange = o["exchange"] as String?,
        importBatch = o["importBatch"] as String?,
        linkedImportKey = o["linkedImportKey"] as String?,
    )

    /** Zustand aus dem Fixture (Format [CsvRestoreState]) — über den Text, wie ihn die Datenbank speichert. */
    private fun restoreState(v: Any?): CsvRestoreState? = v?.let { CsvRestoreState.decode(JSONObject(it.obj()).toString()) }

    /** Zeile für «Import rückgängig». */
    private fun ref(o: Map<String, Any?>) = CsvImportedRef(o["id"].num()!!.toLong(), o["importBatch"] as String?, restoreState(o["restoreState"]))

    private fun cases() = data["cases"].list().map { it.obj() }

    private fun linksOf(c: Map<String, Any?>): List<CsvTransferLink> = CsvTransferMatch.candidates(
        c["txs"].list().map { tx(it.obj()) }, c["place"] as String?, c["stored"].list().map { stored(it.obj()) },
    )

    private fun assertLinks(m: String, expected: List<Map<String, Any?>>, links: List<CsvTransferLink>) {
        assertEquals("$m keys", expected.map { it["key"] }, links.map { it.key })
        expected.zip(links).forEach { (e, l) ->
            val n = "$m ${l.key}"
            assertEquals("$n id", CsvTransferMatch.linkId(l.withdrawalKey, l.depositKey), l.key)
            assertEquals("$n withdrawalKey", e["withdrawalKey"], l.withdrawalKey)
            assertEquals("$n depositKey", e["depositKey"], l.depositKey)
            assertEquals("$n withdrawalId", e["withdrawalId"]?.num()?.toLong(), l.withdrawalId)
            assertEquals("$n depositId", e["depositId"]?.num()?.toLong(), l.depositId)
            assertEquals("$n sent", e["sent"], text(l.sent))
            assertEquals("$n received", e["received"], text(l.received))
            assertEquals("$n fee", e["fee"], text(l.fee))
            assertEquals("$n feeUnit", e["feeUnit"], l.feeUnit?.name)
            assertEquals("$n feeCoin", e["feeCoin"], l.feeCoin)
            assertEquals("$n feeCoinAmount", e["feeCoinAmount"], text(l.feeCoinAmount))
            assertEquals("$n from", e["from"], l.from)
            assertEquals("$n to", e["to"], l.to)
            assertEquals("$n unambiguous", e["unambiguous"], l.unambiguous)
        }
    }

    @Test
    fun candidatesAndDefaultChoice() {
        cases().forEach { c ->
            val m = c["name"] as String
            val links = linksOf(c)
            assertLinks(m, c["links"].list().map { it.obj() }, links)
            assertEquals("$m default", c["default"].list().map { it as String }.toSet(), CsvTransferMatch.defaultChoice(links))
        }
    }

    @Test
    fun toggleKeepsOneLinkPerSide() {
        cases().filter { it["toggle"] != null }.forEach { c ->
            val links = linksOf(c)
            c["toggle"].list().map { it.obj() }.forEach { t ->
                val chosen = t["chosen"].list().map { it as String }.toSet()
                assertEquals("${c["name"]} toggle $t", t["expected"].list().map { it as String }.toSet(),
                    CsvTransferMatch.toggle(links, chosen, t["key"] as String, t["on"] == true))
            }
        }
    }

    @Test
    fun applyMergesNewSidesAndUpdatesStoredOnes() {
        cases().filter { it["apply"] != null }.forEach { c ->
            val m = c["name"] as String
            val links = linksOf(c)
            val a = c["apply"].obj()
            val chosen = a["chosen"]?.list()?.map { it as String }?.toSet() ?: CsvTransferMatch.defaultChoice(links)
            val plan = CsvTransferMatch.apply(c["txs"].list().map { tx(it.obj()) }, links, chosen)
            val txs = a["transactions"].list().map { it.obj() }
            assertEquals("$m transactions", txs.map { it["key"] }, plan.transactions.map { it.importKey })
            txs.zip(plan.transactions).forEach { (e, t) ->
                assertEquals("$m ${t.importKey} linked", e["linkedKey"], t.linkedImportKey)
                assertEquals("$m ${t.importKey} type", e["type"], t.type.name)
                assertEquals("$m ${t.importKey} amount", e["amount"], text(t.amount))
                assertEquals("$m ${t.importKey} fee", e["fee"], text(t.fee))
                assertEquals("$m ${t.importKey} feeUnit", e["feeUnit"], t.feeUnit?.name)
                assertEquals("$m ${t.importKey} from", e["transferFrom"], t.transferFrom)
                assertEquals("$m ${t.importKey} to", e["transferTo"], t.transferTo)
                e["time"]?.let { assertEquals("$m ${t.importKey} time", millis(it), t.time) }
            }
            val updates = a["storedUpdates"].list().map { it.obj() }
            assertEquals("$m updates", updates.map { it["id"].num()!!.toLong() }, plan.storedUpdates.map { it.id })
            updates.zip(plan.storedUpdates).forEach { (e, u) ->
                assertEquals("$m ${u.id} amount", e["amount"], text(u.amount))
                assertEquals("$m ${u.id} fee", e["fee"], text(u.fee))
                assertEquals("$m ${u.id} feeUnit", e["feeUnit"], u.feeUnit?.name)
                assertEquals("$m ${u.id} feeCoin", e["feeCoin"], u.feeCoin)
                assertEquals("$m ${u.id} feeCoinAmount", e["feeCoinAmount"], text(u.feeCoinAmount))
                assertEquals("$m ${u.id} time", millis(e["time"]), u.time)
                assertEquals("$m ${u.id} from", e["transferFrom"], u.transferFrom)
                assertEquals("$m ${u.id} to", e["transferTo"], u.transferTo)
                assertEquals("$m ${u.id} key", e["importKey"], u.importKey)
                assertEquals("$m ${u.id} linked", e["linkedImportKey"], u.linkedImportKey)
                // Zustand für «Import rückgängig» im gespeicherten Format (Reihenfolge der Schlüssel egal)
                assertEquals("$m ${u.id} restoreState", e["restoreState"], ParityJson.parse(u.restoreState.encode()))
                // Zustand davor kommt unverändert zurück (gespeicherte Seite und aufgegangene neue Zeile)
                val before = stored(c["stored"].list().map { it.obj() }.first { it["id"].num()!!.toLong() == u.id })
                val o = CsvRestoreState.decode(u.restoreState.encode())!!
                assertEquals("$m ${u.id} origin", listOf(before.type, before.amount, before.priceUsdt, before.time, before.fee,
                    before.feeUnit, before.feeCoin, before.feeCoinAmount, before.transferFrom, before.transferTo, before.acquiredTime,
                    before.exchange, before.importKey, before.importBatch),
                    listOf(o.type, o.amount, o.priceUsdt, o.time, o.fee, o.feeUnit, o.feeCoin, o.feeCoinAmount, o.transferFrom,
                        o.transferTo, o.acquiredTime, o.exchange, o.importKey, o.importBatch))
                val link = links.first { it.withdrawalId == u.id || it.depositId == u.id }
                val n = link.absorbed!!
                val r = o.absorbed!!
                assertEquals("$m ${u.id} absorbed", listOf(n.type, n.amount, n.priceUsdt, n.time, n.fee, n.feeUnit, n.feeCoin,
                    n.feeCoinAmount, n.importKey), listOf(r.type, r.amount, r.priceUsdt, r.time, r.fee, r.feeUnit, r.feeCoin,
                    r.feeCoinAmount, r.importKey))
            }
        }
    }

    /**
     * Verknüpfte Umbuchung durch FIFO je Wallet: Menge = was ankommt, die Gebühr im Coin geht zusätzlich von der Quelle ab
     * — Bestand je Wallet stimmt (nicht nur gesamt).
     */
    @Test
    fun linkedTransferMovesReceivedAmountBetweenWallets() {
        cases().filter { it["fifoWallet"] != null }.forEach { c ->
            val m = c["name"] as String
            val f = c["fifoWallet"].obj()
            val links = linksOf(c)
            val plan = CsvTransferMatch.apply(c["txs"].list().map { tx(it.obj()) }, links, CsvTransferMatch.defaultChoice(links))
            val base = f["trades"].list().map { it.obj() }.map { o ->
                PortfolioTrade(o["id"].num()!!.toLong(), o["coin"] as String, PortfolioTxType.valueOf(o["type"] as String),
                    dec(o["amount"])!!, dec(o["priceUsdt"]), millis(o["time"]), exchange = o["exchange"] as String?)
            }
            val moves = plan.storedUpdates.map { u ->
                PortfolioTrade(u.id, links.first().coin, PortfolioTxType.TRANSFER,
                    u.amount, null, u.time, fee = u.fee, feeUnit = u.feeUnit, feeCoin = u.feeCoin, feeCoinAmount = u.feeCoinAmount,
                    transferFrom = u.transferFrom, transferTo = u.transferTo)
            }
            val ledger = PortfolioCalculator.ledger(links.first().coin, base + moves, null, CostMethod.FIFO_WALLET)
            val wallets = ledger.lots.groupBy { it.wallet }.mapValues { (_, l) -> text(l.fold(BigDecimal.ZERO) { a, x -> a + x.amount }) }
            assertEquals("$m wallets", f["wallets"].obj(), wallets)
        }
    }

    /** «Import rückgängig»: verknüpfte Seite des späteren Laufs zurücksetzen, sonst löschen; früherer Lauf nimmt sie mit. */
    @Test
    fun undoDissolvesLinksOfTheUndoneImport() {
        data["undo"].list().map { it.obj() }.forEach { c ->
            val plan = CsvTransferMatch.undoPlan(c["rows"].list().map { ref(it.obj()) }, c["batch"] as String)
            assertEquals("undo $c delete", c["delete"].list().map { it.num()!!.toLong() }.toSet(), plan.delete)
            assertEquals("undo $c revert", c["revert"].list().map { it.num()!!.toLong() }.toSet(), plan.revert.keys)
            assertEquals("undo $c detach", c["detach"].list().map { it.num()!!.toLong() }.toSet(), plan.detach.keys)
            c["absorbed"]?.obj()?.let { e ->
                val a = plan.detach.getValue(e["id"].num()!!.toLong())
                assertEquals(e["type"], a.type.name)
                assertEquals(e["amount"], text(a.amount))
                assertEquals(millis(e["time"]), a.time)
                assertEquals(e["importKey"], a.importKey)
            }
            c["origin"]?.obj()?.let { e ->
                val o = plan.revert.getValue(e["id"].num()!!.toLong())
                assertEquals(e["type"], o.type.name)
                assertEquals(e["amount"], text(o.amount))
                assertEquals(millis(e["time"]), o.time)
                assertEquals(e["importKey"], o.importKey)
                assertEquals(e["importBatch"], o.importBatch)
            }
        }
    }

    @Test
    fun carryOverKeepsShownChoices() {
        data["carryOver"].list().map { it.obj() }.forEach { o ->
            val links = linksOf(cases().first { it["name"] == o["case"] })
            assertEquals("carryOver $o", o["expected"].list().map { it as String }.toSet(),
                CsvTransferMatch.carryOver(links, o["seen"].list().map { it as String }.toSet(), o["chosen"].list().map { it as String }.toSet()))
        }
    }

    /** Gespeicherte Zeile im Ablauf von [undoBothImportsInSequence]: Id, Lauf, Schlüssel, Zustand. */
    private data class Row(val id: Long, val importBatch: String?, val importKey: String?, val linkedImportKey: String?,
                           val restoreState: CsvRestoreState?)

    private fun row(o: Map<String, Any?>) = Row(o["id"].num()!!.toLong(), o["importBatch"] as String?, o["importKey"] as String?,
        o["linkedImportKey"] as String?, restoreState(o["restoreState"]))

    /** Beide Läufe nacheinander rückgängig (beide Reihenfolgen): Zustand nach jedem Schritt, am Ende nichts aus ihnen. */
    @Test
    fun undoBothImportsInSequence() {
        data["undoSequence"].list().map { it.obj() }.forEach { c ->
            var rows = c["rows"].list().map { row(it.obj()) }
            c["batches"].list().zip(c["after"].list()).forEach { (batch, after) ->
                val plan = CsvTransferMatch.undoPlan(rows.map { CsvImportedRef(it.id, it.importBatch, it.restoreState) }, batch as String)
                // Wie PortfolioRepository.undoImport: Verknüpfung und Zustand fallen weg
                rows = rows.mapNotNull { r ->
                    plan.revert[r.id]?.let { return@mapNotNull Row(r.id, it.importBatch, it.importKey, null, null) }
                    plan.detach[r.id]?.let { return@mapNotNull Row(r.id, r.importBatch, it.importKey, null, null) }
                    r.takeIf { it.id !in plan.delete }
                }
                assertEquals("sequence ${c["_comment"]} after $batch", after.list().map { row(it.obj()) }, rows)
            }
        }
    }

    /** Beide Seiten einer Verknüpfung gelten als importiert, auch vor Build 58 gekürzte lange Teile. */
    @Test
    fun linkedKeysCountAsImported() {
        val d = data["duplicates"].obj()
        val long = d["longKey"] as String
        val existing = d["existing"].list().map { it as String }.toSet() + LegacyStorageMigration.shortKey(long)
        fun row(key: String) = CsvImportRow(1, CsvImportKind.DEPOSIT, "Deposit", "BTC", BigDecimal.ONE, null, null, null, null, 0L, null, key)
        val known = d["known"].list().map { it as String } + long
        val split = CsvImport.splitDuplicates((known + d["unknown"].list().map { it as String }).map { row(it) }, existing)
        assertEquals(known, split.duplicates.map { it.key })
        assertEquals(d["unknown"].list(), split.fresh.map { it.key })
    }

    /** Verknüpft wird ohne Längengrenze: lange Schlüssel bleiben vollständig, kein Zustand geht verloren. */
    @Test
    fun longKeysLinkWithoutLimit() {
        val c = cases().first { it["name"] == "stored side with separators in its text and a long key both link" }
        val plan = CsvTransferMatch.apply(c["txs"].list().map { tx(it.obj()) }, linksOf(c), CsvTransferMatch.defaultChoice(linksOf(c)))
        val long = plan.storedUpdates.single { it.id == 13L }
        // Bis Build 57 nicht verknüpft (Teil über 98 Zeichen passte nicht in den gepackten Schlüssel)
        assertEquals(c["stored"].list().map { it.obj() }.first { it["id"] == 13.0 }["importKey"], long.importKey)
        assertTrue(long.importKey.length > 98)
        assertNull(plan.transactions.firstOrNull { it.importKey == "COINBASE|X11" })
    }

    /** Ende zu Ende: gespeicherte Binance-Auszahlung, danach der Coinbase-Bericht. */
    @Test
    fun endToEndAcrossFiles() {
        val e = data["endToEnd"].obj()
        fun preview(name: String) = CsvImportMapping.preview(
            CsvImportParser.parse(File(ParityJson.dir().parentFile, "csv/$name").readText(Charsets.UTF_8)), emptySet(),
        ) { _, _ -> null }
        val old = preview(e["storedFile"] as String).transactions.first { it.type.name == e["storedType"] }
        val stored = CsvStoredMove(1, old.coin, old.type, old.amount, old.fee, old.feeUnit, old.feeCoin, old.feeCoinAmount, old.time,
            old.importKey, null, null)
        val fresh = preview(e["importFile"] as String)
        val links = CsvTransferMatch.candidates(fresh.transactions, fresh.format.exchangeName, listOf(stored))
        assertLinks("endToEnd", e["links"].list().map { it.obj() }, links)
        val plan = CsvTransferMatch.apply(fresh.transactions, links, CsvTransferMatch.defaultChoice(links))
        assertEquals(fresh.transactions.size - 1, plan.transactions.size)
        assertEquals(listOf(1L), plan.storedUpdates.map { it.id })
        // Gespeichert erkennt der nächste Import beide Seiten als doppelt (importKey + linkedImportKey)
        val u = plan.storedUpdates.single()
        assertEquals(setOf(old.importKey, links.single().depositKey), setOf(u.importKey, u.linkedImportKey))
    }
}
