package com.cryptochecker.app.domain.portfolio.csvimport

import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressObservation
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Zuordnung importierter Zeilen zu Transaktionen ([CsvImportMapping]) — wie `CsvImportMappingTests.swift`. */
class CsvImportMappingTest {

    private val csv = "Date,Transaction Type,BTC Amount,BTC Price,Currency Pair,Fiat Amount (excl. fees),Fiat Currency,Fee,Fee Currency,Destination,Operation ID,Counterparty\r\n" +
        "2024-09-15T14:23:17Z,Sell,-0.02847593,92341.28,BTC/CHF,2629.45,CHF,0.00,CHF,CH00,op-0001,Relai\r\n" +
        "2024-07-22T09:41:06Z,Buy,0.00284516,95287.63,BTC/CHF,271.05,CHF,2.45,CHF,bc1q1,op-0002,Relai\r\n" +
        "2024-06-20T08:00:00Z,Withdrawal,0.00500000,,,,,0.00001000,BTC,bc1q3,op-0004,\r\n" +
        "2024-06-21T10:00:00Z,Referral bonus,0.00010000,60000.00,BTC/CHF,6.00,CHF,,CHF,bc1q4,op-0005,Relai\r\n" +
        "2024-06-22T10:00:00Z,Cancelled,0.00010000,60000.00,BTC/CHF,6.00,CHF,,CHF,,op-0006,Relai\r\n" +
        "2024-06-23T10:00:00Z,Buy,0.001,,BTC/EUR,,EUR,,EUR,,op-0009,Relai\r\n"

    /** CHF: 0.8 je USD; EUR fehlt. */
    private val fx: (String, Long) -> BigDecimal? = { c, _ -> if (c == "CHF") BigDecimal("0.8") else null }

    @Test
    fun previewCountsNewDuplicatesSkippedAndMissing() {
        val result = CsvImportParser.parse(csv)
        val p = CsvImportMapping.preview(result, existingKeys = setOf("RELAI|op-0001"), fx = fx)
        assertEquals(1, p.duplicates)
        assertEquals(listOf(CsvImportSkip(6, CsvImportSkipReason.UNSUPPORTED_KIND)), p.skipped)
        assertEquals(listOf(3, 4, 5, 7), p.transactions.map { it.line })
        assertEquals(listOf(7), p.missingPrice) // Kauf ohne Preis: trotzdem übernommen
        assertEquals(emptyList<Int>(), p.missingFx)
    }

    /** Belohnung als Zins (Lending): Art als Notiz (AT: Anschaffungskosten = Wert); Staking ohne Notiz. */
    @Test
    fun interestRewardKeepsKindAsNote() {
        val text = "Date,Type,Coin,Amount,Price,Currency\n" +
            "2024-02-01T10:00:00Z,Lending interest,ETH,0.01,2000,USD\n" +
            "2024-02-02T10:00:00Z,Staking Reward,ETH,0.01,2000,USD\n"
        val txs = CsvImportMapping.preview(CsvImportParser.parse(text), emptySet()) { _, _ -> null }.transactions.associateBy { it.line }
        assertEquals(PortfolioTxType.REWARD, txs.getValue(2).type)
        assertEquals("Lending interest", txs.getValue(2).note)
        assertEquals(PortfolioTxType.REWARD, txs.getValue(3).type)
        assertNull(txs.getValue(3).note)
    }

    @Test
    fun mapsTypesPricesAndFees() {
        val txs = CsvImportMapping.preview(CsvImportParser.parse(csv), emptySet(), fx = fx).transactions.associateBy { it.line }
        val sell = txs.getValue(2)
        assertEquals(PortfolioTxType.SELL, sell.type)
        assertEquals(0, BigDecimal("115426.6").compareTo(sell.priceUsdt))
        assertNull(sell.fee) // Gebühr 0 = keine
        val buy = txs.getValue(3)
        assertEquals(PortfolioTxType.BUY, buy.type)
        assertEquals(PortfolioFeeUnit.QUOTE, buy.feeUnit)
        assertEquals(0, BigDecimal("3.0625").compareTo(buy.fee))
        assertEquals("RELAI|op-0002", buy.importKey)
        val withdrawal = txs.getValue(4)
        assertEquals(PortfolioTxType.WITHDRAWAL, withdrawal.type)
        assertNull(withdrawal.priceUsdt)
        assertEquals(PortfolioFeeUnit.COIN, withdrawal.feeUnit)
        assertEquals(0, BigDecimal("0.00001").compareTo(withdrawal.fee))
        assertEquals(PortfolioTxType.REWARD, txs.getValue(5).type)
        assertNull(txs.getValue(7).priceUsdt)
    }

    @Test
    fun missingFxImportsWithoutPriceAndFee() {
        val p = CsvImportMapping.preview(CsvImportParser.parse(csv), emptySet(), fx = { _, _ -> null })
        assertEquals(listOf(2, 3, 5), p.missingFx)
        val buy = p.transactions.first { it.line == 3 }
        assertNull(buy.priceUsdt); assertNull(buy.fee); assertNull(buy.feeUnit)
    }

