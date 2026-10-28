package com.cryptochecker.app.widget

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.refresh.WidgetRefreshState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus WidgetAmountFontTest

/** Widgets wie die App: Systemschrift für Beträge nur in Sprachen mit eigenen Ziffern (ar, fa). */
class WidgetAmountFontTest {

    @Test
    fun `Arabisch und Persisch nehmen die Systemschrift`() {
        assertTrue(WidgetAmountFont.usesSystemFont(Locale.forLanguageTag("ar-EG-u-nu-arab")))
        assertTrue(WidgetAmountFont.usesSystemFont(Locale.forLanguageTag("fa-IR-u-nu-arabext")))
    }

    @Test
    fun `lateinische Ziffern behalten Rubik`() {
        assertFalse(WidgetAmountFont.usesSystemFont(Locale.forLanguageTag("de-CH")))
        assertFalse(WidgetAmountFont.usesSystemFont(Locale.forLanguageTag("he-IL")))
        assertFalse(WidgetAmountFont.usesSystemFont(Locale.forLanguageTag("ar-u-nu-latn")))
    }

    @Test
    fun `ohne Systemschrift bleibt der Text unveraendert`() {
        val text = "61’234.50 USDT"
        assertTrue(WidgetAmountFont.apply(text, systemFont = false) === text)
    }
}

// ---- aus WidgetConfigureGuardTest

/** Exportierte Einrichten-Activities arbeiten nur mit der Id eines eigenen Widgets der passenden Art. */
class WidgetConfigureGuardTest {

    private val invalid = 0
    private val own = "com.cryptochecker.app"
    private val price = "com.cryptochecker.app.widget.PriceWidgetProvider"

    private fun accepts(id: Int, pkg: String?, cls: String?, ownPackage: String = own) =
        WidgetConfigureGuard.accepts(id, invalid, pkg, cls, ownPackage, price)

    @Test
    fun `own widget of the matching provider is accepted`() {
        assertTrue(accepts(42, own, price))
        // Testversion mit anderem Paket: Anbieter-Paket = Paket der App
        assertTrue(accepts(7, "com.cryptochecker.app.debug", price, ownPackage = "com.cryptochecker.app.debug"))
    }

    @Test
    fun `invalid or missing id is rejected`() {
        assertFalse(accepts(invalid, own, price))
    }

    @Test
    fun `unknown widget is rejected`() {
        // Systemdienst kennt die Id nicht bzw. gibt keinen Zugriff (fremdes Widget)
        assertFalse(accepts(42, null, null))
        assertFalse(accepts(42, own, null))
    }

    @Test
    fun `widget of another app is rejected`() {
        assertFalse(accepts(42, "com.example.other", price))
        assertFalse(accepts(42, "com.example.other", "com.example.other.Provider"))
    }

    @Test
    fun `own widget of another kind is rejected`() {
        assertFalse(accepts(42, own, "com.cryptochecker.app.widget.PortfolioWidgetProvider"))
    }

    @Test
    fun `empty own package never matches an unknown provider`() {
        assertFalse(accepts(42, null, price, ownPackage = ""))
    }
}

// ---- aus WidgetFreshnessTest

/** Frische in den Widgets ([WidgetFreshness]); Gegenstück `Tests/WidgetFreshnessTests`. */
class WidgetFreshnessTest {

    private val now = 1_760_000_000_000L
    private val hour = 3_600_000L
    private val after = WidgetFreshness.afterMillis(15) // 45 Min.

    private fun watch(
        lastUpdateAgo: Long = 60_000L,
        changedAgo: Long = 2 * hour,
        exchangeTime: Long? = null,
        disabled: Boolean = false,
        error: String? = null,
    ) = WatchEntity(
        exchangeKey = "kraken", exchangeName = "Kraken", baseAsset = "BTC", quoteAsset = "EUR",
        lastPrice = 62_000.0, lastUpdate = now - lastUpdateAgo, lastError = error, disabled = disabled,
        priceChangedAt = now - changedAgo, exchangeTime = exchangeTime,
    )

    @Test
    fun widgetsUseThePollingLimitNeverTheLiveLimit() {
        assertEquals(45 * 60_000L, WidgetFreshness.afterMillis(15))
        assertEquals(15 * 60_000L, WidgetFreshness.afterMillis(1))
        assertEquals(180 * 60_000L, WidgetFreshness.afterMillis(60))
    }

