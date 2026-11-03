package com.cryptochecker.app.domain.logos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hinweis «Logos werden geladen …» unter dem Kopf der Merkliste — nur beim ersten Laden der Logos nach der
 * Installation bzw. nach dem Leeren des Logo-Zwischenspeichers, nie beim täglichen Nachholen gescheiterter Bilder:
 * [visible], sobald ein solcher Durchgang ([during]) Bilder zu laden hat ([imagesPending] > 0); weg, sobald er endet —
 * fertig, gescheitert oder gebremst —, und in diesem Durchgang nicht wieder. Endet ein Durchgang mit Hinweis, ist das
 * erste Laden erledigt ([runFinished] = true): gemerkt als leere Datei [DONE_MARKER] im Logo-Ordner ([doneMarker]) —
 * sie verschwindet mit dem Zwischenspeicher und beim Zurücksetzen der App. Coins ganz ohne Logo zählen nie als fehlend.
 * Ändert nichts am Laden selbst. Gleich in iOS `LogoLoadingHint.swift`, gemeinsame Testfälle
 * `testdata/parity/coin_logos.json` › `loadingHint`.
 */
class LogoLoadingHint(private val doneMarker: (() -> File)? = null) {
    /** Laufende Durchgänge (zwei gleichzeitig: weg erst nach dem letzten). */
    private var runs = 0

    /** Die laufenden Durchgänge gehören zum ersten Laden (beim ersten Start gelesen). */
    private var firstLoad = false
    private val _visible = MutableStateFlow(false)

    /** Hinweis zeigen: das erste Laden läuft und hat Bilder zu laden. */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** [firstLoadDone]: das erste Laden ist schon gemerkt (dann nie ein Hinweis). */
    @Synchronized
    fun runStarted(firstLoadDone: Boolean) {
        if (runs == 0) firstLoad = !firstLoadDone
        runs++
    }

    /** Ein Bild-Abruf des laufenden Durchgangs hat [count] fehlende Bilder; ausserhalb eines Durchgangs nichts. */
    @Synchronized
    fun imagesPending(count: Int) {
        if (runs > 0 && firstLoad && count > 0) _visible.value = true
    }

    /** true = das erste Laden ist damit erledigt (Hinweis stand und der letzte Durchgang endet): merken. */
    @Synchronized
    fun runFinished(): Boolean {
        if (runs > 0) runs--
        if (runs > 0) return false
        val done = _visible.value
        _visible.value = false
        return done
    }

    /** [block] als ein Durchgang; endet auch bei Fehler und Abbruch. Liest und schreibt die Merk-Datei [doneMarker]. */
    suspend fun <T> during(block: suspend () -> T): T {
        val marker = doneMarker?.let { withContext(Dispatchers.IO) { runCatching { it() }.getOrNull() } }
        runStarted(firstLoadDone = marker != null && withContext(Dispatchers.IO) { marker.isFile })
        try {
            return block()
        } finally {
            // Merken auch nach einem Abbruch (Datei anlegen blockiert nur kurz)
            if (runFinished() && marker != null) runCatching { marker.createNewFile() }
        }
    }

    companion object {
        /** Merk-Datei «erstes Laden erledigt» im Logo-Ordner (kein Logo heisst so: Namen enden auf «.png»). */
        const val DONE_MARKER = ".first_load_done"
    }
}