    @Test
    fun thirdCoinFeeAndFeeRows() {
        val row = CsvImportRow(1, CsvImportKind.BUY, "Buy", "ETH", BigDecimal("1"), BigDecimal("3000"), "USDT",
            BigDecimal("0.01"), "BNB", 5L, null, "k")
        val tx = CsvImportMapping.toTx(CsvImport.priceInUsdt(listOf(row)) { _, _ -> null }.single())
        assertEquals("BNB", tx.feeCoin)
        assertEquals(0, BigDecimal("0.01").compareTo(tx.feeCoinAmount))
        assertNull(tx.fee)
        assertEquals(0, BigDecimal("3000").compareTo(tx.priceUsdt))
        val fee = row.copy(kind = CsvImportKind.FEE, rawKind = " Network fee ", fee = null, feeUnit = null, price = null)
        val feeTx = CsvImportMapping.toTx(CsvImport.priceInUsdt(listOf(fee)) { _, _ -> null }.single())
        // Gebühr-Zeile: Veräusserung ohne Erlös (Verkauf zum Preis 0)
        assertEquals(PortfolioTxType.SELL, feeTx.type)
        assertEquals(0, BigDecimal.ZERO.compareTo(feeTx.priceUsdt))
        assertEquals("Network fee", feeTx.note)
    }

    @Test
    fun fxNeedsPerCurrencyWithDayRange() {
        val needs = CsvImportMapping.fxNeeds(CsvImportParser.parse(csv).rows)
        assertEquals(setOf("CHF"), needs.keys) // EUR-Kauf hat keinen Preis
        val chf = needs.getValue("CHF")
        assertEquals(CsvImport.epochDay(1_718_964_000_000L), chf.first) // 2024-06-21
    }

    @Test
    fun withdrawalsCanBecomeTransfers() {
        val result = CsvImportParser.parse(csv)
        val plain = CsvImportMapping.preview(result, emptySet(), fx = fx).transactions.associateBy { it.line }
        val asTransfer = CsvImportMapping.preview(result, emptySet(), withdrawalsAsTransfer = true, fx = fx).transactions.associateBy { it.line }
        assertEquals(PortfolioTxType.WITHDRAWAL, plain.getValue(4).type)
        assertEquals(PortfolioTxType.TRANSFER, asTransfer.getValue(4).type)
        assertNull(asTransfer.getValue(4).transferTo) // die Formate liefern kein Ziel
        // Gebühr in Coin bleibt (Netzwerkgebühr der Umbuchung)
        assertEquals(plain.getValue(4).fee, asTransfer.getValue(4).fee)
        // Gebühr-Zeilen bleiben Veräusserungen ohne Erlös
        assertEquals(PortfolioTxType.SELL, CsvImportMapping.type(CsvImportKind.FEE, withdrawalsAsTransfer = true))
        assertEquals(PortfolioTxType.TRANSFER, CsvImportMapping.type(CsvImportKind.WITHDRAWAL, withdrawalsAsTransfer = true))
    }

    private fun address(label: String?, change: String, count: Boolean = true) = WatchedAddress(
        id = 1, chain = AddressChain.BTC, address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", label = label,
        observations = listOf(AddressObservation(1_718_900_000_000, BigDecimal(change))), countInPortfolio = count,
    )

