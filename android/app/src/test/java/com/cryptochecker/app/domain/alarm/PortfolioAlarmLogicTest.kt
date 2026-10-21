package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Portfolio-Alarme: Wert über/unter Betrag, Veränderung ±x % (Spiegel: PortfolioAlarmLogicTests.swift). */
class PortfolioAlarmLogicTest {

    private val start = 1_700_000_000_000L

    private fun alarm(kind: PortfolioAlarmKind, threshold: Double, currency: String? = "CHF") =
        PortfolioAlarmInput(kind, threshold, currency, enabled = true, referenceAt = 0, lastTriggeredAt = 0)

    private fun reading(total: Double, change: Double? = null, currency: String = "CHF", usdt: Double? = total * 1.1) =
        PortfolioReading(total = total, currency = currency, totalUsdt = usdt, changePercent = change, empty = false)

    /**
     * Spielt eine Folge durch wie PortfolioAlarmChecker: Rearm → referenceAt 0, Fire → referenceAt = Zeit
     * (einmalige schalten ab). @return Zahl der Meldungen
     */
    private fun run(start: PortfolioAlarmInput, readings: List<PortfolioReading>, repeating: Boolean = true, cooldown: Int = 0): Int {
        var a = start
        var fired = 0
        readings.forEachIndexed { i, r ->
            val now = this.start + i * 60_000L
            when (val d = PortfolioAlarmLogic.decide(a, r, now, cooldown)) {
                PortfolioAlarmDecision.None -> Unit
                PortfolioAlarmDecision.Rearm -> a = a.copy(referenceAt = 0)
                is PortfolioAlarmDecision.Fire -> {
                    fired++
                    a = a.copy(referenceAt = now, lastTriggeredAt = now, enabled = PortfolioAlarmLogic.enabledAfterFire(repeating))
                }
            }
        }
        return fired
    }

    @Test
    fun notifiesOnlyWhenConditionalSaveChangedARow() {
        // 0 Zeilen = Alarm seit dem Lesen geändert (z. B. ausgeschaltet): nicht melden
        assertFalse(PortfolioAlarmLogic.notifyAfterSave(0))
        assertTrue(PortfolioAlarmLogic.notifyAfterSave(1))
    }

    @Test
    fun valueAbove_firesOnceWhileAbove_andRearmsWithHysteresis() {
        val a = alarm(PortfolioAlarmKind.VALUE_ABOVE, 50_000.0)
        // Über die Marke, bleibt drüber, pendelt knapp darunter (innerhalb 0.2 %), dann klar darunter und wieder drüber
        val values = listOf(49_000.0, 50_100.0, 50_500.0, 49_950.0, 50_200.0, 49_000.0, 50_300.0)
        assertEquals(2, run(a, values.map { reading(it) }))
    }

    @Test
    fun valueBelow_andOnceDisables() {
        val a = alarm(PortfolioAlarmKind.VALUE_BELOW, 10_000.0)
        val values = listOf(12_000.0, 9_900.0, 11_000.0, 9_000.0)
        assertEquals(2, run(a, values.map { reading(it) }))
        assertEquals(1, run(a, values.map { reading(it) }, repeating = false))
    }

