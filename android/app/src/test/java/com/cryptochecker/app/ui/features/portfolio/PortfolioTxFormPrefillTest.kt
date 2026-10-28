package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.util.PriceFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Vorbelegen im Erfassen-Blatt (PortfolioTxFormPrefill): heutiger Kurs, geprüfter Tagesschluss eines vergangenen Tags,
 * Kurs des Gebühr-Coins und Tageskurs der Anzeigewährung ([loadEntryFx]) samt Umschalten zurück auf USDT. Die
 * Wartezeit (Tipp-Pause) überspringt die virtuelle Zeit von runTest.
 */
class PortfolioTxFormPrefillTest {

    private val pastTime: Long =
        LocalDate.of(2020, 1, 15).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun state(draft: TxDraft) = TxFormState(draft, '.')

    private fun inputOf(value: Double): String = PriceFormat.amountForInput(PortfolioDecimal.of(value), '.')

    private fun parsed(text: String): BigDecimal? = PriceFormat.parseDecimalAmount(text, '.')

    // ---- Heutiger Kurs ----

    @Test
    fun prefillPriceFillsTodaysPriceForNewTransaction() = runTest {
        val s = state(TxDraft(coin = "BTC"))
        s.prefillPrice("BTC", true) { 50_000.0 }
        assertEquals(inputOf(50_000.0), s.priceText)
        assertTrue(s.priceAuto)
        assertEquals("BTC", s.pricedCoin)
        assertEquals(50_000.0, s.priceHint!!, 0.0)
    }

    @Test
    fun prefillPriceSkipsEditInvalidCoinAndSameCoin() = runTest {
        var calls = 0
        val edit = state(TxDraft(id = 5, coin = "BTC"))
        edit.prefillPrice("ETH", true) { calls++; 1.0 }
        val invalid = state(TxDraft(coin = "BTC"))
        invalid.prefillPrice("B?", false) { calls++; 1.0 }
        assertEquals(0, calls)
        assertEquals("", invalid.priceText)

        val priced = state(TxDraft(coin = "BTC"))
        priced.prefillPrice("BTC", true) { 10.0 }
        priced.prefillPrice("BTC", true) { calls++; 20.0 }
        assertEquals(0, calls)
        assertEquals(inputOf(10.0), priced.priceText)
    }

    @Test
    fun prefillPriceKeepsTypedPriceButUpdatesHint() = runTest {
        val s = state(TxDraft(coin = "BTC"))
        s.onPriceText("123")
        s.prefillPrice("ETH", true) { 3_000.0 }
        assertEquals("123", s.priceText)
        assertFalse(s.priceAuto)
        assertEquals(3_000.0, s.priceHint!!, 0.0)
    }

    @Test
    fun prefillPriceWithoutPriceLeavesFieldEmpty() = runTest {
        val s = state(TxDraft(coin = "BTC"))
        s.prefillPrice("BTC", true) { Double.NaN }
        assertEquals("", s.priceText)
        assertFalse(s.priceAuto)
        assertNull(s.priceHint)
    }

    @Test
    fun prefillPriceOnPastDayClearsPrefilledPrice() = runTest {
        // Vorbelegter Preis (neue Transaktion mit Kurs), aber anderer Tag: Feld leeren
        val s = state(TxDraft(coin = "BTC", priceUsdt = BigDecimal("40000"), time = pastTime))
        assertTrue(s.priceAuto)
        s.prefillPrice("ETH", true) { 2_000.0 }
        assertEquals("", s.priceText)
        assertFalse(s.priceAuto)
        assertEquals("ETH", s.pricedCoin)
    }

    @Test
    fun prefillPriceNotInFiatEntry() = runTest {
        val s = state(TxDraft(coin = "BTC"))
        s.entryFiat = true
        s.prefillPrice("BTC", true) { 50_000.0 }
        assertEquals("", s.priceText)
    }

    // ---- Tagesschluss eines vergangenen Tags ----

    @Test
    fun prefillHistoricCloseForPastReward() = runTest {
        val s = state(TxDraft(coin = "ETH", type = PortfolioTxType.REWARD, time = pastTime))
        var askedDay: LocalDate? = null
        s.prefillHistoricClose("ETH", true) { _, day -> askedDay = day; BigDecimal("42.5") }
        assertEquals(LocalDate.of(2020, 1, 15), askedDay)
        assertEquals(PriceFormat.amountForInput(BigDecimal("42.5"), '.'), s.priceText)
        assertEquals(42.5, s.priceHint!!, 0.0)
        assertFalse(s.priceAuto)
        assertEquals(PortfolioFormat.date(pastTime), s.priceHistoricDay)
    }

