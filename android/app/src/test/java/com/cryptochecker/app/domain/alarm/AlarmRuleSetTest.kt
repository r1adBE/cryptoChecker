package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.domain.alarm.AlarmCardStatus.Kind
import com.cryptochecker.app.notification.AlarmDeliveryMarkers
import com.cryptochecker.app.notification.PortfolioAlarmDeliveryMarkers
import com.cryptochecker.app.util.StringResources
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.market.Lighter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus AlarmCardStatusTest

/** Statuszeile der Alarm-Karte: Reihenfolge der Gründe, Grenzen, ausgeschaltete Alarme. Wie iOS. */
class AlarmCardStatusTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val stale = 45 * minute

    private fun status(
        enabled: Boolean = true,
        allowed: Boolean = true,
        dex: Boolean = false,
        exchangeTime: Long? = null,
        lastUpdate: Long = now - minute,
        unchecked: Boolean = false,
        markFallback: Boolean = false,
    ) = AlarmCardStatus.of(enabled, allowed, dex, exchangeTime, lastUpdate, now, stale, unchecked, markFallback)

    @Test
    fun healthyAlarmHasNoLine() {
        assertNull(status())
        assertNull(status(exchangeTime = now - hour))
    }

    @Test
    fun disabledAlarmNeverHasALine() {
        assertNull(status(enabled = false, allowed = false, dex = true, lastUpdate = now - 10 * hour, unchecked = true))
    }

    @Test
    fun notificationsOffComesFirst() {
        assertEquals(Kind.NOTIFICATIONS_OFF, status(allowed = false, dex = true, lastUpdate = now - 10 * hour, unchecked = true))
    }

    @Test
    fun lowLiquidityBeforeStaleAndUnchecked() {
        assertEquals(Kind.LOW_LIQUIDITY, status(dex = true, exchangeTime = now - 3 * hour, unchecked = true))
    }

    @Test
    fun exchangeTimeOlderThanTwoHoursWaits() {
        assertEquals(Kind.WAITING_FOR_PRICE, status(exchangeTime = now - 2 * hour - 1, unchecked = true))
        assertNull(status(exchangeTime = now - 2 * hour))
    }

    @Test
    fun fetchOlderThanStaleThresholdWaits() {
        assertEquals(Kind.WAITING_FOR_PRICE, status(lastUpdate = now - stale - 1))
        assertNull(status(lastUpdate = now - stale))
        // Noch nie abgerufen: kein «Wartet» (dort steht ohnehin «—»)
        assertNull(status(lastUpdate = 0L))
    }

    @Test
    fun uncheckedOnlyWhenNothingElseApplies() {
        assertEquals(Kind.UNCHECKED, status(unchecked = true))
    }

    @Test
    fun markPriceFallbackWaitsForPrice() {
        // Nur Mark-Preis (kein Mittelkurs): Alarme lösen nicht aus — die Karte sagt «Wartet auf aktuellen Kurs»
        assertEquals(Kind.WAITING_FOR_PRICE, status(markFallback = true, unchecked = true))
        assertEquals(Kind.LOW_LIQUIDITY, status(dex = true, markFallback = true))
        assertEquals(true, AlarmFreshness.blocksAlarms(null, markFallback = true, now = now))
        assertEquals(false, AlarmFreshness.blocksAlarms(now - minute, markFallback = false, now = now))
        assertEquals(true, AlarmFreshness.blocksAlarms(now - 3 * hour, markFallback = false, now = now))
    }
}

// ---- aus AlarmDeliveryB104Test

/**
 * Review ARC2-02: Portfolio-Alarme laufen über [AlarmDelivery] mit eigenem Vermerk-Speicher (wie iOS
 * `AlarmDelivery.portfolio`). Gegenstück: `Tests/AlarmDeliveryB104Tests`.
 */
class AlarmDeliveryB104Test {

    private val at = 1_700_000_000_000L

    @Test
    fun portfolioVermerkeInEigenerDatei() {
        assertNotEquals(AlarmDeliveryMarkers.PREFS, PortfolioAlarmDeliveryMarkers.PREFS)
    }

    /** Paar- und Portfolio-Alarm mit derselben Id stören sich nicht. */
    @Test
    fun portfolioVermerkGetrenntVonPaarVermerk() = runBlocking {
        val pairs = AlarmDeliveryMarkerStore.InMemory()
        val portfolio = AlarmDeliveryMarkerStore.InMemory()
        AlarmDelivery(portfolio).deliver(
            alarmId = 5, now = at, cooldownMinutes = 0, holdAlarms = false,
            canShow = { true }, show = { false }, mark = { true },
        )
        assertEquals(at, portfolio.get(5))
        assertNull(pairs.get(5))
        pairs.retain(emptySet())
        assertEquals(at, portfolio.get(5))
    }

