package com.cryptochecker.app.data

import com.cryptochecker.app.data.BackupFormat.BackupFile
import com.cryptochecker.app.data.BackupFormat.FORMAT
import com.cryptochecker.app.data.BackupFormat.VERSION
import com.cryptochecker.app.data.BackupFormat.checkVersion
import com.cryptochecker.app.data.BackupFormat.plainText
import com.cryptochecker.app.data.BackupFormat.withPasswordChars
import com.cryptochecker.app.data.BackupJournalCodec.PreviousState
import com.cryptochecker.app.data.BackupJournalCodec.journalJson
import com.cryptochecker.app.data.BackupJournalCodec.previousFromJournal
import com.cryptochecker.app.data.BackupWatchlistCodec.alarmToJson
import com.cryptochecker.app.data.BackupWatchlistCodec.jsonToWatch
import com.cryptochecker.app.data.BackupWatchlistCodec.watchToJson
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.watch.WatchIdentity
import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.live.LivePriceStream
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Was eine Wiederherstellung übernommen hat. [appLockKept] = eine Sicherung ohne Passwort wollte die
 * Portfolio-Sperre ausschalten, sie blieb an ([BackupRestorePolicy.appLock]).
 */
data class RestoreResult(
    val watches: Int,
    val alarms: Int,
    val appLockKept: Boolean = false,
    /** Ids der ersetzten Paare: ihre Kurs- und Aktivitäts-Meldungen gehören zu den alten Paaren. */
    val replacedWatchIds: List<Long> = emptyList(),
    /** Ids der ersetzten Alarme: ihre Alarm-Meldungen gehören zu den alten Alarmen. */
    val replacedAlarmIds: List<Long> = emptyList(),
    /** Ids der ersetzten Portfolio-Alarme (leer, wenn die Sicherung keine enthielt und sie blieben). */
    val replacedPortfolioAlarmIds: List<Long> = emptyList(),
)

/**
 * Die eben geschriebene Sicherungsdatei liest sich nicht so zurück, wie sie geschrieben wurde (Grösse oder Inhalt weichen
 * ab — z. B. ein Cloud-Anbieter, der die Datei kürzt). Die Datei taugt nicht als Sicherung: in eine neue Datei sichern.
 */
class BackupNotVerifiedException(message: String) : java.io.IOException(message)

/**
 * Merkliste oder Portfolio sind in diesem Lauf nur leer im Arbeitsspeicher geöffnet, weil der Keystore den Schlüssel
 * der vorhandenen Datei gerade nicht herausgibt ([com.cryptochecker.app.data.local.DatabaseEncryptionNotice.isStoredKept]).
 * Sichern ergäbe eine leere Datei, Einspielen ginge beim Neustart verloren — beides erst nach einem Neustart.
 */
class BackupStorageKeptException : IllegalStateException("Gespeicherter Stand gerade nicht lesbar")

