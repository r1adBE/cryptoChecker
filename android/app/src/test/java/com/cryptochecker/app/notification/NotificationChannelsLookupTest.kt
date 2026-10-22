package com.cryptochecker.app.notification

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Alarm-Kanal (seit Build 102): zuerst nur den einen Kanal abfragen; alle Kanäle listen und alte Versionen wegräumen
 * erst beim Anlegen — sonst zwei Binder-Listungen je Alarm. Systemdienst nicht im Unit-Test: am Quelltext geprüft.
 */
class NotificationChannelsLookupTest {

    @Test
    fun bestehenderKanalOhneListung() {
        val path = "src/main/java/com/cryptochecker/app/notification/NotificationChannels.kt"
        val text = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
        val body = text.substringAfter("fun ensureAlarmChannel(context: Context, mode: AlarmSignal")
            .substringBefore("manager.createNotificationChannel(channel)")
        val lookup = body.indexOf("getNotificationChannel(id) != null) return id")
        val listing = body.indexOf("manager.notificationChannels")
        assertTrue(lookup >= 0 && listing > lookup)
    }
}
