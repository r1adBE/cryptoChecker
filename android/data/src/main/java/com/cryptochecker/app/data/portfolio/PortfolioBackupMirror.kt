package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.asOrgJson
import com.cryptochecker.app.data.BackupFields
import com.cryptochecker.app.data.asDomainJson
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.portfolio.AddressCostLots
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.WatchedAddressExtras
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressBackupEntry
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.math.BigDecimal

/**
 * Schalter «Portfolio in Systemsicherung» (Android).
 *
 * Die Portfolio-Datenbank ([PortfolioDatabase.DB_NAME]) steht nie in den Sicherungsregeln — die sind statisches XML und
 * kennen keine Einstellung, und die verschlüsselte Datei hülfe auf einem neuen Gerät ohnehin nicht (Schlüssel im
 * Keystore). Stattdessen sichert die Systemsicherung die Datei [FILE_NAME] (in backup_rules.xml und
 * data_extraction_rules.xml eingeschlossen) — Klartext-JSON wie die eigene Sicherung (ohne Alarm-Zustand), und darum nur
 * für die Dauer einer Sicherung vorhanden (wie die Kopie der Hauptdatenbank, DatabaseSystemBackup):
 *  - Schalter an: eine Merker-Datei [MARKER_NAME] in `noBackupFilesDir` ([setEnabled], CryptoCheckerApp folgt der
 *    Einstellung). Der Sicherungs-Agent (SystemBackupAgent, eingeschränkter Prozess ohne Hilt und ohne Einstellungen)
 *    liest nur sie, schreibt die Kopie in [SystemBackupAgent.onFullBackup][com.cryptochecker.app.data.local.SystemBackupAgent]
 *    ([writeForBackup]) und löscht sie danach wieder.
 *  - Schalter aus: kein Merker, keine Kopie.
 * Nach einer Wiederherstellung (neue Installation, die Portfolio-Datenbank gibt es noch nicht) übernimmt
 * [PortfolioDatabase] die Datei beim Anlegen ([importInto]) und löscht sie danach. Eine nach abgebrochener Sicherung
 * liegengebliebene Kopie löscht [cleanUpLeftover] beim Start.
 * Scheitert die Übernahme, liegt die Kopie bis zum nächsten Start beiseite ([setAside]); dann folgt genau ein weiterer
 * Versuch ([retryFailed]) und sie wird gelöscht — Klartext bleibt nie dauerhaft liegen.
 */
object PortfolioBackupMirror {
    /** Muss zu backup_rules.xml und data_extraction_rules.xml passen (domain="file"). */
    const val FILE_NAME = "portfolio_system_backup.json"
    private const val FORMAT = "cryptochecker-portfolio-system-backup"
    private const val VERSION = 1

    /** Merker «Portfolio in Systemsicherung» an — in `noBackupFilesDir` (gehört zu diesem Gerät, nie in einer Sicherung). */
    const val MARKER_NAME = "portfolio_system_backup.enabled"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun marker(context: Context): File = File(context.noBackupFilesDir, MARKER_NAME)

    /** Schalter an? (nur der Merker — lesbar ohne Hilt und ohne Einstellungen, im Sicherungs-Agenten) */
    fun isEnabled(context: Context): Boolean = marker(context).exists()

