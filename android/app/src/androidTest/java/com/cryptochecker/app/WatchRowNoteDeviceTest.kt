package com.cryptochecker.app

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.addWatch
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.marketdata.model.ExchangePair
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Notiz unter einem Paar der Merkliste: eine Zeile über die ganze Breite der Zeile
 * (nicht in der Paar-Spalte), lange bzw. mehrzeilige Notizen gekürzt. Echte Merkliste.
 * iOS: `CryptoCheckerUITests.testWatchRowNoteIsOneLineAcrossTheRow`.
 */
@HiltAndroidTest
class WatchRowNoteDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var watchRepository: WatchRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            settingsRepository.setAboutSeen(true)
            settingsRepository.setGestureHintSeen(true)
            settingsRepository.setAppLock(false)
            watchRepository.deleteAllWatches()
            val id = checkNotNull(watchRepository.addWatch(ExchangeInfo("Binance", "Binance"), ExchangePair("BTC", "USDT", "BTCUSDT")))
            watchRepository.setNote(id, "First line of a long note that keeps going well past the width of any phone screen\nsecond line")
        }
    }

    @After
    fun tearDown() {
        runBlocking { watchRepository.deleteAllWatches() }
    }

    @Test
    fun noteIsOneLineAcrossTheWholeRow() {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasTestTag(UiTestTags.WATCH_NOTE), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val note = compose.onAllNodes(hasTestTag(UiTestTags.WATCH_NOTE), useUnmergedTree = true).onFirst().getBoundsInRoot()
        val row = compose.onAllNodes(hasTestTag(UiTestTags.WATCH_ROW), useUnmergedTree = true).onFirst().getBoundsInRoot()
        val noteWidth = note.right - note.left
        val rowWidth = row.right - row.left
        assertTrue("Notiz nicht über die ganze Zeile: $noteWidth von $rowWidth", noteWidth > rowWidth * 0.8f)
        // Eine Zeile der Schrift bodySmall (16 sp Zeilenhöhe) plus 2 dp Abstand; zwei Zeilen wären über 32 sp
        val height = note.bottom - note.top
        val twoLines = with(compose.density) { (32.sp.toDp() + 2.dp) }
        assertTrue("Notiz bricht um: $height", height < twoLines)
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
