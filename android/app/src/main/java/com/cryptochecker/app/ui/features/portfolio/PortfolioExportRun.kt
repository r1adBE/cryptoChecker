// Ablauf eines Exports (Stichtag-Export und Arbeitsunterlage): Zustand, Abbrechen samt Löschen der angefangenen Datei,
// «Erneut versuchen» und die Fehlerart der Meldung — wie `PortfolioExportRun.swift`.
// Eigene Datei, damit der Ablauf ohne Android-Klassen prüfbar ist ([ExportRunner] mit einem Ziel zum Testen).
package com.cryptochecker.app.ui.features.portfolio

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.cryptochecker.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Ablauf eines Exports. */
sealed interface CutoffExportState {
    data object Idle : CutoffExportState
    data object Running : CutoffExportState
    data object Done : CutoffExportState

    /** Fehlgeschlagen; [kind] bestimmt den Text der Meldung. */
    data class Failed(val kind: ExportFailureKind) : CutoffExportState
}

/**
 * Art eines Export-Fehlers mit dem Schlüssel ihres Texts ([key], gleich in `export_errors.json` und iOS):
 * Datei nicht schreibbar bzw. Speicherort weg, Kurse fehlen (offline, Börse), sonstiges.
 */
enum class ExportFailureKind(val key: String, val text: Int) {
    FILE("portfolio_export_failed_file", R.string.portfolio_export_failed_file),
    PRICES("portfolio_export_failed_prices", R.string.portfolio_export_failed_prices),
    OTHER("portfolio_export_failed", R.string.portfolio_export_failed),
}

/** Fehler beim Schreiben der Zieldatei (Datei weg, keine Berechtigung, Speicher voll). */
class ExportWriteException(cause: Throwable) : IOException(cause)

/** Zuordnung Fehler → Art ([ExportFailureKind]); Ursachen werden mitgeprüft. Wie iOS `ExportFailures.classify`. */
object ExportFailures {
    fun classify(error: Throwable): ExportFailureKind {
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < MAX_CAUSES) {
            if (e is ExportWriteException || e is FileNotFoundException || e is SecurityException) {
                return ExportFailureKind.FILE
            }
            e = e.cause
            depth++
        }
        // Übrige Ein-/Ausgabefehler entstehen beim Laden der Kurse (Netz, Börse); geschrieben wird erst danach
        return if (error is IOException) ExportFailureKind.PRICES else ExportFailureKind.OTHER
    }

    private const val MAX_CAUSES = 8
}

/** Ziel eines Exports: Text schreiben bzw. eine angefangene Datei wieder löschen. */
interface ExportTarget<T> {
    /** Schreibt [csv] (UTF-8) nach [target]; Fehler als [ExportWriteException]. */
    suspend fun write(target: T, csv: String)

    /** Löscht [target] (abgebrochener Export); Fehler werden nur protokolliert. */
    suspend fun delete(target: T)
}

/** Datei aus der Dateiauswahl des Systems (SAF), im UI-Test auch eine `file://`-Adresse. */
class UriExportTarget(private val context: Context) : ExportTarget<Uri> {
    override suspend fun write(target: Uri, csv: String) = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openOutputStream(target, "wt")
                ?: throw FileNotFoundException("Datei kann nicht geschrieben werden")
            stream.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ExportWriteException(e)
        }
    }

    override suspend fun delete(target: Uri) {
        withContext(Dispatchers.IO) {
            try {
                when {
                    target.scheme == "file" -> target.path?.let { File(it).delete() }
                    DocumentsContract.isDocumentUri(context, target) ->
                        DocumentsContract.deleteDocument(context.contentResolver, target)
                    else -> context.contentResolver.delete(target, null, null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Export abgebrochen: angefangene Datei nicht gelöscht")
            }
        }
    }
}

/**
 * Ein Export zur Zeit: [start] rechnet den Text (`build`) und schreibt ihn ins Ziel; [cancel] bricht ab und löscht
 * die angefangene Zieldatei (nach dem Ende eines laufenden Schreibvorgangs); [retry] wiederholt den letzten
 * fehlgeschlagenen Export, auf Wunsch in ein neues Ziel (Speicherort weg).
 */
class ExportRunner<T : Any>(
    private val scope: CoroutineScope,
    private val target: ExportTarget<T>,
) {
    private val _state = MutableStateFlow<CutoffExportState>(CutoffExportState.Idle)
    val state: StateFlow<CutoffExportState> = _state.asStateFlow()

    private var job: Job? = null
    private var current: T? = null
    private var last: Attempt<T>? = null

    private class Attempt<T>(val target: T, val label: String, val build: suspend () -> String)

    fun start(to: T, label: String, build: suspend () -> String) {
        if (_state.value == CutoffExportState.Running) return
        _state.value = CutoffExportState.Running
        last = Attempt(to, label, build)
        current = to
        // Erst merken, dann starten: auf dem Hauptthread liefe der Anfang sonst schon vor der Zuweisung
        val run = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                target.write(to, build())
                CutoffExportState.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, label)
                CutoffExportState.Failed(ExportFailures.classify(e))
            }
            current = null
            job = null
            _state.value = result
        }
        job = run
        run.start()
    }

    /** Laufenden Export abbrechen: Zustand gleich [CutoffExportState.Idle], die angefangene Datei wird gelöscht. */
    fun cancel() {
        val running = job ?: return
        val to = current
        job = null
        current = null
        _state.value = CutoffExportState.Idle
        // Sofort betreten und nicht abbrechbar: das Löschen läuft auch, wenn der Bereich gerade endet (Seite verlassen)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                running.cancelAndJoin()
                if (to != null) target.delete(to)
            }
        }
    }

    /** Letzten fehlgeschlagenen Export wiederholen; [to] = neues Ziel, null = dasselbe. */
    fun retry(to: T? = null) {
        val attempt = last ?: return
        if (_state.value !is CutoffExportState.Failed) return
        _state.value = CutoffExportState.Idle
        start(to ?: attempt.target, attempt.label, attempt.build)
    }

    /** Erfolg gemeldet: zurück auf [CutoffExportState.Idle]; ein Fehler bleibt für «Erneut versuchen» stehen. */
    fun consume() {
        if (_state.value == CutoffExportState.Done) _state.value = CutoffExportState.Idle
    }

    /** Fehlermeldung geschlossen. */
    fun dismissFailure() {
        if (_state.value is CutoffExportState.Failed) _state.value = CutoffExportState.Idle
    }
}
