package com.cryptochecker.app.util

import android.content.Context
import android.content.SharedPreferences
import com.cryptochecker.app.domain.diagnostics.BackgroundErrorSummary
import com.cryptochecker.app.domain.diagnostics.BackgroundErrors

/**
 * Seit Build 104 (F-25): zählt Fehler in Hintergrundarbeit dauerhaft je Bereich ([BackgroundErrors.AREAS]) und merkt sich
 * den Zeitpunkt des letzten — nur Zahlen, nie Meldungstexte. Liegt in SharedPreferences [PREFS] (nicht gesichert, nur
 * ausdrücklich Eingeschlossenes geht in die Systemsicherung). Vor [install] (Unit-Tests, sehr früher Fehler) nur im
 * Arbeitsspeicher; [install] übernimmt diese Zahlen.
 */
object BackgroundErrorCounter {
    const val PREFS = "background_errors"
    private const val KEY_LAST_AT = "last_at"
    private const val COUNT_PREFIX = "n_"

    @Volatile private var prefs: SharedPreferences? = null
    private val pending = HashMap<String, Int>()
    private var pendingLastAt = 0L

    /** Einmal beim Start (Application.onCreate). */
    fun install(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            prefs = p
            if (pending.isEmpty()) return
            val edit = p.edit()
            pending.forEach { (area, n) -> edit.putInt(COUNT_PREFIX + area, p.getInt(COUNT_PREFIX + area, 0) + n) }
            edit.putLong(KEY_LAST_AT, maxOf(pendingLastAt, p.getLong(KEY_LAST_AT, 0L)))
            edit.apply()
            pending.clear()
        }
    }

    /** Einen Fehler im Bereich [area] zählen (unbekannt → «other»). Wirft nie. */
    fun record(area: String, now: Long = System.currentTimeMillis()) {
        val code = BackgroundErrors.area(area)
        runCatching {
            synchronized(this) {
                val p = prefs
                if (p == null) {
                    pending[code] = (pending[code] ?: 0) + 1
                    pendingLastAt = now
                } else {
                    val n = p.getInt(COUNT_PREFIX + code, 0)
                    p.edit().putInt(COUNT_PREFIX + code, if (n == Int.MAX_VALUE) n else n + 1).putLong(KEY_LAST_AT, now).apply()
                }
            }
        }
    }

    /** Zählerstand für den Diagnosebericht. */
    fun summary(): BackgroundErrorSummary = synchronized(this) {
        val p = prefs ?: return BackgroundErrorSummary(HashMap(pending), pendingLastAt)
        val counts = BackgroundErrors.AREAS.associateWith { p.getInt(COUNT_PREFIX + it, 0) }.filterValues { it > 0 }
        BackgroundErrorSummary(counts, p.getLong(KEY_LAST_AT, 0L))
    }
}
