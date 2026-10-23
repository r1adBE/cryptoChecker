package com.cryptochecker.app.data

import com.cryptochecker.app.domain.activity.ActivitySensitivity
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.domain.market.IndicatorAlertKind
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.AssetScope
import com.cryptochecker.app.settings.PriceColorScheme
import com.cryptochecker.app.settings.SettingsBackupTarget
import com.cryptochecker.app.settings.UpdateMode
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Einstellungen in der Sicherung ([BackupSettingsCodec]): alle Werte hin und zurück —
 * gemeinsame Fixture `testdata/parity/backup_settings.json` (iOS `BackupSettingsParityTests`) — und ohne Passwort
 * (`trusted` = false) nur in Richtung «mehr Schutz».
 */
class BackupSettingsCodecTest {

    /** Einstellungen im Speicher statt DataStore; die Setter speichern ohne die Grenzen von `SettingsRepository`. */
    private class FakeSettings(var s: AppSettings = AppSettings()) : SettingsBackupTarget {
        override suspend fun current() = s
        override suspend fun setUpdateMode(mode: UpdateMode) { s = s.copy(updateMode = mode) }
        override suspend fun setBackgroundInterval(minutes: Int) { s = s.copy(backgroundIntervalMinutes = minutes) }
        override suspend fun setLiveInterval(seconds: Int) { s = s.copy(liveIntervalSeconds = seconds) }
        override suspend fun setPriceNotifications(enabled: Boolean) { s = s.copy(priceNotifications = enabled) }
        override suspend fun setOngoingNotifications(enabled: Boolean) { s = s.copy(ongoingNotifications = enabled) }
        override suspend fun setNotificationChangePercent(percent: Double) { s = s.copy(notificationChangePercent = percent) }
        override suspend fun setTtsEnabled(enabled: Boolean) { s = s.copy(ttsEnabled = enabled) }
        override suspend fun setTtsAlarmsOnly(enabled: Boolean) { s = s.copy(ttsAlarmsOnly = enabled) }
        override suspend fun setTtsSpeechRate(rate: Float) { s = s.copy(ttsSpeechRate = rate) }
        override suspend fun setAlarmCooldown(minutes: Int) { s = s.copy(alarmCooldownMinutes = minutes) }
        override suspend fun setIncludeRollingFutures(enabled: Boolean) { s = s.copy(includeRollingFutures = enabled) }
        override suspend fun setAssetScope(scope: AssetScope) { s = s.copy(assetScope = scope) }
        override suspend fun setIncludeAlphaTokens(enabled: Boolean) { s = s.copy(includeAlphaTokens = enabled) }
        override suspend fun setAccentColor(accent: AccentColor) { s = s.copy(accentColor = accent) }
        override suspend fun setDarkMode(dark: Boolean?) { s = s.copy(darkMode = dark) }
        override suspend fun setFearGreedBelow(value: Int) { s = s.copy(fearGreedBelow = value) }
        override suspend fun setFearGreedAbove(value: Int) { s = s.copy(fearGreedAbove = value) }
        override suspend fun setIndicatorAlert(kind: IndicatorAlertKind, value: Int) {
            s = s.copy(indicatorAlerts = if (value > 0) s.indicatorAlerts + (kind to value) else s.indicatorAlerts - kind)
        }
        override suspend fun setGasAlertEth(tenths: Int) { s = s.copy(gasAlertEthTenths = tenths) }
        override suspend fun setGasAlertBtc(satPerVb: Int) { s = s.copy(gasAlertBtc = satPerVb) }
        override suspend fun setDepegAlarm(enabled: Boolean) { s = s.copy(depegAlarm = enabled) }
        override suspend fun setDepegThreshold(tenths: Int) { s = s.copy(depegThresholdTenths = tenths) }
        override suspend fun setActivityAlerts(enabled: Boolean) { s = s.copy(activityAlerts = enabled) }
        override suspend fun setActivitySensitivity(sensitivity: ActivitySensitivity) { s = s.copy(activitySensitivity = sensitivity) }
        override suspend fun setMacroNotifications(enabled: Boolean) { s = s.copy(macroNotifications = enabled) }
        override suspend fun setPortfolioEnabled(enabled: Boolean) { s = s.copy(portfolioEnabled = enabled) }
        override suspend fun setPortfolioCurrency(currency: String) { s = s.copy(portfolioCurrency = currency) }
        override suspend fun setShowConverted(show: Boolean) { s = s.copy(showConverted = show) }
        override suspend fun setPriceColorScheme(scheme: PriceColorScheme) { s = s.copy(priceColorScheme = scheme) }
        override suspend fun setWatchlistSparkline(show: Boolean) { s = s.copy(watchlistSparkline = show) }
        override suspend fun setWatchlistNames(show: Boolean) { s = s.copy(watchlistNames = show) }
        override suspend fun setShowChangePeriod(show: Boolean) { s = s.copy(showChangePeriod = show) }
        override suspend fun setAppIconBadge(show: Boolean) { s = s.copy(appIconBadge = show) }
        override suspend fun setWatchlistActivityCard(show: Boolean) { s = s.copy(watchlistActivityCard = show) }
        override suspend fun setCoinLogos(show: Boolean) { s = s.copy(coinLogos = show) }
        override suspend fun setWidgetCoinLogos(show: Boolean) { s = s.copy(widgetCoinLogos = show) }
        override suspend fun setPortfolioCoinLogos(show: Boolean) { s = s.copy(portfolioCoinLogos = show) }
        override suspend fun setChangeBasis(basis: ChangeBasis) { s = s.copy(changeBasis = basis) }
        override suspend fun setHighContrast(enabled: Boolean) { s = s.copy(highContrast = enabled) }
        override suspend fun setPriceColorsInverted(inverted: Boolean) { s = s.copy(priceColorsInverted = inverted) }
        override suspend fun setAlarmSignal(signal: AlarmSignal) { s = s.copy(alarmSignal = signal) }
        override suspend fun setQuietHoursEnabled(enabled: Boolean) { s = s.copy(quietHoursEnabled = enabled) }
        override suspend fun setQuietHoursStart(minute: Int) { s = s.copy(quietHoursStart = minute) }
        override suspend fun setQuietHoursEnd(minute: Int) { s = s.copy(quietHoursEnd = minute) }
        override suspend fun setHidePortfolioAmounts(hidden: Boolean) { s = s.copy(hidePortfolioAmounts = hidden) }
        override suspend fun setPortfolioSystemBackup(allowed: Boolean) { s = s.copy(portfolioSystemBackup = allowed) }
        override suspend fun setCostMethod(method: CostMethod) { s = s.copy(costMethod = method) }
        override suspend fun setTaxTemplate(template: TaxTemplate) { s = s.copy(taxTemplate = template) }
        override suspend fun setWatchlistColumnSort(value: String?) { s = s.copy(watchlistColumnSort = value) }
        override suspend fun setAppLock(enabled: Boolean) { s = s.copy(appLock = enabled) }
        /** Eigener Bitcoin-Server (ausserhalb von AppSettings). */
        var server: String? = null
        override suspend fun addressServer() = server
        override suspend fun setAddressServer(url: String?) { server = url }
    }

