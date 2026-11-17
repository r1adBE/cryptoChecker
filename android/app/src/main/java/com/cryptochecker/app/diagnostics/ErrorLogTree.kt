package com.cryptochecker.app.diagnostics

import android.util.Log
import com.cryptochecker.app.domain.diagnostics.BackgroundErrors
import com.cryptochecker.app.domain.diagnostics.ErrorLog
import timber.log.Timber

/**
 * Warnungen und Fehler (Timber) ins Fehlerprotokoll ([ErrorLog]): Codestelle des Aufrufs (Klasse, Methode, Zeile; im
 * Release-Build verschleiert) und Fehlertyp — der Meldungstext nicht.
 */
class ErrorLogTree : Timber.Tree() {
    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        ErrorLog.record(if (priority == Log.WARN) 'W' else 'E', callSite(), t?.let(BackgroundErrors::kindOf))
    }

    /** Erste Stelle ausserhalb von Timber und dieser Klasse: «paket.Klasse.methode:42». */
    private fun callSite(): String {
        val frame = Throwable().stackTrace.firstOrNull { frame ->
            !frame.className.startsWith("timber.log.") && frame.className != ErrorLogTree::class.java.name
        } ?: return "-"
        return "${frame.className}.${frame.methodName}:${frame.lineNumber}"
    }
}
