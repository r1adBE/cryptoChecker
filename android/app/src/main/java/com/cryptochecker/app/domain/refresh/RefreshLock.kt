package com.cryptochecker.app.domain.refresh

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Beweis, dass der Aufrufer die Refresh-Sperre hält. Nur [RefreshLock] stellt ihn aus (die einzige Umsetzung ist
 * privat); Arbeit, die unter der Sperre laufen muss (Kurse speichern, Live-Kurse übernehmen, späte Tages-Bezüge),
 * verlangt ihn als Parameter. So ist im Typ sichtbar, was die Sperre braucht — und wer ihn schon hat, ruft die
 * Arbeit direkt auf statt erneut zu sperren.
 */
sealed interface RefreshLockScope

/**
 * Die Sperre für alles, was Kurse schreibt: volle und einzelne Aktualisierung, Live-Speichern, späte Tages-Bezüge,
 * Paar bearbeiten und Sicherung wiederherstellen — kein Durchlauf schreibt dazwischen Kurse mit den Daten von
 * vorher.
 *
 * Die Sperre ist nicht wiedereintrittsfähig. Statt still zu verklemmen, bricht ein erneutes Sperren in derselben
 * Coroutine (auch in Kind-Coroutinen) mit [IllegalStateException] ab; erkannt am Kontext-Element, das die Sperre
 * für die Dauer des Blocks setzt. Coroutinen in fremden Scopes erben es nicht — sie warten wie bisher.
 */
@Singleton
class RefreshLock @Inject constructor() {
    private val mutex = Mutex()

    /** Wartet auf die Sperre und führt [block] mit dem Beweis aus. */
    suspend fun <T> withLock(block: suspend (RefreshLockScope) -> T): T {
        checkNotHeld()
        mutex.lock()
        try {
            return withContext(Held) { block(Token) }
        } finally {
            mutex.unlock()
        }
    }

    /** Wie [withLock], gibt aber sofort auf, wenn die Sperre belegt ist: dann null, [block] läuft nicht. */
    suspend fun <T> tryWithLock(block: suspend (RefreshLockScope) -> T): T? {
        checkNotHeld()
        if (!mutex.tryLock()) return null
        try {
            return withContext(Held) { block(Token) }
        } finally {
            mutex.unlock()
        }
    }

    /** Wie [withLock], wartet aber höchstens [timeoutMillis]; danach null, [block] läuft nicht. */
    suspend fun <T> withLockWithin(timeoutMillis: Long, block: suspend (RefreshLockScope) -> T): T? {
        checkNotHeld()
        withTimeoutOrNull(timeoutMillis) { mutex.lock() } ?: return null
        try {
            return withContext(Held) { block(Token) }
        } finally {
            mutex.unlock()
        }
    }

    /** true, solange jemand die Sperre hält (nur für Tests und Protokoll). */
    val isLocked: Boolean get() = mutex.isLocked

    private suspend fun checkNotHeld() {
        check(currentCoroutineContext()[HeldElement] == null) {
            "Refresh-Sperre wird in dieser Coroutine schon gehalten: erneutes Sperren verklemmte"
        }
    }

    /** Einzige Umsetzung von [RefreshLockScope]; nur hier erreichbar. */
    private data object Token : RefreshLockScope

    /** Kontext-Merker «Sperre gehalten» für die Erkennung des Wiedereintritts. */
    private class HeldElement : AbstractCoroutineContextElement(HeldElement) {
        companion object Key : CoroutineContext.Key<HeldElement>
    }

    private companion object {
        val Held: CoroutineContext.Element = HeldElement()
    }
}
