package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioTxForm.PriceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Erfassen-Blatt: heutiger Kurs nur für heute, Verkaufswarnung mit Gebühr im Coin. Wie `PortfolioTxFormTests` (iOS). */
class PortfolioTxFormTest {

    private fun dec(s: String) = BigDecimal(s)

    @Test
    fun prefillOnlyForNewTransactionsOfToday() {
        assertTrue(PortfolioTxForm.mayPrefill(isEdit = false, isToday = true))
        assertFalse(PortfolioTxForm.mayPrefill(isEdit = false, isToday = false))
        assertFalse(PortfolioTxForm.mayPrefill(isEdit = true, isToday = true))
        assertFalse(PortfolioTxForm.mayPrefill(isEdit = true, isToday = false))
    }

    @Test
    fun pastDateClearsAutoFilledPriceButKeepsTypedOne() {
        // Vorbelegter heutiger Kurs, anderer Tag gewählt → leeren
        assertEquals(PriceAction.CLEAR, PortfolioTxForm.onDateChange(isEdit = false, isToday = false, priceAuto = true, priceBlank = false))
        // Getippter Preis bleibt
        assertEquals(PriceAction.KEEP, PortfolioTxForm.onDateChange(isEdit = false, isToday = false, priceAuto = false, priceBlank = false))
        // Leeres Feld an einem anderen Tag bleibt leer
        assertEquals(PriceAction.KEEP, PortfolioTxForm.onDateChange(isEdit = false, isToday = false, priceAuto = false, priceBlank = true))
    }

    @Test
    fun backToTodayWithEmptyFieldPrefillsAgain() {
        assertEquals(PriceAction.PREFILL, PortfolioTxForm.onDateChange(isEdit = false, isToday = true, priceAuto = false, priceBlank = true))
        // Getippter bzw. noch vorbelegter Preis bleibt
        assertEquals(PriceAction.KEEP, PortfolioTxForm.onDateChange(isEdit = false, isToday = true, priceAuto = false, priceBlank = false))
        assertEquals(PriceAction.KEEP, PortfolioTxForm.onDateChange(isEdit = false, isToday = true, priceAuto = true, priceBlank = false))
    }

    @Test
    fun editModeNeverTouchesThePrice() {
        for (today in listOf(true, false)) for (auto in listOf(true, false)) for (blank in listOf(true, false)) {
            assertEquals(PriceAction.KEEP, PortfolioTxForm.onDateChange(isEdit = true, isToday = today, priceAuto = auto, priceBlank = blank))
        }
        assertFalse(PortfolioTxForm.showPastPriceHint(isEdit = true, isToday = false))
    }

    @Test
    fun pastPriceHintOnlyForNewTransactionsNotOfToday() {
        assertTrue(PortfolioTxForm.showPastPriceHint(isEdit = false, isToday = false))
        assertFalse(PortfolioTxForm.showPastPriceHint(isEdit = false, isToday = true))
    }

    @Test
    fun sellWarningIncludesCoinFee() {
        val holdings = dec("1")
        // Menge allein passt, mit Gebühr im Coin nicht
        assertFalse(PortfolioTxForm.sellExceeds(dec("1"), null, PortfolioFeeUnit.COIN, holdings))
        assertTrue(PortfolioTxForm.sellExceeds(dec("1"), dec("0.001"), PortfolioFeeUnit.COIN, holdings))
        assertTrue(PortfolioTxForm.sellExceedsIncludesFee(dec("0.001"), PortfolioFeeUnit.COIN))
        // Gebühr in USDT verringert den Erlös, nicht die Menge
        assertFalse(PortfolioTxForm.sellExceeds(dec("1"), dec("5"), PortfolioFeeUnit.QUOTE, holdings))
        assertFalse(PortfolioTxForm.sellExceedsIncludesFee(dec("5"), PortfolioFeeUnit.QUOTE))
        // Gebühr 0 oder negativ zählt nicht
        assertFalse(PortfolioTxForm.sellExceeds(dec("1"), dec("0"), PortfolioFeeUnit.COIN, holdings))
        assertFalse(PortfolioTxForm.sellExceedsIncludesFee(dec("-1"), PortfolioFeeUnit.COIN))
        // Genau der Bestand inkl. Gebühr: keine Warnung
        assertFalse(PortfolioTxForm.sellExceeds(dec("0.999"), dec("0.001"), PortfolioFeeUnit.COIN, holdings))
        // Ohne Menge oder ohne Bestand (Kauf): nie
        assertFalse(PortfolioTxForm.sellExceeds(null, dec("1"), PortfolioFeeUnit.COIN, holdings))
        assertFalse(PortfolioTxForm.sellExceeds(dec("5"), null, PortfolioFeeUnit.COIN, null))
    }

