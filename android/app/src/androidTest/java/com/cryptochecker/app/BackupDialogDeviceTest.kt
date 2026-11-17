package com.cryptochecker.app

import android.net.Uri
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.addWatch
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.testing.DocumentPickerStub
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.lock.OverlayShield
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.inject.Inject

/**
 * Sichern: der Dialog übersteht das Neuerstellen des Bildschirms (Drehen), und eine
 * gescheiterte Sicherung lässt keine leere Datei mit dem Namen einer Sicherung liegen — eine gewählte bestehende Datei
 * bleibt aber unangetastet. Die Dateiauswahl liefert [DocumentPickerStub] (FileProvider der App, Datei schreibgeschützt,
 * damit das Schreiben scheitert).
 */
@HiltAndroidTest
class BackupDialogDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val picker = DocumentPickerStub()

    @get:Rule(order = 3)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var watchRepository: WatchRepository
    @Inject lateinit var portfolioRepository: PortfolioRepository

    private val files = mutableListOf<File>()

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            settingsRepository.setAboutSeen(true)
            settingsRepository.setGestureHintSeen(true)
            settingsRepository.setAppLock(false)
            watchRepository.deleteAllWatches()
            portfolioRepository.clearAll()
            // Etwas zu sichern (ohne Portfolio: kein Passwort nötig)
            watchRepository.addWatch(ExchangeInfo("Binance", "Binance"), ExchangePair("BTC", "USDT", "BTCUSDT"))
        }
    }

    @After
    fun tearDown() {
        files.forEach { it.setWritable(true); it.delete() }
    }

    /** Schreibgeschützte Datei im freigegebenen Ordner des FileProviders (cache/diagnostics) als `content://`-Adresse. */
    private fun providerFile(name: String, bytes: ByteArray): Pair<File, Uri> {
        val context = compose.activity
        val file = File(context.cacheDir, "diagnostics/$name")
        file.parentFile?.mkdirs()
        file.setWritable(true)
        file.writeBytes(bytes)
        assertTrue(file.setWritable(false))
        files += file
        return file to FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
    }

    private fun openExportDialog() {
        clickTag(UiTestTags.TAB_SETTINGS)
        clickTag(UiTestTags.SETTINGS_BACKUP)
        clickTag(UiTestTags.BACKUP_EXPORT)
        waitForTag(UiTestTags.BACKUP_EXPORT_CONFIRM)
    }

    /** Übersteht das Neuerstellen; solange er offen ist, blendet die App fremde Überlagerungen aus (ab Android 12). */
    @Test
    fun exportDialogSurvivesRecreation() {
        openExportDialog()
        assertTrue("Dialog ohne Schutz vor Überlagerungen", overlayHolders() > 0)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        waitForTag(UiTestTags.BACKUP_EXPORT_CONFIRM)
        assertTrue(overlayHolders() > 0)
    }

    private fun overlayHolders(): Int {
        var count = 0
        compose.runOnUiThread { count = OverlayShield.holders(compose.activity.window) }
        return count
    }

    @Test
    fun failedSaveRemovesTheNewEmptyFile() {
        val (file, uri) = providerFile("new-backup.ccb", ByteArray(0))
        picker.uri = uri
        openExportDialog()
        confirmExport()
        compose.waitUntil(TIMEOUT) { !file.exists() }
    }

    @Test
    fun failedSaveKeepsAChosenExistingFile() {
        val before = "older backup".toByteArray()
        val (file, uri) = providerFile("old-backup.ccb", before)
        picker.uri = uri
        openExportDialog()
        confirmExport()
        // Das Schreiben scheitert sofort (schreibgeschützt); danach darf die Datei nicht verschwinden
        Thread.sleep(SETTLE_MILLIS)
        compose.waitForIdle()
        assertTrue("bestehende Datei gelöscht", file.exists())
        assertArrayEquals(before, file.readBytes())
    }

    /**
     * Sichern bestätigen: mit Passwortfeldern (Schutz an) Passwort eingeben, sonst die Rückfrage «Ohne Passwort sichern?»
     * bestätigen. Danach muss die Dateiauswahl wirklich geöffnet worden sein — sonst prüfte der Test nichts.
     */
    private fun confirmExport() {
        val launches = picker.launches
        if (exists(UiTestTags.BACKUP_PASSWORD_FIELD)) {
            input(UiTestTags.BACKUP_PASSWORD_FIELD, PASSPHRASE)
            input(UiTestTags.BACKUP_PASSWORD_REPEAT, PASSPHRASE)
            clickTag(UiTestTags.BACKUP_EXPORT_CONFIRM)
        } else {
            clickTag(UiTestTags.BACKUP_EXPORT_CONFIRM)
            clickTag(UiTestTags.BACKUP_PLAIN_CONFIRM)
        }
        compose.waitUntil(TIMEOUT) { picker.launches == launches + 1 }
    }

    private fun input(tag: String, text: String) {
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().performTextInput(text)
        compose.waitForIdle()
    }

    private fun exists(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitForTag(tag: String) = compose.waitUntil(TIMEOUT) { exists(tag) }

    private fun clickTag(tag: String) {
        val enabled: SemanticsMatcher = hasTestTag(tag) and isEnabled()
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(enabled, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
        compose.waitForIdle()
    }

    private companion object {
        const val TIMEOUT = 15_000L
        const val SETTLE_MILLIS = 3_000L
        const val PASSPHRASE = "Kiwi-Harbor-4821!"
    }
}