    @Test
    fun prefillHistoricCloseReplacesEarlierClose() = runTest {
        val s = state(TxDraft(coin = "ETH", type = PortfolioTxType.DEPOSIT, time = pastTime))
        s.priceText = "1"
        s.priceHistoricDay = "alt"
        s.prefillHistoricClose("SOL", true) { _, _ -> null }
        // Alter Tagesschluss geleert, kein neuer gefunden
        assertEquals("", s.priceText)
        assertNull(s.priceHistoricDay)
    }

    @Test
    fun prefillHistoricCloseSkipsBuyTodayTypedAndFiat() = runTest {
        var calls = 0
        val close: suspend (String, LocalDate) -> BigDecimal? = { _, _ -> calls++; BigDecimal.ONE }
        state(TxDraft(coin = "ETH", type = PortfolioTxType.BUY, time = pastTime)).prefillHistoricClose("ETH", true, close)
        state(TxDraft(coin = "ETH", type = PortfolioTxType.REWARD)).prefillHistoricClose("ETH", true, close)
        state(TxDraft(coin = "ETH", type = PortfolioTxType.REWARD, time = pastTime)).prefillHistoricClose("ETH", false, close)
        val typed = state(TxDraft(coin = "ETH", type = PortfolioTxType.REWARD, time = pastTime))
        typed.onPriceText("7")
        typed.prefillHistoricClose("ETH", true, close)
        val fiat = state(TxDraft(coin = "ETH", type = PortfolioTxType.REWARD, time = pastTime))
        fiat.entryFiat = true
        fiat.prefillHistoricClose("ETH", true, close)
        assertEquals(0, calls)
        assertEquals("7", typed.priceText)
    }

    // ---- Kurs des Gebühr-Coins ----

    @Test
    fun prefillFeeCoinPriceForToday() = runTest {
        val s = state(TxDraft(coin = "BTC"))
        s.prefillFeeCoinPrice("BNB", "BTC") { 600.0 }
        assertEquals(inputOf(600.0), s.feeCoinPriceText)
        assertTrue(s.feeCoinPriceAuto)
    }

    @Test
    fun prefillFeeCoinPriceIgnoresInvalidSymbol() = runTest {
        var calls = 0
        val s = state(TxDraft(coin = "BTC"))
        s.prefillFeeCoinPrice("", "BTC") { calls++; 1.0 }
        s.prefillFeeCoinPrice("B-N", "BTC") { calls++; 1.0 }
        s.prefillFeeCoinPrice("BTC", "BTC") { calls++; 1.0 }
        s.prefillFeeCoinPrice("ABCDEFGHIJKLMNOP", "BTC") { calls++; 1.0 }
        assertEquals(0, calls)
        assertEquals("", s.feeCoinPriceText)
    }

    @Test
    fun prefillFeeCoinPriceOnPastDayClearsPrefilled() = runTest {
        val s = state(TxDraft(coin = "BTC", time = pastTime))
        s.feeCoinPriceText = "600"
        s.feeCoinPriceAuto = true
        s.prefillFeeCoinPrice("BNB", "BTC") { 700.0 }
        assertEquals("", s.feeCoinPriceText)
        assertFalse(s.feeCoinPriceAuto)
    }

    @Test
    fun prefillFeeCoinPriceKeepsTypedPriceAndMissingPrice() = runTest {
        val typed = state(TxDraft(coin = "BTC"))
        typed.onFeeCoinPriceText("550")
        typed.prefillFeeCoinPrice("BNB", "BTC") { 600.0 }
        assertEquals("550", typed.feeCoinPriceText)
        assertFalse(typed.feeCoinPriceAuto)

        val none = state(TxDraft(coin = "BTC"))
        none.prefillFeeCoinPrice("BNB", "BTC") { null }
        assertEquals("", none.feeCoinPriceText)
    }

    // ---- Tageskurs der Anzeigewährung ----

    @Test
    fun loadEntryFxSkipsUsdtEntryAndUsd() = runTest {
        var calls = 0
        val usdt = state(TxDraft(coin = "BTC"))
        usdt.loadEntryFx("EUR", LocalDate.now()) { _, _ -> calls++; null }
        val usd = state(TxDraft(coin = "BTC"))
        usd.switchToFiat()
        usd.loadEntryFx("USD", LocalDate.now()) { _, _ -> calls++; null }
        assertEquals(0, calls)
        assertNull(usdt.entryFxState)
    }