    @Test
    fun sellWarningMatchesCalculatorOversold() {
        // Gleiche Regel wie der Rechner: Menge + Gebühr im Coin über dem Bestand = gekappt («oversold»)
        val trades = listOf(
            PortfolioTrade(1, "BTC", PortfolioTxType.BUY, dec("1"), dec("100"), 1),
            PortfolioTrade(2, "BTC", PortfolioTxType.SELL, dec("1"), dec("100"), 2, dec("0.001"), PortfolioFeeUnit.COIN),
        )
        assertTrue(PortfolioCalculator.position("BTC", trades, 100.0).oversold)
        assertTrue(PortfolioTxForm.sellExceeds(dec("1"), dec("0.001"), PortfolioFeeUnit.COIN, dec("1")))
    }
    @Test
    fun fieldsPerType() {
        assertEquals(9, PortfolioTxForm.TYPES.size)
        assertEquals(PortfolioTxType.SWAP, PortfolioTxForm.TYPES.last())
        val transfer = PortfolioTxForm.fields(PortfolioTxType.TRANSFER)
        assertFalse(transfer.price); assertTrue(transfer.route); assertTrue(transfer.coinFeeOnly); assertFalse(transfer.exchange)
        val reward = PortfolioTxForm.fields(PortfolioTxType.REWARD)
        assertTrue(reward.price); assertFalse(reward.feeWithUnit); assertFalse(reward.coinFeeOnly); assertFalse(reward.thirdCoinFee)
        assertTrue(PortfolioTxForm.fields(PortfolioTxType.WITHDRAWAL).coinFeeOnly)
        assertTrue(PortfolioTxForm.fields(PortfolioTxType.BUY).feeWithUnit)
        // Auszahlung/Umbuchung: Gebühr immer im Coin
        assertEquals(PortfolioFeeUnit.COIN, PortfolioTxForm.feeUnitFor(PortfolioTxType.WITHDRAWAL, PortfolioFeeUnit.QUOTE))
        assertEquals(PortfolioFeeUnit.QUOTE, PortfolioTxForm.feeUnitFor(PortfolioTxType.BUY, PortfolioFeeUnit.QUOTE))
    }

    @Test
    fun outflowWarningForSellWithdrawalAndTransfer() {
        val h = dec("1")
        assertTrue(PortfolioTxForm.outflowExceeds(PortfolioTxType.WITHDRAWAL, dec("1"), dec("0.1"), PortfolioFeeUnit.QUOTE, h))
        assertTrue(PortfolioTxForm.outflowExceeds(PortfolioTxType.TRANSFER, dec("1.1"), null, PortfolioFeeUnit.QUOTE, h))
        assertFalse(PortfolioTxForm.outflowExceeds(PortfolioTxType.TRANSFER, dec("1"), null, PortfolioFeeUnit.QUOTE, h))
        assertFalse(PortfolioTxForm.outflowExceeds(PortfolioTxType.REWARD, dec("5"), null, PortfolioFeeUnit.QUOTE, h))
        assertFalse(PortfolioTxForm.outflowExceeds(PortfolioTxType.SELL, dec("1"), dec("1"), PortfolioFeeUnit.QUOTE, h))
    }

