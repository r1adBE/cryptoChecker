package com.cryptochecker.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.notification.showPortfolioAlarm
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.resources.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Portfolio-Alarm auf dem Gerät: die echte Mitteilung trägt nur den Titel und den
 * neutralen Satz — keine Zahl in irgendeinem Feld, auch nicht in der öffentlichen Fassung für den Sperrbildschirm —, und
 * sie ist privat. Text in allen Sprachen ohne Ziffer: `PortfolioAlarmLockScreenTest` (JVM). iOS:
 * `PortfolioAlarmLockScreenTests`.
 */
@HiltAndroidTest
class PortfolioAlarmNoticeDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @Inject lateinit var notifier: AppNotifier

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val id = AppNotifier.portfolioAlarmNotificationId(ALARM_ID)

    @Before
    fun setUp() = hiltRule.inject()

    @After
    fun tearDown() = manager.cancel(id)

    private fun texts(n: Notification): List<CharSequence?> = listOf(
        Notification.EXTRA_TITLE,
        Notification.EXTRA_TITLE_BIG,
        Notification.EXTRA_TEXT,
        Notification.EXTRA_BIG_TEXT,
        Notification.EXTRA_SUB_TEXT,
        Notification.EXTRA_INFO_TEXT,
        Notification.EXTRA_SUMMARY_TEXT,
    ).map { n.extras.getCharSequence(it) } + n.tickerText

    @Test
    fun onlyTitleAndNeutralSentenceAndPrivate() {
        assertTrue("nicht gemeldet", runBlocking { notifier.showPortfolioAlarm(ALARM_ID) })
        val posted = manager.activeNotifications.firstOrNull { it.id == id }?.notification
        assertNotNull("Mitteilung fehlt", posted)
        val title = context.getString(R.string.notification_portfolio_alarm_title)
        val body = context.getString(R.string.notification_portfolio_alarm_locked)
        assertEquals(title, posted!!.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(body, posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        val public = posted.publicVersion
        assertNotNull("keine öffentliche Fassung", public)
        for ((name, n) in listOf("Mitteilung" to posted, "öffentlich" to public!!)) {
            for (text in texts(n).filterNotNull()) {
                assertTrue("$name: «$text»", text.toString() == title || text.toString() == body)
                assertFalse("$name: Ziffer in «$text»", text.any { it.isDigit() })
            }
        }
        assertNull(posted.tickerText)
    }

    private companion object {
        const val ALARM_ID = 987_654L
    }
}
