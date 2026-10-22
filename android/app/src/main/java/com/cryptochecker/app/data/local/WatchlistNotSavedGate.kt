package com.cryptochecker.app.data.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Seit Build 104 (Review F-08): Merkliste nur im Arbeitsspeicher ohne gespeicherten Stand ([WatchlistStorageLock.Notice.NOT_SAVED],
 * Verschlüsselung noch nicht aktiv) — Änderungen gehen beim Neustart verloren. Vor der ersten Änderung des Nutzers je
 * App-Lauf (Prozess) einmal fragen: «Abbrechen» schreibt nicht, «Trotzdem» schreibt und fragt in diesem Lauf nicht mehr.
 * Seit Build 105 (Review ARC3-R01/R02, UX3-01/03/04): gefragt wird EINMAL je Nutzeraktion VOR der Aktion
 * ([com.cryptochecker.app.data.confirmUserWrite] im Controller bzw. ViewModel, vor Sperren, Rückgängig-Eintrag,
 * Mitteilungen, Banner und Haptik). Der Schreibweg im Repository fragt nicht mehr, sondern lehnt ohne Bestätigung ab
 * ([needsConfirmation], Sicherung in [com.cryptochecker.app.data.userWriteRefused]). Die Oberfläche zeigt den Dialog
 * (`WatchlistNotSavedGateHost`, einmal in der MainActivity). Ohne sichtbare Oberfläche (Hintergrund, Empfänger) wird nie
 * gefragt — dann wie bisher geschrieben. Gleich wie iOS (`WatchlistNotSavedGate`, Testfälle in beiden Tests).
 * Seit Build 106 (Review ARC4-R02, Variante A): Fehlt die Oberfläche beim Bestätigen nur kurz (Drehen, Dunkelmodus), wartet
 * [confirm] bis zu [ASKER_WAIT_MILLIS] auf sie und fragt dann. Ohne Oberfläche durchgelassen, lehnt die Sicherung im
 * Schreibweg danach nicht ab ([needsConfirmation]) — bis zur nächsten beantworteten Frage.
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
     * auf dieselbe Antwort). [canAsk]: sammelt gerade eine sichtbare Oberfläche [request]? Seit Build 105: Wird der letzte
     * Wartende abgebrochen (ViewModel weg), verschwindet die offene Frage — kein Dialog mehr ohne Aktion dahinter.
     * Seit Build 106: ohne Oberfläche erst [awaitAsker] (true = Oberfläche jetzt da, dann fragen).
     */
    suspend fun confirm(
        notSaved: Boolean = notSavedNow(),
        canAsk: Boolean = canAskNow(),
        awaitAsker: suspend () -> Boolean = { awaitSubscriber(ASKER_WAIT_MILLIS) },
    ): Boolean {
        val asker = canAsk || !mayAsk(notSaved) || awaitAsker()
        val question = synchronized(this) {
            if (!shouldAsk(notSaved, userWrite = true, confirmed = confirmed, canAsk = asker)) {
                // Ohne Oberfläche durchgelassen: die Sicherung lehnt die Schreibwege dieser Aktion nicht ab
                if (!asker && notSaved && !confirmed) passedUnasked = true
                return true
            }
            waiting++
            pending.value ?: CompletableDeferred<Boolean>().also { pending.value = it }
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
     * Seit Build 105: Sicherung im Schreibweg — bräuchte dieser Schreibweg jetzt noch eine Antwort ([shouldAsk])? Dann
     * lehnt das Repository ab (die Aktion hat [confirm] nicht vorher aufgerufen); fragt selbst nie. Seit Build 106 nicht
     * nach einem Durchlass ohne Oberfläche ([passedUnasked]): sonst lehnte sie dieselbe Aktion still ab (ARC4-R02).
     */
    fun needsConfirmation(notSaved: Boolean = notSavedNow(), canAsk: Boolean = canAskNow()): Boolean =
        synchronized(this) { !passedUnasked && shouldAsk(notSaved, userWrite = true, confirmed = confirmed, canAsk = canAsk) }

    /** Seit Build 106: Kann eine Aktion jetzt eine Frage brauchen (nicht gespeichert, noch nicht bestätigt), egal ob sichtbar? */
    fun mayAsk(notSaved: Boolean = notSavedNow()): Boolean =
        synchronized(this) { shouldAsk(notSaved, userWrite = true, confirmed = confirmed, canAsk = true) }

    /** Sammelt gerade eine sichtbare Oberfläche [request]? */
    private fun canAskNow(): Boolean = pending.subscriptionCount.value > 0

    /** Seit Build 106: höchstens [millis] auf eine sichtbare Oberfläche warten; true = sie ist da. */
    internal suspend fun awaitSubscriber(millis: Long): Boolean =
        withTimeoutOrNull(millis) { pending.subscriptionCount.first { it > 0 } } != null

    /** Seit Build 106: letzte Aktion ohne Oberfläche durchgelassen (unter `synchronized`; bis zur nächsten Antwort). */
    private var passedUnasked = false

    /** Wartende Aufrufer von [confirm] (unter `synchronized`). */
    private var waiting = 0

    /** Antwort aus dem Dialog. */
    fun answer(proceed: Boolean) {
        val question = synchronized(this) {
            val open = pending.value ?: return
            confirmed = confirmedAfter(confirmed, proceed)
            passedUnasked = false
            pending.value = null
            open
        }
        question.complete(proceed)
    }

    /** Nur für Tests: warten mindestens [count] Aktionen auf die offene Frage? */
    internal fun hasWaitersForTest(count: Int): Boolean = synchronized(this) { waiting >= count }

    /** Nur für Tests: neuer «App-Lauf». */
    internal fun resetForTest() {
        synchronized(this) {
            confirmed = false
            passedUnasked = false
            waiting = 0
            pending.value?.complete(false)
            pending.value = null
        }
    }

    /** Seit Build 106 (ARC4-R02): so lange wartet [confirm] ohne Oberfläche auf sie (wie das 2-s-Netz unter iOS). */
    const val ASKER_WAIT_MILLIS = 2_000L
}