    @Test
    fun thirdCoinFeeStates() {

        assertEquals(PortfolioTxForm.ThirdFee.NONE, PortfolioTxForm.thirdFee("", "BTC", null, true, null, true))
        assertEquals(PortfolioTxForm.ThirdFee.INCOMPLETE, PortfolioTxForm.thirdFee("bnb", "BTC", dec("0.1"), false, null, true))
        assertEquals(PortfolioTxForm.ThirdFee.COMPLETE, PortfolioTxForm.thirdFee("BNB", "BTC", dec("0.1"), false, dec("600"), false))
        assertEquals(PortfolioTxForm.ThirdFee.INVALID, PortfolioTxForm.thirdFee("BTC", "btc", dec("0.1"), false, null, true))
        assertEquals(PortfolioTxForm.ThirdFee.INVALID, PortfolioTxForm.thirdFee("BNB", "BTC", null, false, null, true))
        assertEquals(PortfolioTxForm.ThirdFee.INVALID, PortfolioTxForm.thirdFee("BNB", "BTC", dec("0.1"), false, null, false))
        assertEquals(PortfolioTxForm.ThirdFee.INVALID, PortfolioTxForm.thirdFee("", "BTC", dec("0.1"), false, null, true))
    }

    @Test
    fun prefillRules() {
        assertTrue(PortfolioTxForm.mayPrefillFeeCoinPrice(isEdit = false, isToday = true, priceBlank = true))
        assertFalse(PortfolioTxForm.mayPrefillFeeCoinPrice(isEdit = false, isToday = false, priceBlank = true))
        assertTrue(PortfolioTxForm.mayPrefillHistoricClose(PortfolioTxType.REWARD, isEdit = false, isToday = false, priceBlank = true))
        assertTrue(PortfolioTxForm.mayPrefillHistoricClose(PortfolioTxType.DEPOSIT, isEdit = false, isToday = false, priceBlank = true))
        assertFalse(PortfolioTxForm.mayPrefillHistoricClose(PortfolioTxType.BUY, isEdit = false, isToday = false, priceBlank = true))
        assertFalse(PortfolioTxForm.mayPrefillHistoricClose(PortfolioTxType.REWARD, isEdit = true, isToday = false, priceBlank = true))
        assertFalse(PortfolioTxForm.mayPrefillHistoricClose(PortfolioTxType.REWARD, isEdit = false, isToday = true, priceBlank = true))
    }

    @Test
    fun swapAndFiatEntry() {
        assertTrue(PortfolioTxForm.swapToCoinValid("eth", "BTC"))
        assertFalse(PortfolioTxForm.swapToCoinValid("btc", "BTC"))
        assertFalse(PortfolioTxForm.swapToCoinValid("", "BTC"))
        assertFalse(PortfolioTxForm.swapToCoinValid("ET-H", "BTC"))
        assertTrue(PortfolioTxForm.feeInReceivedCoin(PortfolioTxType.SWAP, "eth", "ETH"))
        assertFalse(PortfolioTxForm.feeInReceivedCoin(PortfolioTxType.SWAP, "BNB", "ETH"))
        assertFalse(PortfolioTxForm.feeInReceivedCoin(PortfolioTxType.BUY, "ETH", "ETH"))
        // 1’000 CHF bei USD → CHF 0.8 = 1’250 USDT; zurück wieder 1’000
        assertEquals(0, dec("1250").compareTo(PortfolioTxForm.fiatToUsdt(dec("1000"), dec("0.8"))))
        assertEquals(0, dec("1000").compareTo(PortfolioTxForm.usdtToFiat(dec("1250"), dec("0.8"))))
        assertEquals(null, PortfolioTxForm.fiatToUsdt(dec("1000"), null))
        assertEquals(null, PortfolioTxForm.fiatToUsdt(dec("1000"), BigDecimal.ZERO))
        assertEquals(null, PortfolioTxForm.fiatToUsdt(null, dec("0.8")))
        // 1 / 3: 34 gültige Stellen
        assertEquals(34, PortfolioTxForm.fiatToUsdt(BigDecimal.ONE, dec("3"))!!.precision())
        // USDT zum Marktkurs (wie CSV-Import und Export): Faktor = Devisenkurs × USDT/USD; 898.2 CHF bei 0.9 × 0.998 = 1000 USDT
        val rate = PortfolioTxForm.entryRate(dec("0.9"), dec("0.998"))
        assertEquals(0, dec("0.8982").compareTo(rate))
        assertEquals(0, dec("1000").compareTo(PortfolioTxForm.fiatToUsdt(dec("898.2"), rate)))
        // ohne bzw. unplausibler USDT/USD-Kurs: × 1; ohne Devisenkurs: keiner
        assertEquals(0, dec("0.9").compareTo(PortfolioTxForm.entryRate(dec("0.9"), null)))
        assertEquals(0, dec("0.9").compareTo(PortfolioTxForm.entryRate(dec("0.9"), dec("3"))))
        assertEquals(null, PortfolioTxForm.entryRate(null, dec("0.998")))
        // Vermerk «USDT = 1 angenommen» genau dann, wenn der Faktor mit × 1 rechnet
        assertEquals(false, PortfolioTxForm.entryUsdtAssumed(dec("0.9"), dec("0.998")))
        assertEquals(true, PortfolioTxForm.entryUsdtAssumed(dec("0.9"), null))
        assertEquals(true, PortfolioTxForm.entryUsdtAssumed(dec("0.9"), dec("3")))
        assertEquals(true, PortfolioTxForm.entryUsdtAssumed(dec("0.9"), BigDecimal.ZERO))
        assertEquals(false, PortfolioTxForm.entryUsdtAssumed(null, null))
    }

