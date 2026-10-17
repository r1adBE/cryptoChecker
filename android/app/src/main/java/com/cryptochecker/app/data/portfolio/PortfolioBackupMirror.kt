package com.cryptochecker.app.data.portfolio

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.WatchedAddressExtras
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressBackupEntry
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * - an: nach jeder Änderung an Transaktionen, Portfolio-Alarmen oder beobachteten Adressen wird die Datei neu geschrieben
 *   (gleiches JSON wie die eigene Sicherung, ohne Alarm-Zustand);
 * - aus: die Datei wird sofort gelöscht (auch bei jedem Start, [deleteCopy]).
 * Den Schalter beobachtet CryptoCheckerApp: [run] läuft nur, solange er an ist.
 * Nach einer Wiederherstellung (neue Installation, die Portfolio-Datenbank gibt es noch nicht) übernimmt
 * [PortfolioDatabase] die Datei beim Anlegen ([importInto]).
 *
 * Bewusst nicht über den Sicherungs-Agenten (SystemBackupAgent, seit Build 59 nur für die Kopie der
 * Hauptdatenbank): Ob das Portfolio mitgeht, hängt am Schalter, und der Agent läuft in einem eingeschränkten
 * App-Prozess ohne Hilt und ohne Einstellungen. Die Kopie ist bewusst unverschlüsselt (Klartext-JSON, nur mit
 * Schalter) — die verschlüsselte Portfolio-Datenbank hilft auf einem neuen Gerät nicht (Schlüssel im Keystore).
 */
