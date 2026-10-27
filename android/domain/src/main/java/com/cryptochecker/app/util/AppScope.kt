package com.cryptochecker.app.util

import com.cryptochecker.app.data.local.isDatabaseKeyUnavailable
import com.cryptochecker.app.domain.diagnostics.BackgroundErrors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * Prozessweiter Bereich für App-eigene Hintergrundarbeit (Singletons, Empfänger) — einheitlich mit
 * Sicherheitsnetz: SupervisorJob (ein Fehler bricht die anderen Jobs nicht ab) und [appExceptionHandler]. Ohne Handler
 * beendet eine nicht gefangene Ausnahme in einem `launch` den ganzen Prozess.
 */
fun appScope(dispatcher: CoroutineDispatcher, label: String): CoroutineScope =
    CoroutineScope(SupervisorJob() + dispatcher + appExceptionHandler(label))

/**
 * Protokolliert statt abzustürzen (wie [ReceiverAsync]): Datenbank-Schlüssel gerade nicht lesbar (Keystore) ist kein
 * Fehler, nur Hinweis — der nächste Lauf versucht es erneut. [log] nur für Tests austauschbar.
 */
fun appExceptionHandler(
    label: String,
    log: (Throwable) -> Unit = { e ->
        if (e.isDatabaseKeyUnavailable()) Timber.d(e, "%s: Datenbank-Schlüssel nicht verfügbar", label)
        else Timber.w(e, "%s: Hintergrundarbeit fehlgeschlagen", label)
    },
): CoroutineExceptionHandler = CoroutineExceptionHandler { _, e ->
    // Im Release sonst unsichtbar — nur zählen je Bereich und Art (BackgroundErrorCounter), keine Texte
    if (!e.isDatabaseKeyUnavailable()) BackgroundErrorCounter.record(BackgroundErrors.areaForLabel(label), error = e)
    log(e)
}
