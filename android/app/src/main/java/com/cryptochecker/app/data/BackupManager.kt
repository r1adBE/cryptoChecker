package com.cryptochecker.app.data

import com.cryptochecker.app.data.BackupFormat.BackupFile
import com.cryptochecker.app.data.BackupFormat.FORMAT
import com.cryptochecker.app.data.BackupFormat.VERSION
import com.cryptochecker.app.data.BackupFormat.checkVersion
import com.cryptochecker.app.data.BackupFormat.plainText
import com.cryptochecker.app.data.BackupFormat.withPasswordChars
import com.cryptochecker.app.data.BackupJournalCodec.PreviousState
import com.cryptochecker.app.data.BackupJournalCodec.journalJson
import com.cryptochecker.app.data.BackupWatchlistCodec.alarmToJson
import com.cryptochecker.app.data.BackupWatchlistCodec.watchToJson
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.StorageRecoveryNotice
import com.cryptochecker.app.data.live.LivePriceStream
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.portfolio.PortfolioAlarmDao
import com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository
import com.cryptochecker.app.data.portfolio.PortfolioDao
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.PortfolioJson
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.WatchedAddressDao
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sichern und Wiederherstellen: Merkliste, Alarme, Favoriten, Portfolio und
 * Einstellungen als eine JSON-Datei, die der Nutzer selbst ablegt
 * (z. B. in Downloads oder Google Drive). Keine Kurse, kein Konto.
 *
 * Hier Ablauf von Sichern, Wiederherstellen und Zurückrollen; Datei lesen in [BackupFormat], Merkliste und Alarme in
 * [BackupWatchlistCodec], Einstellungen in [BackupSettingsCodec], gemerkter Stand im Journal in [BackupJournalCodec].
 */
