package com.cryptochecker.app.data.portfolio

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cryptochecker.app.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schalter «Portfolio in Systemsicherung» (Android).
 *
 * Die Portfolio-Datenbank ([PortfolioDatabase.DB_NAME]) steht nie in den Sicherungsregeln — die sind
 * statisches XML und kennen keine Einstellung. Stattdessen sichert die Systemsicherung die Datei
 * [FILE_NAME] (in backup_rules.xml und data_extraction_rules.xml eingeschlossen), und die gibt es nur,
 * solange der Schalter an ist:
 * - an: nach jeder Änderung an Transaktionen oder Portfolio-Alarmen wird die Datei neu geschrieben
 *   (gleiches JSON wie die eigene Sicherung, ohne Alarm-Zustand);
 * - aus: die Datei wird sofort gelöscht (auch bei jedem Start).
 * Nach einer Wiederherstellung (neue Installation, die Portfolio-Datenbank gibt es noch nicht) übernimmt
 * [PortfolioDatabase] die Datei beim Anlegen ([importInto]).
 *
 * Bewusst kein eigener BackupAgent: Der müsste die XML-Regeln für Auto Backup und Geräte-Übertragung
 * nachbauen und liefe in einem eingeschränkten App-Prozess ohne Hilt — viel Fläche für Fehler, die sich
 * ohne echtes Gerät kaum prüfen lassen.
 */
@Singleton
class PortfolioBackupMirror @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val portfolioDao: PortfolioDao,
    private val portfolioAlarmDao: PortfolioAlarmDao,
    private val settingsRepository: SettingsRepository,
) {
    /** Läuft für die ganze Lebensdauer des Prozesses (CryptoCheckerApp). */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run() {
        settingsRepository.settings
            .map { it.portfolioSystemBackup }
            .distinctUntilChanged()
            .flatMapLatest { allowed ->
                if (!allowed) flowOf(null)
                else combine(portfolioDao.observeAll(), portfolioAlarmDao.observeAll()) { txs, alarms -> encode(txs, alarms) }
            }
            .distinctUntilChanged()
            // Lesefehler (Datenbank, Einstellungen) beenden nur die Kopie, nicht den Prozess
            .catch { Timber.w(it, "Portfolio: Kopie für die Systemsicherung angehalten") }
            .collect { text ->
                withContext(Dispatchers.IO) {
                    runCatching { if (text == null) delete(context) else write(context, text) }
                        .onFailure { Timber.w(it, "Portfolio: Kopie für die Systemsicherung nicht aktualisiert") }
                }
            }
    }

    companion object {
        /** Muss zu backup_rules.xml und data_extraction_rules.xml passen (domain="file"). */
        const val FILE_NAME = "portfolio_system_backup.json"
        private const val FORMAT = "cryptochecker-portfolio-system-backup"
        private const val VERSION = 1

        fun file(context: Context): File = File(context.filesDir, FILE_NAME)

        /** Name der beiseitegelegten Kopie, wenn sie nicht übernommen werden konnte. */
        const val FAILED_FILE_NAME = "portfolio_system_backup.failed.json"

        /**
         * Legt eine wiederhergestellte, aber nicht übernehmbare Kopie unter [FAILED_FILE_NAME] beiseite
         * (gleicher Ordner), damit sie nie überschrieben wird. Gibt es die schon, kommt ein Zeitstempel
         * in den Namen — eine frühere beiseitegelegte Kopie bleibt ebenfalls erhalten.
         * @return die neue Datei oder null, wenn es nichts beiseitezulegen gab.
         */
        fun setAside(file: File): File? {
            if (!file.isFile) return null
            val dir = file.absoluteFile.parentFile ?: error("Kein Ordner")
            var target = File(dir, FAILED_FILE_NAME)
            if (target.exists()) target = File(dir, "portfolio_system_backup.failed-${System.currentTimeMillis()}.json")
            if (!file.renameTo(target)) error("Umbenennen fehlgeschlagen")
            return target
        }

        fun encode(txs: List<PortfolioTxEntity>, alarms: List<PortfolioAlarmEntity>): String = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("portfolio", JSONArray().apply { txs.forEach { put(PortfolioJson.txToJson(it)) } })
            .put("portfolioAlarms", JSONArray().apply { alarms.forEach { put(PortfolioJson.alarmToJson(it)) } })
            .toString()

        /** Erst in eine Nachbardatei, dann umbenennen: die Sicherung sieht nie eine halbe Datei. */
        private fun write(context: Context, text: String) {
            val target = file(context)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                error("Umbenennen fehlgeschlagen")
            }
        }

        private fun delete(context: Context) {
            file(context).delete()
            File(context.filesDir, "$FILE_NAME.tmp").delete()
        }

        /**
         * Übernimmt eine wiederhergestellte Kopie in die gerade angelegte (leere) Portfolio-Datenbank.
         * Erst alles lesen und prüfen, dann schreiben; ungültige Einträge werden übersprungen (wie beim
         * Wiederherstellen der eigenen Sicherung), Alarme kommen scharf und ohne letzte Meldung zurück.
         * @return Anzahl übernommener Einträge (0 ohne Datei).
         */
        fun importInto(db: SupportSQLiteDatabase, file: File): Int {
            if (!file.isFile) return 0
            val root = JSONObject(file.readText(Charsets.UTF_8))
            require(root.optString("format") == FORMAT) { "Unbekanntes Format" }
            val txs = root.optJSONArray("portfolio")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(PortfolioJson::jsonToTx) }
            }.orEmpty()
            val alarms = root.optJSONArray("portfolioAlarms")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(PortfolioJson::jsonToAlarm) }
            }.orEmpty()
            // Spalten und Speicherform wie Room: Enums als Name, Boolean als 0/1
            txs.forEach { t ->
                db.execSQL(
                    "INSERT OR REPLACE INTO `portfolio_tx` (`id`, `coin`, `type`, `amount`, `priceUsdt`, `time`, `note`) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(t.id.takeIf { it > 0 }, t.coin, t.type.name, t.amount, t.priceUsdt, t.time, t.note),
                )
            }
            alarms.forEach { a ->
                db.execSQL(
                    "INSERT OR REPLACE INTO `portfolio_alarms` (`id`, `kind`, `threshold`, `currency`, `enabled`, " +
                        "`repeating`, `referenceAt`, `lastTriggeredAt`, `lastTriggeredValue`) VALUES (?, ?, ?, ?, ?, ?, 0, 0, NULL)",
                    arrayOf<Any?>(
                        a.id.takeIf { it > 0 }, a.kind.name, a.threshold, a.currency,
                        if (a.enabled) 1L else 0L, if (a.repeating) 1L else 0L,
                    ),
                )
            }
            return txs.size + alarms.size
        }
    }
}
