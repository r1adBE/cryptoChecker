package com.cryptochecker.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.NOTE_MAX
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository
import com.cryptochecker.app.data.portfolio.PortfolioDao
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.lock.AppLockAuth
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.marketdata.model.FuturesContractType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Was eine Wiederherstellung übernommen hat. */
data class RestoreResult(val watches: Int, val alarms: Int)

/**
 * Sichern und Wiederherstellen: Merkliste, Alarme, Favoriten, Portfolio und
 * Einstellungen als eine JSON-Datei, die der Nutzer selbst ablegt
 * (z. B. in Downloads oder Google Drive). Keine Kurse, kein Konto.
 */
@Singleton
class BackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val watchDao: WatchDao,
    private val favoritesRepository: FavoritesRepository,
    private val settingsRepository: SettingsRepository,
    private val activityRepository: ActivityRepository,
    private val portfolioDao: PortfolioDao,
    private val portfolioRepository: PortfolioRepository,
    private val portfolioAlarmRepository: PortfolioAlarmRepository,
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
            .put("portfolio", JSONArray().apply { portfolioDao.getAll().forEach { put(txToJson(it)) } })
            .put("portfolioAlarms", JSONArray().apply { portfolioAlarmRepository.getAll().forEach { put(portfolioAlarmToJson(it)) } })

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
     * Ersetzt Merkliste, Alarme, Favoriten und Einstellungen durch die Sicherung.
     * [password] nur für verschlüsselte Sicherungen; ältere, lesbare Sicherungen brauchen keines.
     */
    suspend fun restore(uri: Uri, password: String?): RestoreResult = withContext(Dispatchers.IO) {
        val root = JSONObject(plainText(read(uri), password))
        // Klartext muss die eigentliche Sicherung sein (eine verschachtelte Hülle hat ein anderes «format»)
        require(root.optString("format") == FORMAT) { "Keine Crypto-Checker-Sicherung" }

        val watches = root.optJSONArray("watches")?.let { a -> (0 until a.length()).map { jsonToWatch(a.getJSONObject(it)) } }.orEmpty()
        val alarms = root.optJSONArray("alarms")?.let { a -> (0 until a.length()).map { jsonToAlarm(a.getJSONObject(it)) } }.orEmpty()
        val watchIds = watches.map { it.id }.toSet()
        // Ältere Sicherungen haben noch kein Portfolio: dann bleibt das bestehende stehen.
        val portfolio = root.optJSONArray("portfolio")?.let { a ->
            (0 until a.length()).mapNotNull { jsonToTx(a.getJSONObject(it)) }
        }
        // Seit Runde 28; ältere Sicherungen ohne Portfolio-Alarme lassen die bestehenden stehen
        val portfolioAlarms = root.optJSONArray("portfolioAlarms")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { jsonToPortfolioAlarm(it) } }
        }

        // Alte ⚡-Ergebnisse gehören zu den alten Paaren (Ids können sich decken).
        activityRepository.retain(emptySet())

        database.withTransaction {
            watchDao.deleteAllWatches()          // Alarme fallen per Fremdschlüssel mit weg
            watches.forEach { watchDao.insertWatch(it) }
            alarms.filter { it.watchId in watchIds }.forEach { watchDao.insertAlarm(it) }
            if (portfolio != null) portfolioRepository.replaceAll(portfolio)
            if (portfolioAlarms != null) portfolioAlarmRepository.replaceAll(portfolioAlarms)
            // Bestand aus alten Sicherungen ins Portfolio übernehmen (nur Coins ohne Transaktion)
            portfolioRepository.importHoldings(onlyNewCoins = true)
        }

        root.optJSONObject("favorites")?.let { fav ->
            FavoriteKind.entries.forEach { kind ->
                fav.optJSONArray(kind.name)?.let { arr ->
                    favoritesRepository.setAll(kind, (0 until arr.length()).map { arr.getString(it) }.toSet())
                }
            }
        }
        root.optJSONObject("settings")?.let { restoreSettings(it) }

        RestoreResult(watches.size, alarms.count { it.watchId in watchIds })
    }

    // ---------------- Datei ----------------

    /** Gelesene Sicherungsdatei: lesbares JSON oder verschlüsselte Hülle. */
    private sealed interface BackupFile {
        class Plain(val json: String) : BackupFile
        class Encrypted(val envelope: BackupCrypto.Envelope) : BackupFile
    }

    private fun read(uri: Uri): BackupFile {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Datei kann nicht gelesen werden")
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

    /** Klartext der Sicherung; verschlüsselt ohne Passwort gilt als falsches Passwort. */
    private fun plainText(file: BackupFile, password: String?): String = when (file) {
        is BackupFile.Plain -> file.json
        is BackupFile.Encrypted -> BackupCrypto.decrypt(
            file.envelope,
            password ?: throw BackupCrypto.WrongPasswordException()
        ).toString(Charsets.UTF_8)
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
        notificationEnabled = o.optBoolean("notificationEnabled", true),
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
    )

    // ---------------- Portfolio ----------------

    private fun txToJson(t: PortfolioTxEntity) = JSONObject()
        .put("id", t.id)
        .put("coin", t.coin)
        .put("type", t.type.name)
        .put("amount", t.amount)
        .put("priceUsdt", t.priceUsdt ?: JSONObject.NULL)
        .put("time", t.time)
        .put("note", t.note ?: JSONObject.NULL)

    /** Unvollständige oder ungültige Einträge werden übersprungen. */
    private fun jsonToTx(o: JSONObject): PortfolioTxEntity? {
        val coin = PortfolioCalculator.normalizeCoin(o.optString("coin"))
        val type = PortfolioTxType.entries.firstOrNull { it.name == o.optString("type") } ?: return null
        val amount = o.optDouble("amount").takeIf { !it.isNaN() && !it.isInfinite() && it > 0.0 } ?: return null
        if (coin.isEmpty()) return null
        return PortfolioTxEntity(
            id = o.optLong("id", 0L).coerceAtLeast(0L),
            coin = coin,
            type = type,
            amount = amount,
            priceUsdt = if (o.isNull("priceUsdt")) null
            else o.optDouble("priceUsdt").takeIf { !it.isNaN() && !it.isInfinite() && it >= 0.0 },
            time = o.optLong("time", System.currentTimeMillis()),
            note = if (o.isNull("note")) null else o.optString("note").trim().takeIf { it.isNotEmpty() },
        )
    }

    /** Portfolio-Alarm (gleiches Format wie iOS); Zustand (gemeldet, zuletzt) wird nicht gesichert. */
    private fun portfolioAlarmToJson(a: PortfolioAlarmEntity) = JSONObject()
        .put("id", a.id)
        .put("kind", a.kind.name)
        .put("threshold", a.threshold)
        .put("currency", a.currency ?: JSONObject.NULL)
        .put("enabled", a.enabled)
        .put("repeating", a.repeating)

    /** Unbekannte Art (neuere Version) oder ungültiger Schwellwert: überspringen. */
    private fun jsonToPortfolioAlarm(o: JSONObject): PortfolioAlarmEntity? {
        val kind = PortfolioAlarmKind.fromName(o.optString("kind")) ?: return null
        val threshold = o.optDouble("threshold").takeIf { PortfolioAlarmLogic.isValidThreshold(kind, it) } ?: return null
        val currency = if (o.isNull("currency")) null else o.optString("currency").trim().uppercase()
            .takeIf { code -> code.length == 3 && code.all { it in 'A'..'Z' } }
        if (kind.isValue && currency == null) return null
        return PortfolioAlarmEntity(
            id = o.optLong("id", 0L).coerceAtLeast(0L),
            kind = kind,
            threshold = threshold,
            currency = currency.takeIf { kind.isValue },
            enabled = o.optBoolean("enabled", true),
            repeating = o.optBoolean("repeating", false),
        )
    }

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

    private fun jsonToAlarm(o: JSONObject) = AlarmEntity(
        id = o.getLong("id"),
        watchId = o.getLong("watchId"),
        condition = AlarmCondition.entries.firstOrNull { it.name == o.optString("condition") }
            ?: AlarmCondition.PRICE_ABOVE,
        threshold = o.getDouble("threshold"),
        enabled = o.optBoolean("enabled", true),
        repeating = o.optBoolean("repeating", false),
        sound = o.optBoolean("sound", true),
        vibrate = o.optBoolean("vibrate", true),
        speak = o.optBoolean("speak", false),
        referencePrice = if (o.isNull("referencePrice")) null else o.optDouble("referencePrice").takeUnless { it.isNaN() },
        windowHours = o.optInt("windowHours", 1),
        // Ältere Sicherungen kennen die Alarmwährung nicht (= Quote-Währung)
        currency = if (o.isNull("currency")) null
        else o.optString("currency").trim().uppercase().takeIf { c -> c.length == 3 && c.all { it in 'A'..'Z' } },
    )

    // ---------------- Einstellungen ----------------

    private suspend fun settingsToJson(): JSONObject {
        val s = settingsRepository.current()
        return JSONObject()
            .put("backgroundUpdates", s.backgroundUpdates)
            .put("backgroundIntervalMinutes", s.backgroundIntervalMinutes)
            .put("liveService", s.liveService)
            .put("liveIntervalSeconds", s.liveIntervalSeconds)
            .put("liveWebSocket", s.liveWebSocket)
            .put("priceNotifications", s.priceNotifications)
            .put("ongoingNotifications", s.ongoingNotifications)
            .put("notificationChangePercent", s.notificationChangePercent)
            .put("ttsEnabled", s.ttsEnabled)
            .put("ttsAlarmsOnly", s.ttsAlarmsOnly)
            .put("ttsSpeechRate", s.ttsSpeechRate.toDouble())
            .put("alarmCooldownMinutes", s.alarmCooldownMinutes)
            .put("includeRollingFutures", s.includeRollingFutures)
            .put("includeTradFiFutures", s.includeTradFiFutures)
            .put("accentColor", s.accentColor.name)
            .put("darkMode", s.darkMode ?: JSONObject.NULL)
            .put("zoneAlerts", s.zoneAlerts)
            .put("fearGreedBelow", s.fearGreedBelow)
            .put("fearGreedAbove", s.fearGreedAbove)
            .put("gasAlertEthTenths", s.gasAlertEthTenths)
            .put("gasAlertBtc", s.gasAlertBtc)
            .put("activityAlerts", s.activityAlerts)
            .put("activitySensitivity", s.activitySensitivity.name)
            .put("macroNotifications", s.macroNotifications)
            .put("portfolioEnabled", s.portfolioEnabled)
            .put("portfolioCurrency", s.portfolioCurrency)
            .put("showConverted", s.showConverted)
            .put("priceColorScheme", s.priceColorScheme.name)
            .put("watchlistSparkline", s.watchlistSparkline)
            .put("changeBasis", s.changeBasis.name)
            .put("highContrast", s.highContrast)
            .put("priceColorsInverted", s.priceColorsInverted)
            .put("alarmSignal", s.alarmSignal.name)
            .put("quietHoursEnabled", s.quietHoursEnabled)
            .put("quietHoursStart", s.quietHoursStart)
            .put("quietHoursEnd", s.quietHoursEnd)
            .put("appLock", s.appLock)
            .put("hidePortfolioAmounts", s.hidePortfolioAmounts)
    }

    private suspend fun restoreSettings(o: JSONObject) = with(settingsRepository) {
        if (o.has("backgroundUpdates")) setBackgroundUpdates(o.getBoolean("backgroundUpdates"))
        if (o.has("backgroundIntervalMinutes")) setBackgroundInterval(o.getInt("backgroundIntervalMinutes"))
        if (o.has("liveService")) setLiveService(o.getBoolean("liveService"))
        if (o.has("liveIntervalSeconds")) setLiveInterval(o.getInt("liveIntervalSeconds"))
        if (o.has("liveWebSocket")) setLiveWebSocket(o.optBoolean("liveWebSocket", true))
        if (o.has("priceNotifications")) setPriceNotifications(o.getBoolean("priceNotifications"))
        if (o.has("ongoingNotifications")) setOngoingNotifications(o.getBoolean("ongoingNotifications"))
        if (o.has("notificationChangePercent")) setNotificationChangePercent(o.getDouble("notificationChangePercent"))
        if (o.has("ttsEnabled")) setTtsEnabled(o.getBoolean("ttsEnabled"))
        if (o.has("ttsAlarmsOnly")) setTtsAlarmsOnly(o.getBoolean("ttsAlarmsOnly"))
        if (o.has("ttsSpeechRate")) setTtsSpeechRate(o.getDouble("ttsSpeechRate").toFloat())
        if (o.has("alarmCooldownMinutes")) setAlarmCooldown(o.getInt("alarmCooldownMinutes"))
        if (o.has("includeRollingFutures")) setIncludeRollingFutures(o.getBoolean("includeRollingFutures"))
        if (o.has("includeTradFiFutures")) setIncludeTradFiFutures(o.getBoolean("includeTradFiFutures"))
        if (o.has("accentColor")) setAccentColor(AccentColor.fromName(o.getString("accentColor")))
        if (o.has("darkMode")) setDarkMode(if (o.isNull("darkMode")) null else o.getBoolean("darkMode"))
        if (o.has("zoneAlerts")) setZoneAlerts(o.getBoolean("zoneAlerts"))
        if (o.has("fearGreedBelow")) setFearGreedBelow(o.getInt("fearGreedBelow"))
        if (o.has("fearGreedAbove")) setFearGreedAbove(o.getInt("fearGreedAbove"))
        if (o.has("gasAlertEthTenths")) setGasAlertEth(o.getInt("gasAlertEthTenths"))
        if (o.has("gasAlertBtc")) setGasAlertBtc(o.getInt("gasAlertBtc"))
        if (o.has("activityAlerts")) setActivityAlerts(o.getBoolean("activityAlerts"))
        // Fehlt der Schlüssel (ältere Sicherung) oder ist er unbekannt: «Normal»
        setActivitySensitivity(
            com.cryptochecker.app.domain.activity.ActivitySensitivity.fromName(
                if (o.isNull("activitySensitivity")) null else o.optString("activitySensitivity")
            )
        )
        if (o.has("macroNotifications")) setMacroNotifications(o.optBoolean("macroNotifications", false))
        if (o.has("portfolioEnabled")) setPortfolioEnabled(o.getBoolean("portfolioEnabled"))
        if (o.has("portfolioCurrency") && !o.isNull("portfolioCurrency")) setPortfolioCurrency(o.getString("portfolioCurrency"))
        if (o.has("showConverted")) setShowConverted(o.optBoolean("showConverted", false))
        if (o.has("priceColorScheme") && !o.isNull("priceColorScheme")) {
            setPriceColorScheme(com.cryptochecker.app.settings.PriceColorScheme.fromName(o.optString("priceColorScheme")))
        }
        if (o.has("watchlistSparkline")) setWatchlistSparkline(o.optBoolean("watchlistSparkline", true))
        // %-Basis: ältere Sicherungen ohne den Schlüssel lassen die Einstellung stehen; unbekannt → «Letzte 24 Std.»
        if (o.has("changeBasis")) {
            setChangeBasis(
                com.cryptochecker.app.domain.watch.ChangeBasis.fromName(
                    if (o.isNull("changeBasis")) null else o.optString("changeBasis")
                )
            )
        }
        // Ältere Sicherungen ohne den Schlüssel lassen die aktuelle Einstellung stehen.
        if (o.has("highContrast")) setHighContrast(o.optBoolean("highContrast", false))
        if (o.has("priceColorsInverted")) setPriceColorsInverted(o.optBoolean("priceColorsInverted", false))
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
        if (o.has("quietHoursEnabled")) setQuietHoursEnabled(o.optBoolean("quietHoursEnabled", false))
        o.optMinute("quietHoursStart")?.let { setQuietHoursStart(it) }
        o.optMinute("quietHoursEnd")?.let { setQuietHoursEnd(it) }
        if (o.has("hidePortfolioAmounts")) setHidePortfolioAmounts(o.optBoolean("hidePortfolioAmounts", false))
        // Portfolio-Sperre nur, wenn dieses Gerät entsperren kann — sonst sperrte sie das Portfolio aus
        if (o.has("appLock")) {
            val wanted = o.optBoolean("appLock", false)
            if (!wanted || AppLockAuth.canAuthenticate(this@BackupManager.context)) setAppLock(wanted)
        }
        Unit
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
        const val VERSION = 1
    }
}
