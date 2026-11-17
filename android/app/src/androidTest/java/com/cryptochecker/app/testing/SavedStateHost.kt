package com.cryptochecker.app.testing

import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import java.io.Serializable

/**
 * Neu-Erzeugen eines Bildschirms (Drehen, Falten, Dunkelmodus, Prozess-Ende) für ein einzelnes Composable: [dispose]
 * speichert alles `rememberSaveable` wie die Activity in ihr Bundle, verwirft die Komposition, [show] baut sie aus dem
 * Gespeicherten neu. Nur Bundle-taugliche Werte werden angenommen (wie im echten Bundle; sonst scheitert das Speichern).
 */
class SavedStateHost {
    private var shown by mutableStateOf(true)
    private var saved: Map<String, List<Any?>>? = null
    private var registry: SaveableStateRegistry? = null

    @Composable
    fun Content(content: @Composable () -> Unit) {
        // Gleiche Stelle vor und nach dem Neu-Erzeugen: die Schlüssel von rememberSaveable (aus der Position) passen
        if (shown) {
            val current = remember { SaveableStateRegistry(saved, ::fitsIntoABundle) }
            registry = current
            CompositionLocalProvider(LocalSaveableStateRegistry provides current) { content() }
        }
    }

    /** Speichern und verwerfen (UI-Thread); nach `waitForIdle` [show]. */
    fun dispose() {
        saved = checkNotNull(registry) { "Content noch nicht gezeigt" }.performSave()
        shown = false
    }

    /** Aus dem Gespeicherten neu aufbauen (UI-Thread). */
    fun show() {
        shown = true
    }

    private fun fitsIntoABundle(value: Any): Boolean =
        value is Serializable || value is Parcelable || value is CharSequence || value is android.os.Bundle
}
