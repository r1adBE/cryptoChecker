package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.alarm.AlarmDelivery
import com.cryptochecker.app.domain.alarm.AlarmDeliveryMarker
import com.cryptochecker.app.domain.alarm.AlarmDeliveryMarkerStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review FUN-05: Prozess-Ende zwischen Mitteilung und Speichern löst keinen zweiten Alarmton aus
 * (`testdata/parity/alarm_delivery_marker.json`). Gegenstück: `Tests/AlarmDeliveryMarkerTests.swift`.
 */
class AlarmDeliveryMarkerParityTest {

    private val fixture = ParityJson.load("alarm_delivery_marker.json")

    @Test
    fun entscheidungWieGemeinsameTestfaelle() {
        assertEquals(fixture["freshMillis"].num()!!.toLong(), AlarmDeliveryMarker.FRESH_MILLIS)
        assertEquals(fixture["postedToleranceMillis"].num()!!.toLong(), AlarmDeliveryMarker.POSTED_TOLERANCE_MILLIS)
        val cases = fixture["cases"].list()
        assertTrue(cases.size >= 15)
        for (raw in cases) {
            val c = raw.obj()
            val markerAt = c["markerAt"].num()?.toLong()
            // «postedAt» statt «shown» — gezeigt nur, wenn ab dem Vermerk gemeldet
            val shown = if (c.containsKey("postedAt")) {
                AlarmDeliveryMarker.shownSinceMarker(c["postedAt"].num()?.toLong(), markerAt ?: 0L)
            } else {
                c["shown"] as Boolean
            }
            val actual = AlarmDeliveryMarker.alreadyDelivered(markerAt, c["now"].num()!!.toLong(), shown)
            assertEquals(c["name"] as String, c["expected"] as Boolean, actual)
        }
    }

    /** Review IOS3-R02: Auslösung zum Vermerk schon gespeichert? */
    @Test
    fun gespeichertSeitDemVermerkWieGemeinsameTestfaelle() {
        val cases = fixture["savedSinceCases"].list()
        assertTrue(cases.size >= 5)
        for (raw in cases) {
            val c = raw.obj()
            val actual = AlarmDeliveryMarker.savedSinceMarker(c["lastTriggeredAt"].num()!!.toLong(), c["markerAt"].num()!!.toLong())
            assertEquals(c["name"] as String, c["expected"] as Boolean, actual)
        }
    }

