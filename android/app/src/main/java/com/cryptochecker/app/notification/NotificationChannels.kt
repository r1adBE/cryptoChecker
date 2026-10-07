package com.cryptochecker.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.content.getSystemService
import com.cryptochecker.app.R

object NotificationChannels {

    /** Laufende Kursanzeige – lautlos. */
    const val PRICES = "prices"

    /** Ausgelöste Alarme – mit Ton und Vibration. Ab einem eigenen Ton: «alarms_v<n>». */
    const val ALARMS = "alarms"

    /** Kanal-ID für eine Version des Alarmtons (0 = ursprünglicher Kanal mit Standardton). */
    fun alarmsId(version: Int): String = if (version <= 0) ALARMS else "${ALARMS}_v$version"

    /**
     * Legt den Alarm-Kanal für den gewählten Ton an (falls nötig) und räumt alte
     * Alarm-Kanäle weg. Android lässt den Ton eines bestehenden Kanals nicht mehr
     * ändern — deshalb je Ton eine neue Version.
     * @param soundUri null = Standard-Mitteilungston
     * @return die Kanal-ID, die für Alarme zu verwenden ist
     */
    fun ensureAlarmChannel(context: Context, version: Int, soundUri: String?): String {
        val id = alarmsId(version)
        val manager = context.getSystemService<NotificationManager>() ?: return id
        manager.notificationChannels
            .filter { (it.id == ALARMS || it.id.startsWith("${ALARMS}_v")) && it.id != id }
            .forEach { manager.deleteNotificationChannel(it.id) }
        if (manager.getNotificationChannel(id) != null) return id
        val sound = soundUri?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val channel = NotificationChannel(
            id,
            context.getString(R.string.channel_alarms),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_alarms_description)
            enableVibration(true)
            setSound(
                sound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        manager.createNotificationChannel(channel)
        return id
    }

    /**
     * Alarme während der Nachtruhe: lautlos, ohne Vibration (IMPORTANCE_LOW).
     * Gilt für Kursalarme, Volumen-Spike, Gas-Alarm und ungewöhnliche Aktivität.
     */
    const val ALARMS_QUIET = "alarms_quiet"

    /** Legt den lautlosen Nachtruhe-Kanal an (falls nötig) und gibt seine Id zurück. */
    fun ensureQuietAlarmChannel(context: Context): String {
        val manager = context.getSystemService<NotificationManager>() ?: return ALARMS_QUIET
        if (manager.getNotificationChannel(ALARMS_QUIET) != null) return ALARMS_QUIET
        manager.createNotificationChannel(quietAlarmChannel(context))
        return ALARMS_QUIET
    }

    private fun quietAlarmChannel(context: Context) = NotificationChannel(
        ALARMS_QUIET,
        context.getString(R.string.channel_alarms_quiet),
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = context.getString(R.string.channel_alarms_quiet_description)
        enableVibration(false)
        setSound(null, null)
    }

    /** Hinweis des Vordergrunddienstes. */
    const val SERVICE = "service"

    /** Wechsel der Bitcoin-Marktphase – normal, mit Ton. */
    const val MARKET = "market"

    fun createAll(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return

        val prices = NotificationChannel(
            PRICES,
            context.getString(R.string.channel_prices),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_prices_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        val service = NotificationChannel(
            SERVICE,
            context.getString(R.string.channel_service),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = context.getString(R.string.channel_service_description)
            setShowBadge(false)
            setSound(null, null)
        }

        val market = NotificationChannel(
            MARKET,
            context.getString(R.string.channel_market),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_market_description)
        }

        manager.createNotificationChannels(listOf(prices, service, market, quietAlarmChannel(context)))
        // Der Alarm-Kanal hängt vom gewählten Ton ab: siehe ensureAlarmChannel
    }
}