    @Test
    fun valueAlarm_usesMatchingCurrencyOrUsdt() {
        val r = reading(45_000.0, currency = "CHF", usdt = 52_000.0)
        assertEquals(45_000.0, PortfolioAlarmLogic.totalIn("chf", r)!!, 1e-9)
        assertEquals(52_000.0, PortfolioAlarmLogic.totalIn("USD", r)!!, 1e-9)
        assertEquals(52_000.0, PortfolioAlarmLogic.totalIn("USDT", r)!!, 1e-9)
        // Andere Währung ohne Devisenkurs: diesmal nicht prüfen
        assertNull(PortfolioAlarmLogic.totalIn("EUR", r))
        val eur = alarm(PortfolioAlarmKind.VALUE_ABOVE, 1.0, currency = "EUR")
        assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(eur, r, start, 0))
    }

    /** Nach Wechsel der Umrechnungswährung (CHF-Alarm, Stand in EUR): mit Devisenkurs weiter wirksam. */
    @Test
    fun valueAlarm_otherCurrencyUsesFxRate() {
        val r = reading(48_000.0, currency = "EUR", usdt = 52_000.0).copy(usdTo = mapOf("CHF" to 0.8))
        assertEquals(41_600.0, PortfolioAlarmLogic.totalIn("CHF", r)!!, 1e-9)
        val chf = alarm(PortfolioAlarmKind.VALUE_ABOVE, 40_000.0, currency = "CHF")
        assertTrue(PortfolioAlarmLogic.decide(chf, r, start, 0) is PortfolioAlarmDecision.Fire)
        assertEquals(setOf("CHF"), PortfolioAlarmLogic.currenciesNeedingRate(listOf("CHF", "eur", "USDT", null), "EUR"))
    }

    /** Wiederholender %-Alarm: erst nach deutlicher Rückkehr (Marke 5 % → unter 4 %) wieder scharf. */
    @Test
    fun changeAlarm_needsRealReturnBeforeRearm() {
        val up = alarm(PortfolioAlarmKind.CHANGE_UP, 5.0, currency = null)
        // Zittern um die Marke: einmal
        assertEquals(1, run(up, listOf(5.1, 4.99, 5.05, 4.5, 5.2).map { reading(1000.0, it) }))
        // Klar zurück (unter 4 %), dann wieder drüber: zweimal
        assertEquals(2, run(up, listOf(5.1, 3.9, 5.2).map { reading(1000.0, it) }))
        assertEquals(1.0, PortfolioAlarmLogic.changeRearmMargin(5.0), 1e-12)
        assertEquals(0.5, PortfolioAlarmLogic.changeRearmMargin(1.0), 1e-12)
    }

    @Test
    fun changeUpAndDown() {
        val up = alarm(PortfolioAlarmKind.CHANGE_UP, 5.0, currency = null)
        val down = alarm(PortfolioAlarmKind.CHANGE_DOWN, 5.0, currency = null)
        val changes = listOf(1.0, 5.2, 6.0, -2.0, -5.5, -7.0, 0.0, 5.0)
        assertEquals(2, run(up, changes.map { reading(1000.0, it) }))
        assertEquals(1, run(down, changes.map { reading(1000.0, it) }))
        // Ohne Vergleichsbasis nie
        assertEquals(0, run(up, listOf(reading(1000.0, null))))
    }

    /** «Heute %» gerundet wie die Kursalarme: 3.00 → 3.09 ergibt 2.9999999999999956 % und meldet bei «+3 %». Wie iOS. */
    @Test
    fun changeAlarm_comparesRoundedPercent() {
        val change = (3.09 - 3.0) / 3.0 * 100.0
        assertTrue(change < 3.0)
        val up = alarm(PortfolioAlarmKind.CHANGE_UP, 3.0, currency = null)
        assertEquals(PortfolioAlarmDecision.Fire(3.0), PortfolioAlarmLogic.decide(up, reading(1000.0, change), start, 0))
        val down = alarm(PortfolioAlarmKind.CHANGE_DOWN, 3.0, currency = null)
        assertEquals(PortfolioAlarmDecision.Fire(3.0), PortfolioAlarmLogic.decide(down, reading(1000.0, -change), start, 0))
        assertEquals(3.0, PortfolioAlarmLogic.measure(up, reading(1000.0, change))!!, 0.0)
    }

    @Test
    fun cooldown_blocksFiring() {
        val a = alarm(PortfolioAlarmKind.VALUE_ABOVE, 100.0)
        val values = listOf(101.0, 90.0, 101.0)
        assertEquals(2, run(a, values.map { reading(it) }))
        assertEquals(1, run(a, values.map { reading(it) }, cooldown = 5))
    }

    @Test
    fun emptyDisabledOrInvalid_neverFire() {
        val a = alarm(PortfolioAlarmKind.VALUE_BELOW, 100.0)
        assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(a, reading(50.0).copy(empty = true), start, 0))
        assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(a.copy(enabled = false), reading(50.0), start, 0))
        assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(a, reading(0.0), start, 0))
        assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(a.copy(threshold = 0.0), reading(50.0), start, 0))
        assertTrue(PortfolioAlarmLogic.decide(a, reading(50.0), start, 0) is PortfolioAlarmDecision.Fire)
    }

    @Test
    fun validThresholdAndNames() {
        assertTrue(PortfolioAlarmLogic.isValidThreshold(PortfolioAlarmKind.VALUE_ABOVE, 1_000_000.0))
        assertFalse(PortfolioAlarmLogic.isValidThreshold(PortfolioAlarmKind.CHANGE_UP, 1_500.0))
        assertFalse(PortfolioAlarmLogic.isValidThreshold(PortfolioAlarmKind.CHANGE_DOWN, 0.0))
        assertFalse(PortfolioAlarmLogic.isValidThreshold(PortfolioAlarmKind.VALUE_BELOW, null))
        assertEquals(PortfolioAlarmKind.CHANGE_DOWN, PortfolioAlarmKind.fromName("CHANGE_DOWN"))
        assertNull(PortfolioAlarmKind.fromName("SOMETHING_NEW"))
    }

    @Test
    fun `missing coin price skips value alarms, neither fire nor rearm`() {
        // Ein Coin ohne Kurs zählt als 0: «unter Betrag» darf nicht auslösen, «über» nicht wieder scharf werden
        val missing = reading(50.0).copy(missingPrices = 1)
        assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(alarm(PortfolioAlarmKind.VALUE_BELOW, 100.0), missing, start, 0))
        val fired = alarm(PortfolioAlarmKind.VALUE_ABOVE, 100.0).copy(referenceAt = start)
        assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(fired, missing, start, 0))
        val firedBelow = alarm(PortfolioAlarmKind.VALUE_BELOW, 10.0).copy(referenceAt = start)
        assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(firedBelow, missing, start, 0))
        // Mit allen Kursen wie bisher
        assertTrue(PortfolioAlarmLogic.decide(alarm(PortfolioAlarmKind.VALUE_BELOW, 100.0), reading(50.0), start, 0) is PortfolioAlarmDecision.Fire)
        assertEquals(PortfolioAlarmDecision.Rearm, PortfolioAlarmLogic.decide(firedBelow, reading(50.0), start, 0))
        // Fehlender Vergleichskurs in «heute» betrifft nur die %-Alarme
        val partial = reading(50.0, change = 12.0).copy(todaySkipped = 1)
        assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(alarm(PortfolioAlarmKind.CHANGE_UP, 10.0), partial, start, 0))
        assertTrue(PortfolioAlarmLogic.decide(alarm(PortfolioAlarmKind.VALUE_BELOW, 100.0), partial, start, 0) is PortfolioAlarmDecision.Fire)
    }

    @Test
    fun `stale price skips every alarm kind, neither fire nor rearm`() {
        // Teilweise alter Kurs: ein alter Kurs gälte sonst als «jetzt» — kein Melden, kein Wieder-scharf-Stellen
        val stale = reading(50.0, change = 12.0).copy(stalePrices = 1)
        for (kind in PortfolioAlarmKind.entries) {
            val threshold = if (kind.isValue) 100.0 else 10.0
            assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(alarm(kind, threshold), stale, start, 0))
            assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(alarm(kind, threshold).copy(referenceAt = start), stale, start, 0))
        }
        // Alle frisch: wie bisher
        assertTrue(PortfolioAlarmLogic.decide(alarm(PortfolioAlarmKind.VALUE_BELOW, 100.0), stale.copy(stalePrices = 0), start, 0) is PortfolioAlarmDecision.Fire)
    }

    @Test
    fun `zaehlende Adresse ohne geladenes Guthaben sperrt jede Art`() {
        // Frisch wiederhergestellt: Guthaben nie geladen — der Gesamtwert ist zu tief, «unter Betrag» löste sonst aus
        val addresses = listOf(
            WatchedAddress(chain = AddressChain.BTC, address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh", lastBalance = null),
            WatchedAddress(chain = AddressChain.ETH, address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", lastBalance = null, countInPortfolio = false),
        )
        val pending = AddressBalance.pendingCount(addresses, start)
        assertEquals(1, pending)
        val r = reading(50.0, change = 12.0).copy(addressesPending = pending)
        for (kind in PortfolioAlarmKind.entries) {
            val threshold = if (kind.isValue) 100.0 else 10.0
            assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(alarm(kind, threshold), r, start, 0))
            assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(alarm(kind, threshold).copy(referenceAt = start), r, start, 0))
        }
        // Guthaben geladen: wie bisher
        val loaded = addresses.map { it.copy(lastBalance = "0.1") }
        assertEquals(0, AddressBalance.pendingCount(loaded, start))
        assertTrue(PortfolioAlarmLogic.decide(alarm(PortfolioAlarmKind.VALUE_BELOW, 100.0), r.copy(addressesPending = 0), start, 0) is PortfolioAlarmDecision.Fire)
    }

    @Test
    fun `alter Kurs unter 1 Prozent des Werts prueft trotzdem`() {
        // totalUsdt 55 (reading × 1.1): 0.5 USDT alter Kurs < 1 % → prüfen; 0.6 USDT > 1 % → nicht
        val small = reading(50.0, change = 12.0).copy(stalePrices = 1, staleValueUsdt = 0.5)
        assertFalse(PortfolioAlarmLogic.staleMaterial(small))
        assertTrue(PortfolioAlarmLogic.decide(alarm(PortfolioAlarmKind.VALUE_BELOW, 100.0), small, start, 0) is PortfolioAlarmDecision.Fire)
        assertTrue(PortfolioAlarmLogic.decide(alarm(PortfolioAlarmKind.CHANGE_UP, 10.0), small, start, 0) is PortfolioAlarmDecision.Fire)
        val material = small.copy(staleValueUsdt = 0.6)
        assertTrue(PortfolioAlarmLogic.staleMaterial(material))
        assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(alarm(PortfolioAlarmKind.VALUE_BELOW, 100.0), material, start, 0))
        // Ohne bekannten Gesamtwert: wesentlich
        assertTrue(PortfolioAlarmLogic.staleMaterial(small.copy(totalUsdt = null)))
        // Hinweis: nur die wesentlichen alten Kurse und (bei Wert-Alarmen) die Coins ohne Kurs
        assertNull(PortfolioAlarmLogic.pause(listOf(PortfolioAlarmKind.VALUE_BELOW), small, emptyList(), listOf("PEPE")))
        assertEquals(
            PortfolioAlarmPause.NoPrice(listOf("PEPE")),
            PortfolioAlarmLogic.pause(listOf(PortfolioAlarmKind.CHANGE_UP), material, emptyList(), listOf("PEPE")),
        )
    }

    @Test
    fun usdtAssumedOnlyForValueAlarmInOtherCurrencyWithoutMeasurement() {
        val chfReading = reading(1000.0)
        // Alarm in EUR, Stand in CHF, keine Messung USDT/USD: USDT = 1 angenommen
        assertTrue(PortfolioAlarmLogic.usdtAssumed(alarm(PortfolioAlarmKind.VALUE_ABOVE, 900.0, "EUR"), chfReading))
        // Gemessen: nichts angenommen
        assertFalse(PortfolioAlarmLogic.usdtAssumed(alarm(PortfolioAlarmKind.VALUE_ABOVE, 900.0, "EUR"), chfReading.copy(usdtUsdLive = 0.999)))
        // Gleiche Währung wie der Stand, USDT selbst und %-Alarme: kein Vermerk
        assertFalse(PortfolioAlarmLogic.usdtAssumed(alarm(PortfolioAlarmKind.VALUE_ABOVE, 900.0, "CHF"), chfReading))
        assertFalse(PortfolioAlarmLogic.usdtAssumed(alarm(PortfolioAlarmKind.VALUE_BELOW, 900.0, "USDT"), chfReading))
        assertFalse(PortfolioAlarmLogic.usdtAssumed(alarm(PortfolioAlarmKind.CHANGE_UP, 5.0, "EUR"), chfReading))
    }
}
