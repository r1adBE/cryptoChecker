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
 * Seit Build 103 (Review FUN-05): Prozess-Ende zwischen Mitteilung und Speichern löst keinen zweiten Alarmton aus
 * (`testdata/parity/alarm_delivery_marker.json`). Gegenstück: `Tests/AlarmDeliveryMarkerTests.swift`.
 */
class AlarmDeliveryMarkerParityTest {

    private val fixture = ParityJson.load("alarm_delivery_marker.json")

    @Test
    fun entscheidungWieGemeinsameTestfaelle() {
        assertEquals(fixture["freshMillis"].num()!!.toLong(), AlarmDeliveryMarker.FRESH_MILLIS)
        val cases = fixture["cases"].list()
        assertTrue(cases.size >= 8)
        for (raw in cases) {
            val c = raw.obj()
            val actual = AlarmDeliveryMarker.alreadyDelivered(
                c["markerAt"].num()?.toLong(), c["now"].num()!!.toLong(), c["shown"] as Boolean,
            )
            assertEquals(c["name"] as String, c["expected"] as Boolean, actual)
        }
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