/** Sicherung enthält mehr ungültige Portfolio-Einträge, als der Nutzer bestätigt hat. */
class UnconfirmedInvalidEntriesException(val invalid: Int) :
    IllegalStateException("$invalid ungültige Portfolio-Einträge nicht bestätigt")

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
    @param:ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val portfolioDatabase: PortfolioDatabase,
    private val watchDao: WatchDao,
    private val favoritesRepository: FavoritesRepository,
    private val settingsRepository: SettingsRepository,
    private val activityRepository: ActivityRepository,
    private val portfolioDao: PortfolioDao,
    private val portfolioRepository: PortfolioRepository,
    private val portfolioAlarmRepository: PortfolioAlarmRepository,
    private val portfolioAlarmDao: PortfolioAlarmDao,
    private val watchedAddressDao: WatchedAddressDao,
    /** Sperre der Aktualisierung: Merkliste ersetzen, ohne dass ein Durchlauf dazwischen Kurse schreibt. */
    private val priceRefresher: PriceRefresher,
    private val livePriceStream: LivePriceStream,
) {
    /** Stand vor dem Wiederherstellen, versiegelt bis zum Ende des Laufs ([RestoreJournal]). */
    private val journal = RestoreJournal(
        java.io.File(context.noBackupFilesDir, RestoreJournal.FILE_NAME),
        com.cryptochecker.app.widget.PortfolioSnapshotKeyStore(RestoreJournal.KEY_ALIAS),
    )

    /** Einstellungen in der Sicherung und im Journal ([BackupSettingsCodec]). */
    private val settingsCodec = BackupSettingsCodec(context, settingsRepository)

    /**
     * Schreibt die Sicherung; mit [password] verschlüsselt ([BackupCrypto], gleiches Format
     * wie unter iOS), sonst wie bisher als lesbares JSON. Danach wird die Datei zurückgelesen und Byte für Byte
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
            // Seit Build 46: beobachtete Adressen (nur Netz, Adresse, Bezeichnung, Datum — kein Guthaben)
            .put(WatchedAddressRules.BACKUP_KEY, WatchedAddressRules.toJson(WatchedAddressRules.backupEntries(portfolio.third)))

        val plain = root.toString(2).toByteArray(Charsets.UTF_8)
        val bytes = if (password == null) plain
        else withPasswordChars(password) { BackupCrypto.toJson(BackupCrypto.encrypt(plain, it)).toByteArray(Charsets.UTF_8) }

        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("Datei kann nicht geschrieben werden")
        stream.use {
            it.write(bytes)
            it.flush()
        }
        verifyWritten(uri, bytes)
    }

    /**
     * Zurücklesen und vergleichen ([BackupVerification]): Manche Speicher-Anbieter (Cloud, USB) bestätigen das Schreiben,
     * legen aber eine gekürzte oder leere Datei ab — das zeigte sich sonst erst beim Wiederherstellen.
     */
    /** Kein Sichern/Einspielen, solange ein gespeicherter Stand nur leer im Arbeitsspeicher steht ([BackupStorageKeptException]). */
    private fun ensureStoredReadable() {
        val kept = listOf(
            com.cryptochecker.app.data.local.AppDatabase.DB_NAME,
            com.cryptochecker.app.data.portfolio.PortfolioDatabase.DB_NAME,
        ).any { com.cryptochecker.app.data.local.DatabaseEncryptionNotice.isStoredKept(it) }
        if (kept) throw BackupStorageKeptException()
    }

    private fun verifyWritten(uri: Uri, expected: ByteArray) {
        val back = try {
            context.contentResolver.openInputStream(uri)?.use { BackupLimits.readLimited(it) }
        } catch (e: BackupLimits.TooLargeException) {
            throw BackupNotVerifiedException("Sicherung grösser als geschrieben")
        } ?: throw BackupNotVerifiedException("Sicherung nicht lesbar")
        BackupVerification.check(expected, back)?.let { throw BackupNotVerifiedException(it) }
    }

    /** Verschlüsselte Sicherung? Dann vor dem Wiederherstellen nach dem Passwort fragen. */
    suspend fun needsPassword(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        BackupFormat.read(context, uri) is BackupFile.Encrypted
    }

    /**
     * Passwort prüfen, ohne etwas zu ändern.
     * @throws BackupCrypto.WrongPasswordException bei falschem Passwort oder veränderter Datei.
     */
    suspend fun checkPassword(uri: Uri, password: String) {
        withContext(Dispatchers.IO) { plainText(BackupFormat.read(context, uri), password) }
    }

    /**
     * Wie viele Einträge der Sicherung ungültig sind und nicht übernommen würden: Paar-Alarme (z. B. Alarmart
     * einer neueren Version), Portfolio-Transaktionen (z. B. Menge fehlt) und Portfolio-Alarme — vor der
     * Rückfrage, damit der Nutzer davon erfährt statt sie still zu verlieren.
     */
    suspend fun invalidEntries(uri: Uri, password: String?): Int = withContext(Dispatchers.IO) {
        val root = JSONObject(plainText(BackupFormat.read(context, uri), password))
        require(root.optString("format") == FORMAT) { "Keine Crypto-Checker-Sicherung" }
        // Neuere Version: schon vor der Rückfrage ablehnen (klare Meldung, nichts übernommen)
        checkVersion(root)
        (root.optJSONArray("alarms")?.let { AlarmJson.parse(it).invalid } ?: 0) +
            (root.optJSONArray("portfolio")?.let { PortfolioJson.parseTxs(it).invalid } ?: 0) +
            (root.optJSONArray("portfolioAlarms")?.let { PortfolioJson.parseAlarms(it).invalid } ?: 0)
    }

    /**
     * Ersetzt Merkliste, Alarme, Favoriten und Einstellungen durch die Sicherung.
     * [password] nur für verschlüsselte Sicherungen; ältere, lesbare Sicherungen brauchen keines.
     *
     * Alles oder nichts, so weit es geht: Erst wird die ganze Datei gelesen und bereinigt
     * ([WatchlistRestoreCleanup]), dann geschrieben — Merkliste zuerst (da kann am ehesten etwas
     * scheitern), dann Portfolio, Favoriten, Einstellungen. Merkliste und Portfolio liegen in getrennten
     * Datenbankdateien (keine gemeinsame Transaktion); scheitert ein späterer Schritt, wird der vorher
     * gemerkte Stand von Merkliste, Portfolio, Favoriten und Einstellungen zurückgeschrieben.
     *
     * Ungültige Portfolio-Einträge werden nur weggelassen, wenn der Nutzer das bestätigt hat
     * ([acceptInvalid] = gezeigte Anzahl aus [invalidEntries]); sind es mehr, bricht der Import ab,
     * bevor etwas ersetzt wird ([UnconfirmedInvalidEntriesException]).
     */
    suspend fun restore(uri: Uri, password: String?, acceptInvalid: Int = 0): RestoreResult = withContext(Dispatchers.IO) {
        ensureStoredReadable()
        val file = BackupFormat.read(context, uri)
        val root = JSONObject(plainText(file, password))
        // Ohne Passwort keine Echtheitsprüfung: die Sperre bleibt an ([BackupRestorePolicy])
        val encrypted = file is BackupFile.Encrypted
        // Klartext muss die eigentliche Sicherung sein (eine verschachtelte Hülle hat ein anderes «format»)
        require(root.optString("format") == FORMAT) { "Keine Crypto-Checker-Sicherung" }
        // Sicherung einer neueren App-Version: ganz ablehnen, bevor etwas gelesen oder ersetzt wird
        checkVersion(root)

        // ── 1. Alles lesen und prüfen, bevor etwas geschrieben wird ──
        val parsedWatches = root.optJSONArray("watches")?.let { a -> (0 until a.length()).map { jsonToWatch(a.getJSONObject(it)) } }.orEmpty()
        val alarmsRead = root.optJSONArray("alarms")?.let(AlarmJson::parse)
        val parsedAlarms = alarmsRead?.items.orEmpty()
        val (watches, alarms) = WatchlistRestoreCleanup.clean(
            watches = parsedWatches,
            alarms = parsedAlarms,
            watchId = { it.id },
            pairKey = { WatchIdentity.key(it.marketKey, it.baseAsset, it.quoteAsset, it.contractType.name, it.pairId) },
            alarmId = { it.id },
            alarmWatchId = { it.watchId },
            withWatchId = { a, id -> a.copy(watchId = id) },
        )
        // Ältere Sicherungen haben noch kein Portfolio: dann bleibt das bestehende stehen.
        val parsedPortfolio = root.optJSONArray("portfolio")?.let(PortfolioJson::parseTxs)
        // Seit Runde 28; ältere Sicherungen ohne Portfolio-Alarme lassen die bestehenden stehen
        val parsedPortfolioAlarms = root.optJSONArray("portfolioAlarms")?.let(PortfolioJson::parseAlarms)
        val invalid = (alarmsRead?.invalid ?: 0) + (parsedPortfolio?.invalid ?: 0) + (parsedPortfolioAlarms?.invalid ?: 0)
        if (invalid > acceptInvalid) throw UnconfirmedInvalidEntriesException(invalid)
        val portfolio = parsedPortfolio?.items
        val portfolioAlarms = parsedPortfolioAlarms?.items
        // Seit Build 46; fehlt der Schlüssel (ältere Sicherung), bleiben die bestehenden Adressen stehen.
        // Sonst ersetzen wie die Transaktionen (ungültige fallen weg, [WatchedAddressRules.merge])
        val watchedAddresses = if (root.has(WatchedAddressRules.BACKUP_KEY)) {
            WatchedAddressRules.fromJson(root.optJSONArray(WatchedAddressRules.BACKUP_KEY))
        } else null
        // Nur Texte übernehmen (wie iOS); fehlt eine Art, bleibt sie stehen
        val favorites = root.optJSONObject("favorites")?.let { fav ->
            FavoriteKind.entries.mapNotNull { kind ->
                fav.optJSONArray(kind.name)?.let { arr -> kind to (0 until arr.length()).mapNotNull { arr.opt(it) as? String }.toSet() }
            }.toMap()
        }.orEmpty()
        val settings = root.optJSONObject("settings")

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

        // ── 3. Schreiben ── vorher das Journal: bricht der Prozess mittendrin ab, rollt der nächste Start zurück
        val journaled = runCatchingCancellable { journal.write(journalJson(previous).toString()) }
            .onFailure { Timber.w(it, "Wiederherstellen: Journal nicht geschrieben") }
            .getOrDefault(false)
        if (!journaled) Timber.w("Wiederherstellen ohne Journal (Schlüssel nicht verfügbar)")
        val touched = mutableSetOf<Step>()
        var appLockKept = false
        try {
            writeWatchlist(watches, alarms)
            touched += Step.WATCHLIST
            portfolioDatabase.withTransaction {
                if (portfolio != null) portfolioRepository.replaceAll(portfolio)
                if (portfolioAlarms != null) portfolioAlarmRepository.replaceAll(portfolioAlarms)
                if (watchedAddresses != null) portfolioRepository.replaceWatchedAddresses(watchedAddresses)
            }
            touched += Step.PORTFOLIO
            // Bestand aus alten Sicherungen ins Portfolio übernehmen (nur Coins ohne Transaktion)
            portfolioRepository.importHoldings(onlyNewCoins = true)
            touched += Step.FAVORITES
            favorites.forEach { (kind, items) -> favoritesRepository.setAll(kind, items) }
            touched += Step.SETTINGS
            settings?.let { appLockKept = settingsCodec.restoreSettings(it, trusted = encrypted) }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                // Ganz zurückgerollt: Journal weg; sonst bleibt es, und der nächste Start versucht es erneut
                if (rollBack(previous, touched, e)) runCatchingCancellable { journal.delete() }
                Unit
            }
            throw e
        }
        runCatchingCancellable { journal.delete() }.onFailure { Timber.w(it, "Wiederherstellen: Journal nicht gelöscht") }
        // Gespeicherte Daten liessen sich nicht entsperren (StorageRecoveryNotice): mit dieser Sicherung erledigt
        runCatchingCancellable { com.cryptochecker.app.data.local.StorageRecoveryNotice.acknowledgeSetAside(context) }

        // Alte ⚡-Ergebnisse gehören zu den alten Paaren (Ids können sich decken).
        activityRepository.retain(emptySet())

        RestoreResult(
            watches.size, alarms.size, appLockKept,
            // Alte Meldungen entfernt der Aufrufer (Ids können sich mit den neuen decken)
            replacedWatchIds = previous.watches.map { it.id },
            replacedAlarmIds = previous.alarms.map { it.id },
            replacedPortfolioAlarmIds = if (portfolioAlarms != null) previous.portfolioAlarms.map { it.id } else emptyList(),
        )
    }

    /**
     * Beim App-Start (vor dem ersten Lesen der Daten durch die Oberfläche): Liegt ein Journal ([RestoreJournal]) da, ist ein
     * Wiederherstellen abgebrochen — den Stand davor zurückschreiben und den Nutzer informieren
     * ([com.cryptochecker.app.data.local.StorageRecoveryNotice.recordInterruptedRestore]). Ohne Journal: nichts.
     */
    suspend fun recoverInterruptedRestore() = withContext(Dispatchers.IO) {
        when (val read = journal.read()) {
            RestoreJournal.Read.Absent, RestoreJournal.Read.Unavailable -> return@withContext
            RestoreJournal.Read.Unreadable -> {
                // Nicht mehr zu öffnen: der Stand ist womöglich halb — sagen, nicht still weiter
                Timber.w("Wiederherstellen abgebrochen, Journal unlesbar")
                runCatchingCancellable { journal.delete() }
                com.cryptochecker.app.data.local.StorageRecoveryNotice.recordInterruptedRestore(context, rolledBack = false)
            }
            is RestoreJournal.Read.Found -> {
                val previous = try {
                    previousFromJournal(JSONObject(read.text))
                } catch (e: Exception) {
                    Timber.w(e, "Wiederherstellen abgebrochen, Journal nicht lesbar")
                    runCatchingCancellable { journal.delete() }
                    com.cryptochecker.app.data.local.StorageRecoveryNotice.recordInterruptedRestore(context, rolledBack = false)
                    return@withContext
                }
                // Welche Schritte schon liefen, ist unbekannt: alle zurückschreiben (gleicher Stand, beliebig oft anwendbar)
                val complete = withContext(NonCancellable) {
                    rollBack(previous, Step.entries.toSet(), IllegalStateException("Wiederherstellen abgebrochen"))
                }
                if (complete) runCatchingCancellable { journal.delete() }
                com.cryptochecker.app.data.local.StorageRecoveryNotice.recordInterruptedRestore(context, rolledBack = complete)
            }
        }
    }

    /** Schritte, die schon (ganz oder teilweise) geschrieben wurden. */
    private enum class Step { WATCHLIST, PORTFOLIO, FAVORITES, SETTINGS }

    /** Merkliste in einer Transaktion ersetzen; ein übergangenes Paar bricht ab (statt Alarme zu verlieren). */
    private suspend fun writeWatchlist(watches: List<WatchEntity>, alarms: List<AlarmEntity>) {
        // Unter der Refresh-Sperre: Ein laufender Durchlauf schrieb sonst Kurse alter Paare unter Ids, die
        // die Sicherung neu vergibt (zusätzlich verwirft WatchDao.updatePriceForPair Kurse anderer Paare).
        // Gepufferte Live-Kurse gehören zu den alten Paaren: vorher vergessen.
        priceRefresher.withRefreshLock {
            livePriceStream.forgetAll()
            database.withTransaction {
                watchDao.deleteAllWatches()          // Alarme fallen per Fremdschlüssel mit weg
                watches.forEach { w -> check(watchDao.insertWatch(w) != -1L) { "Paar ${w.id} nicht übernommen" } }
                alarms.forEach { watchDao.insertAlarm(it) }
            }
        }
    }

    /**
     * Schreibt den gemerkten Stand der schon berührten Teile zurück (bestmöglich; Fehler dabei werden
     * an [cause] angehängt). Portfolio-Zeilen und Alarme kommen unverändert zurück (samt Alarm-Zustand).
     * @return true = alles zurückgeschrieben (das Journal darf weg).
     */
    private suspend fun rollBack(previous: PreviousState, touched: Set<Step>, cause: Throwable): Boolean {
        var complete = true
        suspend fun attempt(what: String, block: suspend () -> Unit) {
            runCatchingCancellable { block() }.onFailure {
                complete = false
                Timber.w(it, "Wiederherstellen: %s nicht zurückgesetzt", what)
                if (it !== cause) cause.addSuppressed(it)
            }
        }
        if (Step.SETTINGS in touched) attempt("Einstellungen") { settingsCodec.restoreSettings(previous.settings, trusted = true) }
        if (Step.FAVORITES in touched) attempt("Favoriten") {
            previous.favorites.forEach { (kind, items) -> favoritesRepository.setAll(kind, items) }
        }
        if (Step.PORTFOLIO in touched) attempt("Portfolio") {
            portfolioDatabase.withTransaction {
                portfolioDao.deleteAll()
                previous.portfolio.forEach { portfolioDao.insert(it) }
                portfolioAlarmDao.deleteAll()
                previous.portfolioAlarms.forEach { portfolioAlarmDao.insert(it) }
                // Samt letztem Guthaben (unverändert zurück)
                watchedAddressDao.deleteAll()
                previous.watchedAddresses.forEach { watchedAddressDao.insert(it) }
            }
        }
        // Auch nach dem Portfolio-Schritt: importHoldings leert den Bestand der Merkliste
        if (Step.WATCHLIST in touched) attempt("Merkliste") { writeWatchlist(previous.watches, previous.alarms) }
        Timber.w(cause, "Wiederherstellen fehlgeschlagen, bisheriger Stand zurückgeschrieben")
        return complete
    }
}