@Singleton
class PortfolioBackupMirror @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val portfolioDao: PortfolioDao,
    private val portfolioAlarmDao: PortfolioAlarmDao,
    private val watchedAddressDao: WatchedAddressDao,
) {
    /**
     * Schreibt die Kopie nach jeder Änderung neu, bis der Aufrufer abbricht (Schalter aus,
     * CryptoCheckerApp). Nur bei eingeschaltetem Schalter aufrufen.
     */
    suspend fun run() {
        combine(
            portfolioDao.observeAll(),
            portfolioAlarmDao.observeAll(),
            // Nur Eingaben und beobachtete Änderungen, nicht das Guthaben: sonst schriebe jede Aktualisierung
            // die Datei neu (eine Beobachtung kommt nur bei geändertem Guthaben dazu, AddressBalanceHistory.record)
            watchedAddressDao.observeAll().map { list -> backupEntries(list) }.distinctUntilChanged(),
        ) { txs, alarms, addresses -> encode(txs, alarms, addresses) }
            .distinctUntilChanged()
            // Lesefehler (Datenbank) beenden nur die Kopie, nicht den Prozess
            .catch { Timber.w(it, "Portfolio: Kopie für die Systemsicherung angehalten") }
            .collect { text ->
                withContext(Dispatchers.IO) {
                    runCatching { write(context, text) }
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

        fun encode(
            txs: List<PortfolioTxEntity>,
            alarms: List<PortfolioAlarmEntity>,
            addresses: List<WatchedAddressBackupEntry> = emptyList(),
        ): String = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("portfolio", JSONArray().apply { txs.forEach { put(PortfolioJson.txToJson(it)) } })
            .put("portfolioAlarms", JSONArray().apply { alarms.forEach { put(PortfolioJson.alarmToJson(it)) } })
            // Seit Build 46 (gleiches Format wie die eigene Sicherung); ältere Kopien haben den Schlüssel nicht
            .put(WatchedAddressRules.BACKUP_KEY, WatchedAddressRules.toJson(addresses))
            .toString()

        /** Sicherungs-Einträge der gespeicherten Adressen (unbekanntes Netz übersprungen). */
        fun backupEntries(list: List<WatchedAddressEntity>): List<WatchedAddressBackupEntry> =
            WatchedAddressRules.backupEntries(list.mapNotNull { it.toDomain() })

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

        /** Schalter aus: Kopie (und eine halbe Nachbardatei) löschen; Fehler nur protokollieren. */
        suspend fun deleteCopy(context: Context) = withContext(Dispatchers.IO) {
            runCatching { delete(context) }
                .onFailure { Timber.w(it, "Portfolio: Kopie für die Systemsicherung nicht gelöscht") }
            Unit
        }

        /** Inhalt einer Kopie: gültige Einträge und wie viele ungültig waren (nicht übernommen). */
        class Content(
            val txs: List<PortfolioTxEntity>,
            val alarms: List<PortfolioAlarmEntity>,
            val invalid: Int,
            /** Geprüft und normalisiert ([WatchedAddressRules.merge]); ungültige fallen weg. */
            val addresses: List<WatchedAddress> = emptyList(),
        )

        /**
         * Kopie lesen und prüfen (ohne Datenbank, testbar). Ungültige Transaktionen und Alarme werden
         * gezählt ([PortfolioJson.parseTxs]) statt still verworfen.
         * @throws IllegalArgumentException bei fremdem Format.
         */
        fun parse(text: String): Content {
            com.cryptochecker.app.data.BackupLimits.checkDepth(text)
            val root = JSONObject(text)
            require(root.optString("format") == FORMAT) { "Unbekanntes Format" }
            val txs = root.optJSONArray("portfolio")?.let(PortfolioJson::parseTxs)
            val alarms = root.optJSONArray("portfolioAlarms")?.let(PortfolioJson::parseAlarms)
            return Content(
                txs = txs?.items.orEmpty(),
                alarms = alarms?.items.orEmpty(),
                invalid = (txs?.invalid ?: 0) + (alarms?.invalid ?: 0),
                addresses = WatchedAddressRules.merge(emptyList(), WatchedAddressRules.fromJson(root.optJSONArray(WatchedAddressRules.BACKUP_KEY))),
            )
        }

        /**
         * Übernimmt eine wiederhergestellte Kopie in die gerade angelegte (leere) Portfolio-Datenbank.
         * Erst alles lesen und prüfen, dann schreiben; ungültige Einträge werden übersprungen, aber gezählt
         * ([onInvalid], für den einmaligen Hinweis [PortfolioRestoreNotice]); Alarme kommen scharf und ohne
         * letzte Meldung zurück.
         * @return Anzahl übernommener Einträge (0 ohne Datei).
         */
        fun importInto(db: SupportSQLiteDatabase, file: File, onInvalid: (Int) -> Unit = {}): Int {
            if (!file.isFile) return 0
            // Nie ungebremst lesen (wie die eigene Sicherung, [com.cryptochecker.app.data.BackupLimits])
            val text = file.inputStream().use { com.cryptochecker.app.data.BackupLimits.readLimited(it) }.toString(Charsets.UTF_8)
            val content = parse(text)
            val txs = content.txs
            val alarms = content.alarms
            // Spalten und Speicherform wie Room: Enums als Name, Boolean als 0/1, Dezimalzahlen als Text
            // ([PortfolioDecimalConverters]; ein BigDecimal würde sonst als ganze Zahl gebunden)
            val decimals = PortfolioDecimalConverters()
            val restoreStates = PortfolioRestoreStateConverters()
            txs.forEach { t ->
                db.execSQL(
                    "INSERT OR REPLACE INTO `portfolio_tx` (`id`, `coin`, `type`, `amount`, `priceUsdt`, `time`, `note`, " +
                        "`fee`, `feeUnit`, `exchange`, `transferFrom`, `transferTo`, `feeCoin`, `feeCoinAmount`, " +
                        "`feeCoinPriceUsdt`, `importKey`, `acquiredTime`, `toCoin`, `toAmount`, `swapValueUsdt`, `importBatch`, " +
                        "`giftToSpouse`, `linkedImportKey`, `importRestoreState`) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        t.id.takeIf { it > 0 }, t.coin, t.type.name, decimals.toText(t.amount), decimals.toText(t.priceUsdt),
                        t.time, t.note, decimals.toText(t.fee), t.feeUnit, t.exchange,
                        t.transferFrom, t.transferTo, t.feeCoin, decimals.toText(t.feeCoinAmount), decimals.toText(t.feeCoinPriceUsdt),
                        t.importKey, t.acquiredTime,
                        // Tausch und Importlauf (Build 46)
                        t.toCoin, decimals.toText(t.toAmount), decimals.toText(t.swapValueUsdt), t.importBatch,
                        // Partner-Merker und Verknüpfung (Build 58, Portfolio-Datenbank v4)
                        if (t.giftToSpouse) 1L else 0L, t.linkedImportKey, restoreStates.toText(t.importRestoreState),
                    ),
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
            // Beobachtete Adressen ohne Guthaben (lädt die nächste Aktualisierung), mit ihren Beobachtungen und
            // Zusatzdaten (Token-Reihen, «Tokens mitzählen», EVM-Netze, «Nur anzeigen» — WatchedAddressExtras)
            content.addresses.forEach { a ->
                db.execSQL(
                    "INSERT OR IGNORE INTO `${WatchedAddressEntity.TABLE}` (`chain`, `address`, `label`, `lastBalance`, " +
                        "`lastUpdated`, `lastAttempt`, `lastError`, `lastProvider`, `addedAt`, `observations`) " +
                        "VALUES (?, ?, ?, NULL, 0, 0, NULL, NULL, ?, ?)",
                    arrayOf<Any?>(a.chain.name, a.address, a.label, a.addedAt, WatchedAddressExtras.encode(a)),
                )
            }
            if (content.invalid > 0) onInvalid(content.invalid)
            return txs.size + alarms.size + content.addresses.size
        }
    }
}
