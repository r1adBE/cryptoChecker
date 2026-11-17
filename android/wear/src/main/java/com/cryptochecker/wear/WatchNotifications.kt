package com.cryptochecker.wear

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * «Auf der Uhr zeigen» (Handy-Schalter, nur Android): Mitteilungen des Handys ([PATH], Gegenstück `WatchNotifyBridge`)
 * zeigt die Uhr-App selbst — Alarme mit Vibration ([ALARM_CHANNEL], ohne Ton), Kurs- und Markt-Mitteilungen still
 * ([CHANNEL]; Inhaber 16.11.), Tipp öffnet die Uhr-App. Ohne Erlaubnis für Mitteilungen merkt sie sich, beim nächsten
 * Öffnen danach zu fragen ([wantsPermission], `WatchActivity`). Ob sie Mitteilungen zeigen darf, meldet sie dem Handy
 * ([publishState], [STATE_PATH]): Nur dann lässt das Handy seine eigene Mitteilung weg, sonst leitet das System weiter.
 */
object WatchNotifications {
    /** Nachricht vom Handy (gleich in `WatchNotifyBridge.PATH`). */
    const val PATH = "/cc/notify"
    private const val CHANNEL = "phone_notifications"
    private const val ALARM_CHANNEL = "phone_alarms"

    /** Meldung ans Handy (gleich in `WatchNotifyBridge.STATE_PATH` / `STATE_KEY_PERMITTED`). */
    const val STATE_PATH = "/cc/notify/state"
    private const val STATE_KEY_PERMITTED = "permitted"
    private const val TIMEOUT_S = 10L
    private const val PREFS = "watch_notifications"
    private const val KEY_ASK = "ask_permission"
    private const val TAG = "WatchNotifications"

    /** Nachricht vom Handy auswerten: zeigen, entfernen oder («hello») um Erlaubnis bitten lassen. */
    fun handle(context: Context, data: ByteArray) {
        val json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull() ?: return
        val id = json.optInt("id", 0)
        when (json.optString("op")) {
            "show" -> show(context, id, json.optString("title"), json.optString("text"), json.optBoolean("alarm"))
            "cancel" -> runCatching { NotificationManagerCompat.from(context).cancel(id) }
            "hello" -> {
                if (!permitted(context)) prefs(context).edit { putBoolean(KEY_ASK, true) }
                publishState(context)
            }
        }
    }

    /**
     * Dem Handy melden, ob die Uhr-App Mitteilungen zeigen darf (Datenelement, nur der Wahrheitswert: unverändert =
     * kein neuer Verkehr). Beim Öffnen der App, nach «hello» und wenn eine Mitteilung mangels Erlaubnis entfiel.
     */
    fun publishState(context: Context) {
        val app = context.applicationContext
        val allowed = permitted(app)
        WatchDataLayer.processScope.launch {
            WatchTaskAwait.catching {
                val request = PutDataMapRequest.create(STATE_PATH)
                    .apply { dataMap.putBoolean(STATE_KEY_PERMITTED, allowed) }
                    .asPutDataRequest().setUrgent()
                WatchTaskAwait.await(Wearable.getDataClient(app).putDataItem(request), TIMEOUT_S)
            }
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
    private fun show(context: Context, id: Int, title: String, text: String, alarm: Boolean) {
        if (!permitted(context)) {
            prefs(context).edit { putBoolean(KEY_ASK, true) }
            publishState(context)
            return
        }
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, WatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, if (alarm) ALARM_CHANNEL else CHANNEL)
            .setSmallIcon(R.drawable.ic_watch_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setSilent(!alarm)
            .setOnlyAlertOnce(!alarm)
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
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.watch_notify_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        enableVibration(false)
                        setSound(null, null)
                    }
            )
        }
        if (manager.getNotificationChannel(ALARM_CHANNEL) == null) {
            // Alarme tippen ans Handgelenk (Vibration), ohne Ton
            manager.createNotificationChannel(
                NotificationChannel(ALARM_CHANNEL, context.getString(R.string.watch_alarm_channel), NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        enableVibration(true)
                        setSound(null, null)
                    }
            )
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
