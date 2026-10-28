package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxLine
import com.cryptochecker.app.domain.portfolio.TaxSummaryTexts
import com.cryptochecker.app.domain.portfolio.TaxWealthRow
import com.cryptochecker.app.domain.portfolio.csvimport.CsvRestoreState
import com.cryptochecker.app.domain.portfolio.csvimport.CsvStoredTransfer
import com.cryptochecker.app.domain.portfolio.wealthCoinNoteBelowValue
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus PortfolioBackupMirrorRetryTest

/**
 * Beiseitegelegte Kopie der Systemsicherung: genau ein zweiter Versuch, danach gelöscht — nie dauerhaft Klartext.
 */
class PortfolioBackupMirrorRetryTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("mirror-retry").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun setAsideCopiesAreRetriedOnceThenDeleted() {
        val mirror = File(dir, PortfolioBackupMirror.FILE_NAME).apply { writeText("{ kaputt") }
        val first = PortfolioBackupMirror.setAside(mirror)
        File(dir, PortfolioBackupMirror.FILE_NAME).writeText("{ auch kaputt")
        val second = PortfolioBackupMirror.setAside(File(dir, PortfolioBackupMirror.FILE_NAME))
        // Fremde Dateien bleiben unberührt
        val other = File(dir, "portfolio_system_backup.json.tmp").apply { writeText("x") }
        assertEquals(listOf(first, second).map { it!!.name }.sorted(), PortfolioBackupMirror.failedCopies(dir).map { it.name })

        val tried = mutableListOf<String>()
        val lost = PortfolioBackupMirror.retryFailed(dir) { file ->
            tried += file.name
            if (file.readText().contains("auch")) error("nicht lesbar")
        }

        assertEquals(2, tried.size)
        assertEquals(1, lost)
        assertTrue(PortfolioBackupMirror.failedCopies(dir).isEmpty())
        assertTrue(other.exists())
        // Ein weiterer Start versucht nichts mehr
        assertEquals(0, PortfolioBackupMirror.retryFailed(dir) { error("kein Versuch erwartet") })
    }

    @Test
    fun successfulRetryCountsNothingAsLost() {
        File(dir, PortfolioBackupMirror.FAILED_FILE_NAME).writeText(PortfolioBackupMirror.encode(emptyList(), emptyList()))
        assertEquals(0, PortfolioBackupMirror.retryFailed(dir) { PortfolioBackupMirror.parse(it.readText()) })
        assertFalse(File(dir, PortfolioBackupMirror.FAILED_FILE_NAME).exists())
        assertEquals(0, PortfolioBackupMirror.retryFailed(null) { error("ohne Ordner nichts") })
    }
}

// ---- aus PortfolioCoinDeletionTest

/**
 * «Coin löschen» entfernt auch die Gebühr in diesem Coin bei Transaktionen anderer Coins (sonst erschiene
 * der Coin als Position «mehr abgegeben als erfasst»). Wie iOS (`PortfolioDeleteCoinFeeTests`).
 */
class PortfolioCoinDeletionTest {

    private val buyBnb = PortfolioTxEntity(id = 1, coin = "BNB", type = PortfolioTxType.BUY, amount = BigDecimal.ONE, time = 1L)
    private val transfer = PortfolioTxEntity(
        id = 2, coin = "ETH", type = PortfolioTxType.TRANSFER, amount = BigDecimal.ONE, time = 2L,
        feeCoin = "bnb", feeCoinAmount = BigDecimal("0.01"), feeCoinPriceUsdt = BigDecimal(600),
    )
    private val buyEth = PortfolioTxEntity(id = 3, coin = "ETH", type = PortfolioTxType.BUY, amount = BigDecimal(2), time = 3L)
    private val swapToBnb = PortfolioTxEntity(
        id = 4, coin = "ETH", type = PortfolioTxType.SWAP, amount = BigDecimal.ONE, time = 4L, toCoin = "BNB", toAmount = BigDecimal(3),
    )