    @Test
    fun loadEntryFxConvertsFieldsAndDeriveReadsFiat() = runTest {
        val s = state(TxDraft(coin = "BTC", amount = BigDecimal("1")))
        s.onPriceText("100")
        s.switchToFiat()
        assertTrue(s.entryFiat)
        assertTrue(s.pendingToFiat)
        val day = localDate(s.time)
        var calls = 0
        s.loadEntryFx("EUR", day) { _, d -> calls++; EntryFx(BigDecimal("0.9"), d) }
        assertEquals(1, calls)
        assertEquals(EntryFxState.READY, s.entryFxState)
        assertEquals("EUR|$day", s.entryFxKey)
        assertFalse(s.pendingToFiat)
        assertEquals(0, BigDecimal("90").compareTo(parsed(s.priceText)))
        assertFalse(s.fxFailed("EUR", day))

        // Gleicher Schlüssel, Kurs schon geladen: kein zweiter Abruf
        s.loadEntryFx("EUR", day) { _, d -> calls++; EntryFx(BigDecimal("2"), d) }
        assertEquals(1, calls)

        val d = s.derive("EUR")
        assertTrue(d.fiatPossible)
        assertTrue(d.fiatActive)
        assertEquals(0, BigDecimal("0.9").compareTo(d.fxRate))
        assertEquals(0, BigDecimal("100").compareTo(d.price))

        // Zurück auf USDT: Feld wieder in USDT
        s.switchToUsdt(d)
        assertFalse(s.entryFiat)
        assertEquals(0, BigDecimal("100").compareTo(parsed(s.priceText)))
    }

    @Test
    fun loadEntryFxWithoutRateStaysOnUsdt() = runTest {
        val s = state(TxDraft(coin = "BTC"))
        s.onPriceText("100")
        s.switchToFiat()
        val day = localDate(s.time)
        s.loadEntryFx("EUR", day) { _, _ -> null }
        assertEquals(EntryFxState.FAILED, s.entryFxState)
        assertNull(s.entryFx)
        assertFalse(s.entryFiat)
        assertFalse(s.pendingToFiat)
        assertEquals("100", s.priceText)
        assertTrue(s.fxFailed("EUR", day))
        assertFalse(s.fxFailed("CHF", day))
    }

    // ---- Abgeleitete Werte ----

    @Test
    fun derivedForwardsCheckValues() {
        val s = state(
            TxDraft(
                coin = "BTC",
                type = PortfolioTxType.SWAP,
                amount = BigDecimal("0.5"),
                toCoin = "ETH",
                toAmount = BigDecimal("8"),
            )
        )
        val d = s.derive("USD")
        assertTrue(d.isSwap)
        assertEquals(PortfolioTxType.SWAP, d.type)
        assertEquals(d.check.fields, d.fields)
        assertEquals(d.check.coin, d.coin)
        assertEquals(d.check.coinValid, d.coinValid)
        assertEquals(d.check.priceZero, d.priceZero)
        assertEquals(d.check.priceValid, d.priceValid)
        assertEquals(d.check.toCoin, d.toCoin)
        assertEquals(d.check.toCoinValid, d.toCoinValid)
        assertEquals(d.check.swapValueValid, d.swapValueValid)
        assertEquals(d.check.hasFee, d.hasFee)
        assertEquals(d.check.fee, d.fee)
        assertEquals(d.check.feeValid, d.feeValid)
        assertEquals(d.check.feeUnit, d.effectiveFeeUnit)
        assertEquals(d.check.feeInReceived, d.feeInReceived)
        assertEquals(d.check.thirdFee, d.thirdFee)
        assertEquals(d.check.feeConsumes, d.feeConsumes)
        assertEquals(d.check.valid, d.valid)
        // In USD keine Eingabe in der Anzeigewährung
        assertFalse(d.fiatPossible)
        assertFalse(d.dirty)
        assertTrue(d.isToday)
        assertEquals(0, BigDecimal("0.5").compareTo(d.amount))
        assertEquals(0, BigDecimal("8").compareTo(d.toAmount))
    }

    @Test
    fun derivedMarksChangesAsDirty() {
        val s = state(TxDraft(coin = "BTC", time = pastTime))
        assertFalse(s.derive("USD").dirty)
        assertFalse(s.derive("USD").isToday)
        s.note = "Notiz"
        assertTrue(s.derive("USD").dirty)
    }
}
