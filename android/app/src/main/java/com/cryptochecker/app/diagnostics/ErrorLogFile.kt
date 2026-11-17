package com.cryptochecker.app.diagnostics

import android.content.Context
import android.util.AtomicFile
import com.cryptochecker.app.domain.diagnostics.ErrorLog
import java.io.File
import java.util.concurrent.Executors

/**
 * Ablage des Fehlerprotokolls ([ErrorLog]): eine kleine Textdatei in `noBackupFilesDir` (nicht in der Systemsicherung).
 * Geschrieben wird nacheinander auf einem eigenen Faden (nie auf dem Aufrufer, nie über Timber — sonst schriebe ein
 * Fehler beim Schreiben wieder ins Protokoll).
 */
class ErrorLogFile private constructor(context: Context) : ErrorLog.Sink {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "error-log").apply { isDaemon = true } }

    override fun append(line: String) {
        writer.execute {
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

        @Volatile private var instance: ErrorLogFile? = null

        /** Eine Ablage je Prozess (App, Widgets und Hintergrund schreiben nacheinander in dieselbe Datei). */
        fun of(context: Context): ErrorLogFile =
            instance ?: synchronized(this) { instance ?: ErrorLogFile(context.applicationContext).also { instance = it } }
    }
}
