package com.cryptochecker.app.diagnostics

import android.content.Context
import android.util.AtomicFile
import com.cryptochecker.app.domain.diagnostics.ErrorLog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ablage des Fehlerprotokolls ([ErrorLog]): eine kleine Textdatei in `noBackupFilesDir` (nicht in der Systemsicherung).
 * Geschrieben wird nacheinander auf einem eigenen Faden (nie auf dem Aufrufer, nie über Timber — sonst schriebe ein
 * Fehler beim Schreiben wieder ins Protokoll).
 */
class ErrorLogFile private constructor(context: Context) : ErrorLog.Sink {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "error-log").apply { isDaemon = true } }

    override fun append(line: String) {
        writer.execute { write(line) }
    }

    /** Nach den wartenden Einträgen und vor der Rückkehr geschrieben (höchstens [NOW_TIMEOUT_MILLIS]). */
    override fun appendNow(line: String) {
        runCatching { writer.submit { write(line) }.get(NOW_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
    }

    private fun write(line: String) {
        synchronized(this) {
            runCatching {
                val next = ErrorLog.appended(readLines(), line)
                val out = file.startWrite()
                try {
                    out.write((next.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
                    file.finishWrite(out)
                } catch (e: java.io.IOException) {
                    file.failWrite(out)
                    throw e
                }
            }
        }
    }

    /** Gespeicherte Zeilen (älteste zuerst); unlesbar → leer. */
    fun readLines(): List<String> = synchronized(this) {
        runCatching { file.readFully().toString(Charsets.UTF_8).lines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
    }

    companion object {
        const val FILE_NAME = "error_log.txt"
        private const val NOW_TIMEOUT_MILLIS = 1_500L

        @Volatile private var instance: ErrorLogFile? = null

        /** Eine Ablage je Prozess (App, Widgets und Hintergrund schreiben nacheinander in dieselbe Datei). */
        fun of(context: Context): ErrorLogFile =
            instance ?: synchronized(this) { instance ?: ErrorLogFile(context.applicationContext).also { instance = it } }
    }
}