    @Test
    fun unchangedPriceShowsSinceAndItsTime() {
        assertEquals(now - 2 * hour, WidgetFreshness.unchangedSince(watch(), now, after))
        assertEquals(now - 2 * hour, WidgetFreshness.priceTime(watch(), now, after))
    }

    @Test
    fun recentChangeIsFreshWithFetchTime() {
        val w = watch(changedAgo = 30 * 60_000L)
        assertNull(WidgetFreshness.unchangedSince(w, now, after))
        assertEquals(now - 60_000L, WidgetFreshness.priceTime(w, now, after))
    }

    @Test
    fun exchangeTimeWinsAndNeverUnchanged() {
        val w = watch(exchangeTime = now - 5 * 60_000L)
        assertNull(WidgetFreshness.unchangedSince(w, now, after))
        assertEquals(now - 5 * 60_000L, WidgetFreshness.priceTime(w, now, after))
    }

    @Test
    fun outdatedDisabledOrFailedPairsShowNoUnchangedHint() {
        assertNull(WidgetFreshness.unchangedSince(watch(lastUpdateAgo = hour), now, after))
        assertNull(WidgetFreshness.unchangedSince(watch(disabled = true), now, after))
        assertNull(WidgetFreshness.unchangedSince(watch(error = "timeout"), now, after))
    }

    @Test
    fun outdatedLikeTheRowAndTheAlarmBlock() {
        // Abruf zu alt: Zeit des Abrufs
        assertEquals(now - hour, WidgetFreshness.outdatedTime(watch(lastUpdateAgo = hour), now, after))
        // Abruf frisch, letzter Handel 3 h her: Zeit des Handels; ruhiges Paar (1 h) nicht
        assertEquals(now - 3 * hour, WidgetFreshness.outdatedTime(watch(exchangeTime = now - 3 * hour), now, after))
        assertNull(WidgetFreshness.outdatedTime(watch(exchangeTime = now - hour), now, after))
        assertNull(WidgetFreshness.outdatedTime(watch(), now, after))
        assertNull(WidgetFreshness.outdatedTime(watch(lastUpdateAgo = hour, disabled = true), now, after))
        // Wecker: Handel so verschoben, dass er mit derselben Grenze genau nach 2 h wechselt
        val w = watch(exchangeTime = now - hour)
        assertEquals(listOf(w.lastUpdate, now - hour + 2 * hour - after), WidgetFreshness.outdatedTimes(w, after))
        assertEquals(now + hour + 1, com.cryptochecker.app.domain.refresh.OutdatedRule.nextChangeAt(
            listOf(now - hour + 2 * hour - after), now, after,
        ))
    }
}

// ---- aus WidgetListPriceFitTest

/**
 * Kurs der Merklisten-Zeile ([WidgetTextFit.listPriceFit]): Er bekommt zuerst seine Breite und ist immer vollständig;
 * bei vergrösserter Schrift wird er zuerst kleiner (bis 70 % der normalen Grösse), dann weicht der Paarname; bei
 * normaler Schrift weicht gleich der Name.
 */
class WidgetListPriceFitTest {

    /** Breite des Kurses bei der Grösse [fit] (gemessen [priceDp] bei 14 sp). */
    private fun shownPrice(priceDp: Float, fit: WidgetTextFit.ListPriceFit) = priceDp * fit.sp / 14f

    @Test
    fun bothFitKeepBaseSizeWithoutUpperLimit() {
        val fit = WidgetTextFit.listPriceFit(14f, 2f, priceWidthDp = 150f, nameWidthDp = 40f, availableDp = 200f)
        assertEquals(14f, fit.sp, 0f)
        assertEquals(50f, fit.nameDp, 0.01f)
        // Breite unbekannt: 14 sp
        assertEquals(14f, WidgetTextFit.listPriceFit(14f, 2f, 400f, 40f, availableDp = null).sp, 0f)
        assertEquals(14f, WidgetTextFit.listPriceFit(14f, 2f, 400f, 40f, availableDp = 0f).sp, 0f)
    }

