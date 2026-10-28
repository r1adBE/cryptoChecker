package com.cryptochecker.app.data.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Rückfrage «nicht gespeichert»: Merkliste nur im Arbeitsspeicher ohne gespeicherten Stand ([WatchlistStorageLock.Notice.NOT_SAVED],
 * Verschlüsselung noch nicht aktiv) — Änderungen gehen beim Neustart verloren. Vor der ersten Änderung des Nutzers je
 * App-Lauf (Prozess) einmal fragen: «Abbrechen» schreibt nicht, «Trotzdem» schreibt und fragt in diesem Lauf nicht mehr.
 * Gefragt wird EINMAL je Nutzeraktion VOR der Aktion
 * ([com.cryptochecker.app.data.confirmUserWrite] im Controller bzw. ViewModel, vor Sperren, Rückgängig-Eintrag,
 * Mitteilungen, Banner und Haptik). Der Schreibweg im Repository fragt nicht, sondern lehnt ohne Bestätigung ab
 * ([needsConfirmation], Sicherung in [com.cryptochecker.app.data.userWriteRefused]). Die Oberfläche zeigt den Dialog
 * (`WatchlistNotSavedGateHost`, einmal in der MainActivity). Ohne sichtbare Oberfläche (Hintergrund, Empfänger) wird nie
 * gefragt — dann geschrieben. Gleich wie iOS (`WatchlistNotSavedGate`, Testfälle in beiden Tests).
 * Fehlt die Oberfläche beim Bestätigen nur kurz (Drehen, Dunkelmodus), wartet
 * [confirm] bis zu [ASKER_WAIT_MILLIS] auf sie und fragt dann. Ohne Oberfläche durchgelassen, lehnt die Sicherung im
 * Schreibweg danach nicht ab ([needsConfirmation]).
 * Dieser Durchlass gilt nur für die eine Aktion — die Coroutine, die [confirm] aufrief, samt ihren Kindern
 * (`withContext`, Transaktionen). Kein globaler Merker mehr: eine andere Aktion danach wird nicht still durchgelassen, und
 * «Abbrechen» einer anderen Aktion lehnt die laufende nicht ab. Gewählt statt eines Kontext-Elements, weil [confirm] den
 * Kontext seines Aufrufers nicht ändern kann; so bleiben alle Aufrufer (`if (!confirmUserWrite()) return`) unverändert.
 */
object WatchlistNotSavedGate {

    /** Reine Entscheidung: vor diesem Schreibweg fragen? Nur Schreibwege des Nutzers, nur ohne Bestätigung, nur mit Oberfläche. */
    fun shouldAsk(notSaved: Boolean, userWrite: Boolean, confirmed: Boolean, canAsk: Boolean): Boolean =
        notSaved && userWrite && !confirmed && canAsk

    /** Reine Folge einer Antwort: «Trotzdem» bestätigt für den Rest des Laufs, «Abbrechen» nicht (nächste Änderung fragt erneut). */
    fun confirmedAfter(confirmed: Boolean, proceed: Boolean): Boolean = confirmed || proceed

    /** Gerade [WatchlistStorageLock.Notice.NOT_SAVED]? Erst nach dem Öffnen der Datenbank verlässlich. */
    fun notSavedNow(): Boolean = WatchlistStorageLock.notice(
        DatabaseEncryptionNotice.inMemory.value, DatabaseEncryptionNotice.storedKept.value,
    ) == WatchlistStorageLock.Notice.NOT_SAVED

    @Volatile
    private var confirmed = false

    private val pending = MutableStateFlow<CompletableDeferred<Boolean>?>(null)

    /** Offene Frage (Dialog zeigen); null = keine. Die Oberfläche sammelt nur, solange sie sichtbar ist. */
    val request: StateFlow<CompletableDeferred<Boolean>?> = pending.asStateFlow()

