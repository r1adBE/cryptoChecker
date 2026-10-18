package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.notification.NotificationBudget
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Ablauf «ausgelösten Alarm melden» ohne Android-Abhängigkeiten (Spiegel von `report` in
 * RefreshEffects, damit die Kette Kurs → Alarm → Meldung als Unit-Test prüfbar ist):
 * Nachtruhe oder keine zustellbare Meldung → nichts tun, Alarm bleibt scharf. Sonst ZUERST
 * melden ([show]) und erst speichern ([mark]), wenn die Meldung bestätigt ist; unbestätigte
 * Meldungen werden frühestens nach der Abklingzeit wiederholt ([NotificationBudget.retryBlocked]).
 * Lässt sich der Alarm danach nicht speichern (inzwischen bearbeitet, abgeschaltet oder gelöscht),
 * wird die schon gezeigte Meldung wieder entfernt ([retract]) — sie gehörte zu einem Alarm, den es so
 * nicht mehr gibt.
 */
class AlarmDelivery {
    /** Alarm-Id → Zeitpunkt des unbestätigten Versuchs (nur im Speicher). */
    private val unconfirmed = ConcurrentHashMap<Long, Long>()

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
    ): Boolean {
        if (holdAlarms) return false
        if (!QuietHours.mayFire(holdAlarms, canShow())) return false
        if (NotificationBudget.retryBlocked(unconfirmed[alarmId], now, cooldownMinutes)) return false
        // Melden, Prüfen und Speichern nicht abbrechbar: sonst Meldung ohne gespeicherten Alarm
        return withContext(NonCancellable) {
            if (!show()) {
                unconfirmed[alarmId] = now
                return@withContext false
            }
            unconfirmed.remove(alarmId)
            val saved = mark()
            if (!saved) retract()
            saved
        }
    }
}