    /**
     * Auszahlung an eine eigene Adresse: Vorschlag «Umbuchung» nur bei passender beobachteter Änderung (± 1 %, Menge oder
     * Menge − Gebühr im Coin) einer ZÄHLENDEN Adresse des Coins; sonst keine Vorauswahl, «Importieren» erst nach Wahl.
     */
    @Test
    fun withdrawalProposalNeedsMatchingObservedChange() {
        val txs = CsvImportMapping.preview(CsvImportParser.parse(csv), emptySet(), fx = fx).transactions
        val w = txs.single { it.type == PortfolioTxType.WITHDRAWAL } // 0.005 BTC, Gebühr 0.00001 BTC
        // Erhalten 0.00499 (nach Netzwerkgebühr) → Umbuchung zu «Ledger»
        val hit = CsvImportMapping.proposeWithdrawals(txs, listOf(address("Ledger", "0.00499"))).single()
        assertEquals(w.importKey, hit.importKey)
        assertEquals(CsvWithdrawalChoice.TRANSFER, hit.choice)
        assertEquals("Ledger", hit.transferTo)
        // ± 1 %: 0.00504 passt noch, 0.0051 nicht
        assertEquals(CsvWithdrawalChoice.TRANSFER, CsvImportMapping.proposeWithdrawals(txs, listOf(address(null, "0.00504"))).single().choice)
        assertNull(CsvImportMapping.proposeWithdrawals(txs, listOf(address("X", "0.0051"))).single().choice)
        // Ohne Bezeichnung: gekürzte Adresse als Ziel
        assertEquals("bc1qar…5mdq", CsvImportMapping.proposeWithdrawals(txs, listOf(address(null, "0.005"))).single().transferTo)
        // «Nur anzeigen» bzw. keine Adresse: keine Vorauswahl
        val none = CsvImportMapping.proposeWithdrawals(txs, listOf(address("Ledger", "0.00499", count = false))).single()
        assertNull(none.choice)
        assertNull(none.transferTo)
        assertNull(CsvImportMapping.proposeWithdrawals(txs, emptyList()).single().choice)
        // Erst mit Wahl je Auszahlung entschieden
        assertFalse(CsvImportMapping.allWithdrawalsDecided(txs, emptyMap()))
        assertTrue(CsvImportMapping.allWithdrawalsDecided(txs, mapOf(w.importKey to CsvWithdrawalChoice.WITHDRAWAL)))
        // Übernehmen: Umbuchung mit Ziel bzw. Auszahlung; andere Zeilen unverändert
        val asTransfer = CsvImportMapping.applyWithdrawalChoices(txs, mapOf(w.importKey to CsvWithdrawalChoice.TRANSFER), mapOf(w.importKey to "Ledger"))
        val t = asTransfer.single { it.importKey == w.importKey }
        assertEquals(PortfolioTxType.TRANSFER, t.type)
        assertEquals("Ledger", t.transferTo)
        assertEquals(w.fee, t.fee)
        assertEquals(txs.filter { it.importKey != w.importKey }, asTransfer.filter { it.importKey != w.importKey })
        val kept = CsvImportMapping.applyWithdrawalChoices(txs, mapOf(w.importKey to CsvWithdrawalChoice.WITHDRAWAL))
        assertEquals(txs, kept)
    }

    /** Tausch: zwei Seiten (gleiche Auftragsnummer) → EIN Tausch mit Wert aus dem Fiat-Betrag (CHF → USDT). */
    @Test
    fun swapLegsBecomeOneSwap() {
        val sq = "Datum;Auftrag #;Transaktionen;Symbol;Name;ISIN;Anzahl;Stückpreis;Kosten;Nettobetrag;Währung Nettobetrag\n" +
            "07-01-2023 10:00:00;120000010;Tausch;ETH;Ethereum;;-1.0;1300.00;0.00;1300.00;CHF\n" +
            "07-01-2023 10:00:00;120000010;Tausch;BTC;Bitcoin;;0.08;16250.00;0.0004;-1300.00;CHF\n"
        val p = CsvImportMapping.preview(CsvImportParser.parse(sq), emptySet(), fx = fx)
        val swap = p.transactions.single()
        assertEquals(PortfolioTxType.SWAP, swap.type)
        assertEquals("ETH", swap.coin)
        assertEquals(0, BigDecimal("1").compareTo(swap.amount))
        assertEquals("BTC", swap.toCoin)
        assertEquals(0, BigDecimal("0.08").compareTo(swap.toAmount))
        assertEquals(0, BigDecimal("1625").compareTo(swap.swapValueUsdt)) // 1300 CHF ÷ 0.8
        assertNull(swap.priceUsdt)
        // Gebühr «Kosten» der erhaltenen Seite in CHF → USDT
        assertEquals(PortfolioFeeUnit.QUOTE, swap.feeUnit)
        assertEquals(0, BigDecimal("0.0005").compareTo(swap.fee))
        assertEquals(PortfolioTxType.SWAP, CsvImportMapping.type(CsvImportKind.SWAP))
        assertEquals(mapOf("CHF" to 19364L..19364L), CsvImportMapping.fxNeeds(CsvImportParser.parse(sq).rows))
    }

    /** Datei «neueste zuerst»: älteste zuerst speichern (Id folgt der Zeit); aufsteigende/gemischte bleiben. */
    @Test
    fun insertOrderReversesNewestFirstFiles() {
        val desc = "Date,Type,Coin,Amount,Price,Currency\n" +
            "2024-03-02T10:00:00Z,Sell,BTC,0.5,61000,USD\n" +
            "2024-03-02T10:00:00Z,Buy,BTC,1,60000,USD\n" +
            "2024-03-01T10:00:00Z,Deposit,BTC,0.1,,\n"
        val txs = CsvImportMapping.preview(CsvImportParser.parse(desc), emptySet()) { _, _ -> null }.transactions
        assertEquals(listOf(2, 3, 4), txs.map { it.line })
        assertEquals(listOf(4, 3, 2), CsvImportMapping.insertOrder(txs).map { it.line })
        val asc = txs.asReversed().toList()
        assertEquals(asc, CsvImportMapping.insertOrder(asc))
        assertEquals(emptyList<CsvImportTx>(), CsvImportMapping.insertOrder(emptyList()))
        // Nur gleiche Zeiten: wie in der Datei
        val same = txs.take(2)
        assertEquals(same, CsvImportMapping.insertOrder(same))
    }
}
