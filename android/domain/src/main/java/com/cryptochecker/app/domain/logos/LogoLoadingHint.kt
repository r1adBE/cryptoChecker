package com.cryptochecker.app.domain.logos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Hinweis «Logos werden geladen …» unter dem Kopf der Merkliste — nur beim ersten Laden der Logos nach der
 * Installation bzw. nach dem Leeren des Logo-Zwischenspeichers, nie beim täglichen Nachholen gescheiterter Bilder:
 * [visible], sobald ein solcher Durchgang ([during]) Bilder zu laden hat ([imagesPending] > 0); weg, sobald er endet —
 * fertig, gescheitert oder gebremst —, und in diesem Durchgang nicht wieder. Endet ein Durchgang mit Hinweis, ist das
 * erste Laden erledigt ([runFinished] = true): gemerkt als leere Datei [DONE_MARKER] im Logo-Ordner ([doneMarker]) —
 * sie verschwindet mit dem Zwischenspeicher und beim Zurücksetzen der App. Coins ganz ohne Logo zählen nie als fehlend.
 *
 * Fortschritt «Logos werden geladen · 40 %» ([percent]): erledigte Bilder des Durchgangs ([imageDone]: geladen oder
 * endgültig gescheitert) durch alle zu ladenden — bekannt erst mit der letzten Stufe ([imagesPending] mit `last`,
 * vorher laden noch Seiten der Rangliste: ohne Prozent); höchstens 99 %, solange er läuft ([percentOf]). Bremst ein
 * Bild-Server ([imagesPaused]), bis zum Ende des Durchgangs ohne Prozent (kein stehender Wert). Angezeigt höchstens
 * etwa einmal je Sekunde bzw. je 5 Prozentpunkte ([shouldShow]), damit die Liste nicht je Bild neu zeichnet.
 * Ändert nichts am Laden selbst. Gleich in iOS `LogoLoadingHint.swift`, gemeinsame Testfälle
 * `testdata/parity/coin_logos.json` › `loadingHint` und `loadingPercent`.
 */
