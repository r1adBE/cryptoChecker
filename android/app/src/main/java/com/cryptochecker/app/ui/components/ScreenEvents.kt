package com.cryptochecker.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext

/**
 * Review ARC5-R01: Ergebnis einer Aktion als Einmal-Ereignis für den GERADE gezeigten Bildschirm. Ein
 * Rückruf des Bildschirms, der erst nach der Rückfrage F-08 läuft, träfe nach dem Neuerstellen der Activity (Drehen,
 * Dunkelmodus, Sprache) die alte Komposition: ihr Zustand, ihre Snackbar und ihr schon abgemeldeter Erlaubnis-Launcher
 * (`IllegalStateException`). Darum schicken Rückrufe nur Daten hierher; ausgeführt wird in [CollectScreenEvents] des
 * aktuellen Bildschirms. Lebt so lange wie das ViewModel, das es hält; gepuffert, bis ein Bildschirm sammelt; jedes
 * Ereignis genau einmal (ein Empfänger).
 */
class ScreenEvents<T : Any> {
    private val channel = Channel<T>(Channel.UNLIMITED)

    /**
     * Nur vom Hauptfaden (viewModelScope, Klick-Handler): dann setzt der Sammler (`Main.immediate`) noch im selben Aufruf
     * fort, das Ereignis geht nicht verloren (unbegrenzt gepuffert). Aus einem Hintergrundfaden würde die Fortsetzung nur
     * gepostet; geht der Bildschirm vorher unter STARTED, wäre das schon entnommene Ereignis weg.
     */
    fun send(event: T) {
        channel.trySend(event)
    }

    /** Nur für [CollectScreenEvents] und Tests: genau ein Sammler. */
    val events: Flow<T> = channel.receiveAsFlow()
}

/**
 * Sammelt [events] im aktuellen Bildschirm, solange er mindestens STARTED ist; [onEvent] mit dem neuesten Stand der
 * Komposition (Launcher, Snackbar, Zustand). `Main.immediate`: ein empfangenes Ereignis läuft sofort, ein Abbruch
 * dazwischen (Bildschirm geht) verliert keines.
 */
@Composable
fun <T : Any> CollectScreenEvents(events: ScreenEvents<T>, onEvent: (T) -> Unit) {
    val handler by rememberUpdatedState(onEvent)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(events, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Main.immediate) { events.events.collect { handler(it) } }
        }
    }
}
