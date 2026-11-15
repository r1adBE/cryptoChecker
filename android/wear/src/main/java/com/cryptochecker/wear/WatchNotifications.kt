package com.cryptochecker.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import android.annotation.SuppressLint
import android.util.Log
import org.json.JSONObject

/**
 * «Auf der Uhr zeigen» (Handy-Schalter, nur Android): Mitteilungen des Handys ([PATH], Gegenstück `WatchNotifyBridge`)
 * zeigt die Uhr-App selbst — still (Kanal ohne Ton und Vibration), Tipp öffnet die Uhr-App. Ohne Erlaubnis für
 * Mitteilungen merkt sie sich, beim nächsten Öffnen danach zu fragen ([wantsPermission], `WatchActivity`).
 */
object WatchNotifications {
    /** Nachricht vom Handy (gleich in `WatchNotifyBridge.PATH`). */
    const val PATH = "/cc/notify"
    private const val CHANNEL = "phone_notifications"
    private const val PREFS = "watch_notifications"
    private const val KEY_ASK = "ask_permission"
    private const val TAG = "WatchNotifications"

    /** Nachricht vom Handy auswerten: zeigen, entfernen oder («hello») um Erlaubnis bitten lassen. */
    fun handle(context: Context, data: ByteArray) {
        val json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull() ?: return
        val id = json.optInt("id", 0)
        when (json.optString("op")) {
            "show" -> show(context, id, json.optString("title"), json.optString("text"))
            "cancel" -> runCatching { NotificationManagerCompat.from(context).cancel(id) }
            "hello" -> if (!permitted(context)) prefs(context).edit { putBoolean(KEY_ASK, true) }
        }
    }

    /** Erlaubnis für Mitteilungen da? (vor Android 13 immer) */
    fun permitted(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    /** Beim Öffnen um die Erlaubnis bitten? (Handy-Schalter eingeschaltet, Erlaubnis fehlt) */
    fun wantsPermission(context: Context): Boolean =
        !permitted(context) && prefs(context).getBoolean(KEY_ASK, false)

    /** Gefragt (erlaubt oder nicht): nicht bei jedem Öffnen erneut. */
    fun asked(context: Context) = prefs(context).edit { putBoolean(KEY_ASK, false) }

    // Erlaubnis prüft permitted() direkt davor; eine dazwischen entzogene fängt der catch
    @SuppressLint("MissingPermission")
    private fun show(context: Context, id: Int, title: String, text: String) {
        if (!permitted(context)) {
            prefs(context).edit { putBoolean(KEY_ASK, true) }
            return
        }
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, WatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_watch_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Mitteilung auf der Uhr nicht erlaubt", e)
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.watch_notify_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { enableVibration(false); setSound(null, null) }
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