@Singleton
class BackupManager @Inject constructor(
    @param:ApplicationContext internal val context: Context,
    internal val database: AppDatabase,
    internal val portfolioDatabase: PortfolioDatabase,
    internal val watchDao: WatchDao,
    internal val favoritesRepository: FavoritesRepository,
    private val settingsRepository: SettingsRepository,
    private val activityRepository: ActivityRepository,
    internal val portfolioDao: PortfolioDao,
    private val portfolioRepository: PortfolioRepository,
    private val portfolioAlarmRepository: PortfolioAlarmRepository,
    internal val portfolioAlarmDao: PortfolioAlarmDao,
    internal val watchedAddressDao: WatchedAddressDao,
    /** Sperre der Aktualisierung: Merkliste ersetzen, ohne dass ein Durchlauf dazwischen Kurse schreibt. */
    internal val priceRefresher: PriceRefresher,
    internal val livePriceStream: LivePriceStream,
) {
    /** Stand vor dem Wiederherstellen, versiegelt bis zum Ende des Laufs ([RestoreJournal]). */
    internal val journal = RestoreJournal(
        java.io.File(context.noBackupFilesDir, RestoreJournal.FILE_NAME),
        com.cryptochecker.app.widget.PortfolioSnapshotKeyStore(
            RestoreJournal.KEY_ALIAS,
            com.cryptochecker.app.data.local.DatabaseKeyStore.suspectMarker(context),
        ),
    )

    /** Gewählte Sicherung einmal je Wiederherstellen lesen und entschlüsseln ([BackupReadCache]). */
    private val readCache = BackupReadCache<Uri, BackupFile>(decrypt = { f, pw -> plainText(f, pw) })

    /** Einstellungen in der Sicherung und im Journal ([BackupSettingsCodec]). */
    internal val settingsCodec = BackupSettingsCodec(settingsRepository) {
        com.cryptochecker.app.lock.AppLockAuth.canAuthenticate(context)
    }

    /**
     * Schreibt die Sicherung; mit [password] verschlüsselt ([BackupCrypto], gleiches Format
     * wie unter iOS), sonst als lesbares JSON. Danach wird die Datei zurückgelesen und Byte für Byte
     * verglichen; weicht sie ab → [BackupNotVerifiedException] (der Nutzer soll in eine neue Datei sichern).
     */
    suspend fun export(uri: Uri, password: String?) = withContext(Dispatchers.IO) {
        ensureStoredReadable()
        // Ein Stand je Datenbank: Merkliste und Alarme in einer Lesetransaktion der Hauptdatenbank, Transaktionen,
        // Portfolio-Alarme und Adressen in einer der Portfolio-Datenbank — eine gleichzeitige Änderung (Aktualisierung,
        // Widget, Alarm) landet ganz oder gar nicht in der Datei, nie halb. Getrennte Dateien: keine gemeinsame Transaktion
        val (watches, alarms) = database.withTransaction { watchDao.getWatches() to watchDao.getAllAlarms() }
        val portfolio = portfolioDatabase.withTransaction {
            Triple(portfolioDao.getAll(), portfolioAlarmRepository.getAll(), portfolioRepository.getWatchedAddresses())
        }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("watches", JSONArray().apply { watches.forEach { put(watchToJson(it)) } })
            .put("alarms", JSONArray().apply { alarms.forEach { put(alarmToJson(it)) } })
            .put("favorites", JSONObject().apply {
                FavoriteKind.entries.forEach { kind ->
                    put(kind.name, JSONArray(favoritesRepository.favorites(kind).value.toList()))
                }
            })
            .put("settings", settingsCodec.settingsToJson())
            .put("portfolio", JSONArray().apply { portfolio.first.forEach { put(PortfolioJson.txToJson(it)) } })
            .put("portfolioAlarms", JSONArray().apply { portfolio.second.forEach { put(PortfolioJson.alarmToJson(it)) } })
            // Beobachtete Adressen (Netz, Adresse, Bezeichnung, Datum, Guthaben-Verlauf und Einstandsposten)
            .put(WatchedAddressRules.BACKUP_KEY, WatchedAddressRules.toJson(WatchedAddressRules.backupEntries(portfolio.third)).asOrgJson())

        // Kompakt (das Einlesen nimmt auch eingerückte) und nie grösser als lesbar
        val plain = BackupFormat.encode(root)
        val bytes = if (password == null) plain
        else withPasswordChars(password) { BackupCrypto.toJson(BackupCrypto.encrypt(plain, it)).toByteArray(Charsets.UTF_8) }
        // Die verschlüsselte Hülle ist grösser (Base64): erst die fertige Datei zählt
        BackupLimits.checkWritable(bytes.size)

        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("Datei kann nicht geschrieben werden")
        stream.use {
            it.write(bytes)
            it.flush()
        }
        verifyWritten(uri, bytes)
    }

    /**
     * Kein Sichern/Einspielen, solange ein gespeicherter Stand nur leer im Arbeitsspeicher steht ([BackupStorageKeptException]);
     * kein Einspielen ([restoring]) in eine Datenbank nur im Arbeitsspeicher ([BackupStorageInMemoryException],
     * [BackupRestorePolicy.storageRefusal]). Vorher beide Datenbanken öffnen ([openBoth]): Die Merker gelten erst nach dem
     * ersten Öffnen, und das Portfolio ist bei ausgeschaltetem Portfolio sonst womöglich noch zu.
     */
    private fun ensureStoredReadable(restoring: Boolean = false) {
        openBoth()
        val refusals = listOf(AppDatabase.DB_NAME, PortfolioDatabase.DB_NAME).mapNotNull {
            BackupRestorePolicy.storageRefusal(
                storedKept = DatabaseEncryptionNotice.isStoredKept(it),
                inMemory = DatabaseEncryptionNotice.isInMemory(it),
                restoring = restoring,
            )
        }
        if (BackupRestorePolicy.StorageRefusal.KEPT in refusals) throw BackupStorageKeptException()
        if (BackupRestorePolicy.StorageRefusal.IN_MEMORY in refusals) throw BackupStorageInMemoryException()
    }

    /**
     * Beide Datenbanken öffnen, damit [DatabaseEncryptionNotice.isStoredKept] und [DatabaseEncryptionNotice.isInMemory]
     * verlässlich sind. Dasselbe Öffnen wie gleich danach: Sichern, Einspielen und Zurückrollen lesen bzw. schreiben beide
     * Dateien ohnehin, auch bei ausgeschaltetem Portfolio — also keine zusätzlichen Callbacks. Ein Fehler zeigt sich dort.
     */
    internal fun openBoth() {
        runCatching { database.openHelper.writableDatabase }
        runCatching { portfolioDatabase.openHelper.writableDatabase }
    }

    /** Darf in [name] zurückgeschrieben werden? Nach [openBoth] verlässlich ([BackupRestorePolicy.mayRollBackInto]). */
    internal fun rollBackTarget(name: String): Boolean = BackupRestorePolicy.mayRollBackInto(
        storedKept = DatabaseEncryptionNotice.isStoredKept(name),
        inMemory = DatabaseEncryptionNotice.isInMemory(name),
    )

    /**
     * Zurücklesen und vergleichen ([BackupVerification]): Manche Speicher-Anbieter (Cloud, USB) bestätigen das Schreiben,
     * legen aber eine gekürzte oder leere Datei ab — das zeigte sich sonst erst beim Wiederherstellen.
     */
    private fun verifyWritten(uri: Uri, expected: ByteArray) {
        val back = try {
            context.contentResolver.openInputStream(uri)?.use { BackupLimits.readLimited(it) }
        } catch (e: BackupLimits.TooLargeException) {
            throw BackupNotVerifiedException("Sicherung grösser als geschrieben")
        } ?: throw BackupNotVerifiedException("Sicherung nicht lesbar")
        BackupVerification.check(expected, back)?.let { throw BackupNotVerifiedException(it) }
    }

    /** Verschlüsselte Sicherung? Dann vor dem Wiederherstellen nach dem Passwort fragen. Liest die Datei frisch ein. */
    suspend fun needsPassword(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        readCache.clear()
        readCache.file(uri) { BackupFormat.read(context, uri) } is BackupFile.Encrypted
    }

    /** Wiederherstellen abgebrochen: gemerkte Datei und Klartext vergessen ([BackupReadCache]). */
    fun forgetPickedBackup() = readCache.clear()

    /**
     * Was vor der Rückfrage zu nennen ist ([RestorePreview]): ungültige Einträge, die nicht übernommen würden —
     * Paar-Alarme (z. B. Alarmart einer neueren Version), Portfolio-Transaktionen (z. B. Menge fehlt) und
     * Portfolio-Alarme — sowie Paare und Alarme über den Grenzen ([BackupRestoreLimits]), damit der Nutzer davon
     * erfährt, bevor etwas ersetzt wird, statt sie still zu verlieren.
     */
    suspend fun preview(uri: Uri, password: String?): RestorePreview = withContext(Dispatchers.IO) {
        val root = JSONObject(readCache.plain(uri, password) { BackupFormat.read(context, uri) })
        require(root.optString("format") == FORMAT) { "Keine Crypto-Checker-Sicherung" }
        // Neuere Version: schon vor der Rückfrage ablehnen (klare Meldung, nichts übernommen)
        checkVersion(root)
        BackupFields.checkParts(root)
        val watchlist = BackupWatchlistCodec.readWatchlist(root)
        RestorePreview(
            invalid = AlarmJson.parse(root.getJSONArray("alarms")).invalid +
                PortfolioJson.parseTxs(root.getJSONArray("portfolio")).invalid +
                PortfolioJson.parseAlarms(root.getJSONArray("portfolioAlarms")).invalid,
            droppedPairs = watchlist.droppedPairs,
            droppedAlarms = watchlist.droppedAlarms,
        )
    }

    /**
     * Ersetzt Merkliste, Alarme, Favoriten und Einstellungen durch die Sicherung.
     * [password] nur für verschlüsselte Sicherungen; lesbare Sicherungen brauchen keines.
     *
     * Alles oder nichts, so weit es geht: Erst wird die ganze Datei gelesen und bereinigt
     * ([WatchlistRestoreCleanup]), dann geschrieben — Merkliste zuerst (da kann am ehesten etwas
     * scheitern), dann Portfolio, Favoriten, Einstellungen. Merkliste und Portfolio liegen in getrennten
     * Datenbankdateien (keine gemeinsame Transaktion); scheitert ein späterer Schritt, wird der vorher
     * gemerkte Stand von Merkliste, Portfolio, Favoriten und Einstellungen zurückgeschrieben.
     *
     * Ungültige Portfolio-Einträge werden nur weggelassen, wenn der Nutzer das bestätigt hat
     * ([acceptInvalid] = gezeigte Anzahl aus [preview]); sind es mehr, bricht der Import ab,
     * bevor etwas ersetzt wird ([UnconfirmedInvalidEntriesException]).
     */
    suspend fun restore(uri: Uri, password: String?, acceptInvalid: Int = 0): RestoreResult = withContext(Dispatchers.IO) {
        ensureStoredReadable(restoring = true)
        // Was die Rückfrage geprüft hat (ohne erneutes Lesen und Argon2id), danach nicht mehr gemerkt. Von der Datei nur
        // die Art behalten: die verschlüsselte Hülle (bis 64 MB) nicht bis zum Ende des Laufs im Speicher.
        // Ohne Passwort keine Echtheitsprüfung: die Sperre bleibt an ([BackupRestorePolicy])
        val (encrypted, text) = readCache.take(uri, password) { BackupFormat.read(context, uri) }
            .let { (file, plain) -> (file is BackupFile.Encrypted) to plain }
        val root = JSONObject(text)
        // Klartext muss die eigentliche Sicherung sein (eine verschachtelte Hülle hat ein anderes «format»)
        require(root.optString("format") == FORMAT) { "Keine Crypto-Checker-Sicherung" }
        // Sicherung einer neueren App-Version: ganz ablehnen, bevor etwas gelesen oder ersetzt wird
        checkVersion(root)
        BackupFields.checkParts(root)

        // ── 1. Alles lesen und prüfen, bevor etwas geschrieben wird ──
        // Paare mit unzulässigen Kennungen und über den Grenzen fallen weg, gezählt ([BackupWatchlistCodec.readWatchlist])
        val watchlist = BackupWatchlistCodec.readWatchlist(root)
        val watches = watchlist.watches
        val alarms = watchlist.alarms
        val parsedPortfolio = PortfolioJson.parseTxs(root.getJSONArray("portfolio"))
        val parsedPortfolioAlarms = PortfolioJson.parseAlarms(root.getJSONArray("portfolioAlarms"))
        val invalid = watchlist.invalidAlarms + parsedPortfolio.invalid + parsedPortfolioAlarms.invalid
        if (invalid > acceptInvalid) throw UnconfirmedInvalidEntriesException(invalid)
        val portfolio = parsedPortfolio.items
        val portfolioAlarms = parsedPortfolioAlarms.items
        // Adressen ersetzen wie die Transaktionen (ungültige fallen weg, [WatchedAddressRules.merge])
        val watchedAddresses = WatchedAddressRules.fromJson(root.getJSONArray(WatchedAddressRules.BACKUP_KEY).asDomainJson())
        // Je Art eine Liste von Texten ([BackupFields.checkParts])
        val fav = root.getJSONObject("favorites")
        val favorites = FavoriteKind.entries.associateWith { kind ->
            fav.getJSONArray(kind.name).let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
        }
        val settings = root.getJSONObject("settings")

        // ── 2. Bisherigen Stand merken (für das Zurückrollen) ──
        val previous = PreviousState(
            watches = watchDao.getWatches(),
            alarms = watchDao.getAllAlarms(),
            portfolio = portfolioDao.getAll(),
            portfolioAlarms = portfolioAlarmDao.getAll(),
            watchedAddresses = watchedAddressDao.getAll(),
            favorites = FavoriteKind.entries.associateWith { favoritesRepository.favorites(it).value.toSet() },
            settings = settingsCodec.settingsToJson(),
        )

        // ── 3. Schreiben ── vorher das Journal: bricht der Prozess mittendrin ab, rollt der nächste Start zurück.
        // Ohne Journal nichts schreiben ([RestoreRun.execute], [BackupJournalUnavailableException])
        var settingsNotes = SettingsRestoreNotes()
        val journalText = journalJson(previous).toString()
        RestoreRun.execute(
            journal = object : RestoreRun.Journal {
                override fun write() = journal.write(journalText)
                override fun delete() = journal.delete()
            },
            writes = listOf(
                RestoreRun.Write(Step.WATCHLIST, touchedBefore = false) { writeWatchlist(watches, alarms) },
                RestoreRun.Write(Step.PORTFOLIO, touchedBefore = false) {
                    portfolioDatabase.withTransaction {
                        portfolioRepository.replaceAll(portfolio)
                        portfolioAlarmRepository.replaceAll(portfolioAlarms)
                        portfolioRepository.replaceWatchedAddresses(watchedAddresses)
                    }
                },
                RestoreRun.Write(Step.FAVORITES, touchedBefore = true) {
                    favorites.forEach { (kind, items) -> favoritesRepository.setAll(kind, items) }
                },
                RestoreRun.Write(Step.SETTINGS, touchedBefore = true) {
                    settingsNotes = settingsCodec.restoreSettings(settings, trusted = encrypted)
                },
            ),
            rollBack = { touched, cause -> rollBack(previous, touched, cause) },
        )
        // Gespeicherte Daten liessen sich nicht entsperren (StorageRecoveryNotice): mit dieser Sicherung erledigt
        runCatchingCancellable { StorageRecoveryNotice.acknowledgeSetAside(context) }

        // Alte ⚡-Ergebnisse gehören zu den alten Paaren (Ids können sich decken).
        activityRepository.retain(emptySet())

        RestoreResult(
            watches.size, alarms.size, settingsNotes.appLockKept,
            // Alte Meldungen entfernt der Aufrufer (Ids können sich mit den neuen decken)
            replacedWatchIds = previous.watches.map { it.id },
            replacedAlarmIds = previous.alarms.map { it.id },
            replacedPortfolioAlarmIds = previous.portfolioAlarms.map { it.id },
            droppedPairs = watchlist.droppedPairs,
            droppedAlarms = watchlist.droppedAlarms,
            addressServerNote = settingsNotes.addressServer,
        )
    }

    /**
     * Beim App-Start (vor dem ersten Lesen der Daten durch die Oberfläche): Liegt ein Journal ([RestoreJournal]) da, ist ein
     * Wiederherstellen abgebrochen — den Stand davor zurückschreiben und den Nutzer informieren
     * ([StorageRecoveryNotice.recordInterruptedRestore], [RestoreRecoveryPolicy]). Ohne Journal: nichts.
     */
    suspend fun recoverInterruptedRestore() = withContext(Dispatchers.IO) {
        when (val read = journal.read()) {
            RestoreJournal.Read.Absent, RestoreJournal.Read.Unavailable -> return@withContext
            RestoreJournal.Read.Unreadable -> {
                // Nicht mehr zu öffnen: der Stand ist womöglich halb — sagen, nicht still weiter
                Timber.w("Wiederherstellen abgebrochen, Journal unlesbar")
                runCatchingCancellable { journal.delete() }
                StorageRecoveryNotice.recordInterruptedRestore(context, rolledBack = false)
            }
            is RestoreJournal.Read.Found -> recoverFromJournal(read.text)
        }
    }

    /** Schritte, die schon (ganz oder teilweise) geschrieben wurden. */
    internal enum class Step { WATCHLIST, PORTFOLIO, FAVORITES, SETTINGS }
}