    @Test
    fun zaehltGeloeschteUndGebuehrBezuegeGetrennt() {
        val all = listOf(buyBnb, transfer, buyEth, swapToBnb)
        // Gelöscht: Kauf BNB und Tausch in BNB; nur die Gebühr verliert die ETH-Umbuchung
        assertEquals(2 to 1, PortfolioCoinDeletion.counts(all, "bnb"))
        assertEquals(3 to 0, PortfolioCoinDeletion.counts(all, "ETH"))
    }

    @Test
    fun gebuehrBezugNurBeiAnderemCoin() {
        assertTrue(PortfolioCoinDeletion.paysFeeIn(transfer, "BNB"))
        assertFalse(PortfolioCoinDeletion.paysFeeIn(transfer, "ETH"))
        assertFalse(PortfolioCoinDeletion.paysFeeIn(buyEth, "BNB"))
        // Ohne Menge keine Gebühr
        assertFalse(PortfolioCoinDeletion.paysFeeIn(transfer.copy(feeCoinAmount = null), "BNB"))
    }

    @Test
    fun ohneGebuehrBleibtDerRestUnveraendert() {
        val clean = PortfolioCoinDeletion.withoutThirdFee(transfer)
        assertNull(clean.feeCoin)
        assertNull(clean.feeCoinAmount)
        assertNull(clean.feeCoinPriceUsdt)
        assertEquals(transfer.copy(feeCoin = null, feeCoinAmount = null, feeCoinPriceUsdt = null), clean)
        assertEquals(transfer.id, clean.id)
    }
}

// ---- aus PortfolioCsvLinksTest

/**
 * CSV-Import: geänderte gespeicherte Seiten verknüpfter Umbuchungen ([linkedStoredEntities], geschrieben in EINER
 * Datenbank-Transaktion von [insertImportedLinked]) und Hinweis «unvollständig» unter einem Teilwert der CH-Steuerkarte
 * (iOS `PortfolioWriteFailureTests`).
 */
class PortfolioCsvLinksTest {

    private fun stored(id: Long, linked: String? = null) = PortfolioTxEntity(
        id = id, coin = "BTC", type = PortfolioTxType.WITHDRAWAL, amount = BigDecimal("1"), time = 100L,
        importKey = "k$id", importBatch = "old", linkedImportKey = linked,
    )

    private fun update(id: Long) = CsvStoredTransfer(
        id = id, amount = BigDecimal("0.9"), fee = BigDecimal("0.1"), feeUnit = PortfolioFeeUnit.COIN, feeCoin = null,
        feeCoinAmount = null, time = 200L, transferFrom = "Binance", transferTo = "Kraken", importKey = "k$id",
        linkedImportKey = "d$id",
        restoreState = CsvRestoreState(
            type = PortfolioTxType.WITHDRAWAL, amount = BigDecimal("1"), priceUsdt = null, time = 100L, fee = null,
            feeUnit = null, feeCoin = null, feeCoinAmount = null, feeCoinPrice = null, transferFrom = null,
            transferTo = null, acquiredTime = null, exchange = null, importKey = "k$id", importBatch = "old", absorbed = null,
        ),
    )

    @Test
    fun linksOnlyStoredUnlinkedSides() {
        val current = listOf(stored(1), stored(2, linked = "x"))
        // id 3 inzwischen gelöscht, id 2 schon verknüpft: nur id 1 wird geändert (alle zusammen in einer Transaktion)
        val changed = linkedStoredEntities(current, listOf(update(1), update(2), update(3)), batch = "run")
        assertEquals(listOf(1L), changed.map { it.id })
        val tx = changed.single()
        assertEquals(PortfolioTxType.TRANSFER, tx.type)
        assertEquals(BigDecimal("0.9"), tx.amount)
        assertEquals("run", tx.importBatch)
        assertEquals("d1", tx.linkedImportKey)
        assertEquals("old", tx.importRestoreState?.importBatch)
        assertEquals(PortfolioFeeUnit.COIN.name, tx.feeUnit)
        assertNull(tx.priceUsdt)
    }

