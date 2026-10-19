package com.cryptochecker.app.util

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.cryptochecker.app.domain.alarm.AlarmReadiness
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.work.ExactCheckScheduler
import timber.log.Timber

/**
 * Liest den echten Zustand für «Damit Alarme ankommen» ([AlarmReadiness]) — nur lesen: kein Kanal wird angelegt, keine
 * Erlaubnis erfragt, keine Akku-Ausnahme verlangt (die App fragt bewusst nie per ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).
 * Dazu die Wege zu den passenden Systemseiten.
 */
object AlarmReadinessProbe {

    fun read(
        context: Context,
        settings: AppSettings,
        lastBackgroundCheck: Long?,
        hasAlarms: Boolean,
        now: Long = System.currentTimeMillis(),
        /** Android 13+ würde die Erlaubnis noch abfragen (`NotificationPrePrompt.notDetermined`, mit Activity-Kontext). */
        notificationsNotDetermined: Boolean = false,
    ): AlarmReadiness.Input {
        val manager = context.getSystemService<NotificationManager>()
        return AlarmReadiness.Input(
            platform = AlarmReadiness.Platform.ANDROID,
            notifications = AlarmReadiness.androidAccess(notificationsAllowed(context), notificationsNotDetermined),
            now = now,
            minuteOfDay = QuietHours.minuteOfDay(now),
            quietEnabled = settings.quietHoursEnabled,
            quietStart = settings.quietHoursStart,
            quietEnd = settings.quietHoursEnd,
            lastBackgroundCheck = lastBackgroundCheck,
            hasAlarms = hasAlarms,
            channel = channel(manager, alarmChannelId(settings)),
            exactEnabled = settings.exactChecks,
            exactPermitted = ExactCheckScheduler.canScheduleExact(context),
            batteryUnrestricted = BatteryOptimization.isIgnoring(context),
            backgroundRestricted = backgroundRestricted(context),
            systemDnd = systemDnd(manager),
            oemVendor = OemBatterySettings.vendor(),
        )
    }

    /** Erlaubnis (Android 13+) und App-Benachrichtigungen an — wie die Prüfung vor dem Melden. */
    fun notificationsAllowed(context: Context): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Kanal des gewählten «Alarm-Signals» (ohne ihn anzulegen). */
    fun alarmChannelId(settings: AppSettings): String =
        AlarmSignal.channelId(settings.alarmSignal, settings.alarmChannelVersion)

    private fun channel(manager: NotificationManager?, id: String): AlarmReadiness.Channel? {
        val channel = runCatching { manager?.getNotificationChannel(id) }.getOrNull() ?: return null
        return when {
            channel.importance == NotificationManager.IMPORTANCE_NONE -> AlarmReadiness.Channel.OFF
            channel.importance < NotificationManager.IMPORTANCE_HIGH -> AlarmReadiness.Channel.LOW
            else -> AlarmReadiness.Channel.HIGH
        }
    }

    private fun backgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val manager = context.getSystemService<ActivityManager>() ?: return false
        return runCatching { manager.isBackgroundRestricted }.getOrDefault(false)
    }

    private fun systemDnd(manager: NotificationManager?): Boolean? {
        val filter = runCatching { manager?.currentInterruptionFilter }.getOrNull() ?: return null
        return when (filter) {
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> null
            NotificationManager.INTERRUPTION_FILTER_ALL -> false
            else -> true
        }
    }

    // ---- Wege zu den Systemseiten (fehlt eine Seite beim Hersteller: App-Info als Rückfall)

    /** Einstellungen des Alarm-Kanals; sonst die Benachrichtigungen der App. */
    fun openChannelSettings(context: Context, channelId: String) {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        if (!start(context, intent)) openAppNotificationSettings(context)
    }

    fun openAppNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (!start(context, intent)) openAppDetails(context)
    }

    /** App-Info (dort u. a. «Akku» und «Hintergrundnutzung»). */
    fun openAppDetails(context: Context) {
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${context.packageName}")))
    }

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
            .onFailure { Timber.w(it, "Systemseite nicht erreichbar") }
            .getOrDefault(false)
}
