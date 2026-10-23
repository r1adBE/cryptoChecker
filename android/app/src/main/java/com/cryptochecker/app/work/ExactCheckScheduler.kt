package com.cryptochecker.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.cryptochecker.app.domain.refresh.BackgroundSchedule
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Pünktlich prüfen»: exakter Wecker ([AlarmManager.setExactAndAllowWhileIdle]) alle
 * 15 Minuten, der dieselbe Prüfung wie WorkManager auslöst ([ExactCheckReceiver]). Nach jedem Lauf
 * neu gestellt; nach Neustart/Update/neuer Berechtigung stellt [BootReceiver] ihn neu. WorkManager
 * bleibt als Netz darunter; doppelte Läufe verhindert [BackgroundSchedule.shouldSkip].
 * Berechtigung SCHEDULE_EXACT_ALARM (nicht USE_EXACT_ALARM): fehlt sie, kein Wecker.
 */
@Singleton
class ExactCheckScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val alarmManager: AlarmManager? get() = context.getSystemService()

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Darf die App exakte Wecker stellen? Vor Android 12 immer. */
    fun canScheduleExact(): Boolean = canScheduleExact(context)

    /**
     * Wecker stellen bzw. absagen. [hasWork]: gibt es Paare? Unbekannt (Einstellungen geändert) = true;
     * der Wecker prüft beim Auslösen selbst und sagt sich ab, wenn nichts zu tun ist.
     * [force]: auch einen gestellten Wecker neu stellen (Android hat ihn gelöscht).
     */
    fun sync(enabled: Boolean, hasWork: Boolean = true, force: Boolean = false) {
        val action = BackgroundWorkPolicy.exactAction(
            enabled = enabled,
            permitted = canScheduleExact(),
            hasWork = hasWork,
            // Nach «Beenden erzwingen» sind Wecker und PendingIntent weg, die Einstellung sagt aber
            // noch «gestellt»: dann als nicht gestellt behandeln (neu stellen)
            armedFor = if (pendingIntentExists(context)) prefs.getLong(KEY_ARMED_FOR, 0L) else 0L,
            now = System.currentTimeMillis(),
            force = force,
        )
        when (action) {
            BackgroundWorkPolicy.ExactAction.Cancel -> cancel()
            BackgroundWorkPolicy.ExactAction.Keep -> Unit
            is BackgroundWorkPolicy.ExactAction.Arm -> arm(action.at)
        }
    }

    /** «Pünktlich prüfen» schon einmal nach dem ersten Alarm angeboten ([ExactCheckOffer])? Nur lokal. */
    var offerShown: Boolean
        get() = prefs.getBoolean(KEY_OFFER_SHOWN, false)
        set(value) = prefs.edit { putBoolean(KEY_OFFER_SHOWN, value) }

    /** Nächsten Wecker in 15 Min. stellen (nach jedem Lauf). */
    fun rearm() = arm(BackgroundSchedule.nextExactAt(System.currentTimeMillis()))

    private fun arm(at: Long) {
        val manager = alarmManager ?: return
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent())
            prefs.edit { putLong(KEY_ARMED_FOR, at) }
        } catch (e: SecurityException) {
            // Berechtigung inzwischen entzogen: WorkManager allein
            Timber.w(e, "Exakter Wecker nicht erlaubt")
            prefs.edit { remove(KEY_ARMED_FOR) }
        }
    }

    fun cancel() {
        if (!prefs.contains(KEY_ARMED_FOR)) return
        runCatching {
            val pi = pendingIntent()
            alarmManager?.cancel(pi)
            // Auch den PendingIntent verwerfen: pendingIntentExists() spiegelt so den Wecker
            pi.cancel()
        }
        prefs.edit { remove(KEY_ARMED_FOR) }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        checkIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val PREFS = "exact_check"
        private const val KEY_ARMED_FOR = "armed_for"
        private const val KEY_OFFER_SHOWN = "offer_shown"
        private const val REQUEST_CODE = 4601

        private fun checkIntent(context: Context): Intent =
            Intent(context, ExactCheckReceiver::class.java).setAction(ExactCheckReceiver.ACTION_CHECK)

        /** Gibt es den PendingIntent des Weckers noch (fehlt nach «Beenden erzwingen»)? */
        private fun pendingIntentExists(context: Context): Boolean = runCatching {
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                checkIntent(context),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) != null
        }.getOrDefault(true)

        fun canScheduleExact(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val manager = context.getSystemService<AlarmManager>() ?: return false
            return runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
        }

        /** Ist gerade ein Wecker gestellt (für den Hinweis unter dem Alarm)? */
        fun isArmed(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_ARMED_FOR, 0L) > 0L &&
                canScheduleExact(context) && pendingIntentExists(context)

        /** Öffnet «Wecker und Erinnerungen» für diese App (Android 12+); sonst nichts. */
        fun openPermissionSettings(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData("package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure { Timber.w(it, "Einstellung «Wecker und Erinnerungen» nicht erreichbar") }
        }
    }
}
