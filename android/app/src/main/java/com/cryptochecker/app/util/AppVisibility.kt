package com.cryptochecker.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ist die App gerade sichtbar (MainActivity zwischen onStart und onStop)? Für Arbeit,
 * die nur mit geöffneter App jemandem nützt — etwa die Auswertung «Ungewöhnliche
 * Aktivität», wenn ihre Meldung ausgeschaltet ist, oder den kurzen Takt des Live-Modus.
 */
object AppVisibility {
    private val _flow = MutableStateFlow(false)

    /** Sichtbarkeit als Fluss (der Live-Modus wacht beim Öffnen sofort auf). */
    val flow: StateFlow<Boolean> = _flow.asStateFlow()

    val visible: Boolean get() = _flow.value

    fun onStart() {
        _flow.value = true
    }

    fun onStop() {
        _flow.value = false
    }
}
