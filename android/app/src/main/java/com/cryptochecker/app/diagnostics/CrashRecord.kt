package com.cryptochecker.app.diagnostics

import com.cryptochecker.app.domain.diagnostics.BackgroundErrors
import com.cryptochecker.app.domain.diagnostics.ErrorLog

/**
 * Absturz ins Fehlerprotokoll ([ErrorLog]): Stelle im eigenen Code («crash:Klasse.methode:42», im Release-Build
 * verschleiert) und Fehlertyp der eigentlichen Ursache — ohne Meldungstext, nur auf dem Gerät, nichts wird gesendet.
 * Danach übernimmt der bisherige Behandler (Absturz wie bisher). So lässt sich ein Absturz mit dem Diagnosebericht
 * finden, den man selbst teilt.
 */
object CrashRecord {

    private const val OWN_PACKAGE = "com.cryptochecker."

    /** Einmal beim Start, nach [ErrorLog.install]. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { ErrorLog.recordNow('E', area(error), BackgroundErrors.kindOf(rootCause(error))) }
            if (previous != null) previous.uncaughtException(thread, error) else throw error
        }
    }

    /** Innerste Ursache (höchstens einige Stufen, gegen Ringe). */
    fun rootCause(error: Throwable): Throwable {
        var current = error
        repeat(MAX_CAUSES) { current = current.cause?.takeIf { it !== current } ?: return current }
        return current
    }

    /** «crash:Klasse.methode:42» — erste Stelle im eigenen Code, von der Ursache her; sonst die oberste Stelle. */
    fun area(error: Throwable): String {
        val chain = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSES + 1).toList()
        val frame = chain.asReversed().firstNotNullOfOrNull { t -> t.stackTrace.firstOrNull { it.className.startsWith(OWN_PACKAGE) } }
            ?: error.stackTrace.firstOrNull()
            ?: return "crash"
        return "crash:${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
    }

    private const val MAX_CAUSES = 8
}