    private val fixture = JSONObject(File(ParityJson.dir(), "backup_settings.json").readText(Charsets.UTF_8))
    private val fixtureSettings: JSONObject = fixture.getJSONObject("settings")

    private fun codec(settings: FakeSettings, canAuthenticate: Boolean = true) = BackupSettingsCodec(settings) { canAuthenticate }

    /** Gleicher JSON-Wert: Zahlen nach Wert (30 = 30.0), Objekte Schlüssel für Schlüssel. */
    private fun sameValue(expected: Any?, actual: Any?): Boolean = when {
        expected is Number && actual is Number -> expected.toDouble() == actual.toDouble()
        expected is JSONObject && actual is JSONObject ->
            keys(expected) == keys(actual) && keys(expected).all { sameValue(expected.opt(it), actual.opt(it)) }
        expected is JSONArray && actual is JSONArray ->
            expected.length() == actual.length() && (0 until expected.length()).all { sameValue(expected.opt(it), actual.opt(it)) }
        else -> expected == actual
    }

    private fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    @Test
    fun alleWerteDerFixtureKommenUnveraendertZurueck() = runTest {
        val settings = FakeSettings()
        codec(settings).restoreSettings(fixtureSettings, trusted = true)
        val written = codec(settings).settingsToJson()
        for (key in keys(fixtureSettings)) {
            assertTrue("$key: ${fixtureSettings.opt(key)} ≠ ${written.opt(key)}", sameValue(fixtureSettings.opt(key), written.opt(key)))
        }
    }

