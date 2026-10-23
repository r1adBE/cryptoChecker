package com.cryptochecker.app.util

import android.content.Context
import android.content.SharedPreferences
import com.cryptochecker.app.domain.diagnostics.BackgroundErrorSummary
import com.cryptochecker.app.domain.diagnostics.BackgroundErrors

/**
 * F-25: zählt Fehler in Hintergrundarbeit dauerhaft je Bereich ([BackgroundErrors.AREAS]) und merkt sich
 * den Zeitpunkt des letzten — nur Zahlen, nie Meldungstexte. Dazu je Bereich die Fehlerart (einfacher Klassenname der
 * Ausnahme, [BackgroundErrors.kindOf], höchstens [BackgroundErrors.MAX_KINDS] je Bereich). Liegt in SharedPreferences [PREFS] (nicht gesichert, nur
 * ausdrücklich Eingeschlossenes geht in die Systemsicherung). Vor [install] (Unit-Tests, sehr früher Fehler) nur im
 * Arbeitsspeicher; [install] übernimmt diese Zahlen.
 */
object BackgroundErrorCounter {
    const val PREFS = "background_errors"
    private const val KEY_LAST_AT = "last_at"
    private const val COUNT_PREFIX = "n_"
    private const val KIND_PREFIX = "k_"

    @Volatile private var prefs: SharedPreferences? = null
    private val pending = HashMap<String, Int>()
    private val pendingKinds = HashMap<String, HashMap<String, Int>>()
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
            pendingKinds.forEach { (area, kinds) -> kinds.forEach { (kind, n) -> addKind(p, area, kind, n) } }
            pendingKinds.clear()
        }
    }

    /**
     * Einen Fehler im Bereich [area] zählen (unbekannt → «other»); mit [error] auch seine Art (Klassenname, ohne Text), ohne
     * Ausnahme eine feste Art [kind] (z. B. [BackgroundErrors.ALL_FAILED_KIND]). Wirft nie.
     */
    fun record(area: String, now: Long = System.currentTimeMillis(), error: Throwable? = null, kind: String? = null) {
        val code = BackgroundErrors.area(area)
        runCatching {
            val errorKind = kind?.let(BackgroundErrors::kind) ?: error?.let(BackgroundErrors::kindOf)
            synchronized(this) {
                val p = prefs
                if (p == null) {
                    pending[code] = (pending[code] ?: 0) + 1
                    pendingLastAt = now
                    if (errorKind != null) pendingKinds.getOrPut(code) { HashMap() }.let { kinds ->
                        val slot = BackgroundErrors.kindSlot(kinds.keys, errorKind)
                        kinds[slot] = (kinds[slot] ?: 0) + 1
                    }
                } else {
                    val n = p.getInt(COUNT_PREFIX + code, 0)
                    p.edit().putInt(COUNT_PREFIX + code, if (n == Int.MAX_VALUE) n else n + 1).putLong(KEY_LAST_AT, now).apply()
                    if (errorKind != null) addKind(p, code, errorKind, 1)
                }
            }
        }
    }

    /** Art [kind] im Bereich [area] um [n] erhöhen (Fach nach [BackgroundErrors.kindSlot]). */
    private fun addKind(p: SharedPreferences, area: String, kind: String, n: Int) {
        val slot = BackgroundErrors.kindSlot(kindsOf(p)[area].orEmpty().keys, kind)
        val key = "$KIND_PREFIX$area|$slot"
        val old = p.getInt(key, 0)
        p.edit().putInt(key, if (old > Int.MAX_VALUE - n) Int.MAX_VALUE else old + n).apply()
    }

    /** Gespeicherte Arten: Bereich → (Art → Anzahl); nur bekannte Bereiche. */
    private fun kindsOf(p: SharedPreferences): Map<String, Map<String, Int>> {
        val out = HashMap<String, HashMap<String, Int>>()
        p.all.forEach { (key, value) ->
            if (!key.startsWith(KIND_PREFIX) || value !is Int || value <= 0) return@forEach
            val area = key.removePrefix(KIND_PREFIX).substringBefore('|')
            if (area in BackgroundErrors.AREAS) out.getOrPut(area) { HashMap() }[key.substringAfter('|')] = value
        }
        return out
    }

    /** Zählerstand für den Diagnosebericht. */
    fun summary(): BackgroundErrorSummary = synchronized(this) {
        val p = prefs ?: return BackgroundErrorSummary(HashMap(pending), pendingLastAt, pendingKinds.mapValues { HashMap(it.value) })
        val counts = BackgroundErrors.AREAS.associateWith { p.getInt(COUNT_PREFIX + it, 0) }.filterValues { it > 0 }
        BackgroundErrorSummary(counts, p.getLong(KEY_LAST_AT, 0L), kindsOf(p))
    }
}