    @Test
    fun priceShrinksFirstSoTheNameStaysWhole() {
        // 160 + 60 > 200: Kurs auf 140 dp (12.25 sp ≥ Untergrenze 4.9 sp), Name ganz
        val fit = WidgetTextFit.listPriceFit(14f, 2f, priceWidthDp = 160f, nameWidthDp = 60f, availableDp = 200f)
        assertEquals(12.25f, fit.sp, 0.001f)
        assertEquals(60f, fit.nameDp, 0.01f)
    }

    @Test
    fun tooNarrowPriceCompleteNameShortened() {
        // Schrift 2,0: Untergrenze 4.9 sp; Kurs 200 dp → 70 dp, für den Namen (100 dp) bleiben 80 dp
        val fit = WidgetTextFit.listPriceFit(14f, 2f, priceWidthDp = 200f, nameWidthDp = 100f, availableDp = 150f)
        assertEquals(4.9f, fit.sp, 0.001f)
        assertTrue("Kurs vollständig", shownPrice(200f, fit) <= 150f + 0.01f)
        assertEquals(80f, fit.nameDp, 0.01f)
        assertTrue("Name gekürzt", fit.nameDp < 100f)
    }

    @Test
    fun normalTextKeepsPriceSizeAndShortensTheName() {
        // Normale Schrift: Kurs bleibt 14 sp, der lange Name weicht
        val fit = WidgetTextFit.listPriceFit(14f, 1f, priceWidthDp = 120f, nameWidthDp = 100f, availableDp = 150f)
        assertEquals(14f, fit.sp, 0f)
        assertEquals(30f, fit.nameDp, 0.01f)
        // Kurs allein zu breit: genau passend, nie abgeschnitten
        val tight = WidgetTextFit.listPriceFit(14f, 1f, priceWidthDp = 300f, nameWidthDp = 50f, availableDp = 150f)
        assertEquals(150f, shownPrice(300f, tight), 0.01f)
        assertEquals(0f, tight.nameDp, 0f)
    }

    @Test
    fun evenWithoutNameThePriceIsNeverCut() {
        // Selbst bei der Untergrenze (4.9 sp → 350 dp) zu breit: genau passend, Name leer
        val fit = WidgetTextFit.listPriceFit(14f, 2f, priceWidthDp = 1000f, nameWidthDp = 80f, availableDp = 100f)
        assertEquals(100f, shownPrice(1000f, fit), 0.01f)
        assertEquals(0f, fit.nameDp, 0f)
        assertEquals(4.9f, WidgetTextFit.listPriceFloorSp(14f, 2f), 0.001f)
    }
}

// ---- aus WidgetQuoteTextTest

/** Kleine, graue Währung hinter Kurs und Betrag in den Widgets ([WidgetQuoteText]). */
class WidgetQuoteTextTest {

    @Test
    fun findsQuoteAfterNumber() {
        assertEquals(10..13, WidgetQuoteText.quoteRange("67’000.00 USDT", "USDT"))
        assertEquals(10..12, WidgetQuoteText.quoteRange("↘ −968.40 CHF · 24h", "CHF"))
        assertEquals(12..15, WidgetQuoteText.quoteRange("≈ 92’310.00 USDT · ohne 1 Coin", "USDT"))
    }

    @Test
    fun ignoresQuoteWithoutNumberOrInsideWords() {
        // Verborgen oder ohne Kurs: nichts zu verkleinern
        assertNull(WidgetQuoteText.quoteRange("•••", "CHF"))
        assertNull(WidgetQuoteText.quoteRange("—", "USDT"))
        assertNull(WidgetQuoteText.quoteRange("USDT", "USDT"))
        assertNull(WidgetQuoteText.quoteRange("12.00 USDTX", "USDT"))
        assertNull(WidgetQuoteText.quoteRange("12.00 USDT", ""))
    }

    @Test
    fun takesLastOccurrence() {
        // «USD» kommt auch in «USDT» vor: nur das eigenständige Wort am Ende zählt
        assertEquals(9..11, WidgetQuoteText.quoteRange("1 USDT 2 USD", "USD"))
    }

    @Test
    fun bidiIsolatesDoNotHideQuote() {
        val text = "⁦+12.00 CHF⁩"
        assertEquals(8..10, WidgetQuoteText.quoteRange(text, "CHF"))
    }

    @Test
    fun scaleKeepsMinimumSize() {
        assertEquals(0.42f, WidgetQuoteText.scale(28f), 1e-4f)
        // 14 sp × 0.42 < 11 sp → 11 sp
        assertEquals(11f / 14f, WidgetQuoteText.scale(14f), 1e-4f)
        assertEquals(1f, WidgetQuoteText.scale(8f), 1e-4f)
        assertEquals(1f, WidgetQuoteText.scale(0f), 1e-4f)
    }

