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
 * Der Versuch liegt dauerhaft in [markers] (wie iOS `AlarmDelivery`) und wird schon VOR dem Melden
 * vermerkt: Endet der Prozess zwischen Melden und Speichern, findet der nächste Lauf einen frischen Merker
 * ([AlarmDeliveryMarker.alreadyDelivered]) — steht die Mitteilung noch da ([isShown]), wird der Alarm nur noch als
 * gemeldet gespeichert statt ein zweites Mal zu klingeln.
 *
 * [isShown] bekommt den Zeitpunkt des Vermerks — nur eine Mitteilung, die NACH dem Vermerk gemeldet
 * wurde ([AlarmDeliveryMarker.shownSinceMarker]), bestätigt; eine ältere desselben Alarms nicht. Lässt sich der Alarm
 * dann nicht speichern, wird die Mitteilung wie im normalen Ablauf wieder entfernt.
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
        /**
         * Steht die Mitteilung dieses Alarms noch unter den offenen? Mit dem Zeitpunkt des
         * Vermerks: nur eine danach gemeldete zählt ([AlarmDeliveryMarker.shownSinceMarker]).
         */
        isShown: suspend (markerAt: Long) -> Boolean = { false },
        /**
         * Ist die Auslösung zum Vermerk schon gespeichert ([AlarmDeliveryMarker.savedSinceMarker] mit
         * `lastTriggeredAt` des ausgewerteten Alarms)? Dann ist der Vermerk liegen geblieben (Prozess-Ende nach dem Speichern).
         */
        savedSince: (markerAt: Long) -> Boolean = { false },
    ): Boolean {
        var pending = markers.get(alarmId)
        // Liegengebliebener Vermerk einer schon gespeicherten Auslösung → verwerfen und normal melden
        // (sonst würde eine neue, echte Auslösung still als «schon gemeldet» gespeichert)
        if (pending != null && savedSince(pending)) {
            markers.set(alarmId, null)
            pending = null
        }
        // Voriger Lauf endete zwischen Melden und Speichern — nur speichern, nicht erneut melden.
        // false: in diesem Lauf nichts gemeldet (keine zweite Ansage, nicht gezählt)
        if (pending != null && AlarmDeliveryMarker.alreadyDelivered(pending, now, isShown(pending))) {
            withContext(NonCancellable) {
                // Nicht gespeichert (inzwischen bearbeitet/gelöscht) → Mitteilung wieder weg
                if (!mark()) retract()
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
 * Vermerk «Alarm wird gemeldet» (Alarm-Id → Zeitpunkt), dauerhaft in der App
 * ([com.cryptochecker.app.notification.AlarmDeliveryMarkers]), im Test nur im Speicher.
 */
interface AlarmDeliveryMarkerStore {
    fun get(alarmId: Long): Long?

    /** null = Vermerk entfernen. */
    fun set(alarmId: Long, at: Long?)

    /** Vermerke dieser Alarme entfernen (gelöscht bzw. durch eine Sicherung ersetzt). */
    fun remove(alarmIds: Collection<Long>) = alarmIds.forEach { set(it, null) }

    /** Nur Vermerke noch vorhandener Alarme behalten. */
    fun retain(alarmIds: Set<Long>)

    class InMemory : AlarmDeliveryMarkerStore {
        private val map = ConcurrentHashMap<Long, Long>()
        override fun get(alarmId: Long): Long? = map[alarmId]
        override fun set(alarmId: Long, at: Long?) {
            if (at == null) map.remove(alarmId) else map[alarmId] = at
        }
        override fun retain(alarmIds: Set<Long>) {
            map.keys.retainAll(alarmIds)
        }
    }
}

/** Entscheidung nach einem Prozess-Ende zwischen Melden und Speichern (rein; `alarm_delivery_marker.json`). */
object AlarmDeliveryMarker {
    /** So lange gilt ein Vermerk als frisch (danach normaler Neuversuch nach der Abklingzeit). Wie iOS. */
    const val FRESH_MILLIS = 10 * 60_000L

    /** Frischer Vermerk und die Mitteilung steht noch da: schon gemeldet, nur noch speichern. */
    fun alreadyDelivered(markerAt: Long?, now: Long, shown: Boolean): Boolean =
        shown && markerAt != null && markerAt > 0L && now - markerAt in 0 until FRESH_MILLIS

    /** Spielraum für die Meldezeit des Systems gegenüber dem Vermerk (wie iOS 1 s). */
    const val POSTED_TOLERANCE_MILLIS = 1_000L

    /**
     * Zählt die offene Mitteilung (Meldezeit [postedAt], null = keine offen) als die nach dem Vermerk
     * [markerAt] gemeldete? Eine ältere desselben Alarms (z. B. vom vorigen Auslösen) bestätigt nichts.
     */
    fun shownSinceMarker(postedAt: Long?, markerAt: Long): Boolean =
        postedAt != null && postedAt >= markerAt - POSTED_TOLERANCE_MILLIS

    /**
     * Ist die Auslösung zum Vermerk [markerAt] schon gespeichert? `mark` speichert `lastTriggeredAt`
     * mit derselben Zeit wie der Vermerk; ein Vermerk aus der Zeit davor gehört zu einer ungespeicherten Auslösung.
     */
    fun savedSinceMarker(lastTriggeredAt: Long, markerAt: Long): Boolean =
        markerAt > 0L && lastTriggeredAt >= markerAt

    /**
     * Vermerke löschen bzw. gegen die Alarme dieses Laufs kürzen? Nicht, solange die Datenbank nur im
     * Speicher liegt und der nächste Prozess den Stand der Datei zurückbringt — gesperrt behaltene Datei ([storedKept])
     * oder Kopie einer alten Datei ([volatileCopy], nur iOS; Android übergibt false): Die Vermerke gehören dann zu den
     * echten Alarmen.
     * Gemeinsame Fälle in `alarm_delivery_marker.json` («resetCases»).
     */
    fun shouldClear(storedKept: Boolean, volatileCopy: Boolean): Boolean = !storedKept && !volatileCopy
}
