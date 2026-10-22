package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.notification.NotificationBudget
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Ablauf «ausgelösten Alarm melden» ohne Android-Abhängigkeiten (Spiegel von `report` in
 * RefreshEffects, damit die Kette Kurs → Alarm → Meldung als Unit-Test prüfbar ist):
 * Nachtruhe, «1 h später» ([AlarmSnooze]) oder keine zustellbare Meldung → nichts tun, Alarm bleibt scharf. Sonst ZUERST
 * melden ([show]) und erst speichern ([mark]), wenn die Meldung bestätigt ist; unbestätigte
 * Meldungen werden frühestens nach der Abklingzeit wiederholt ([NotificationBudget.retryBlocked]).
 * Lässt sich der Alarm danach nicht speichern (inzwischen bearbeitet, abgeschaltet oder gelöscht),
 * wird die schon gezeigte Meldung wieder entfernt ([retract]) — sie gehörte zu einem Alarm, den es so
 * nicht mehr gibt.
 *
 * Seit Build 103 liegt der Versuch dauerhaft in [markers] (wie iOS `AlarmDelivery`) und wird schon VOR dem Melden
 * vermerkt: Endet der Prozess zwischen Melden und Speichern, findet der nächste Lauf einen frischen Merker
 * ([AlarmDeliveryMarker.alreadyDelivered]) — steht die Mitteilung noch da ([isShown]), wird der Alarm nur noch als
 * gemeldet gespeichert statt ein zweites Mal zu klingeln.
 */
class AlarmDelivery(
    /** Alarm-Id → Zeitpunkt des laufenden bzw. unbestätigten Versuchs; Standard nur im Speicher (Tests). */
    private val markers: AlarmDeliveryMarkerStore = AlarmDeliveryMarkerStore.InMemory(),
) {

    /** @return true = gemeldet und gespeichert (zählt als ausgelöst). */
    suspend fun deliver(
        alarmId: Long,
        now: Long,
        cooldownMinutes: Int,
        holdAlarms: Boolean,
        canShow: () -> Boolean,
        show: suspend () -> Boolean,
        mark: suspend () -> Boolean,
        retract: suspend () -> Unit = {},
        /** «1 h später» läuft ([AlarmSnooze.isSnoozed]): wie die Nachtruhe — nicht melden, scharf bleiben. */
        snoozed: Boolean = false,
        /** Seit Build 103: Steht die Mitteilung dieses Alarms noch unter den offenen? */
        isShown: suspend () -> Boolean = { false },
    ): Boolean {
        val pending = markers.get(alarmId)
        // Seit Build 103: voriger Lauf endete zwischen Melden und Speichern — nur speichern, nicht erneut melden.
        // false: in diesem Lauf nichts gemeldet (keine zweite Ansage, nicht gezählt)
        if (pending != null && AlarmDeliveryMarker.alreadyDelivered(pending, now, isShown())) {
            withContext(NonCancellable) {
                mark()
                markers.set(alarmId, null)
            }
            return false
        }
        if (holdAlarms || snoozed) return false
        if (!QuietHours.mayFire(holdAlarms, canShow())) return false
        if (NotificationBudget.retryBlocked(pending, now, cooldownMinutes)) return false
        // Melden, Prüfen und Speichern nicht abbrechbar: sonst Meldung ohne gespeicherten Alarm
        return withContext(NonCancellable) {
            // «Wird gemeldet» vor dem Melden vermerken (dauerhaft, wie ein unbestätigter Versuch)
            markers.set(alarmId, now)
            // Nicht bestätigt: Vermerk bleibt — frühestens nach der Abklingzeit erneut
            if (!show()) return@withContext false
            val saved = mark()
            markers.set(alarmId, null)
            if (!saved) retract()
            saved
        }
    }
}

/**
 * Seit Build 103: Vermerk «Alarm wird gemeldet» (Alarm-Id → Zeitpunkt), dauerhaft in der App
 * ([com.cryptochecker.app.notification.AlarmDeliveryMarkers]), im Test nur im Speicher.
 */
interface AlarmDeliveryMarkerStore {
    fun get(alarmId: Long): Long?

    /** null = Vermerk entfernen. */
    fun set(alarmId: Long, at: Long?)

    class InMemory : AlarmDeliveryMarkerStore {
        private val map = ConcurrentHashMap<Long, Long>()
        override fun get(alarmId: Long): Long? = map[alarmId]
        override fun set(alarmId: Long, at: Long?) {
            if (at == null) map.remove(alarmId) else map[alarmId] = at
        }
    }
}

/** Seit Build 103: Entscheidung nach einem Prozess-Ende zwischen Melden und Speichern (rein; `alarm_delivery_marker.json`). */
object AlarmDeliveryMarker {
    /** So lange gilt ein Vermerk als frisch (danach normaler Neuversuch nach der Abklingzeit). Wie iOS. */
    const val FRESH_MILLIS = 10 * 60_000L

    /** Frischer Vermerk und die Mitteilung steht noch da: schon gemeldet, nur noch speichern. */
    fun alreadyDelivered(markerAt: Long?, now: Long, shown: Boolean): Boolean =
        shown && markerAt != null && markerAt > 0L && now - markerAt in 0 until FRESH_MILLIS
}