    /**
     * Vor einer Aktion des Nutzers: true = schreiben. Fragt höchstens einmal gleichzeitig (weitere Aktionen warten
     * auf dieselbe Antwort). [canAsk]: sammelt gerade eine sichtbare Oberfläche [request]? Wird der letzte
     * Wartende abgebrochen (ViewModel weg), verschwindet die offene Frage — kein Dialog mehr ohne Aktion dahinter.
     * Ohne Oberfläche erst [awaitAsker] (true = Oberfläche jetzt da, dann fragen).
     */
    suspend fun confirm(
        notSaved: Boolean = notSavedNow(),
        canAsk: Boolean = canAskNow(),
        awaitAsker: suspend () -> Boolean = { awaitSubscriber(ASKER_WAIT_MILLIS) },
    ): Boolean {
        val asker = canAsk || !mayAsk(notSaved) || awaitAsker()
        val action = currentCoroutineContext()[Job]
        var granted: Job? = null
        val question = synchronized(this) {
            if (shouldAsk(notSaved, userWrite = true, confirmed = confirmed, canAsk = asker)) {
                waiting++
                pending.value ?: CompletableDeferred<Boolean>().also { pending.value = it }
            } else {
                // Ohne Oberfläche durchgelassen: Zustimmung nur für diese Aktion (ihre Coroutine samt Kindern)
                if (!asker && notSaved && !confirmed && action != null) {
                    unaskedPasses += action
                    granted = action
                }
                null
            }
        }
        if (question == null) {
            // Aktion fertig: ihre Zustimmung verfällt (ausserhalb der Sperre, der Rückruf kann sofort laufen)
            granted?.let { job -> job.invokeOnCompletion { synchronized(this) { unaskedPasses.remove(job) } } }
            return true
        }
        try {
            return question.await()
        } finally {
            synchronized(this) {
                waiting = (waiting - 1).coerceAtLeast(0)
                // Abgebrochen und niemand wartet mehr: Frage zurückziehen (beantwortet ist sie dann schon weg)
                if (waiting == 0 && pending.value === question) {
                    pending.value = null
                    question.cancel()
                }
            }
        }
    }

    /**
     * Sicherung im Schreibweg — bräuchte dieser Schreibweg jetzt noch eine Antwort ([shouldAsk])? Dann
     * lehnt das Repository ab (die Aktion hat [confirm] nicht vorher aufgerufen); fragt selbst nie. Nicht für Schreibwege
     * einer Aktion, die ohne Oberfläche durchgelassen wurde — nur in deren eigener Coroutine
     * ([unaskedPasses]), nicht global.
     */
    suspend fun needsConfirmation(notSaved: Boolean = notSavedNow(), canAsk: Boolean = canAskNow()): Boolean {
        val passes = synchronized(this) {
            if (!shouldAsk(notSaved, userWrite = true, confirmed = confirmed, canAsk = canAsk)) return false
            unaskedPasses.toList()
        }
        val current = currentCoroutineContext()[Job] ?: return true
        return passes.none { it.covers(current) }
    }

    /** Ist [job] diese Coroutine oder eines ihrer Kinder (auch tiefer)? */
    private fun Job.covers(job: Job): Boolean = this === job || children.any { it.covers(job) }

    /** Kann eine Aktion jetzt eine Frage brauchen (nicht gespeichert, noch nicht bestätigt), egal ob sichtbar? */
    fun mayAsk(notSaved: Boolean = notSavedNow()): Boolean =
        synchronized(this) { shouldAsk(notSaved, userWrite = true, confirmed = confirmed, canAsk = true) }

    /** Sammelt gerade eine sichtbare Oberfläche [request]? */
    private fun canAskNow(): Boolean = pending.subscriptionCount.value > 0

    /** Höchstens [millis] auf eine sichtbare Oberfläche warten; true = sie ist da. */
    suspend fun awaitSubscriber(millis: Long): Boolean =
        withTimeoutOrNull(millis) { pending.subscriptionCount.first { it > 0 } } != null

    /** Ohne Oberfläche durchgelassene Aktionen (ihre Coroutine; unter `synchronized`; bis die Aktion endet). */
    private val unaskedPasses = mutableSetOf<Job>()

    /** Wartende Aufrufer von [confirm] (unter `synchronized`). */
    private var waiting = 0

    /** Antwort aus dem Dialog. */
    fun answer(proceed: Boolean) {
        val question = synchronized(this) {
            val open = pending.value ?: return
            confirmed = confirmedAfter(confirmed, proceed)
            pending.value = null
            open
        }
        question.complete(proceed)
    }

    /** Nur für Tests: warten mindestens [count] Aktionen auf die offene Frage? */
    fun hasWaitersForTest(count: Int): Boolean = synchronized(this) { waiting >= count }

    /** Nur für Tests: neuer «App-Lauf». */
    fun resetForTest() {
        synchronized(this) {
            confirmed = false
            unaskedPasses.clear()
            waiting = 0
            pending.value?.complete(false)
            pending.value = null
        }
    }

    /** So lange wartet [confirm] ohne Oberfläche auf sie (wie das 2-s-Netz unter iOS). */
    const val ASKER_WAIT_MILLIS = 2_000L
}