    @Test
    fun widthMeasuresQuoteSmaller() {
        // Messung: Zeichen × Grösse
        val measure: (String, Float) -> Float = { s, sp -> s.length * sp }
        // «12 » in 20, «USDT» in max(20 × 0.42, 11) = 11
        assertEquals(3 * 20f + 4 * 11f, WidgetQuoteText.width("12 USDT", "USDT", 20f, measure), 1e-4f)
        // Ohne Währung im Text: alles in voller Grösse
        assertEquals(3 * 20f, WidgetQuoteText.width("•••", "USDT", 20f, measure), 1e-4f)
    }
}

// ---- aus WidgetRefreshUnlockTest

/**
 * Warten bis zum Ende der 15-s-Sperre ([WidgetRefreshUnlock.holdStep]): in Schritten unter der Grenze von goAsync,
 * am Ende genau ein Neuzeichnen — damit der Chronometer nie ins Minus zählt.
 */
class WidgetRefreshUnlockTest {
    private val now = 1_700_000_000_000L
    private val slack = WidgetRefreshState.UNLOCK_SLACK_MILLIS

    @Test
    fun fullLockIsSplitIntoHopsAndEndsWithOneRedraw() {
        var clock = now
        val blockedUntil = now + 15_000
        val steps = mutableListOf<WidgetRefreshUnlock.HoldStep>()
        do {
            val step = WidgetRefreshUnlock.holdStep(blockedUntil, clock)
            steps += step
            clock += step.waitMillis
        } while (!step.redraw && steps.size < 10)
        assertEquals(listOf(false, false, true), steps.map { it.redraw })
        assertEquals(blockedUntil + slack, clock)
        steps.forEach { assertTrue(it.waitMillis <= WidgetRefreshUnlock.HOP_MILLIS) }
    }

    @Test
    fun shortRestWaitsOnceThenRedraws() {
        assertEquals(WidgetRefreshUnlock.HoldStep(2_000 + slack, redraw = true),
            WidgetRefreshUnlock.holdStep(now + 2_000, now))
    }

    @Test
    fun lockAlreadyOverRedrawsAtOnce() {
        assertEquals(WidgetRefreshUnlock.HoldStep(0, redraw = true), WidgetRefreshUnlock.holdStep(now - 60_000, now))
        assertEquals(WidgetRefreshUnlock.HoldStep(0, redraw = true), WidgetRefreshUnlock.holdStep(0, now))
    }

    @Test
    fun implausibleEndRedrawsAtOnce() {
        // Uhr verstellt: Ende weiter weg als die Sperre selbst — nicht minutenlang warten
        assertEquals(WidgetRefreshUnlock.HoldStep(0, redraw = true), WidgetRefreshUnlock.holdStep(now + 3_600_000, now))
    }
}

// ---- aus WidgetScreenGateTest

class WidgetScreenGateTest {

    @Test
    fun drawsWhenScreenOn() {
        val gate = WidgetScreenGate()
        assertTrue(gate.onTick(deferWhenOff = true, interactive = true))
        assertFalse(gate.isDirty)
        assertFalse(gate.onScreenOn())
    }

    @Test
    fun defersWhileOffAndDrawsOnceOnScreenOn() {
        val gate = WidgetScreenGate()
        assertFalse(gate.onTick(deferWhenOff = true, interactive = false))
        assertFalse(gate.onTick(deferWhenOff = true, interactive = false))
        assertTrue(gate.isDirty)
        assertTrue(gate.onScreenOn())
        // Nur einmal
        assertFalse(gate.onScreenOn())
    }

    @Test
    fun nextInteractiveTickClearsPending() {
        val gate = WidgetScreenGate()
        gate.onTick(deferWhenOff = true, interactive = false)
        assertTrue(gate.onTick(deferWhenOff = true, interactive = true))
        assertFalse(gate.onScreenOn())
    }

    @Test
    fun backgroundJobAlwaysDraws() {
        val gate = WidgetScreenGate()
        assertTrue(gate.onTick(deferWhenOff = false, interactive = false))
        assertFalse(gate.isDirty)
    }
}
