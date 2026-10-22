package com.cryptochecker.app.data.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Seit Build 104 (Review F-08): Merkliste nur im Arbeitsspeicher ohne gespeicherten Stand ([WatchlistStorageLock.Notice.NOT_SAVED],
 * Verschlüsselung noch nicht aktiv) — Änderungen gehen beim Neustart verloren. Vor der ersten Änderung des Nutzers je
 * App-Lauf (Prozess) einmal fragen: «Abbrechen» schreibt nicht, «Trotzdem» schreibt und fragt in diesem Lauf nicht mehr.
 * Gefragt wird zentral im Schreibweg ([com.cryptochecker.app.data.userWriteRefused]); die Oberfläche zeigt den Dialog
 * (`WatchlistNotSavedGateHost`, einmal in der MainActivity). Ohne sichtbare Oberfläche (Hintergrund, Empfänger) wird nie
 * gefragt — dann wie bisher geschrieben. Gleich wie iOS (`WatchlistNotSavedGate`, Testfälle in beiden Tests).
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
     * Vor einem Schreibweg des Nutzers: true = schreiben. Fragt höchstens einmal gleichzeitig (weitere Schreibwege warten
     * auf dieselbe Antwort). [canAsk]: sammelt gerade eine sichtbare Oberfläche [request]?
     */
    suspend fun confirm(notSaved: Boolean = notSavedNow(), canAsk: Boolean = pending.subscriptionCount.value > 0): Boolean {
        val question = synchronized(this) {
            if (!shouldAsk(notSaved, userWrite = true, confirmed = confirmed, canAsk = canAsk)) return true
            pending.value ?: CompletableDeferred<Boolean>().also { pending.value = it }
        }
        return question.await()
    }

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

    /** Nur für Tests: neuer «App-Lauf». */
    internal fun resetForTest() {
        synchronized(this) {
            confirmed = false
            pending.value?.complete(false)
            pending.value = null
        }
    }
}