    /**
     * Review IOS3-R02: ganze Läufe von [AlarmDelivery.deliver] — ein liegengebliebener Vermerk einer schon
     * gespeicherten Auslösung verschluckt die neue nicht; ein Prozess-Ende zwischen Melden und Speichern speichert weiter nur.
     */
    @Test
    fun meldenWieGemeinsameTestfaelle() = runBlocking {
        val cases = fixture["deliverCases"].list()
        assertTrue(cases.size >= 7)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val store = AlarmDeliveryMarkerStore.InMemory()
            c["markerAt"].num()?.toLong()?.let { store.set(1, it) }
            val lastTriggeredAt = c["lastTriggeredAt"].num()!!.toLong()
            var shows = 0
            var marks = 0
            val result = AlarmDelivery(store).deliver(
                alarmId = 1, now = c["now"].num()!!.toLong(), cooldownMinutes = 0,
                holdAlarms = c["holdAlarms"] as? Boolean ?: false,
                canShow = { true }, show = { shows++; true }, mark = { marks++; true },
                isShown = { c["shown"] as Boolean },
                savedSince = { AlarmDeliveryMarker.savedSinceMarker(lastTriggeredAt, it) },
            )
            assertEquals("$name: show", c["expectShow"] as Boolean, shows > 0)
            assertEquals("$name: mark", c["expectMark"] as Boolean, marks > 0)
            assertEquals("$name: Vermerk danach", c["expectMarkerAfter"].num()?.toLong(), store.get(1))
            assertEquals("$name: Ergebnis", c["expectResult"] as Boolean, result)
        }
    }

    /** Review ARC3-R03: Vermerke nur löschen/kürzen, wenn der Stand dieses Laufs bleibt. */
    @Test
    fun zuruecksetzenWieGemeinsameTestfaelle() {
        val cases = fixture["resetCases"].list()
        assertTrue(cases.size >= 4)
        for (raw in cases) {
            val c = raw.obj()
            val actual = AlarmDeliveryMarker.shouldClear(c["storedKept"] as Boolean, c["volatileCopy"] as Boolean)
            assertEquals(c["name"] as String, c["expected"] as Boolean, actual)
        }
    }

    /** Eine ältere Mitteilung desselben Alarms bestätigt nicht — Abklingzeit statt stillem Speichern. */
    @Test
    fun aeltereMitteilungBestaetigtNicht() = runBlocking {
        val store = AlarmDeliveryMarkerStore.InMemory()
        val at = 1_700_000_000_000L
        store.set(11, at)
        var marks = 0
        var seenMarker: Long? = null
        val result = AlarmDelivery(store).deliver(
            alarmId = 11, now = at + 60_000L, cooldownMinutes = 0, holdAlarms = false,
            canShow = { true }, show = { true }, mark = { marks++; true },
            isShown = { markerAt -> seenMarker = markerAt; AlarmDeliveryMarker.shownSinceMarker(at - 3_600_000L, markerAt) },
        )
        assertFalse(result)
        assertEquals(at, seenMarker)
        assertEquals(0, marks)
        assertEquals(at, store.get(11))
    }

    /** Schon gemeldet, aber nicht mehr speicherbar (inzwischen bearbeitet) → Mitteilung wieder weg. */
    @Test
    fun schonGemeldetAberNichtGespeichertEntferntMitteilung() = runBlocking {
        val store = AlarmDeliveryMarkerStore.InMemory()
        val at = 1_700_000_000_000L
        store.set(12, at)
        var retracted = 0
        val result = AlarmDelivery(store).deliver(
            alarmId = 12, now = at + 60_000L, cooldownMinutes = 0, holdAlarms = false,
            canShow = { true }, show = { true }, mark = { false }, retract = { retracted++ },
            isShown = { markerAt -> AlarmDeliveryMarker.shownSinceMarker(at + 300L, markerAt) },
        )
        assertFalse(result)
        assertEquals(1, retracted)
        assertNull(store.get(12))
    }

    /** Vermerke gelöschter bzw. ersetzter Alarme aufräumen. */
    @Test
    fun vermerkeAufraeumen() {
        val store = AlarmDeliveryMarkerStore.InMemory()
        store.set(1, 10L)
        store.set(2, 20L)
        store.set(3, 30L)
        store.retain(setOf(1L, 3L))
        assertNull(store.get(2))
        store.remove(listOf(3L))
        assertNull(store.get(3))
        assertEquals(10L, store.get(1))
    }

    /** Erster Lauf endet nach dem Melden (vor dem Speichern); der nächste Lauf speichert nur, ohne erneut zu melden. */
    @Test
    fun prozessEndeNachDemMeldenKlingeltNichtNochmals() = runBlocking {
        val store = AlarmDeliveryMarkerStore.InMemory()
        val at = 1_700_000_000_000L
        // Lauf 1: «Prozess endet» in mark (Ausnahme), der Vermerk bleibt stehen
        runCatching {
            AlarmDelivery(store).deliver(
                alarmId = 8, now = at, cooldownMinutes = 0, holdAlarms = false,
                canShow = { true }, show = { true }, mark = { throw IllegalStateException("Prozess beendet") },
            )
        }
        assertEquals(at, store.get(8))
        // Lauf 2 im neuen Prozess: Mitteilung steht noch da → nur speichern
        var shows = 0
        var marks = 0
        val result = AlarmDelivery(store).deliver(
            alarmId = 8, now = at + 60_000L, cooldownMinutes = 0, holdAlarms = false,
            canShow = { true }, show = { shows++; true }, mark = { marks++; true }, isShown = { true },
        )
        assertFalse(result)
        assertEquals(0, shows)
        assertEquals(1, marks)
        assertNull(store.get(8))
    }

    /** Mitteilung weggewischt: der Vermerk zählt als unbestätigter Versuch (Abklingzeit), kein stilles Speichern. */
    @Test
    fun weggewischteMitteilungWartetDieAbklingzeit() = runBlocking {
        val store = AlarmDeliveryMarkerStore.InMemory()
        val at = 1_700_000_000_000L
        store.set(9, at)
        var marks = 0
        val result = AlarmDelivery(store).deliver(
            alarmId = 9, now = at + 60_000L, cooldownMinutes = 0, holdAlarms = false,
            canShow = { true }, show = { true }, mark = { marks++; true }, isShown = { false },
        )
        assertFalse(result)
        assertEquals(0, marks)
    }

    /** Normaler Ablauf: Vermerk vor dem Melden, nach dem Speichern wieder weg. */
    @Test
    fun vermerkNurWaehrendDesMeldens() = runBlocking {
        val store = AlarmDeliveryMarkerStore.InMemory()
        var seenDuringShow: Long? = null
        val result = AlarmDelivery(store).deliver(
            alarmId = 10, now = 5_000L, cooldownMinutes = 0, holdAlarms = false,
            canShow = { true }, show = { seenDuringShow = store.get(10); true }, mark = { true },
        )
        assertTrue(result)
        assertEquals(5_000L, seenDuringShow)
        assertNull(store.get(10))
    }
}