    /** Teilwert: Wert und darunter «unvollständig – Grund»; ohne Wert steht der Hinweis an Stelle des Werts. */
    @Test
    fun wealthNoteBelowPartialValue() {
        val texts = TaxSummaryTexts(
            title = "T", disclaimer = "D", item = "Item", usdt = "USDT", target = "CHF", note = "Note", orientation = "O",
            labels = TaxLine.entries.associateWith { it.name }, below = "below", reached = "reached", unknown = "unknown",
            incomplete = "incomplete", noFx = "nofx", wealthMissing = "nc", wealthAddressUnobserved = "unobserved",
        )
        val partial = TaxWealthRow(
            "BTC", BigDecimal("0.1"), BigDecimal("60000"), BigDecimal("6000"), BigDecimal("4800"),
            addressUnobserved = true, incomplete = true,
        )
        assertEquals("incomplete – unobserved", texts.wealthCoinNoteBelowValue(partial, hasValue = true))
        assertNull(texts.wealthCoinNoteBelowValue(partial, hasValue = false))
        val full = TaxWealthRow("BTC", BigDecimal("0.1"), BigDecimal("60000"), BigDecimal("6000"), BigDecimal("4800"))
        assertNull(texts.wealthCoinNoteBelowValue(full, hasValue = true))
    }
}

// ---- aus PortfolioStorageLockTest

/** [PortfolioStorageLock]: Schreiben sperren, solange das gespeicherte Portfolio nur leer im Arbeitsspeicher steht. */
class PortfolioStorageLockTest {

    @Test
    fun portfolioKeptBlocksWrites() {
        val kept = setOf("cryptochecker_portfolio.db")
        assertTrue(PortfolioStorageLock.isLocked(kept))
        assertTrue(PortfolioStorageLock.blocksWrites(kept))
    }

    @Test
    fun nothingKeptAllowsWrites() {
        assertFalse(PortfolioStorageLock.blocksWrites(emptySet()))
    }

    @Test
    fun onlyWatchlistKeptDoesNotBlockPortfolio() {
        assertFalse(PortfolioStorageLock.blocksWrites(setOf("cryptochecker.db")))
    }

    @Test
    fun ensureWritableThrowsOnlyWhileLocked() {
        assertThrows(PortfolioStorageLockedException::class.java) { ensurePortfolioWritable(setOf("cryptochecker_portfolio.db")) }
        ensurePortfolioWritable(emptySet())
        ensurePortfolioWritable(setOf("cryptochecker.db"))
    }

    /**
     * Adress-Schreibwege des Nutzers prüfen die Sperre (in der Transaktion), Guthaben-Abrufe und automatische
     * Umstellungen nicht — sie dürfen im Hintergrund weiterlaufen. Prüft die Quelle, weil das Repository Room braucht.
     */
    @Test
    fun addressUserWritesAreLockedBackgroundUpdatesAreNot() {
        val source = repositorySource()
        val locked = listOf(
            "addWatchedAddress", "removeWatchedAddress", "setWatchedAddressLabel", "setWatchedAddressCostLots",
            "replaceWatchedAddresses", "mergeWatchedAddresses", "changeWatched",
        )
        for (name in locked) assertTrue("$name prüft die Sperre nicht", body(source, name).contains("ensureWritable()"))
        assertFalse(body(source, "updateWatchedAddressBalance").contains("ensureWritable()"))
        assertFalse(source("WatchedAddressBalanceWriter.kt").contains("ensure"))
        // Nutzer-Änderungen über changeWatched (Standard userEdit = true), automatische ausdrücklich ohne Sperre
        assertTrue(body(source, "setWatchedAddressOverlapShowOnly").contains("userEdit = false"))
        for (name in listOf("setWatchedAddressCountTokens", "setWatchedAddressNetworks", "setWatchedAddressCountInPortfolio")) {
            assertFalse("$name darf die Sperre nicht umgehen", body(source, name).contains("userEdit = false"))
        }
        // «Rückgängig» nach «Adresse entfernen» ist ebenfalls ein Schreibweg des Nutzers
        assertTrue(source("PortfolioRepositoryUndo.kt").contains("ensurePortfolioWritable()"))
    }

