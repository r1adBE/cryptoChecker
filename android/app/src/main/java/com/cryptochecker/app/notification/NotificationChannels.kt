package com.cryptochecker.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.content.getSystemService
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.alarm.AlarmSignal

object NotificationChannels {

    /** Laufende Kursanzeige – lautlos. */
    const val PRICES = "prices"

    /**
     * Ausgelöste Alarme – mit Ton und Vibration. Ab einem eigenen Ton: «alarms_v<n>».
     * Weitere Alarm-Kanäle je «Alarm-Signal»: siehe [AlarmSignal.channelId].
     */
    const val ALARMS = AlarmSignal.SYSTEM_BASE

    /** Kanal-ID für eine Version des Alarmtons (0 = ursprünglicher Kanal mit Standardton). */
    fun alarmsId(version: Int): String = AlarmSignal.channelId(AlarmSignal.SYSTEM, version)

    /**
     * Legt den Alarm-Kanal für den gewählten Ton an (falls nötig) und räumt alte
     * Alarm-Kanäle weg. Android lässt den Ton eines bestehenden Kanals nicht mehr
     * ändern — deshalb je Ton eine neue Version.
     * @param soundUri null = Standard-Mitteilungston
     * @return die Kanal-ID, die für Alarme zu verwenden ist
     */
    fun ensureAlarmChannel(context: Context, version: Int, soundUri: String?): String =
        ensureAlarmChannel(context, AlarmSignal.SYSTEM, version, soundUri)

    /**
     * Wie oben, für ein «Alarm-Signal» ([AlarmSignal]): je Signal ein eigener Kanal
     * (gleiche Wichtigkeit «hoch», damit der Alarm oben erscheint — auch lautlos).
     * Weggeräumt werden nur alte Ton-Versionen DESSELBEN Signals; die Kanäle der
     * anderen Signale bleiben, falls man sie in den Systemeinstellungen angepasst hat.
     */
    fun ensureAlarmChannel(context: Context, mode: AlarmSignal, version: Int, soundUri: String?): String {
        val id = AlarmSignal.channelId(mode, version)
        val manager = context.getSystemService<NotificationManager>() ?: return id
        AlarmSignal.staleChannelIds(manager.notificationChannels.map { it.id }, mode, version)
            .forEach { manager.deleteNotificationChannel(it) }
        if (manager.getNotificationChannel(id) != null) return id
        val channel = NotificationChannel(
            id,
            context.getString(alarmChannelName(mode)),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_alarms_description)
            enableVibration(mode.vibrate)
            if (mode.sound) {
                val sound = soundUri?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                setSound(
                    sound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            } else {
                setSound(null, null)
            }
        }
        manager.createNotificationChannel(channel)
        return id
    }

    private fun alarmChannelName(mode: AlarmSignal): Int = when (mode) {
        AlarmSignal.SYSTEM -> R.string.channel_alarms
        AlarmSignal.SOUND_VIBRATE -> R.string.channel_alarms_sound_vibrate
        AlarmSignal.SOUND -> R.string.channel_alarms_sound
        AlarmSignal.VIBRATE -> R.string.channel_alarms_vibrate
        AlarmSignal.SILENT -> R.string.channel_alarms_silent
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
