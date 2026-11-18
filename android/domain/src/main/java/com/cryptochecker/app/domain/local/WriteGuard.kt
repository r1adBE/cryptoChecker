package com.cryptochecker.app.domain.local

/**
 * Schreibweg des Nutzers mit Schutz vor Schreibfehlern des Speichers (Umsetzung in :data, `guardedWrite`): Speicher voll
 * o. ä. ergibt den kurzen Hinweis «Konnte nicht gespeichert werden» statt eines Absturzes.
 */
interface WriteGuard {
    /** [block] ausführen; scheitert er am Speicher, kommt der Hinweis und [failed] zurück. */
    suspend fun <T> guarded(failed: T, block: suspend () -> T): T

    /** Schreibfehler des Speichers melden (andere Fehler nicht). */
    fun reportFailure(e: Throwable)
}