    /** Text von `fun name(` bis zur nächsten Funktion. */
    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name nicht gefunden", start >= 0)
        val next = source.indexOf("\n    suspend fun ", start + 1).let { if (it < 0) source.length else it }
        val nextPrivate = source.indexOf("\n    private ", start + 1).let { if (it < 0) source.length else it }
        return source.substring(start, minOf(next, nextPrivate))
    }

    private fun repositorySource(): String = source("PortfolioRepository.kt")

    private fun source(name: String): String {
        val path = "src/main/java/com/cryptochecker/app/data/portfolio/$name"
        val file = listOf("", "app/", "android/app/", "../data/", "data/", "android/data/").map { File(it + path) }.firstOrNull { it.isFile }
        assertNotNull("$name nicht gefunden (Arbeitsverzeichnis?)", file)
        return file!!.readText()
    }
}

// ---- aus PortfolioTxCleanupTest

/**
 * Mengen mit mehr als 18 Nachkommastellen werden vor der Prüfung gerundet (eine Menge unter 10⁻¹⁸ ist
 * ungültig statt als 0 gespeichert), und Rechnen ([PortfolioDecimal.amount]) und Speichern ([PortfolioDecimal.text])
 * runden gleich (halbe auf gerade). Wie iOS (`PortfolioDecimalRoundingTests`).
 */
class PortfolioTxCleanupTest {

    private fun tx(amount: String, fee: String? = null, toAmount: String? = null, type: PortfolioTxType = PortfolioTxType.BUY) =
        PortfolioTxEntity(
            id = 0, coin = "eth", type = type, amount = BigDecimal(amount), time = 1_000L,
            fee = fee?.let { BigDecimal(it) }, feeUnit = fee?.let { PortfolioFeeUnit.COIN.name },
            toCoin = toAmount?.let { "BTC" }, toAmount = toAmount?.let { BigDecimal(it) },
        )

    @Test
    fun mengeUnterEinemWeiWirdNullUndAbgelehnt() {
        val clean = cleanForSave(tx("0.0000000000000000004"))
        // PortfolioRepository.save prüft genau diese bereinigte Menge (> 0)
        assertEquals(0, clean.amount.signum())
    }

    @Test
    fun mengeWirdAufGespeicherteStellenGerundet() {
        val clean = cleanForSave(tx("1.0000000000000000005"))
        assertEquals(0, clean.amount.compareTo(BigDecimal.ONE))
        // Was geprüft wurde, wird auch gespeichert
        assertEquals(PortfolioDecimal.text(clean.amount), PortfolioDecimal.text(BigDecimal("1.0000000000000000005")))
    }

    @Test
    fun gebuehrUndErhalteneMengeUnterEinemWeiFallenWeg() {
        val clean = cleanForSave(tx("1", fee = "0.0000000000000000001"))
        assertNull(clean.fee)
        assertNull(clean.feeUnit)
        val swap = runCatching { cleanForSave(tx("1", toAmount = "0.0000000000000000001", type = PortfolioTxType.SWAP)) }
        assertTrue("Tausch mit erhaltener Menge 0 muss ungültig sein", swap.isFailure)
    }

    @Test
    fun eineRundungsregelFuerRechnenUndSpeichern() {
        for (v in listOf("0.0000000000000000015", "0.0000000000000000025", "2.0000000000000000035", "0.0000000000000000005")) {
            val d = BigDecimal(v)
            assertEquals(v, 0, PortfolioDecimal.amount(d).compareTo(BigDecimal(PortfolioDecimal.text(d))))
        }
        // Halbe auf gerade
        assertEquals("0.000000000000000002", PortfolioDecimal.text(BigDecimal("0.0000000000000000015")))
        assertEquals("0.000000000000000002", PortfolioDecimal.text(BigDecimal("0.0000000000000000025")))
        assertEquals("0", PortfolioDecimal.text(BigDecimal("0.0000000000000000005")))
    }
}
