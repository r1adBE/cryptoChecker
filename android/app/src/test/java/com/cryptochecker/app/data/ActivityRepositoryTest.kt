package com.cryptochecker.app.data

import com.cryptochecker.app.domain.activity.ActivityReport
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.domain.activity.OiSample
import com.cryptochecker.app.domain.activity.SignalKind
import com.cryptochecker.app.domain.activity.SignalSeverity
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ergebnisse «Ungewöhnliche Aktivität» je Paar ([ActivityRepository]) in SharedPreferences (hier im Speicher): Berichte
 * überstehen einen Neustart, Open-Interest-Verlauf samt Übernahme der alten Einzelmessung, Verlauf der OI-Alarme,
 * Meldezeit und zurückgehaltene Signale; Aufräumen gelöschter und bearbeiteter Paare.
 */
class ActivityRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: FolderContext

    @Before
    fun setUp() {
        context = FolderContext(temp.newFolder())
    }

    private val prefs get() = context.getSharedPreferences("activity", 0)

    private val strong = ActivitySignal(SignalKind.PRICE_MOVE, SignalSeverity.STRONG, value = 12.5, factor = 3.0, seenAt = 1_000L)
    private val notable = ActivitySignal(SignalKind.VOLUME_SPIKE, SignalSeverity.NOTABLE, value = Double.NaN, seenAt = 2_000L)

    @Test
    fun reportsSurviveARestart() {
        val repo = ActivityRepository(context)
        assertTrue(repo.reports.value.isEmpty())
        repo.putAll(emptyMap())
        assertNull("leere Übernahme schreibt nichts", prefs.getString("reports", null))

        repo.putAll(mapOf(1L to ActivityReport(listOf(strong, notable), computedAt = 5_000L), 2L to ActivityReport(emptyList(), 6_000L)))
        assertEquals(listOf(strong, notable), repo.report(1L)!!.signals)

        val restarted = ActivityRepository(context)
        val report = restarted.report(1L)!!
        assertEquals(5_000L, report.computedAt)
        assertEquals(strong, report.signals[0])
        // Nicht endlicher Wert als 0 gespeichert, fehlender Faktor bleibt leer
        assertEquals(notable.copy(value = 0.0), report.signals[1])
        assertEquals(setOf(1L, 2L), restarted.reports.value.keys)
        assertNull(restarted.report(3L))
    }

    @Test
    fun unreadableOrPartlyUnknownReportsAreSkipped() {
        prefs.edit().putString("reports", "{kaputt").apply()
        assertTrue(ActivityRepository(context).reports.value.isEmpty())

        prefs.edit().putString(
            "reports",
            """{"x":{"t":1},"7":{"t":9,"s":[{"k":"UNBEKANNT"},{"k":"FUNDING_EXTREME","sv":"?","v":0.5,"f":null,"a":3}]},"8":{}}""",
        ).apply()
        val repo = ActivityRepository(context)
        assertEquals(setOf(7L, 8L), repo.reports.value.keys)
        assertEquals(
            listOf(ActivitySignal(SignalKind.FUNDING_EXTREME, SignalSeverity.NOTABLE, 0.5, null, 3L)),
            repo.report(7L)!!.signals,
        )
        assertEquals(ActivityReport(emptyList(), 0L), repo.report(8L))
    }

    @Test
    fun openInterestSeriesTakesOverTheOldSingleReading() {
        // Alte Einzelmessung (vor dem Verlauf) zählt als Verlauf mit einer Messung ohne Quelle
        prefs.edit().putString("open_interest", """{"4":{"u":1500.0,"t":100}}""").apply()
        val repo = ActivityRepository(context)
        assertEquals(listOf(OiSample(1500.0, 100L)), repo.oiSeries(4L))
        assertTrue(repo.oiSeries(5L).isEmpty())

        repo.setOiSeries(4L, listOf(OiSample(1600.0, 200L, "Binance"), OiSample(Double.POSITIVE_INFINITY, 300L)))
        assertEquals(listOf(OiSample(1600.0, 200L, "Binance"), OiSample(0.0, 300L)), repo.oiSeries(4L))
        assertEquals("übernommen: alte Messung weg", "{}", prefs.getString("open_interest", null))

        // Einträge, die keine Liste sind, fallen weg; leere Quelle = unbekannt
        prefs.edit().putString("open_interest_series", """{"4":[[1,2.0," "],"kaputt",[3,4.0]]}""").apply()
        assertEquals(listOf(OiSample(2.0, 1L), OiSample(4.0, 3L)), repo.oiSeries(4L))
    }

    @Test
    fun oiHistoryNotificationsAndPendingKinds() {
        val repo = ActivityRepository(context)
        val now = 10 * 3_600_000L
        repo.appendOiHistory(1L, DerivativesAlarm.OiPoint(100.0, now - 20 * 60_000L), now)
        repo.appendOiHistory(1L, DerivativesAlarm.OiPoint(110.0, now), now)
        // Zu dicht an der letzten Messung: nicht angehängt
        repo.appendOiHistory(1L, DerivativesAlarm.OiPoint(120.0, now + 60_000L), now)
        assertEquals(listOf(100.0, 110.0), repo.oiHistory(1L).map { it.units })
        assertTrue(repo.oiHistory(2L).isEmpty())

        assertEquals(0L, repo.lastNotifiedAt(1L))
        repo.setNotifiedAt(1L, 777L)
        assertEquals(777L, repo.lastNotifiedAt(1L))

        assertTrue(repo.pendingKinds(1L).isEmpty())
        repo.setPendingKinds(1L, setOf(SignalKind.FUNDING_EXTREME, SignalKind.PRICE_MOVE))
        assertEquals(setOf(SignalKind.PRICE_MOVE, SignalKind.FUNDING_EXTREME), repo.pendingKinds(1L))
        assertEquals("""{"1":["PRICE_MOVE","FUNDING_EXTREME"]}""", prefs.getString("pending", null))
        repo.setPendingKinds(1L, emptySet())
        assertTrue(repo.pendingKinds(1L).isEmpty())
        // Unbekannte Namen werden übergangen
        prefs.edit().putString("pending", """{"1":["PRICE_MOVE","ALT"]}""").apply()
        assertEquals(setOf(SignalKind.PRICE_MOVE), repo.pendingKinds(1L))
    }

    @Test
    fun deletedAndEditedPairsAreCleanedUp() {
        val repo = ActivityRepository(context)
        repo.putAll((1L..3L).associateWith { ActivityReport(listOf(strong), it * 10) })
        for (id in 1L..3L) {
            repo.setNotifiedAt(id, id)
            repo.setOiSeries(id, listOf(OiSample(1.0, id)))
            repo.setPendingKinds(id, setOf(SignalKind.PRICE_MOVE))
            repo.appendOiHistory(id, DerivativesAlarm.OiPoint(5.0, 1_000L), 1_000L)
        }
        prefs.edit().putString("open_interest", """{"1":{"u":1,"t":1},"x":{}}""").apply()

        // Paar 3 gelöscht
        repo.retain(setOf(1L, 2L))
        assertEquals(setOf(1L, 2L), repo.reports.value.keys)
        assertEquals(0L, repo.lastNotifiedAt(3L))
        assertTrue(repo.oiSeries(3L).isEmpty())
        assertTrue(repo.pendingKinds(3L).isEmpty())
        assertTrue(repo.oiHistory(3L).isEmpty())
        assertEquals("""{"1":{"u":1,"t":1}}""", prefs.getString("open_interest", null))
        // Nichts zu löschen: keine Änderung
        val before = prefs.all
        repo.retain(setOf(1L, 2L, 9L))
        assertEquals(before, prefs.all)

        // Paar 2 bearbeitet (gleiche Id, anderes Paar): alles des alten Paars weg
        repo.forget(2L)
        assertNull(repo.report(2L))
        assertEquals(0L, repo.lastNotifiedAt(2L))
        assertTrue(repo.oiSeries(2L).isEmpty())
        assertEquals(1L, repo.lastNotifiedAt(1L))
        // Unbekannte Id: nur die Einträge (keine Berichte) aufgeräumt
        repo.forget(42L)
        assertEquals(setOf(1L), ActivityRepository(context).reports.value.keys)
    }
}
