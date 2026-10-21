package com.cryptochecker.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * Einmal-Rückmeldung (Haptik, Snackbar) über einen Neuaufbau hinweg: Die App hat kein `configChanges`, beim
 * Drehen oder Wechsel des Dunkelmodus baut Android die Activity neu, und die LaunchedEffects laufen erneut,
 * während das ViewModel die Meldung noch hält — ohne diesen Merker käme dieselbe Vibration (und Snackbar) doppelt.
 *
 * Merkt sich den Schlüssel (`toString()`) der zuletzt gezeigten Meldung; das passt zu Meldungen als Enum,
 * Data-Klasse, Zähler oder Boolean. `null` (Meldung weg) setzt zurück, sodass die nächste gleiche Meldung wieder zählt.
 * Seit Build 100.
 */
internal class OneShotGate(private var shownKey: String? = null) {

    /**
     * true, wenn [event] neu ist und gezeigt werden soll (merkt es sich dann); false bei `null` oder wenn genau
     * diese Meldung schon vor dem Neuaufbau gezeigt wurde.
     */
    fun claim(event: Any?): Boolean {
        val key = event?.toString()
        val isNew = key != null && key != shownKey
        shownKey = key
        return isNew
    }

    companion object {
        /** Ohne gezeigte Meldung wird nichts gesichert (frischer Merker beim Wiederherstellen). */
        val Saver: Saver<OneShotGate, String> = Saver(save = { it.shownKey }, restore = { OneShotGate(it) })
    }
}

/** [OneShotGate], der Drehen, Dunkelmodus und Prozess-Neustart übersteht. */
@Composable
internal fun rememberOneShotGate(vararg inputs: Any?): OneShotGate =
    rememberSaveable(*inputs, saver = OneShotGate.Saver) { OneShotGate() }