    @Test
    fun walletHoldingsFromOpenLots() {
        val trades = listOf(
            PortfolioTrade(1, "BTC", PortfolioTxType.BUY, dec("1"), dec("100"), 1, exchange = "Binance"),
            PortfolioTrade(2, "BTC", PortfolioTxType.BUY, dec("0.5"), dec("100"), 2, exchange = "binance "),
            PortfolioTrade(3, "BTC", PortfolioTxType.BUY, dec("0.25"), null, 3),
        )
        val lots = PortfolioCalculator.openLots("BTC", trades, CostMethod.FIFO_WALLET)
        assertEquals(0, dec("1.5").compareTo(PortfolioTxForm.walletHoldings(lots, "BINANCE")))
        assertEquals(0, dec("0.25").compareTo(PortfolioTxForm.walletHoldings(lots, null)))
        assertEquals(0, dec("0.25").compareTo(PortfolioTxForm.walletHoldings(lots, "")))
        assertEquals(0, BigDecimal.ZERO.compareTo(PortfolioTxForm.walletHoldings(lots, "Kraken")))
    }

    /** Gebühr ≥ erhaltene Menge sperrt das Speichern (Kauf/Belohnung/Einzahlung: Gebühr im Coin; Tausch: im erhaltenen Coin). */
    @Test
    fun feeConsumingTheReceivedAmountBlocksSaving() {
        val coin = PortfolioFeeUnit.COIN
        val quote = PortfolioFeeUnit.QUOTE
        assertTrue(PortfolioTxForm.feeConsumesReceived(PortfolioTxType.BUY, dec("0.001"), dec("0.001"), coin))
        assertTrue(PortfolioTxForm.feeConsumesReceived(PortfolioTxType.BUY, dec("0.001"), dec("0.002"), coin))
        assertFalse(PortfolioTxForm.feeConsumesReceived(PortfolioTxType.BUY, dec("0.001"), dec("0.0009"), coin))
        assertFalse(PortfolioTxForm.feeConsumesReceived(PortfolioTxType.BUY, dec("0.001"), dec("5"), quote)) // USDT-Gebühr
        assertTrue(PortfolioTxForm.feeConsumesReceived(PortfolioTxType.DEPOSIT, dec("1"), dec("1"), coin))
        assertTrue(PortfolioTxForm.feeConsumesReceived(PortfolioTxType.REWARD, dec("1"), dec("1"), coin))
        assertFalse(PortfolioTxForm.feeConsumesReceived(PortfolioTxType.BUY, null, dec("1"), coin))
        // Abgänge: Gebühr über der Menge ist keine Sperre (Bestandswarnung)
        assertFalse(PortfolioTxForm.feeConsumesReceived(PortfolioTxType.SELL, dec("1"), dec("2"), coin))
        assertFalse(PortfolioTxForm.feeConsumesReceived(PortfolioTxType.WITHDRAWAL, dec("1"), dec("2"), coin))
        // Tausch: Gebühr im erhaltenen Coin gegen die erhaltene Menge
        assertTrue(PortfolioTxForm.feeConsumesReceived(PortfolioTxType.SWAP, dec("1"), null, quote, dec("0.01"), dec("0.01")))
        assertFalse(PortfolioTxForm.feeConsumesReceived(PortfolioTxType.SWAP, dec("1"), null, quote, dec("0.01"), dec("0.005")))
        assertFalse(PortfolioTxForm.feeConsumesReceived(PortfolioTxType.SWAP, dec("1"), null, quote, null, dec("0.005")))
        assertFalse(PortfolioTxForm.feeConsumesReceived(PortfolioTxType.SWAP, dec("1"), null, quote, dec("0.01"), null))
    }