    @Test
    fun fixtureWeichtUeberallVomStandardAb() = runTest {
        // Sonst prüfte der Hin- und Rückweg für diesen Schlüssel nichts
        val defaults = codec(FakeSettings()).settingsToJson()
        for (key in keys(fixtureSettings)) {
            assertFalse("$key steht auf dem Standard", sameValue(fixtureSettings.opt(key), defaults.opt(key)))
        }
    }

    @Test
    fun ganzeSicherungHinUndZurueck() = runTest {
        // Alle Werte nicht auf dem Standard → settingsToJson → leere Einstellungen → restoreSettings → gleich
        val source = FakeSettings().also { codec(it).restoreSettings(fixtureSettings, trusted = true) }
        val written = codec(source).settingsToJson()
        val target = FakeSettings()
        assertFalse(codec(target).restoreSettings(written, trusted = true))
        val again = codec(target).settingsToJson()
        assertEquals(keys(written), keys(again))
        for (key in keys(written)) assertTrue(key, sameValue(written.opt(key), again.opt(key)))
        assertEquals(source.s.copy(), target.s.copy())
    }

    @Test
    fun ohnePasswortNieWenigerSchutz() = runTest {
        val protectedNow = AppSettings(appLock = true, hidePortfolioAmounts = true, portfolioSystemBackup = false)
        val settings = FakeSettings(protectedNow)
        val lessProtection = JSONObject()
            .put("appLock", false)
            .put("hidePortfolioAmounts", false)
            .put("portfolioSystemBackup", true)
        val keptOn = codec(settings).restoreSettings(lessProtection, trusted = false)
        assertTrue(keptOn)
        assertTrue(settings.s.appLock)
        assertTrue(settings.s.hidePortfolioAmounts)
        assertFalse(settings.s.portfolioSystemBackup)
        // Mit Passwort (echt): übernommen
        assertFalse(codec(settings).restoreSettings(lessProtection, trusted = true))
        assertFalse(settings.s.appLock)
        assertFalse(settings.s.hidePortfolioAmounts)
        assertTrue(settings.s.portfolioSystemBackup)
    }

    @Test
    fun ohnePasswortMehrSchutzErlaubt() = runTest {
        val settings = FakeSettings(AppSettings(appLock = false, hidePortfolioAmounts = false, portfolioSystemBackup = true))
        val moreProtection = JSONObject()
            .put("appLock", true)
            .put("hidePortfolioAmounts", true)
            .put("portfolioSystemBackup", false)
        assertFalse(codec(settings).restoreSettings(moreProtection, trusted = false))
        assertTrue(settings.s.appLock)
        assertTrue(settings.s.hidePortfolioAmounts)
        assertFalse(settings.s.portfolioSystemBackup)
    }

    /** Eigener Bitcoin-Server: ohne Passwort nie zurück auf öffentliche Knoten, ungültige Werte bleiben aussen vor. */
    @Test
    fun eigenerServerAusDerSicherung() = runTest {
        val settings = FakeSettings().apply { server = "https://a.example/api" }
        codec(settings).restoreSettings(JSONObject().put("addressServerBtc", JSONObject.NULL), trusted = false)
        assertEquals("https://a.example/api", settings.server)
        codec(settings).restoreSettings(JSONObject().put("addressServerBtc", "http://b.example"), trusted = true)
        assertEquals("https://a.example/api", settings.server)
        codec(settings).restoreSettings(JSONObject().put("addressServerBtc", "https://B.example/api/"), trusted = false)
        assertEquals("https://b.example/api", settings.server)
        codec(settings).restoreSettings(JSONObject(), trusted = true)
        assertEquals("https://b.example/api", settings.server)
        codec(settings).restoreSettings(JSONObject().put("addressServerBtc", JSONObject.NULL), trusted = true)
        assertEquals(null, settings.server)
    }

    @Test
    fun sperreNurMitEntsperrbaremGeraet() = runTest {
        val settings = FakeSettings()
        codec(settings, canAuthenticate = false).restoreSettings(JSONObject().put("appLock", true), trusted = true)
        assertFalse(settings.s.appLock)
    }
}