class LogoLoadingHint(
    private val doneMarker: (() -> File)? = null,
    /** Monotone Uhr (ms) für [shouldShow]. */
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    /** Laufende Durchgänge (zwei gleichzeitig: weg erst nach dem letzten). */
    private var runs = 0

    /** Die laufenden Durchgänge gehören zum ersten Laden (beim ersten Start gelesen). */
    private var firstLoad = false

    /** Das erste Laden endete eben in diesem Prozess (vor dem Anlegen der Merk-Datei; [firstLoadDone]). */
    @Volatile
    private var finishedNow = false
    private val _visible = MutableStateFlow(false)

    /** Hinweis zeigen: das erste Laden läuft und hat Bilder zu laden. */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** Erledigte Bilder des Durchgangs (geladen oder endgültig gescheitert). */
    private var done = 0

    /** Alle zu ladenden Bilder des Durchgangs; null = noch nicht bekannt (Seiten der Rangliste laden noch). */
    private var total: Int? = null

    /** Ein Bild-Server bremst: bis zum Ende des Durchgangs ohne Prozent. */
    private var paused = false
    private val _percent = MutableStateFlow<Int?>(null)
    private var shownAt = 0L

    /** Angezeigter Fortschritt (gedrosselt, [shouldShow]); null = Hinweis ohne Prozent. */
    val percent: StateFlow<Int?> = _percent.asStateFlow()

    /** Fortschritt nach der Regel, ungedrosselt; null = ohne Prozent. */
    @get:Synchronized
    val currentPercent: Int?
        get() = total?.takeIf { _visible.value && !paused }?.let { percentOf(done, it) }

    /** [firstLoadDone]: das erste Laden ist schon gemerkt (dann nie ein Hinweis). */
    @Synchronized
    fun runStarted(firstLoadDone: Boolean) {
        if (runs == 0) {
            firstLoad = !firstLoadDone
            finishedNow = firstLoadDone
            // Wert des vorigen Durchgangs (stand beim Ausblenden) nie wieder zeigen
            _percent.value = null
        }
        runs++
    }

    /**
     * Ein Bild-Abruf des laufenden Durchgangs hat [count] fehlende Bilder; ausserhalb eines Durchgangs nichts.
     * [last]: die letzte Stufe — damit ist bekannt, wie viele Bilder der Durchgang insgesamt lädt.
     */
    @Synchronized
    fun imagesPending(count: Int, last: Boolean = false) {
        if (runs == 0 || !firstLoad || count <= 0) return
        _visible.value = true
        if (last) total = done + count
        show()
    }

    /** Ein Bild des Durchgangs ist erledigt: geladen oder endgültig gescheitert (nicht gebremst). */
    @Synchronized
    fun imageDone() {
        if (!_visible.value) return
        done++
        show()
    }

    /** Ein Bild-Server bremst: kein Prozent mehr in diesem Durchgang. */
    @Synchronized
    fun imagesPaused() {
        if (!_visible.value) return
        paused = true
        show()
    }

    /** true = das erste Laden ist damit erledigt (Hinweis stand und der letzte Durchgang endet): merken. */
    @Synchronized
    fun runFinished(): Boolean {
        if (runs > 0) runs--
        if (runs > 0) return false
        val wasVisible = _visible.value
        // Vor dem Ausblenden: wer auf das Ende wartet ([firstLoadDone]), sieht es schon erledigt
        if (wasVisible) finishedNow = true
        _visible.value = false
        done = 0
        total = null
        paused = false
        // [percent] bleibt, solange die Zeile ausblendet (kein Sprung zurück auf «…»); der nächste Durchgang leert ihn
        return wasVisible
    }

    private fun show() {
        if (!_visible.value) return
        val next = currentPercent
        val now = clock()
        if (!shouldShow(_percent.value, shownAt, next, now)) return
        _percent.value = next
        shownAt = now
    }

    /**
     * Ist das erste Laden erledigt (eben beendet oder gemerkt, [doneMarker])? Vorher zeigt die Merkliste keinen Hinweis
     * «Coins ohne Logo» ([MissingLogoNotice]): Fehlende Logos kommen dann ja noch. Liest die Merk-Datei (nicht im
     * Haupt-Thread aufrufen).
     */
    fun firstLoadDone(): Boolean =
        finishedNow || doneMarker?.let { runCatching { it().isFile }.getOrDefault(false) } == true

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

        /** Höchstens so oft ein neuer Prozentwert … */
        const val SHOW_INTERVAL_MILLIS = 1_000L

        /** … ausser er springt um mindestens so viele Prozentpunkte. */
        const val SHOW_STEP = 5
        private const val MAX_RUNNING = 99
        private const val NANOS_PER_MILLI = 1_000_000L

        /** [done] von [total] in ganzen Prozent, abgerundet, 0…99 (100 % zeigt nie — am Ende ist der Hinweis weg). */
        fun percentOf(done: Int, total: Int): Int =
            if (total <= 0) 0 else (done.coerceAtLeast(0).toLong() * 100 / total).toInt().coerceIn(0, MAX_RUNNING)

        /**
         * Neuen Wert [next] anzeigen statt [shown] (angezeigt um [shownAt])? Wechsel von und zu «ohne Prozent» sofort,
         * sonst höchstens einmal je [SHOW_INTERVAL_MILLIS] oder bei einem Sprung um [SHOW_STEP] Punkte.
         */
        fun shouldShow(shown: Int?, shownAt: Long, next: Int?, now: Long): Boolean = when {
            next == shown -> false
            next == null || shown == null -> true
            else -> now - shownAt >= SHOW_INTERVAL_MILLIS || abs(next - shown) >= SHOW_STEP
        }
    }
}
