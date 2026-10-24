package com.cryptochecker.app.data

import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.domain.portfolio.AddressServer
import com.cryptochecker.app.domain.watch.ColumnSort
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.SettingsBackupTarget
import org.json.JSONObject

/**
 * Einstellungen in der Sicherung ([BackupManager]) und im Journal: schreiben ([settingsToJson]) und
 * tolerant übernehmen ([restoreSettings]). [settings]: in der App `SettingsRepository`, im Test eine Fälschung;
 * [canAuthenticate]: kann dieses Gerät die Portfolio-Sperre entsperren (`AppLockAuth.canAuthenticate`)?
 */
internal class BackupSettingsCodec(
    private val settings: SettingsBackupTarget,
    private val canAuthenticate: () -> Boolean,
) {

    suspend fun settingsToJson(): JSONObject {
        val s = settings.current()
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
            // USDT-Depeg-Alarm; ältere Versionen ignorieren die Felder
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
     * Einstellungen übernehmen. [trusted] = verschlüsselte Sicherung oder eigener vorheriger Stand; sonst
     * schaltet die Datei die Portfolio-Sperre und «Beträge verbergen» nicht aus, «Portfolio in Systemsicherung» nicht ein
     * und ändert den eigenen Bitcoin-Server nicht.
     * @return Hinweise für die Meldung ([SettingsRestoreNotes]).
     */
    suspend fun restoreSettings(o: JSONObject, trusted: Boolean): SettingsRestoreNotes = with(settings) {
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
        // Ältere Sicherungen ohne den Wert: bisheriger Standard (aus), nicht der neue
        setShowConverted(o.bool("showConverted") ?: com.cryptochecker.app.settings.AppSettings.LEGACY_SHOW_CONVERTED)
        if (o.has("priceColorScheme") && !o.isNull("priceColorScheme")) {
            setPriceColorScheme(com.cryptochecker.app.settings.PriceColorScheme.fromName(o.optString("priceColorScheme")))
        }
        // «pillTextColor» (frühere Einstellung «Schrift auf Pillen und Buttons») in älteren
        // Sicherungen wird bewusst übergangen — die Einstellung gibt es nicht mehr
        o.bool("watchlistSparkline")?.let { setWatchlistSparkline(it) }
        // Ältere Sicherungen ohne den Wert: bisheriger Standard (aus), wie iOS
        setWatchlistNames(o.bool("watchlistNames") ?: com.cryptochecker.app.settings.AppSettings.LEGACY_WATCHLIST_NAMES)
        // «showChangePeriod» (früherer Schalter «Zeitraum neben der Änderung») in älteren Sicherungen wird
        // übergangen — der Zeitraum steht immer da
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
        // Ohne Passwort (nicht [trusted]) nur in Richtung «mehr Schutz» ([BackupRestorePolicy])
        BackupRestorePolicy.hideAmounts(o.bool("hidePortfolioAmounts"), current().hidePortfolioAmounts, trusted)
            ?.let { setHidePortfolioAmounts(it) }
        // «Portfolio in Systemsicherung»: ältere Sicherungen ohne den Schlüssel lassen die Einstellung stehen
        BackupRestorePolicy.systemBackup(o.bool("portfolioSystemBackup"), current().portfolioSystemBackup, trusted)
            ?.let { setPortfolioSystemBackup(it) }
        // Einstandsmethode: ältere Sicherungen ohne den Schlüssel (oder unbekannter Wert) → FIFO (Standard)
        setCostMethod(com.cryptochecker.app.domain.portfolio.CostMethod.fromName(o.string("costMethod")))
        // Steuer-Vorlage: ältere Sicherungen ohne den Schlüssel lassen sie stehen; unbekannt → Neutral
        o.string("taxTemplate")?.let { setTaxTemplate(com.cryptochecker.app.domain.portfolio.TaxTemplate.fromName(it)) }
        // Spaltensortierung: ältere Sicherungen ohne das Feld lassen sie stehen; null/unbekannt = eigene Reihenfolge
        if (o.has(ColumnSort.BACKUP_FIELD)) setWatchlistColumnSort(ColumnSort.backupValue(o.string(ColumnSort.BACKUP_FIELD)))
        // Eigener Bitcoin-Server: fehlt/ungültig bleibt er; ohne Passwort nie gesetzt, ersetzt oder gelöscht
        val currentServer = addressServer()
        val server = AddressServer.restored(o.has(ADDRESS_SERVER), o.string(ADDRESS_SERVER), currentServer, trusted)
        if (server != currentServer) setAddressServer(server)
        val serverNote = BackupRestorePolicy.addressServerNote(
            o.has(ADDRESS_SERVER), o.string(ADDRESS_SERVER), currentServer, trusted,
        )
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

    private companion object {
        /** Schlüssel des eigenen Bitcoin-Servers in der Sicherung (wie iOS). */
        const val ADDRESS_SERVER = "addressServerBtc"
    }

    // Tolerantes Lesen wie iOS (BackupManager.swift: bool/int/double/string): falscher Typ → null,
    // der Schlüssel wird dann übergangen statt das Wiederherstellen mittendrin abzubrechen.

    private fun JSONObject.bool(key: String): Boolean? = when (val v = opt(key)) {
        null -> null
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> when (v.lowercase()) { "true" -> true; "false" -> false; else -> null }
        else -> null
    }

    // Ausserhalb von Int gesättigt statt still umgebrochen (sonst würde z. B. 4294967311 zu 15); die Setter kürzen danach
    private fun JSONObject.int(key: String): Int? = when (val v = opt(key)) {
        null -> null
        is Number -> v.toDouble().takeIf { it.isFinite() }?.toLong()?.let(::saturatedInt)
        is String -> (v.toLongOrNull() ?: v.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong())?.let(::saturatedInt)
        else -> null
    }

    private fun saturatedInt(value: Long): Int = value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private fun JSONObject.double(key: String): Double? = when (val v = opt(key)) {
        null -> null
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is String -> v.toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    private fun JSONObject.string(key: String): String? = when (val v = opt(key)) {
        null -> null
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
}