    /** Portfolio-Alarm: schon gemeldet (Prozess-Ende), aber inzwischen geändert (markTriggered 0 Zeilen) → Mitteilung weg. */
    @Test
    fun portfolioSchonGemeldetNichtGespeichertEntfernt() = runBlocking {
        val portfolio = AlarmDeliveryMarkerStore.InMemory()
        portfolio.set(6, at)
        var retracted = 0
        val result = AlarmDelivery(portfolio).deliver(
            alarmId = 6, now = at + 30_000L, cooldownMinutes = 0, holdAlarms = false,
            canShow = { true }, show = { true }, mark = { false }, retract = { retracted++ },
            isShown = { markerAt -> AlarmDeliveryMarker.shownSinceMarker(at + 100L, markerAt) },
        )
        assertFalse(result)
        assertEquals(1, retracted)
        assertNull(portfolio.get(6))
    }
}

// ---- aus AlarmDeliveryRetractTest

/** Gezeigte Meldung, aber Alarm nicht gespeichert (inzwischen bearbeitet): Meldung wieder weg. Wie iOS. */
class AlarmDeliveryRetractTest {

    private suspend fun deliver(shown: Boolean, saved: Boolean, retracted: MutableList<Long>): Boolean =
        AlarmDelivery().deliver(
            alarmId = 7, now = 1_000_000L, cooldownMinutes = 15, holdAlarms = false,
            canShow = { true }, show = { shown }, mark = { saved },
            retract = { retracted += 7L },
        )

    @Test
    fun notSavedRetractsTheNotification() = runBlocking {
        val retracted = ArrayList<Long>()
        assertFalse(deliver(shown = true, saved = false, retracted))
        assertEquals(listOf(7L), retracted)
    }

    @Test
    fun savedKeepsTheNotification() = runBlocking {
        val retracted = ArrayList<Long>()
        assertTrue(deliver(shown = true, saved = true, retracted))
        assertTrue(retracted.isEmpty())
    }

    @Test
    fun unconfirmedPostIsNotRetracted() = runBlocking {
        // Nicht bestätigt: nichts gespeichert, aber auch nichts zu entfernen (bleibt scharf, Abklingzeit)
        val retracted = ArrayList<Long>()
        assertFalse(deliver(shown = false, saved = true, retracted))
        assertTrue(retracted.isEmpty())
    }
}

// ---- aus AlarmFreshnessTest

/** Alarme nicht auf einem laut Börse über 2 Stunden alten Kurs; ohne Börsenzeit wie bisher. Wie iOS. */
class AlarmFreshnessTest {

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60_000L

    @Test
    fun withoutExchangeTimeNothingChanges() {
        assertFalse(AlarmFreshness.exchangeTimeTooOld(null, now))
        assertFalse(AlarmFreshness.exchangeTimeTooOld(0L, now))
        assertFalse(AlarmFreshness.exchangeTimeTooOld(-5L, now))
    }

    @Test
    fun oldExchangeTimeHoldsAlarms() {
        assertTrue(AlarmFreshness.exchangeTimeTooOld(now - 2 * hour - 1, now))
        assertTrue(AlarmFreshness.exchangeTimeTooOld(now - 26 * hour, now))
    }

    @Test
    fun recentOrFutureExchangeTimeFires() {
        assertFalse(AlarmFreshness.exchangeTimeTooOld(now - 2 * hour, now))
        assertFalse(AlarmFreshness.exchangeTimeTooOld(now - 5 * 60_000L, now))
        // Uhr der Börse etwas voraus: kein Grund zu schweigen
        assertFalse(AlarmFreshness.exchangeTimeTooOld(now + 60_000L, now))
    }

    /** Lighter ohne Handel in 24 h: der stehengebliebene Kurs bleibt sichtbar, löst aber keinen Alarm aus. */
    @Test
    fun lighterWithoutTradesIn24hHoldsAlarms() {
        val info = PairQuery("ADI", "USDC", "ADI", FuturesContractType.PERPETUAL)
        fun read(trades: Int) = Lighter().readTicker(
            0,
            """{"code":200,"order_book_stats":[{"symbol":"ADI","last_trade_price":8.8236,"daily_trades_count":$trades,""" +
                """"daily_base_token_volume":0,"daily_quote_token_volume":0,"daily_price_change":0}]}""",
            SimpleTicker(),
            info,
        )
        val quiet = read(0)
        assertTrue(quiet.last == 8.8236)
        assertTrue(AlarmFreshness.blocksAlarms(quiet.exchangeTime, markFallback = false, now = quiet.timestamp))
        val traded = read(5)
        assertFalse(AlarmFreshness.blocksAlarms(traded.exchangeTime, markFallback = false, now = traded.timestamp))
    }
}

