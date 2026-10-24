package com.cryptochecker.app.domain.alarm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Alarme, die bei der Prüfung ausgelassen werden mussten (kein Devisenkurs für die Alarmwährung,
 * keine Kerzen für «Nahe am Hoch/Tief» bzw. den Volumen-Spike): «nicht geprüft seit …» je Alarm-Id.
 * Bewusst nur im Speicher (nur dieses Gerät, nicht in der Sicherung, keine Room-Spalte); nach einem
 * Neustart der App leer, bis die nächste Prüfung wieder etwas auslässt. Spiegel: AlarmCheckStatus.swift.
 */
@Singleton
class AlarmCheckStatus @Inject constructor() {

    enum class Reason {
        /** Kein Faktor Quote → Alarmwährung (bzw. Kerzenwährung bei «Nahe am Hoch/Tief»). */
        NO_RATE,

        /** Keine Tages- bzw. Stundenkerzen. */
        NO_CANDLES,

        /** Keine Funding- bzw. Open-Interest-Werte der Quelle (Fehlschlag, gesperrt, nicht geliefert). */
        NO_DATA,
    }

    /** [since] = erste ausgelassene Prüfung in Folge; [reason] = Grund der letzten. */
    data class Unchecked(val reason: Reason, val since: Long)

    private val _state = MutableStateFlow<Map<Long, Unchecked>>(emptyMap())

    /** Alarm-Id → nicht geprüft; fehlt die Id, wurde der Alarm zuletzt geprüft (oder noch nie ausgelassen). */
    val state: StateFlow<Map<Long, Unchecked>> = _state.asStateFlow()

    private val lock = Any()

    /** Laufender Durchlauf ([batch]): Änderungen hier sammeln, am Ende EINMAL veröffentlichen (unter [lock]). */
    private var working: HashMap<Long, Unchecked>? = null
    private var depth = 0

    /**
     * Ein Prüfdurchlauf über viele Paare: Vermerke werden gesammelt und am Ende einmal veröffentlicht – sonst kopierte
     * jeder neue Vermerk die ganze Karte (bei vielen fehlenden Devisenkursen quadratisch). Verschachtelt und
     * gleichzeitig nutzbar; während des Durchlaufs zeigt [state] noch den vorigen Stand. Wie iOS (Einträge einzeln).
     */
    suspend fun <T> batch(block: suspend () -> T): T {
        synchronized(lock) { if (depth++ == 0) working = HashMap(_state.value) }
        try {
            return block()
        } finally {
            synchronized(lock) {
                if (--depth == 0) {
                    val done = working
                    working = null
                    if (done != null && done != _state.value) _state.value = done.toMap()
                }
            }
        }
    }

    fun markUnchecked(alarmId: Long, reason: Reason, now: Long) {
        synchronized(lock) {
            working?.let { w ->
                w[alarmId] = Unchecked(reason, w[alarmId]?.since ?: now)
                return
            }
        }
        _state.update { unchecked(it, alarmId, reason, now) }
    }

    fun markChecked(alarmId: Long) {
        synchronized(lock) {
            working?.let { w ->
                w.remove(alarmId)
                return
            }
        }
        _state.update { checked(it, alarmId) }
    }

    companion object {
        /** Ausgelassen: «seit» bleibt die erste ausgelassene Prüfung, der Grund wird der neueste. */
        fun unchecked(map: Map<Long, Unchecked>, alarmId: Long, reason: Reason, now: Long): Map<Long, Unchecked> {
            val next = Unchecked(reason, map[alarmId]?.since ?: now)
            return if (map[alarmId] == next) map else map + (alarmId to next)
        }

        /** Geprüft: Marke entfernen. */
        fun checked(map: Map<Long, Unchecked>, alarmId: Long): Map<Long, Unchecked> =
            if (alarmId in map) map - alarmId else map
    }
}
