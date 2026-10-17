package com.cryptochecker.app.util

import android.content.BroadcastReceiver
import com.cryptochecker.app.data.local.isDatabaseKeyUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Gemeinsamer Bereich für Arbeit aus Broadcast-Empfängern (Widgets). Ein Fehler (Datenbank,
 * Bitmap) darf den Prozess im Hintergrund nicht beenden: wird nur protokolliert (Timber nur
 * in Debug-Builds gepflanzt, im Release also kein Protokoll).
 */
object ReceiverAsync {
    private val handler = CoroutineExceptionHandler { _, e ->
        if (e.isDatabaseKeyUnavailable()) Timber.d(e, "Empfänger-Arbeit: Datenbank-Schlüssel nicht verfügbar")
        else Timber.w(e, "Empfänger-Arbeit fehlgeschlagen")
    }

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)

    /**
     * Harte Grenze für Arbeit nach goAsync (Android erwartet finish() nach rund 10 s, sonst droht
     * im Hintergrund ein ANR); danach wird abgebrochen, das Widget behält den gezeichneten Stand.
     */
    const val TIMEOUT_MILLIS = 9_000L

    /** Netz im Empfänger (Kerzen, Momentaufnahme) höchstens so lange, Zeichnen davor und danach passt noch. */
    const val FETCH_MILLIS = 7_000L
}

/**
 * Führt [block] nach [BroadcastReceiver.goAsync] im gemeinsamen Bereich aus, höchstens
 * [ReceiverAsync.TIMEOUT_MILLIS]. Fehler werden abgefangen und protokolliert;
 * [BroadcastReceiver.PendingResult.finish] wird immer aufgerufen.
 */
fun BroadcastReceiver.launchAsync(label: String, block: suspend CoroutineScope.() -> Unit) {
    val pendingResult = goAsync()
    ReceiverAsync.scope.launch {
        try {
            if (withTimeoutOrNull(ReceiverAsync.TIMEOUT_MILLIS) { block() } == null) {
                Timber.d("%s: Zeitgrenze erreicht", label)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Widget bleibt beim alten Stand — auch bei OutOfMemoryError (grosse Bitmaps). Datenbank-Schlüssel gerade
            // nicht lesbar (Keystore): kein Fehler, das nächste Zeichnen versucht es erneut
            if (e.isDatabaseKeyUnavailable()) {
                Timber.d(e, "%s: Datenbank-Schlüssel nicht verfügbar", label)
            } else {
                Timber.w(e, "%s fehlgeschlagen", label)
            }
        } finally {
            pendingResult.finish()
        }
    }
}
