package com.cryptochecker.app.data

import com.cryptochecker.app.domain.watch.ColumnSort
import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.NOTE_MAX
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.PortfolioAlarmDao
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository
import com.cryptochecker.app.data.portfolio.PortfolioDao
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.PortfolioJson
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.data.portfolio.WatchedAddressDao
import com.cryptochecker.app.data.portfolio.WatchedAddressEntity
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.lock.AppLockAuth
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.marketdata.model.FuturesContractType
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
data class RestoreResult(val watches: Int, val alarms: Int, val appLockKept: Boolean = false)

/** Sicherung enthält mehr ungültige Portfolio-Einträge, als der Nutzer bestätigt hat. */
class UnconfirmedInvalidEntriesException(val invalid: Int) :
    IllegalStateException("$invalid ungültige Portfolio-Einträge nicht bestätigt")

/**
 * Sichern und Wiederherstellen: Merkliste, Alarme, Favoriten, Portfolio und
 * Einstellungen als eine JSON-Datei, die der Nutzer selbst ablegt
 * (z. B. in Downloads oder Google Drive). Keine Kurse, kein Konto.
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
) {
    /**
     * Schreibt die Sicherung; mit [password] verschlüsselt ([BackupCrypto], gleiches Format
     * wie unter iOS), sonst wie bisher als lesbares JSON.
     */
    suspend fun export(uri: Uri, password: String?) = withContext(Dispatchers.IO) {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("watches", JSONArray().apply { watchDao.getWatches().forEach { put(watchToJson(it)) } })
            .put("alarms", JSONArray().apply { watchDao.getAllAlarms().forEach { put(alarmToJson(it)) } })
            .put("favorites", JSONObject().apply {
                FavoriteKind.entries.forEach { kind ->
                    put(kind.name, JSONArray(favoritesRepository.favorites(kind).value.toList()))
                }
            })
            .put("settings", settingsToJson())
            .put("portfolio", JSONArray().apply { portfolioDao.getAll().forEach { put(PortfolioJson.txToJson(it)) } })
            .put("portfolioAlarms", JSONArray().apply { portfolioAlarmRepository.getAll().forEach { put(PortfolioJson.alarmToJson(it)) } })
            // Seit Build 46: beobachtete Adressen (nur Netz, Adresse, Bezeichnung, Datum — kein Guthaben)
            .put(WatchedAddressRules.BACKUP_KEY, WatchedAddressRules.toJson(WatchedAddressRules.backupEntries(portfolioRepository.getWatchedAddresses())))

        val plain = root.toString(2).toByteArray(Charsets.UTF_8)
        val bytes = if (password == null) plain
        else BackupCrypto.toJson(BackupCrypto.encrypt(plain, password)).toByteArray(Charsets.UTF_8)

        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("Datei kann nicht geschrieben werden")
        stream.use { it.write(bytes) }
    }

    /** Verschlüsselte Sicherung? Dann vor dem Wiederherstellen nach dem Passwort fragen. */
    suspend fun needsPassword(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        read(uri) is BackupFile.Encrypted
    }

    /**
     * Passwort prüfen, ohne etwas zu ändern.
     * @throws BackupCrypto.WrongPasswordException bei falschem Passwort oder veränderter Datei.
     */
    suspend fun checkPassword(uri: Uri, password: String) {
        withContext(Dispatchers.IO) { plainText(read(uri), password) }
    }

    /**
     * Wie viele Einträge der Sicherung ungültig sind und nicht übernommen würden: Paar-Alarme (z. B. Alarmart
     * einer neueren Version), Portfolio-Transaktionen (z. B. Menge fehlt) und Portfolio-Alarme — vor der
     * Rückfrage, damit der Nutzer davon erfährt statt sie still zu verlieren.
     */
    suspend fun invalidEntries(uri: Uri, password: String?): Int = withContext(Dispatchers.IO) {
        val root = JSONObject(plainText(read(uri), password))
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
        val file = read(uri)
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
            pairKey = { listOf(it.marketKey, it.baseAsset, it.quoteAsset, it.contractType.name) },
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
            settings = settingsToJson(),
        )

        // ── 3. Schreiben ──
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
            settings?.let { appLockKept = restoreSettings(it, trusted = encrypted) }
        } catch (e: Throwable) {
            withContext(NonCancellable) { rollBack(previous, touched, e) }
            throw e
        }

        // Alte ⚡-Ergebnisse gehören zu den alten Paaren (Ids können sich decken).
        activityRepository.retain(emptySet())

        RestoreResult(watches.size, alarms.size, appLockKept)
    }

    /** Was vor dem Wiederherstellen da war. */
    private class PreviousState(
        val watches: List<WatchEntity>,
        val alarms: List<AlarmEntity>,
        val portfolio: List<PortfolioTxEntity>,
        val portfolioAlarms: List<PortfolioAlarmEntity>,
        val watchedAddresses: List<WatchedAddressEntity>,
        val favorites: Map<FavoriteKind, Set<String>>,
        val settings: JSONObject,
    )

    /** Schritte, die schon (ganz oder teilweise) geschrieben wurden. */
    private enum class Step { WATCHLIST, PORTFOLIO, FAVORITES, SETTINGS }

    /** Merkliste in einer Transaktion ersetzen; ein übergangenes Paar bricht ab (statt Alarme zu verlieren). */
    private suspend fun writeWatchlist(watches: List<WatchEntity>, alarms: List<AlarmEntity>) {
        database.withTransaction {
            watchDao.deleteAllWatches()          // Alarme fallen per Fremdschlüssel mit weg
            watches.forEach { w -> check(watchDao.insertWatch(w) != -1L) { "Paar ${w.id} nicht übernommen" } }
            alarms.forEach { watchDao.insertAlarm(it) }
        }
    }

    /**
     * Schreibt den gemerkten Stand der schon berührten Teile zurück (bestmöglich; Fehler dabei werden
     * an [cause] angehängt). Portfolio-Zeilen und Alarme kommen unverändert zurück (samt Alarm-Zustand).
     */
    private suspend fun rollBack(previous: PreviousState, touched: Set<Step>, cause: Throwable) {
        suspend fun attempt(what: String, block: suspend () -> Unit) {
            runCatching { block() }.onFailure {
                Timber.w(it, "Wiederherstellen: %s nicht zurückgesetzt", what)
                if (it !== cause) cause.addSuppressed(it)
            }
        }
        if (Step.SETTINGS in touched) attempt("Einstellungen") { restoreSettings(previous.settings, trusted = true) }
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
    }

    // ---------------- Datei ----------------

    /** Gelesene Sicherungsdatei: lesbares JSON oder verschlüsselte Hülle. */
    private sealed interface BackupFile {
        class Plain(val json: String) : BackupFile
        class Encrypted(val envelope: BackupCrypto.Envelope) : BackupFile
    }

    private fun read(uri: Uri): BackupFile {
        // Höchstens 16 MB und flach verschachtelt ([BackupLimits]) — nie eine beliebig grosse Datei in den Speicher
        val text = context.contentResolver.openInputStream(uri)?.use { BackupLimits.readLimited(it).toString(Charsets.UTF_8) }
            ?: error("Datei kann nicht gelesen werden")
        BackupLimits.checkDepth(text)
        val root = JSONObject(text)
        // Verschlüsselt: eigenes «format» (cryptochecker-backup-enc), damit ältere App-Versionen
        // die Datei ablehnen statt sie als leere Sicherung zu übernehmen
        if (!BackupCrypto.isEnvelope(root.optString("format"), root.optString("enc"))) {
            require(root.optString("format") == FORMAT) { "Keine Crypto-Checker-Sicherung" }
            return BackupFile.Plain(text)
        }
        return BackupFile.Encrypted(
            BackupCrypto.envelope(
                version = root.optInt("v"),
                enc = root.optString("enc"),
                kdf = root.optString("kdf"),
                iterations = root.optInt("iter"),
                salt = root.optString("salt"),
                iv = root.optString("iv"),
                data = root.optString("data"),
            )
        )
    }

    /** «version» der Sicherung ([BackupVersion.check]): neuere → [BackupVersion.NewerVersionException]. */
    private fun checkVersion(root: JSONObject) {
        BackupVersion.check(root.opt("version").takeIf { it != JSONObject.NULL })
    }

    /** Klartext der Sicherung; verschlüsselt ohne Passwort gilt als falsches Passwort. */
    private fun plainText(file: BackupFile, password: String?): String = when (file) {
        is BackupFile.Plain -> file.json
        is BackupFile.Encrypted -> BackupCrypto.decrypt(
            file.envelope,
            password ?: throw BackupCrypto.WrongPasswordException()
        ).toString(Charsets.UTF_8).also { BackupLimits.checkDepth(it) }
    }

    // ---------------- Merkliste & Alarme ----------------

    private fun watchToJson(w: WatchEntity) = JSONObject()
        .put("id", w.id)
        .put("marketKey", w.marketKey)
        .put("marketName", w.marketName)
        .put("baseAsset", w.baseAsset)
        .put("quoteAsset", w.quoteAsset)
        .put("contractType", w.contractType.name)
        .put("pairId", w.pairId ?: JSONObject.NULL)
        .put("sortOrder", w.sortOrder)
        .put("notificationEnabled", w.notificationEnabled)
        .put("ttsEnabled", w.ttsEnabled)
        .put("favorite", w.favorite)
        .put("holdings", w.holdings ?: JSONObject.NULL)
        .put("group", w.groupName ?: JSONObject.NULL)
        .put("note", w.note ?: JSONObject.NULL)
        .put("disabled", w.disabled)

    private fun jsonToWatch(o: JSONObject) = WatchEntity(
        id = o.getLong("id"),
        marketKey = o.getString("marketKey"),
        marketName = o.getString("marketName"),
        baseAsset = o.getString("baseAsset"),
        quoteAsset = o.getString("quoteAsset"),
        contractType = FuturesContractType.entries.firstOrNull { it.name == o.optString("contractType") }
            ?: FuturesContractType.NONE,
        pairId = if (o.isNull("pairId")) null else o.optString("pairId"),
        sortOrder = o.optInt("sortOrder"),
        // Ohne Angabe: an wie in der Version der Sicherung (nicht der Standard neuer Paare)
        notificationEnabled = o.optBoolean("notificationEnabled", WatchRepository.LEGACY_NOTIFICATION_ENABLED),
        ttsEnabled = o.optBoolean("ttsEnabled", false),
        favorite = o.optBoolean("favorite", false),
        // Ältere Sicherungen kennen Bestand und Gruppe noch nicht.
        holdings = if (o.isNull("holdings")) null
        else o.optDouble("holdings").takeIf { !it.isNaN() && it > 0.0 },
        groupName = if (o.isNull("group")) null
        else o.optString("group").trim().takeIf { it.isNotEmpty() },
        // Ältere Sicherungen haben keine Notiz
        note = if (o.isNull("note")) null
        else o.optString("note").trim().take(NOTE_MAX).takeIf { it.isNotEmpty() },
        // Ältere Sicherungen kennen «deaktiviert» nicht: Paar aktiv
        disabled = o.optBoolean("disabled", false),
    )

    // Portfolio: Format in PortfolioJson (auch für die Kopie der Systemsicherung)

    private fun alarmToJson(a: AlarmEntity) = JSONObject()
        .put("id", a.id)
        .put("watchId", a.watchId)
        .put("condition", a.condition.name)
        .put("threshold", a.threshold)
        .put("enabled", a.enabled)
        .put("repeating", a.repeating)
        .put("sound", a.sound)
        .put("vibrate", a.vibrate)
        .put("speak", a.speak)
        .put("referencePrice", a.referencePrice ?: JSONObject.NULL)
        .put("windowHours", a.windowHours)
        .put("currency", a.currency ?: JSONObject.NULL)

    // ---------------- Einstellungen ----------------

    private suspend fun settingsToJson(): JSONObject {
        val s = settingsRepository.current()
        return JSONObject()
            // «Wie aktuell?»; dazu die früheren Schalter, abgeleitet, für ältere Versionen
            .put("updateMode", s.updateMode.name)
            .put("backgroundUpdates", s.updateMode.legacyFlags.backgroundUpdates)
            .put("backgroundIntervalMinutes", s.backgroundIntervalMinutes)
            .put("liveService", s.updateMode.legacyFlags.liveService)
            .put("liveIntervalSeconds", s.liveIntervalSeconds)
            .put("liveWebSocket", s.updateMode.legacyFlags.liveWebSocket)
            .put("priceNotifications", s.priceNotifications)
            .put("ongoingNotifications", s.ongoingNotifications)
            .put("notificationChangePercent", s.notificationChangePercent)
            .put("ttsEnabled", s.ttsEnabled)
            .put("ttsAlarmsOnly", s.ttsAlarmsOnly)
            .put("ttsSpeechRate", s.ttsSpeechRate.toDouble())
            .put("alarmCooldownMinutes", s.alarmCooldownMinutes)
            .put("includeRollingFutures", s.includeRollingFutures)
            .put("assetScope", s.assetScope.name)
            // Für ältere Versionen (kennen nur den Schalter): TradFi in der Auswahl ja/nein
            .put("includeTradFiFutures", s.assetScope.allowsTradFi)
            .put("includeAlphaTokens", s.includeAlphaTokens)
            .put("accentColor", s.accentColor.name)
            .put("darkMode", s.darkMode ?: JSONObject.NULL)
            .put("fearGreedBelow", s.fearGreedBelow)
            // Eigene Grenzen der Bitcoin-Kennzahlen (Art → gespeicherte Ganzzahl), wie iOS
            .put("indicatorAlerts", JSONObject().apply { s.indicatorAlerts.forEach { (kind, v) -> if (v > 0) put(kind.key, v) } })
            .put("fearGreedAbove", s.fearGreedAbove)
            .put("gasAlertEthTenths", s.gasAlertEthTenths)
            .put("gasAlertBtc", s.gasAlertBtc)
            // USDT-Depeg-Alarm (Build 46); ältere Versionen ignorieren die Felder
            .put("depegAlarm", s.depegAlarm)
            .put("depegThresholdTenths", s.depegThresholdTenths)
            .put("activityAlerts", s.activityAlerts)
            .put("activitySensitivity", s.activitySensitivity.name)
            .put("macroNotifications", s.macroNotifications)
            .put("portfolioEnabled", s.portfolioEnabled)
            .put("portfolioCurrency", s.portfolioCurrency)
            .put("showConverted", s.showConverted)
            .put("priceColorScheme", s.priceColorScheme.name)
            .put("watchlistSparkline", s.watchlistSparkline)
            .put("watchlistNames", s.watchlistNames)
            .put("showChangePeriod", s.showChangePeriod)
            .put("appIconBadge", s.appIconBadge)
            .put("watchlistActivityCard", s.watchlistActivityCard)
            .put("coinLogos", s.coinLogos)
            .put("widgetCoinLogos", s.widgetCoinLogos)
            .put("portfolioCoinLogos", s.portfolioCoinLogos)
            .put("changeBasis", s.changeBasis.name)
            .put("highContrast", s.highContrast)
            .put("priceColorsInverted", s.priceColorsInverted)
            .put("alarmSignal", s.alarmSignal.name)
            .put("quietHoursEnabled", s.quietHoursEnabled)
            .put("quietHoursStart", s.quietHoursStart)
            .put("quietHoursEnd", s.quietHoursEnd)
            .put("appLock", s.appLock)
            .put("hidePortfolioAmounts", s.hidePortfolioAmounts)
            .put("portfolioSystemBackup", s.portfolioSystemBackup)
            // Einstandsmethode «FIFO» | «AVERAGE» (wie iOS)
            .put("costMethod", s.costMethod.name)
            // Steuer-Vorlage «NEUTRAL» | «DE» | … (wie iOS)
            .put("taxTemplate", s.taxTemplate.name)
            // Spaltensortierung der Merkliste; null = eigene Reihenfolge
            .put(ColumnSort.BACKUP_FIELD, ColumnSort.backupValue(s.watchlistColumnSort) ?: JSONObject.NULL)
    }

    /**
     * Einstellungen übernehmen. [trusted] = verschlüsselte Sicherung oder eigener vorheriger Stand; sonst
     * schaltet die Datei die Portfolio-Sperre nicht aus.
     * @return true, wenn die Sicherung die Sperre ausschalten wollte, sie aber an blieb.
     */
    private suspend fun restoreSettings(o: JSONObject, trusted: Boolean): Boolean = with(settingsRepository) {
        // «Wie aktuell?»; ältere Sicherungen nur mit den früheren Schaltern (UpdateMode.fromBackup)
        com.cryptochecker.app.settings.UpdateMode.fromBackup(
            stored = o.string("updateMode"),
            legacyLiveService = o.bool("liveService"),
            legacyWebSocket = o.bool("liveWebSocket"),
            legacyBackground = o.bool("backgroundUpdates"),
        )?.let { setUpdateMode(it) }
        o.int("backgroundIntervalMinutes")?.let { setBackgroundInterval(it) }
        o.int("liveIntervalSeconds")?.let { setLiveInterval(it) }
        o.bool("priceNotifications")?.let { setPriceNotifications(it) }
        o.bool("ongoingNotifications")?.let { setOngoingNotifications(it) }
        o.double("notificationChangePercent")?.let { setNotificationChangePercent(it) }
        o.bool("ttsEnabled")?.let { setTtsEnabled(it) }
        o.bool("ttsAlarmsOnly")?.let { setTtsAlarmsOnly(it) }
        o.double("ttsSpeechRate")?.let { setTtsSpeechRate(it.toFloat()) }
        o.int("alarmCooldownMinutes")?.let { setAlarmCooldown(it) }
        o.bool("includeRollingFutures")?.let { setIncludeRollingFutures(it) }
        // «Token & TradFi»; ältere Sicherungen nur mit dem Schalter: an → beides, aus → nur Token
        com.cryptochecker.app.settings.AssetScope.fromBackup(
            if (o.isNull("assetScope")) null else o.optString("assetScope"),
            o.bool("includeTradFiFutures"),
        )?.let { setAssetScope(it) }
        // «inkl. Alpha-Tokens»; ältere Sicherungen ohne den Wert: bisheriger Standard (an), wie iOS
        setIncludeAlphaTokens(o.bool("includeAlphaTokens") ?: com.cryptochecker.app.settings.AppSettings.LEGACY_INCLUDE_ALPHA_TOKENS)
        if (o.has("accentColor")) setAccentColor(AccentColor.fromName(o.string("accentColor")))
        // Ungültiger Wert: «Wie das System» (wie iOS)
        if (o.has("darkMode")) setDarkMode(if (o.isNull("darkMode")) null else o.bool("darkMode"))
        // «zoneAlerts» älterer Sicherungen: Meldung «Marktphase gewechselt» gibt es nicht mehr, ignoriert
        o.int("fearGreedBelow")?.let { setFearGreedBelow(it) }
        // Ältere Sicherungen ohne das Feld lassen die Grenzen stehen; unbekannte Arten werden übergangen
        o.optJSONObject("indicatorAlerts")?.let { alerts ->
            com.cryptochecker.app.domain.market.IndicatorAlertKind.entries.forEach { kind ->
                setIndicatorAlert(kind, alerts.int(kind.key) ?: 0)
            }
        }
        o.int("fearGreedAbove")?.let { setFearGreedAbove(it) }
        o.int("gasAlertEthTenths")?.let { setGasAlertEth(it) }
        o.int("gasAlertBtc")?.let { setGasAlertBtc(it) }
        // Ältere Sicherungen ohne die Felder lassen die Einstellung stehen
        o.bool("depegAlarm")?.let { setDepegAlarm(it) }
        o.int("depegThresholdTenths")?.let { setDepegThreshold(it) }
        o.bool("activityAlerts")?.let { setActivityAlerts(it) }
        // Fehlt der Schlüssel (ältere Sicherung) oder ist er unbekannt: «Normal»
        setActivitySensitivity(
            com.cryptochecker.app.domain.activity.ActivitySensitivity.fromName(
                if (o.isNull("activitySensitivity")) null else o.optString("activitySensitivity")
            )
        )
        o.bool("macroNotifications")?.let { setMacroNotifications(it) }
        // Fehlt der Schlüssel (ältere Sicherung): Portfolio an (Standard), wie iOS
        // Sicherung ohne das Feld (vor dem Schalter): bisheriger Standard an
        setPortfolioEnabled(o.bool("portfolioEnabled") ?: com.cryptochecker.app.settings.AppSettings.LEGACY_PORTFOLIO_ENABLED)
        o.string("portfolioCurrency")?.let { setPortfolioCurrency(it) }
        // Ältere Sicherungen ohne den Wert: bisheriger Standard (aus), nicht der neue von Build 43
        setShowConverted(o.bool("showConverted") ?: com.cryptochecker.app.settings.AppSettings.LEGACY_SHOW_CONVERTED)
        if (o.has("priceColorScheme") && !o.isNull("priceColorScheme")) {
            setPriceColorScheme(com.cryptochecker.app.settings.PriceColorScheme.fromName(o.optString("priceColorScheme")))
        }
        // «pillTextColor» (frühere Einstellung «Schrift auf Pillen und Buttons») in älteren
        // Sicherungen wird bewusst übergangen — die Einstellung gibt es nicht mehr
        o.bool("watchlistSparkline")?.let { setWatchlistSparkline(it) }
        // Ältere Sicherungen ohne den Wert: bisheriger Standard (aus), wie iOS
        setWatchlistNames(o.bool("watchlistNames") ?: com.cryptochecker.app.settings.AppSettings.LEGACY_WATCHLIST_NAMES)
        o.bool("showChangePeriod")?.let { setShowChangePeriod(it) }
        o.bool("appIconBadge")?.let { setAppIconBadge(it) }
        o.bool("watchlistActivityCard")?.let { setWatchlistActivityCard(it) }
        o.bool("coinLogos")?.let { setCoinLogos(it) }
        o.bool("widgetCoinLogos")?.let { setWidgetCoinLogos(it) }
        o.bool("portfolioCoinLogos")?.let { setPortfolioCoinLogos(it) }
        // %-Basis: ältere Sicherungen ohne den Schlüssel lassen die Einstellung stehen; unbekannt → «Letzte 24 Std.»
        if (o.has("changeBasis")) {
            setChangeBasis(
                com.cryptochecker.app.domain.watch.ChangeBasis.fromName(
                    if (o.isNull("changeBasis")) null else o.optString("changeBasis")
                )
            )
        }
        // Ältere Sicherungen ohne den Schlüssel lassen die aktuelle Einstellung stehen.
        o.bool("highContrast")?.let { setHighContrast(it) }
        o.bool("priceColorsInverted")?.let { setPriceColorsInverted(it) }
        // Alarm-Signal: ältere Sicherungen ohne den Schlüssel lassen die Einstellung stehen;
        // unbekannter Wert (neuere Version) → «System»
        if (o.has("alarmSignal")) {
            setAlarmSignal(
                com.cryptochecker.app.domain.alarm.AlarmSignal.fromName(
                    if (o.isNull("alarmSignal")) null else o.optString("alarmSignal")
                )
            )
        }
        // Nachtruhe: nur übernehmen, was vorhanden und gültig ist (Minuten 0..1439)
        o.bool("quietHoursEnabled")?.let { setQuietHoursEnabled(it) }
        o.optMinute("quietHoursStart")?.let { setQuietHoursStart(it) }
        o.optMinute("quietHoursEnd")?.let { setQuietHoursEnd(it) }
        o.bool("hidePortfolioAmounts")?.let { setHidePortfolioAmounts(it) }
        // «Portfolio in Systemsicherung»: ältere Sicherungen ohne den Schlüssel lassen die Einstellung stehen
        o.bool("portfolioSystemBackup")?.let { setPortfolioSystemBackup(it) }
        // Einstandsmethode: ältere Sicherungen ohne den Schlüssel (oder unbekannter Wert) → FIFO (Standard)
        setCostMethod(com.cryptochecker.app.domain.portfolio.CostMethod.fromName(o.string("costMethod")))
        // Steuer-Vorlage: ältere Sicherungen ohne den Schlüssel lassen sie stehen; unbekannt → Neutral
        o.string("taxTemplate")?.let { setTaxTemplate(com.cryptochecker.app.domain.portfolio.TaxTemplate.fromName(it)) }
        // Spaltensortierung: ältere Sicherungen ohne das Feld lassen sie stehen; null/unbekannt = eigene Reihenfolge
        if (o.has(ColumnSort.BACKUP_FIELD)) setWatchlistColumnSort(ColumnSort.backupValue(o.string(ColumnSort.BACKUP_FIELD)))
        // Portfolio-Sperre nur, wenn dieses Gerät entsperren kann — sonst sperrte sie das Portfolio aus
        // Ohne Passwort (nicht [trusted]) nie ausschalten, solange sie an ist
        val lock = BackupRestorePolicy.appLock(
            wanted = o.bool("appLock"),
            current = current().appLock,
            encrypted = trusted,
            canAuthenticate = AppLockAuth.canAuthenticate(this@BackupManager.context),
        )
        lock.apply?.let { setAppLock(it) }
        lock.keptOn
    }

    // Tolerantes Lesen wie iOS (BackupManager.swift: bool/int/double/string): falscher Typ → null,
    // der Schlüssel wird dann übergangen statt das Wiederherstellen mittendrin abzubrechen.

    private fun JSONObject.bool(key: String): Boolean? = when (val v = opt(key)) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> when (v.lowercase()) { "true" -> true; "false" -> false; else -> null }
        else -> null
    }

    // Ausserhalb von Int gesättigt statt still umgebrochen (sonst würde z. B. 4294967311 zu 15); die Setter kürzen danach
    private fun JSONObject.int(key: String): Int? = when (val v = opt(key)) {
        is Number -> v.toDouble().takeIf { it.isFinite() }?.toLong()?.let(::saturatedInt)
        is String -> (v.toLongOrNull() ?: v.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong())?.let(::saturatedInt)
        else -> null
    }

    private fun saturatedInt(value: Long): Int = value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private fun JSONObject.double(key: String): Double? = when (val v = opt(key)) {
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is String -> v.toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    private fun JSONObject.string(key: String): String? = when (val v = opt(key)) {
        is String -> v
        is Number -> v.toString()
        else -> null
    }

    /** Minute des Tages (0..1439) oder null, wenn fehlend oder ungültig. */
    private fun JSONObject.optMinute(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key)
        if (value.isNaN() || value % 1.0 != 0.0) return null
        return value.toInt().takeIf { QuietHours.isValidMinute(it) }
    }

    private companion object {
        const val FORMAT = "cryptochecker-backup"
        /** Geschriebene Version = höchste lesbare ([BackupVersion.SUPPORTED]). */
        const val VERSION = BackupVersion.SUPPORTED
    }
}
