package com.cryptochecker.app.data

import com.cryptochecker.app.data.BackupFields.Type
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.domain.portfolio.AddressServer
import com.cryptochecker.app.domain.watch.ColumnSort
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.SettingsBackupTarget
import org.json.JSONObject

/**
 * Einstellungen in der Sicherung ([BackupManager]) und im Journal: schreiben ([settingsToJson]), prüfen
 * ([checkSettings]) und übernehmen ([restoreSettings]). [settings]: in der App `SettingsRepository`, im Test eine Fälschung;
 * [canAuthenticate]: kann dieses Gerät die Portfolio-Sperre entsperren (`AppLockAuth.canAuthenticate`)?
 */
class BackupSettingsCodec(
    private val settings: SettingsBackupTarget,
    private val canAuthenticate: () -> Boolean,
) {

    suspend fun settingsToJson(): JSONObject {
        val s = settings.current()
        return JSONObject()
            // «Wie aktuell?»
            .put("updateMode", s.updateMode.name)
            .put("backgroundIntervalMinutes", s.backgroundIntervalMinutes)
            .put("priceNotifications", s.priceNotifications)
            .put("ongoingNotifications", s.ongoingNotifications)
            .put("notificationChangePercent", s.notificationChangePercent)
            .put("ttsEnabled", s.ttsEnabled)
            .put("ttsAlarmsOnly", s.ttsAlarmsOnly)
            .put("ttsSpeechRate", s.ttsSpeechRate.toDouble())
            .put("alarmCooldownMinutes", s.alarmCooldownMinutes)
            .put("includeRollingFutures", s.includeRollingFutures)
            .put("assetScope", s.assetScope.name)
            .put("includeAlphaTokens", s.includeAlphaTokens)
            .put("accentColor", s.accentColor.name)
            .put("darkMode", s.darkMode ?: JSONObject.NULL)
            .put("fearGreedBelow", s.fearGreedBelow)
            // Eigene Grenzen der Bitcoin-Kennzahlen (Art → gespeicherte Ganzzahl), wie iOS
            .put("indicatorAlerts", JSONObject().apply { s.indicatorAlerts.forEach { (kind, v) -> if (v > 0) put(kind.key, v) } })
            .put("fearGreedAbove", s.fearGreedAbove)
            .put("gasAlertEthTenths", s.gasAlertEthTenths)
            .put("gasAlertBtc", s.gasAlertBtc)
            // USDT-Depeg-Alarm
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
            // Eigener Bitcoin-Server für Adressabfragen; null = öffentliche Knoten (wie iOS)
            .put(ADDRESS_SERVER, settings.addressServer() ?: JSONObject.NULL)
    }

    /**
     * Einstellungen übernehmen. [o] muss jedes Feld von [settingsToJson] mit seinem Typ enthalten ([checkSettings]),
     * sonst [IllegalArgumentException]. [trusted] = verschlüsselte Sicherung oder eigener vorheriger Stand; sonst
     * schaltet die Datei die Portfolio-Sperre und «Beträge verbergen» nicht aus, «Portfolio in Systemsicherung» nicht ein
     * und ändert den eigenen Bitcoin-Server nicht.
     * @return Hinweise für die Meldung ([SettingsRestoreNotes]).
     */
    suspend fun restoreSettings(o: JSONObject, trusted: Boolean): SettingsRestoreNotes = with(settings) {
        checkSettings(o)
        // «Wie aktuell?»; unbekannt: Einstellung bleibt
        com.cryptochecker.app.settings.UpdateMode.fromNameOrNull(o.text("updateMode"))?.let { setUpdateMode(it) }
        setBackgroundInterval(o.int("backgroundIntervalMinutes"))
        setPriceNotifications(o.bool("priceNotifications"))
        setOngoingNotifications(o.bool("ongoingNotifications"))
        setNotificationChangePercent(o.double("notificationChangePercent"))
        setTtsEnabled(o.bool("ttsEnabled"))
        setTtsAlarmsOnly(o.bool("ttsAlarmsOnly"))
        setTtsSpeechRate(o.double("ttsSpeechRate").toFloat())
        setAlarmCooldown(o.int("alarmCooldownMinutes"))
        setIncludeRollingFutures(o.bool("includeRollingFutures"))
        // «Token & TradFi»; unbekannt: Einstellung bleibt
        com.cryptochecker.app.settings.AssetScope.fromNameOrNull(o.text("assetScope"))?.let { setAssetScope(it) }
        setIncludeAlphaTokens(o.bool("includeAlphaTokens"))
        setAccentColor(AccentColor.fromName(o.text("accentColor")))
        // null = «Wie das System»
        setDarkMode(if (o.isNull("darkMode")) null else o.bool("darkMode"))
        setFearGreedBelow(o.int("fearGreedBelow"))
        // Nur eingeschaltete Grenzen stehen in der Datei; fehlende Arten = aus, unbekannte werden übergangen
        val alerts = o.getJSONObject("indicatorAlerts")
        com.cryptochecker.app.domain.market.IndicatorAlertKind.entries.forEach { kind ->
            setIndicatorAlert(kind, if (alerts.has(kind.key)) alerts.int(kind.key) else 0)
        }
        setFearGreedAbove(o.int("fearGreedAbove"))
        setGasAlertEth(o.int("gasAlertEthTenths"))
        setGasAlertBtc(o.int("gasAlertBtc"))
        setDepegAlarm(o.bool("depegAlarm"))
        setDepegThreshold(o.int("depegThresholdTenths"))
        setActivityAlerts(o.bool("activityAlerts"))
        // Unbekannt: «Normal»
        setActivitySensitivity(com.cryptochecker.app.domain.activity.ActivitySensitivity.fromName(o.text("activitySensitivity")))
        setMacroNotifications(o.bool("macroNotifications"))
        setPortfolioEnabled(o.bool("portfolioEnabled"))
        setPortfolioCurrency(o.text("portfolioCurrency"))
        setShowConverted(o.bool("showConverted"))
        setPriceColorScheme(com.cryptochecker.app.settings.PriceColorScheme.fromName(o.text("priceColorScheme")))
        setWatchlistSparkline(o.bool("watchlistSparkline"))
        setWatchlistNames(o.bool("watchlistNames"))
        setAppIconBadge(o.bool("appIconBadge"))
        setWatchlistActivityCard(o.bool("watchlistActivityCard"))
        setCoinLogos(o.bool("coinLogos"))
        setWidgetCoinLogos(o.bool("widgetCoinLogos"))
        setPortfolioCoinLogos(o.bool("portfolioCoinLogos"))
        // %-Basis: unbekannt → «Letzte 24 Std.»
        setChangeBasis(com.cryptochecker.app.domain.watch.ChangeBasis.fromName(o.text("changeBasis")))
        setHighContrast(o.bool("highContrast"))
        setPriceColorsInverted(o.bool("priceColorsInverted"))
        // Alarm-Signal: unbekannter Wert (neuere Version) → «System»
        setAlarmSignal(com.cryptochecker.app.domain.alarm.AlarmSignal.fromName(o.text("alarmSignal")))
        // Nachtruhe: Zeiten nur übernehmen, wenn gültig (Minuten 0..1439)
        setQuietHoursEnabled(o.bool("quietHoursEnabled"))
        o.int("quietHoursStart").takeIf { QuietHours.isValidMinute(it) }?.let { setQuietHoursStart(it) }
        o.int("quietHoursEnd").takeIf { QuietHours.isValidMinute(it) }?.let { setQuietHoursEnd(it) }
        // Ohne Passwort (nicht [trusted]) nur in Richtung «mehr Schutz» ([BackupRestorePolicy])
        BackupRestorePolicy.hideAmounts(o.bool("hidePortfolioAmounts"), current().hidePortfolioAmounts, trusted)
            ?.let { setHidePortfolioAmounts(it) }
        BackupRestorePolicy.systemBackup(o.bool("portfolioSystemBackup"), current().portfolioSystemBackup, trusted)
            ?.let { setPortfolioSystemBackup(it) }
        // Einstandsmethode: unbekannter Wert → FIFO (Standard)
        setCostMethod(com.cryptochecker.app.domain.portfolio.CostMethod.fromName(o.text("costMethod")))
        // Steuer-Vorlage: unbekannt → Neutral
        setTaxTemplate(com.cryptochecker.app.domain.portfolio.TaxTemplate.fromName(o.text("taxTemplate")))
        // Spaltensortierung: null/unbekannt = eigene Reihenfolge
        setWatchlistColumnSort(ColumnSort.backupValue(o.textOrNull(ColumnSort.BACKUP_FIELD)))
        // Eigener Bitcoin-Server: ungültig bleibt er; ohne Passwort nie gesetzt, ersetzt oder gelöscht
        val currentServer = addressServer()
        val wantedServer = o.textOrNull(ADDRESS_SERVER)
        val server = AddressServer.restored(wantedServer, currentServer, trusted)
        if (server != currentServer) setAddressServer(server)
        val serverNote = BackupRestorePolicy.addressServerNote(wantedServer, currentServer, trusted)
        // Portfolio-Sperre nur, wenn dieses Gerät entsperren kann — sonst sperrte sie das Portfolio aus
        // Ohne Passwort (nicht [trusted]) nie ausschalten, solange sie an ist
        val lock = BackupRestorePolicy.appLock(
            wanted = o.bool("appLock"),
            current = current().appLock,
            encrypted = trusted,
            canAuthenticate = canAuthenticate(),
        )
        lock.apply?.let { setAppLock(it) }
        SettingsRestoreNotes(appLockKept = lock.keptOn, addressServer = serverNote)
    }

    companion object {
        /** Schlüssel des eigenen Bitcoin-Servers in der Sicherung (wie iOS). */
        const val ADDRESS_SERVER = "addressServerBtc"

        /** Jedes Feld, das [settingsToJson] schreibt, mit seinem Typ (wie iOS `BackupManager.settingsFields`). */
        val FIELDS: Map<String, Type> = linkedMapOf(
            "updateMode" to Type.STRING, "backgroundIntervalMinutes" to Type.INT,
            "priceNotifications" to Type.BOOL, "ongoingNotifications" to Type.BOOL,
            "notificationChangePercent" to Type.DOUBLE, "ttsEnabled" to Type.BOOL,
            "ttsAlarmsOnly" to Type.BOOL, "ttsSpeechRate" to Type.DOUBLE,
            "alarmCooldownMinutes" to Type.INT, "includeRollingFutures" to Type.BOOL,
            "assetScope" to Type.STRING, "includeAlphaTokens" to Type.BOOL,
            "accentColor" to Type.STRING, "darkMode" to Type.BOOL_OR_NULL,
            "fearGreedBelow" to Type.INT, "indicatorAlerts" to Type.INT_MAP,
            "fearGreedAbove" to Type.INT, "gasAlertEthTenths" to Type.INT, "gasAlertBtc" to Type.INT,
            "depegAlarm" to Type.BOOL, "depegThresholdTenths" to Type.INT,
            "activityAlerts" to Type.BOOL, "activitySensitivity" to Type.STRING,
            "macroNotifications" to Type.BOOL, "portfolioEnabled" to Type.BOOL,
            "portfolioCurrency" to Type.STRING, "showConverted" to Type.BOOL,
            "priceColorScheme" to Type.STRING, "watchlistSparkline" to Type.BOOL,
            "watchlistNames" to Type.BOOL, "appIconBadge" to Type.BOOL,
            "watchlistActivityCard" to Type.BOOL, "coinLogos" to Type.BOOL,
            "widgetCoinLogos" to Type.BOOL, "portfolioCoinLogos" to Type.BOOL,
            "changeBasis" to Type.STRING, "highContrast" to Type.BOOL,
            "priceColorsInverted" to Type.BOOL, "alarmSignal" to Type.STRING,
            "quietHoursEnabled" to Type.BOOL, "quietHoursStart" to Type.INT, "quietHoursEnd" to Type.INT,
            "appLock" to Type.BOOL, "hidePortfolioAmounts" to Type.BOOL,
            "portfolioSystemBackup" to Type.BOOL, "costMethod" to Type.STRING,
            "taxTemplate" to Type.STRING, ColumnSort.BACKUP_FIELD to Type.STRING_OR_NULL,
            ADDRESS_SERVER to Type.STRING_OR_NULL,
        )

        /**
         * Jedes Feld aus [FIELDS] vorhanden und vom richtigen Typ ([BackupFields.check]); sonst
         * [IllegalArgumentException] — die Sicherung ist unlesbar. Unbekannte Felder werden übergangen.
         */
        fun checkSettings(o: JSONObject) = BackupFields.check(o, FIELDS, "Einstellung")
    }

    // Lesen nach [checkSettings]: Feld vorhanden und vom richtigen Typ

    private fun JSONObject.bool(key: String): Boolean = get(key) as Boolean

    // Ausserhalb von Int gesättigt statt still umgebrochen (sonst würde z. B. 4294967311 zu 15); die Setter kürzen danach
    private fun JSONObject.int(key: String): Int =
        (get(key) as Number).toDouble().toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private fun JSONObject.double(key: String): Double = (get(key) as Number).toDouble()

    private fun JSONObject.text(key: String): String = get(key) as String

    private fun JSONObject.textOrNull(key: String): String? = if (isNull(key)) null else text(key)
}
