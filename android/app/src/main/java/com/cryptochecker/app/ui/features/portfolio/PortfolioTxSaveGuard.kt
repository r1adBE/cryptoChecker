package com.cryptochecker.app.ui.features.portfolio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Schutz vor doppeltem Anlegen beim Speichern einer Transaktion — im ViewModel statt nur im Knopf-Zustand des Blatts
 * (wie iOS `guard !saving`). Reines Kotlin, als Unit-Test prüfbar.
 * - Während ein Speichern läuft, startet kein zweites (Doppeltipp, Drehen oder Falten mitten im Schreiben).
 * - Ein Blatt, dessen Eingaben gespeichert sind ([session], übersteht das Drehen), speichert nie ein zweites Mal; das
 *   nach dem Drehen neu aufgebaute Blatt sieht es in [saved] und schliesst sich (der Rückruf traf die alte Komposition).
 * - Gescheitertes Schreiben gibt das Blatt wieder frei (erneut speichern geht).
 */
internal class TxSaveGuard {
    private val _busy = MutableStateFlow(false)
    private val _saved = MutableStateFlow<Set<String>>(emptySet())

    /** Ein Speichern läuft (Knopf gesperrt, auch im nach dem Drehen neu aufgebauten Blatt). */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Sitzungen (Blätter), deren Eingaben gespeichert sind. */
    val saved: StateFlow<Set<String>> = _saved.asStateFlow()

    /** Darf [session] jetzt speichern? true = ja, der Wächter ist belegt bis [finish]. Nur Hauptthread (Klick). */
    fun begin(session: String): Boolean {
        if (session in _saved.value || _busy.value) return false
        _busy.value = true
        return true
    }

    /** Speichern beendet: [ok] = geschrieben (die Sitzung speichert nie wieder), sonst wieder frei. */
    fun finish(session: String, ok: Boolean) {
        if (ok) _saved.update { it + session }
        _busy.value = false
    }
}