    /** Merker setzen bzw. entfernen; aus → auch eine noch liegende Kopie löschen. Fehler nur protokollieren. */
    suspend fun setEnabled(context: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val m = marker(context)
            if (enabled) {
                if (!m.exists()) {
                    m.parentFile?.mkdirs()
                    m.writeText("1")
                }
            } else {
                m.delete()
                // Eine noch nicht übernommene Wiederherstellung bleibt (die Portfolio-Datenbank übernimmt sie beim Anlegen)
                deleteNow(context)
            }
        }.onFailure { Timber.w(it, "Portfolio: Merker für die Systemsicherung nicht gesetzt") }
        Unit
    }

    /**
     * Beim Start, vor dem ersten Öffnen der Portfolio-Datenbank: Eine Kopie neben einer bestehenden Datenbank ist ein Rest
     * (abgebrochene Sicherung) — Klartext, also weg. Ohne Datenbank ist sie eine noch nicht
     * übernommene Wiederherstellung und bleibt für [importInto].
     */
    fun cleanUpLeftover(context: Context) {
        if (!file(context).exists() && !File(context.filesDir, "$FILE_NAME.tmp").exists()) return
        if (context.getDatabasePath(PortfolioDatabase.DB_NAME).exists()) delete(context)
    }

    /**
     * Im Sicherungs-Agenten: Kopie aus der Portfolio-Datenbank schreiben (geöffnet wie in der App, verschlüsselt). Ohne Datenbank keine Kopie. Fehler werfen — der Agent bricht die Sicherung dann ab, die vorige (mit
     * Portfolio) bleibt bestehen.
     */
    fun writeForBackup(context: Context) {
        // Noch nicht übernommene Wiederherstellung: die Kopie IST das Portfolio — nicht anfassen
        if (pendingRestore(context)) return
        delete(context)
        if (!context.getDatabasePath(PortfolioDatabase.DB_NAME).exists()) return
        val db = androidx.room.Room.databaseBuilder(context, PortfolioDatabase::class.java, PortfolioDatabase.DB_NAME)
            .openHelperFactory(com.cryptochecker.app.data.local.EncryptingOpenHelperFactory(context))
            .build()
        try {
            val text = kotlinx.coroutines.runBlocking {
                encode(
                    db.getPortfolioDao().getAll(),
                    db.getPortfolioAlarmDao().getAll(),
                    backupEntries(db.getWatchedAddressDao().getAll()),
                )
            }
            write(context, text)
        } finally {
            db.close()
        }
    }

    /** Name der beiseitegelegten Kopie, wenn sie nicht übernommen werden konnte. */
    const val FAILED_FILE_NAME = "portfolio_system_backup.failed.json"

    /** Gemeinsamer Anfang aller beiseitegelegten Kopien ([FAILED_FILE_NAME] und die mit Zeitstempel). */
    private const val FAILED_PREFIX = "portfolio_system_backup.failed"

    /** Beiseitegelegte Kopien in [dir] (älteste zuerst); leer ohne Ordner. */
    fun failedCopies(dir: File?): List<File> =
        dir?.listFiles { f -> f.isFile && f.name.startsWith(FAILED_PREFIX) && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * Nächster Start nach einer gescheiterten Übernahme: jede beiseitegelegte Kopie in [dir] genau einmal noch übernehmen
     * ([importOne], z. B. [importInto] in einer Transaktion) und danach löschen — gleich ob es geklappt hat. Eine Kopie,
     * die auch jetzt nicht lesbar ist, bliebe sonst als Klartext mit Beträgen und Adressen für immer im App-Ordner.
     * @return Anzahl Kopien, die sich auch beim zweiten Versuch nicht übernehmen liessen (dann nennt die App das).
     */
    fun retryFailed(dir: File?, importOne: (File) -> Unit): Int {
        var lost = 0
        failedCopies(dir).forEach { file ->
            runCatching { importOne(file) }
                .onSuccess { Timber.i("Portfolio: beiseitegelegte Kopie der Systemsicherung doch übernommen") }
                .onFailure {
                    lost++
                    Timber.w(it, "Portfolio: beiseitegelegte Kopie der Systemsicherung auch beim zweiten Versuch nicht lesbar")
                }
            if (!file.delete() && file.exists()) Timber.w("Portfolio: beiseitegelegte Kopie nicht gelöscht")
        }
        return lost
    }

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
        // Gleiches Format wie die eigene Sicherung
        .put(WatchedAddressRules.BACKUP_KEY, WatchedAddressRules.toJson(addresses).asOrgJson())
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

    /**
     * Kopie sofort löschen (Sicherungs-Agent nach der Sicherung, ohne Coroutine) — ausser sie ist eine noch nicht
     * übernommene Wiederherstellung ([pendingRestore]).
     */
    fun deleteNow(context: Context) {
        if (!pendingRestore(context)) delete(context)
    }

    /** Kopie da, Portfolio-Datenbank noch nicht: eine Wiederherstellung, die der erste Start übernimmt ([importInto]). */
    fun pendingRestore(context: Context): Boolean =
        file(context).isFile && !context.getDatabasePath(PortfolioDatabase.DB_NAME).exists()

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
     * @throws IllegalArgumentException bei fremdem Format, anderer Version, fehlender Liste oder fehlendem Feld eines
     * Eintrags ([encode] schreibt alle; [BackupFields]).
     */
    fun parse(text: String): Content {
        com.cryptochecker.app.data.BackupLimits.checkDepth(text)
        val root = JSONObject(text)
        require(root.optString("format") == FORMAT) { "Unbekanntes Format" }
        require((root.opt("version") as? Number)?.toDouble() == VERSION.toDouble()) { "Version ${root.opt("version")}" }
        BackupFields.checkEntries(root, listOf(BackupFields.TX, BackupFields.PORTFOLIO_ALARM, BackupFields.WATCHED_ADDRESS))
        val txs = PortfolioJson.parseTxs(root.getJSONArray("portfolio"))
        val alarms = PortfolioJson.parseAlarms(root.getJSONArray("portfolioAlarms"))
        val addresses = WatchedAddressRules.fromJson(root.getJSONArray(WatchedAddressRules.BACKUP_KEY).asDomainJson())
        return Content(
            txs = txs.items,
            alarms = alarms.items,
            invalid = txs.invalid + alarms.invalid,
            addresses = WatchedAddressRules.merge(emptyList(), addresses),
        )
    }

    /**
     * Übernimmt eine wiederhergestellte Kopie in die gerade angelegte (leere) Portfolio-Datenbank.
     * Erst alles lesen und prüfen, dann schreiben; ungültige Einträge werden übersprungen, aber gezählt
     * ([onInvalid], für den einmaligen Hinweis [PortfolioRestoreNotice]); Alarme kommen scharf und ohne
     * letzte Meldung zurück. [keepIds] false (zweiter Versuch in eine schon benutzte Datenbank, [retryFailed]): neue Ids,
     * damit kein inzwischen erfasster Eintrag mit gleicher Id ersetzt wird.
     * @return Anzahl übernommener Einträge (0 ohne Datei).
     */
    fun importInto(db: SupportSQLiteDatabase, file: File, keepIds: Boolean = true, onInvalid: (Int) -> Unit = {}): Int {
        if (!file.isFile) return 0
        // Nie ungebremst lesen (wie die eigene Sicherung, [com.cryptochecker.app.data.BackupLimits])
        val text = file.inputStream().use { com.cryptochecker.app.data.BackupLimits.readLimited(it) }.toString(Charsets.UTF_8)
        val content = parse(text)
        val txs = content.txs
        val alarms = content.alarms
        // Spalten und Speicherform wie Room: Enums als Name, Boolean als 0/1, Dezimalzahlen als Text (ein BigDecimal
        // würde sonst als ganze Zahl gebunden) — Mengen, Gebühren und Werte auf 18 Nachkommastellen wie [cleanForSave],
        // Preise wie [PortfolioDecimalConverters]
        val amountText = { v: BigDecimal? -> v?.let { PortfolioDecimal.text(it) } }
        val prices = PortfolioDecimalConverters()
        val restoreStates = PortfolioRestoreStateConverters()
        txs.forEach { t ->
            db.execSQL(
                "INSERT OR REPLACE INTO `portfolio_tx` (`id`, `coin`, `type`, `amount`, `priceUsdt`, `time`, `note`, " +
                    "`fee`, `feeUnit`, `exchange`, `transferFrom`, `transferTo`, `feeCoin`, `feeCoinAmount`, " +
                    "`feeCoinPriceUsdt`, `importKey`, `acquiredTime`, `toCoin`, `toAmount`, `swapValueUsdt`, `importBatch`, " +
                    "`giftToSpouse`, `linkedImportKey`, `importRestoreState`, `feeRow`, `interest`, `stakingMove`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    t.id.takeIf { keepIds && it > 0 }, t.coin, t.type.name, amountText(t.amount), prices.toText(t.priceUsdt),
                    t.time, t.note, amountText(t.fee), t.feeUnit, t.exchange,
                    t.transferFrom, t.transferTo, t.feeCoin, amountText(t.feeCoinAmount), prices.toText(t.feeCoinPriceUsdt),
                    t.importKey, t.acquiredTime,
                    // Tausch und Importlauf
                    t.toCoin, amountText(t.toAmount), amountText(t.swapValueUsdt), t.importBatch,
                    // Partner-Merker und Verknüpfung
                    if (t.giftToSpouse) 1L else 0L, t.linkedImportKey, restoreStates.toText(t.importRestoreState),
                    // Gebühr-Zeile, Zins und Staking-Umbuchung
                    if (t.feeRow) 1L else 0L, if (t.interest) 1L else 0L, if (t.stakingMove) 1L else 0L,
                ),
            )
        }
        alarms.forEach { a ->
            db.execSQL(
                "INSERT OR REPLACE INTO `portfolio_alarms` (`id`, `kind`, `threshold`, `currency`, `enabled`, " +
                    "`repeating`, `referenceAt`, `lastTriggeredAt`, `lastTriggeredValue`) VALUES (?, ?, ?, ?, ?, ?, 0, 0, NULL)",
                arrayOf<Any?>(
                    a.id.takeIf { keepIds && it > 0 }, a.kind.name, a.threshold, a.currency,
                    if (a.enabled) 1L else 0L, if (a.repeating) 1L else 0L,
                ),
            )
        }
        // Beobachtete Adressen ohne Guthaben (lädt die nächste Aktualisierung), mit ihren Beobachtungen,
        // Zusatzdaten (Token-Reihen, «Tokens mitzählen», EVM-Netze, «Nur anzeigen» — WatchedAddressExtras) und
        // Eröffnungsposten
        content.addresses.forEach { a ->
            db.execSQL(
                "INSERT OR IGNORE INTO `${WatchedAddressEntity.TABLE}` (`chain`, `address`, `label`, `lastBalance`, " +
                    "`lastUpdated`, `lastAttempt`, `lastError`, `lastProvider`, `addedAt`, `observations`, `costLots`) " +
                    "VALUES (?, ?, ?, NULL, 0, 0, NULL, NULL, ?, ?, ?)",
                arrayOf<Any?>(
                    a.chain.name, a.address, a.label, a.addedAt, WatchedAddressExtras.encode(a), AddressCostLots.encode(a.costLots),
                ),
            )
        }
        if (content.invalid > 0) onInvalid(content.invalid)
        return txs.size + alarms.size + content.addresses.size
    }
}