// ---- aus AlarmSavedTextTest

/** Bestätigung nach dem Speichern eines Alarms — gleiche Fälle wie `AlarmLogicTests.testSavedText` (iOS). */
class AlarmSavedTextTest {

    private val de = Locale.GERMAN

    @Test
    fun conditionFrequencyAndNotificationsOff() {
        val condition = AlarmSavedText.pairCondition("BTC", "Kurs über 70’000 USDT")
        assertEquals("BTC Kurs über 70’000 USDT", condition)
        assertEquals("BTC Kurs über 70’000 USDT · einmalig", AlarmSavedText.detail(condition, "Einmalig", null, de))
        assertEquals(
            "BTC Kurs über 70’000 USDT · wiederholend · Mitteilungen aus",
            AlarmSavedText.detail(condition, "Wiederholend", "Mitteilungen aus", de),
        )
        // Portfolio-Alarm: fertige Bedingung
        assertEquals("Portfolio über 50’000.00 CHF · once", AlarmSavedText.detail("Portfolio über 50’000.00 CHF", "Once", null, Locale.ENGLISH))
    }

    @Test
    fun emptyPartsAreSkipped() {
        assertEquals("Kurs über 70’000", AlarmSavedText.pairCondition(" ", " Kurs über 70’000 "))
        assertEquals("einmalig", AlarmSavedText.detail(" ", "Einmalig", " ", de))
        assertEquals("BTC", AlarmSavedText.detail("BTC", "", null, de))
    }
}

// ---- aus AlarmSignalTest

class AlarmSignalTest {

    @Test
    fun fromName_unknownOrMissing_isSystem() {
        assertEquals(AlarmSignal.SYSTEM, AlarmSignal.fromName(null))
        assertEquals(AlarmSignal.SYSTEM, AlarmSignal.fromName("LOUD"))
        assertEquals(AlarmSignal.VIBRATE, AlarmSignal.fromName("VIBRATE"))
        AlarmSignal.entries.forEach { assertEquals(it, AlarmSignal.fromName(it.name)) }
    }