    /**
     * Preis 0 ausdrücklich eingegeben: nur bei einer importierten Transaktion abgelehnt (dort Kennzeichen der Gebühr-Zeile);
     * von Hand erfasst ist 0 ein echter Preis (wertlosen Coin verkaufen); leer und > 0 sind erlaubt. Wie iOS.
     */
    @Test
    fun priceZeroIsRejectedOnlyForImported() {
        assertTrue(PortfolioTxForm.priceZero(dec("0"), imported = true))
        assertTrue(PortfolioTxForm.priceZero(dec("0.000"), imported = true))
        assertFalse(PortfolioTxForm.priceZero(null, imported = true))
        assertFalse(PortfolioTxForm.priceZero(dec("0.0001"), imported = true))
        assertFalse(PortfolioTxForm.priceZero(dec("95000"), imported = true))
        // Von Hand: 0 erlaubt
        assertFalse(PortfolioTxForm.priceZero(dec("0"), imported = false))
        assertFalse(PortfolioTxForm.priceZero(dec("0.000"), imported = false))
    }

    /**
     * Verschenkt und Damit bezahlt: nach der Auszahlung in der Liste; Verschenkt mit den Feldern der Auszahlung,
     * Damit bezahlt mit Preisfeld (Marktwert) und Gebühren wie ein Verkauf.
     */
    @Test
    fun giftAndPaymentFieldsAndOrder() {
        val types = PortfolioTxForm.TYPES
        assertEquals(types.indexOf(PortfolioTxType.WITHDRAWAL) + 1, types.indexOf(PortfolioTxType.GIFT))
        assertEquals(types.indexOf(PortfolioTxType.GIFT) + 1, types.indexOf(PortfolioTxType.PAYMENT))
        assertEquals(PortfolioTxType.entries.size, types.size)
        assertEquals(PortfolioTxForm.fields(PortfolioTxType.WITHDRAWAL), PortfolioTxForm.fields(PortfolioTxType.GIFT))
        assertTrue(PortfolioTxForm.mayPrefillHistoricClose(PortfolioTxType.PAYMENT, isEdit = false, isToday = false, priceBlank = true))
        val f = PortfolioTxForm.fields(PortfolioTxType.PAYMENT)
        assertTrue(f.price)
        assertTrue(f.feeWithUnit)
        assertTrue(f.thirdCoinFee)
        assertTrue(f.outflow)
        assertFalse(f.route)
        // Tagesschluss vorbelegen wie bei der Belohnung (neue Transaktion eines vergangenen Tags, Feld leer)
        assertTrue(PortfolioTxForm.mayPrefillHistoricClose(PortfolioTxType.GIFT, isEdit = false, isToday = false, priceBlank = true))
        assertFalse(PortfolioTxForm.mayPrefillHistoricClose(PortfolioTxType.GIFT, isEdit = true, isToday = false, priceBlank = true))
        assertFalse(PortfolioTxForm.mayPrefillHistoricClose(PortfolioTxType.SELL, isEdit = false, isToday = false, priceBlank = true))
    }
}
