package com.cryptochecker.app.domain.refresh

import com.cryptochecker.marketdata.usage.UsageMeter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Speicher von «Letzte Prüfungen» ([CheckRunLog]): kleine Textdatei, auf Android in `noBackupFilesDir`
 * (nicht gesichert, gilt nur für dieses Gerät). Bereitgestellt von `LocalDataModule`. Fehler beim
 * Lesen oder Schreiben bleiben still — die Liste ist nur ein Blick zurück.
 */
class CheckRunStore(private val file: File, private val meter: UsageMeter = UsageMeter.shared) {

    private val lock = Any()

    private val _runs = MutableStateFlow(read())

    /** Neueste zuerst. */
    val runs: StateFlow<List<CheckRun>> = _runs.asStateFlow()

    /**
     * Lauf vorne anfügen (höchstens [CheckRunLog.MAX]) und gleich schreiben. Jeder Lauf ausser in der offenen App
     * ([CheckTrigger.FOREGROUND]) zählt für «Akku & Aktivität» als Hintergrund-Lauf ([meter]); der Lauf ist hier zu
     * Ende, darum die Zähler gleich mitschreiben.
     */
    fun add(run: CheckRun) {
        if (run.trigger != CheckTrigger.FOREGROUND) {
            meter.countRun()
            meter.flush()
        }
        synchronized(lock) {
            val next = CheckRunLog.add(_runs.value, run)
            _runs.value = next
            runCatching {
                file.parentFile?.mkdirs()
                // Erst daneben schreiben, dann umbenennen: nie eine halbe Datei
                val tmp = File(file.path + ".tmp")
                tmp.writeText(CheckRunLog.encode(next))
                if (!tmp.renameTo(file)) {
                    file.writeText(CheckRunLog.encode(next))
                    tmp.delete()
                }
            }
        }
    }

    private fun read(): List<CheckRun> =
        runCatching { if (file.exists()) CheckRunLog.decode(file.readText()) else emptyList() }.getOrDefault(emptyList())

    companion object {
        const val FILE_NAME = "check_runs.txt"
    }
}
