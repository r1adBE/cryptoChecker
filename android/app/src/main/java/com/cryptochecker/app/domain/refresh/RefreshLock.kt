package com.cryptochecker.app.domain.refresh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
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

/** Nacharbeit unter der weitergereichten Sperre ([RefreshLock.withLockThen]). */
typealias LockedTail = suspend (RefreshLockScope) -> Unit

/** Ergebnis eines Blocks unter der Sperre und seine Nacharbeit (null = keine). */
typealias Locked<T> = Pair<T, LockedTail?>

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

    /**
     * Wie [withLock], aber [block] kann eine Nacharbeit zurückgeben, die die Sperre über sein Ende hinaus hält: Sie
     * läuft in [scope] und gibt die Sperre erst frei, wenn sie fertig ist (auch nach Fehler oder Abbruch). Der Aufrufer
     * ist so früher fertig, die Sperre wird dazwischen aber nie frei — kein anderer Schreiber kommt vor der Nacharbeit
     * an die Reihe. Rückgabe: Ergebnis von [block] und der Job der Nacharbeit (null = keine). Mit [stageTail]
     * vorgemerkte Nacharbeit läuft auch, wenn [block] danach mit Fehler oder Abbruch endet.
     */
    suspend fun <T> withLockThen(scope: CoroutineScope, block: suspend (RefreshLockScope) -> Locked<T>): Pair<T, Job?> {
        checkNotHeld()
        mutex.lock()
        return runThen(scope, block)
    }

    /** Wie [withLockThen], gibt aber sofort auf, wenn die Sperre belegt ist (wie [tryWithLock]): dann null. */
    suspend fun <T> tryWithLockThen(
        scope: CoroutineScope,
        block: suspend (RefreshLockScope) -> Locked<T>,
    ): Pair<T, Job?>? {
        checkNotHeld()
        if (!mutex.tryLock()) return null
        return runThen(scope, block)
    }

    /** Wie [withLockThen], wartet aber höchstens [timeoutMillis] (wie [withLockWithin]); danach null. */
    suspend fun <T> withLockWithinThen(
        timeoutMillis: Long,
        scope: CoroutineScope,
        block: suspend (RefreshLockScope) -> Locked<T>,
    ): Pair<T, Job?>? {
        checkNotHeld()
        withTimeoutOrNull(timeoutMillis) { mutex.lock() } ?: return null
        return runThen(scope, block)
    }

    /**
     * Merkt [tail] als Nacharbeit des laufenden Blocks unter [withLockThen] (oder einer Variante) vor; ein späterer
     * Aufruf ersetzt sie. Vorgemerkte Nacharbeit läuft auch, wenn der Block danach mit Fehler oder Abbruch endet (die
     * Sperre bleibt bis zu ihrem Ende gehalten, der Fehler geht danach an den Aufrufer). Gibt der Block selbst eine
     * Nacharbeit zurück, gilt diese. [lock] ist der Beweis der gehaltenen Sperre; ausserhalb eines solchen Blocks
     * (auch unter [withLock]) [IllegalStateException].
     */
    suspend fun stageTail(@Suppress("UNUSED_PARAMETER") lock: RefreshLockScope, tail: LockedTail?) {
        val slot = checkNotNull(currentCoroutineContext()[TailSlot]) {
            "Nacharbeit vormerken nur in einem Block unter withLockThen"
        }
        slot.tail = tail
    }

    /**
     * [block] mit gehaltener Sperre; danach die Nacharbeit (zurückgegeben oder vorgemerkt, [stageTail]) in [scope], die
     * die Sperre freigibt (sonst gleich hier). Endet [block] mit Fehler oder Abbruch, startet eine vorgemerkte
     * Nacharbeit trotzdem, danach geht der Fehler weiter.
     */
    private suspend fun <T> runThen(
        scope: CoroutineScope,
        block: suspend (RefreshLockScope) -> Locked<T>,
    ): Pair<T, Job?> {
        var handedOver = false
        val slot = TailSlot()
        try {
            val (value, returned) = try {
                withContext(Held + slot) { block(Token) }
            } catch (ex: Throwable) {
                slot.tail?.let { staged ->
                    handOver(scope, staged)
                    handedOver = true
                }
                throw ex
            }
            val tail = returned ?: slot.tail ?: return value to null
            val job = handOver(scope, tail)
            handedOver = true
            return value to job
        } finally {
            if (!handedOver) mutex.unlock()
        }
    }

    /** Startet [tail] in [scope]; sie gibt die Sperre am Ende frei (auch nach Fehler, Abbruch oder ohne Start). */
    private fun handOver(scope: CoroutineScope, tail: LockedTail): Job {
        val started = AtomicBoolean(false)
        val job = scope.launch(Held) {
            started.set(true)
            try {
                tail(Token)
            } finally {
                mutex.unlock()
            }
        }
        // Nie gestartet (Bereich schon beendet): dann gibt niemand sonst die Sperre frei
        job.invokeOnCompletion { if (!started.get()) mutex.unlock() }
        return job
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

    /** Kontext-Ablage der vorgemerkten Nacharbeit eines Blocks ([stageTail]); gilt nur für diesen einen Block. */
    private class TailSlot : AbstractCoroutineContextElement(TailSlot) {
        companion object Key : CoroutineContext.Key<TailSlot>

        @Volatile
        var tail: LockedTail? = null
    }

    private companion object {
        val Held: CoroutineContext.Element = HeldElement()
    }
}