    @Test
    fun channelIds_stableAndDistinct() {
        assertEquals("alarms", AlarmSignal.channelId(AlarmSignal.SYSTEM, 0))
        assertEquals("alarms_v3", AlarmSignal.channelId(AlarmSignal.SYSTEM, 3))
        assertEquals("alarms_sound_vibrate", AlarmSignal.channelId(AlarmSignal.SOUND_VIBRATE, 0))
        assertEquals("alarms_sound_v2", AlarmSignal.channelId(AlarmSignal.SOUND, 2))
        // Ohne Ton keine Versionen
        assertEquals("alarms_vibrate", AlarmSignal.channelId(AlarmSignal.VIBRATE, 5))
        assertEquals("alarms_silent", AlarmSignal.channelId(AlarmSignal.SILENT, 5))
        val ids = AlarmSignal.entries.map { AlarmSignal.channelId(it, 4) }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it == "alarms_quiet" })
    }

    @Test
    fun isChannelOf_vibrateIsNotAVersionOfSystem() {
        assertTrue(AlarmSignal.isChannelOf(AlarmSignal.SYSTEM, "alarms"))
        assertTrue(AlarmSignal.isChannelOf(AlarmSignal.SYSTEM, "alarms_v12"))
        assertFalse(AlarmSignal.isChannelOf(AlarmSignal.SYSTEM, "alarms_vibrate"))
        assertFalse(AlarmSignal.isChannelOf(AlarmSignal.SYSTEM, "alarms_v"))
        assertFalse(AlarmSignal.isChannelOf(AlarmSignal.SYSTEM, "alarms_quiet"))
        assertFalse(AlarmSignal.isChannelOf(AlarmSignal.SOUND, "alarms_sound_vibrate"))
        assertTrue(AlarmSignal.isChannelOf(AlarmSignal.SOUND, "alarms_sound_v1"))
        assertFalse(AlarmSignal.isChannelOf(AlarmSignal.VIBRATE, "alarms_vibrate_v1"))
    }

    @Test
    fun staleChannelIds_onlySameSignalOldVersions() {
        val existing = listOf(
            "prices", "market", "alarms_quiet", "alarms", "alarms_v1", "alarms_v2",
            "alarms_vibrate", "alarms_silent", "alarms_sound", "alarms_sound_v1", "alarms_sound_vibrate",
        )
        assertEquals(listOf("alarms", "alarms_v1"), AlarmSignal.staleChannelIds(existing, AlarmSignal.SYSTEM, 2))
        assertEquals(listOf("alarms_sound"), AlarmSignal.staleChannelIds(existing, AlarmSignal.SOUND, 1))
        assertEquals(emptyList<String>(), AlarmSignal.staleChannelIds(existing, AlarmSignal.VIBRATE, 2))
        assertEquals(emptyList<String>(), AlarmSignal.staleChannelIds(existing, AlarmSignal.SILENT, 2))
    }

    @Test
    fun effective_alarmSwitchesOnlyRemove() {
        // System: der Kanal entscheidet; nur beides aus = lautlos
        assertEquals(AlarmSignal.SYSTEM, AlarmSignal.effective(AlarmSignal.SYSTEM, alarmSound = true, alarmVibrate = true))
        assertEquals(AlarmSignal.SYSTEM, AlarmSignal.effective(AlarmSignal.SYSTEM, alarmSound = false, alarmVibrate = true))
        assertEquals(AlarmSignal.SILENT, AlarmSignal.effective(AlarmSignal.SYSTEM, alarmSound = false, alarmVibrate = false))
        // Gewähltes Signal, vom Alarm eingeschränkt
        assertEquals(AlarmSignal.SOUND_VIBRATE, AlarmSignal.effective(AlarmSignal.SOUND_VIBRATE, true, true))
        assertEquals(AlarmSignal.VIBRATE, AlarmSignal.effective(AlarmSignal.SOUND_VIBRATE, false, true))
        assertEquals(AlarmSignal.SOUND, AlarmSignal.effective(AlarmSignal.SOUND_VIBRATE, true, false))
        // Nie mehr als gewählt
        assertEquals(AlarmSignal.VIBRATE, AlarmSignal.effective(AlarmSignal.VIBRATE, true, true))
        assertEquals(AlarmSignal.SILENT, AlarmSignal.effective(AlarmSignal.VIBRATE, true, false))
        assertEquals(AlarmSignal.SILENT, AlarmSignal.effective(AlarmSignal.SILENT, true, true))
    }
}

// ---- aus AlarmTimingHintTest

/** Hinweis «Wann Alarme geprüft werden» — wie `testAlarmTimingHint` (iOS, AlarmLogicTests). */
class AlarmTimingHintTest {

    @Test
    fun showsOnceAndNotWithTheFirstAlarmDialog() {
        assertTrue(AlarmTimingHint.shouldShow(alreadyShown = false, firstAlarmDialog = false))
        // Schon gezeigt
        assertFalse(AlarmTimingHint.shouldShow(alreadyShown = true, firstAlarmDialog = false))
        // Bestätigung des ersten Alarms zeigt denselben Satz: nicht zwei Dialoge
        assertFalse(AlarmTimingHint.shouldShow(alreadyShown = false, firstAlarmDialog = true))
    }

    @Test
    fun settingsPathJoinsLevels() {
        assertEquals(
            "Einstellungen › Kurs-Aktualisierung",
            AlarmTimingHint.settingsPath("Einstellungen", " Kurs-Aktualisierung "),
        )
        assertEquals("A › B", AlarmTimingHint.settingsPath("A", "", "B"))
    }

    /**
     * Der Satz ist der ganze Text der Bestätigung nach dem ersten Alarm — in allen 31 Sprachen
     * höchstens [AlarmTimingHint.MAX_CHARS] Zeichen (Android mit eingesetztem Namen «Pünktlich prüfen»),
     * ein Satz und für beide Plattformen vorhanden (iOS liest dieselben Texte).
     */
    @Test
    fun hintIsOneShortSentenceInEveryLanguage() {
        val languages = StringResources.byLanguage()
        assertEquals(31, languages.size)
        for ((folder, strings) in languages) {
            val exact = strings.getValue("settings_exact_checks")
            val android = strings.getValue("alarm_timing_hint_android").replace("%1\$s", exact)
            val ios = strings.getValue("alarm_timing_hint_ios")
            for ((name, text) in listOf("android" to android, "ios" to ios)) {
                assertTrue("$folder $name: ${text.length} Zeichen", text.length <= AlarmTimingHint.MAX_CHARS)
                assertFalse("$folder $name: Platzhalter übrig", text.contains('%'))
            }
        }
    }
}
