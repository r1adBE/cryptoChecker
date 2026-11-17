package com.cryptochecker.app.testing

import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import java.io.Serializable

/**
 * Neu-Erzeugen eines Bildschirms (Drehen, Falten, Dunkelmodus, Prozess-Ende) für ein einzelnes Composable: [restore]
 * speichert alles `rememberSaveable` wie die Activity in ihr Bundle, verwirft die Komposition und baut sie aus dem
 * Gespeicherten neu. Nur Bundle-taugliche Werte werden angenommen (wie im echten Bundle; sonst scheitert das Speichern).
 */
class SavedStateHost {
    private var generation by mutableIntStateOf(0)
    private var saved: Map<String, List<Any?>>? = null
    private var registry: SaveableStateRegistry? = null

    @Composable
    fun Content(content: @Composable () -> Unit) {
        key(generation) {
            val current = remember { SaveableStateRegistry(saved, ::fitsIntoABundle) }
            registry = current
            CompositionLocalProvider(LocalSaveableStateRegistry provides current) { content() }
        }
    }

    /** Auf dem UI-Thread aufrufen; danach `waitForIdle`. */
    fun restore() {
        saved = checkNotNull(registry) { "Content noch nicht gezeigt" }.performSave()
        generation++
    }

    private fun fitsIntoABundle(value: Any): Boolean =
        value is Serializable || value is Parcelable || value is CharSequence || value is android.os.Bundle
}
