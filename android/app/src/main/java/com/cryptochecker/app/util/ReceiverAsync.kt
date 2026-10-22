package com.cryptochecker.app.util

import android.content.BroadcastReceiver
import com.cryptochecker.app.data.local.isDatabaseKeyUnavailable
import com.cryptochecker.app.domain.diagnostics.BackgroundErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Gemeinsamer Bereich für Arbeit aus Broadcast-Empfängern (Widgets). Ein Fehler (Datenbank,
 * Bitmap) darf den Prozess im Hintergrund nicht beenden: wird nur protokolliert (Timber nur
 * in Debug-Builds gepflanzt, im Release also kein Protokoll). Seit Build 104 über [appScope] (gleiches Sicherheitsnetz,
 * Datenbank-Schlüssel nicht lesbar nur als Hinweis).
 */
object ReceiverAsync {
    val scope: CoroutineScope = appScope(Dispatchers.Default, "Empfänger-Arbeit")

    /**
     * Harte Grenze für Arbeit nach goAsync (Android erwartet finish() nach rund 10 s, sonst droht
     * im Hintergrund ein ANR); danach wird abgebrochen, das Widget behält den gezeichneten Stand.
     */
    const val TIMEOUT_MILLIS = 9_000L

    /** Netz im Empfänger (Kerzen, Momentaufnahme) höchstens so lange, Zeichnen davor und danach passt noch. */
    const val FETCH_MILLIS = 7_000L

    /**
     * Führt [block] in [scope] aus, höchstens [timeoutMillis]; Fehler werden protokolliert, [onDone] kommt immer
     * (auch nach Zeitgrenze, Fehler oder Abbruch). Kern von [launchAsync], seit Build 104 eigens für Tests.
     */
    internal fun launchBounded(
        label: String,
        timeoutMillis: Long = TIMEOUT_MILLIS,
        onDone: () -> Unit,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = scope.launch {
        try {
            if (withTimeoutOrNull(timeoutMillis) { block() } == null) {
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
                // Seit Build 104: im Release nur gezählt (Bereich, keine Texte)
                BackgroundErrorCounter.record(BackgroundErrors.areaForLabel(label))
            }
        } finally {
            onDone()
        }
    }
}

/**
 * Führt [block] nach [BroadcastReceiver.goAsync] im gemeinsamen Bereich aus, höchstens
 * [ReceiverAsync.TIMEOUT_MILLIS]. Fehler werden abgefangen und protokolliert;
 * [BroadcastReceiver.PendingResult.finish] wird immer aufgerufen.
 */
fun BroadcastReceiver.launchAsync(label: String, block: suspend CoroutineScope.() -> Unit) {
    val pendingResult = goAsync()
    ReceiverAsync.launchBounded(label, onDone = { pendingResult.finish() }, block = block)
}
