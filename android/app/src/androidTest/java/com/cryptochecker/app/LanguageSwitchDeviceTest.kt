package com.cryptochecker.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.notification.NotificationChannels
import com.cryptochecker.app.setup.AppLanguages
import com.cryptochecker.resources.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Sprachwechsel in der App (Audit A-01 / B3-07, früher Quelltext-Wächter): bis Android 12 spricht der App-Kontext
 * (Widgets, Mitteilungen, Ansagen) sofort die neue Sprache; die Kanäle — auch schon angelegte Alarm-Kanäle — tragen
 * danach Namen in der neuen Sprache. Läuft auf API 26 (sofort) und 34 (Kanäle mit dem Kontext der neuen Sprache).
 */
@RunWith(AndroidJUnit4::class)
class LanguageSwitchDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: Context = instrumentation.targetContext.applicationContext
    private val manager: NotificationManager = checkNotNull(app.getSystemService())

    private fun inLocale(tag: String): Context =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) })

    private fun select(tag: String) = instrumentation.runOnMainSync { AppLanguages.select(app, tag) }

    @After
    fun restore() {
        select("")
        manager.deleteNotificationChannel(AlarmSignal.channelId(AlarmSignal.VIBRATE, 7))
    }

    @Test
    fun upToAndroid12TheAppContextSwitchesAtOnce() {
        assumeTrue("ab Android 13 übernimmt das System die Sprache je App", Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        val french = inLocale("fr").getString(R.string.channel_prices)
        assertNotEquals("Testannahme: Französisch unterscheidet sich", inLocale("en").getString(R.string.channel_prices), french)
        select("fr")
        assertEquals("App-Kontext (Widgets, Mitteilungen) sofort in der neuen Sprache", french, app.getString(R.string.channel_prices))
        assertEquals(french, manager.getNotificationChannel(NotificationChannels.PRICES).name.toString())
        select("en")
        assertEquals(inLocale("en").getString(R.string.channel_prices), app.getString(R.string.channel_prices))
    }

    @Test
    fun existingAlarmChannelsAreRenamed() {
        val id = AlarmSignal.channelId(AlarmSignal.VIBRATE, 7)
        // Alarm-Kanal, einst in Englisch angelegt
        manager.createNotificationChannel(
            NotificationChannel(id, inLocale("en").getString(R.string.channel_alarms_vibrate), NotificationManager.IMPORTANCE_HIGH),
        )
        val german = inLocale("de")
        NotificationChannels.createAll(german)
        assertEquals(german.getString(R.string.channel_alarms_vibrate), manager.getNotificationChannel(id).name.toString())
        assertEquals(german.getString(R.string.channel_alarms_description), manager.getNotificationChannel(id).description)
    }
}
