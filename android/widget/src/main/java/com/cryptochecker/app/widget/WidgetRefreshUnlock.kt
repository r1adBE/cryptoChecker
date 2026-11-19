package com.cryptochecker.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.cryptochecker.app.domain.refresh.RefreshDebounce
import com.cryptochecker.app.domain.refresh.WidgetRefreshState
import com.cryptochecker.app.util.ExactAlarms
import timber.log.Timber

/**
 * Ende der 15-s-Sperre im Listen-, Puls- und Portfolio-Widget: Den Countdown zählt der Launcher selbst herunter (Chronometer),
 * neu gezeichnet wird, wenn die Sperre endet — dann kommt das Symbol zurück. Ein Chronometer bleibt nicht bei
 * 0 stehen, sondern zählt ins Minus weiter («−01:05»); das Neuzeichnen muss also sicher kommen:
 *
 * - **Warten im Empfänger** ([hold]): Ein Broadcast an den Provider ([Target]) wartet mit goAsync bis zum Ende
 *   und zeichnet dann neu. Solange ein Empfänger läuft, friert Android den Prozess nicht ein (sonst lief ein
 *   `delay` im Prozess erst nach Minuten weiter). Je Empfang höchstens [HOP_MILLIS] (unter der Grenze von
 *   goAsync, [com.cryptochecker.app.util.ReceiverAsync.TIMEOUT_MILLIS]); bleibt mehr, reicht er an den nächsten
 *   Empfang weiter ([holdStep]).
 * - **Wecker** ([schedule]) als Absicherung, falls der Prozess beendet wird: weckend und auch im Ruhemodus,
 *   pünktlich (exakt) mit «Wecker und Erinnerungen», sonst ungefähr.
 *
 * Kommt ein Neuzeichnen später (etwa nach dem Einschalten des Bildschirms), zeigt es sofort das Symbol: Der
 * Countdown erscheint nur, solange die Sperre noch läuft ([WidgetRefreshState.remainingMillis]).
 */
object WidgetRefreshUnlock {
    /**
     * Empfänger der Sperre: das Listen-Widget ([LIST]) bzw. Puls- und Portfolio-Widget ([WidgetRefreshHeader.Kind]),
     * je mit eigenem Wecker ([requestCode]).
     */
    data class Target(val provider: Class<*>, val holdAction: String, val unlockedAction: String, val requestCode: Int)

    val LIST = Target(
        PriceWidgetProvider::class.java,
        PriceWidgetProvider.ACTION_REFRESH_HOLD,
        PriceWidgetProvider.ACTION_REFRESH_UNLOCKED,
        -7_014,
    )

    /** Wartezeit je Empfang; mit dem Zeichnen danach sicher unter der Grenze von goAsync (9 s). */
    const val HOP_MILLIS = 6_000L

    /**
     * Nächster Schritt eines Empfangs: [waitMillis] warten, dann neu zeichnen ([redraw]) oder an den nächsten
     * Empfang weiterreichen. Unplausibles Ende (weiter weg als die Sperre selbst, Uhr verstellt): gleich neu
     * zeichnen — das Zeichnen entscheidet selbst, ob noch ein Countdown gilt.
     */
    data class HoldStep(val waitMillis: Long, val redraw: Boolean)

    fun holdStep(blockedUntil: Long, now: Long): HoldStep {
        val rest = blockedUntil - now + WidgetRefreshState.UNLOCK_SLACK_MILLIS
        return when {
            rest <= 0 || rest > RefreshDebounce.WINDOW_MILLIS + WidgetRefreshState.UNLOCK_SLACK_MILLIS ->
                HoldStep(0, redraw = true)
            rest <= HOP_MILLIS -> HoldStep(rest, redraw = true)
            else -> HoldStep(HOP_MILLIS, redraw = false)
        }
    }

    /** Empfang an den Provider von [target] ([Target.holdAction]), der bis [blockedUntil] wartet. */
    fun hold(context: Context, blockedUntil: Long, target: Target = LIST) {
        val intent = Intent(context, target.provider)
            .setAction(target.holdAction)
            .putExtra(PriceWidgetProvider.EXTRA_BLOCKED_UNTIL, blockedUntil)
        send(context, intent)
    }

    /** Empfang an den eigenen Provider schicken (ausserhalb von Coroutinen; Fehler nur protokolliert). */
    fun send(context: Context, intent: Intent) {
        runCatching { context.sendBroadcast(intent) }
            .onFailure { Timber.w(it, "Warten auf das Ende im Widget nicht gestartet") }
    }

    /**
     * Empfang von [hold] ausführen: bis zum Ende warten, dann [redraw] — oder an den nächsten Empfang weiterreichen.
     */
    suspend fun onHold(context: Context, blockedUntil: Long, target: Target, redraw: suspend () -> Unit) {
        val step = holdStep(blockedUntil, System.currentTimeMillis())
        kotlinx.coroutines.delay(step.waitMillis)
        if (step.redraw) redraw() else hold(context, blockedUntil, target)
    }

    fun schedule(context: Context, blockedUntil: Long, now: Long = System.currentTimeMillis(), target: Target = LIST) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, target.provider).setAction(target.unlockedAction)
        val pending = PendingIntent.getBroadcast(
            context,
            target.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = SystemClock.elapsedRealtime() + (blockedUntil - now).coerceAtLeast(0) +
            WidgetRefreshState.UNLOCK_SLACK_MILLIS
        val type = AlarmManager.ELAPSED_REALTIME_WAKEUP
        runCatching {
            if (!ExactAlarms.canSchedule(context)) {
                alarmManager.setAndAllowWhileIdle(type, at, pending)
                return@runCatching
            }
            try {
                alarmManager.setExactAndAllowWhileIdle(type, at, pending)
            } catch (e: SecurityException) {
                // Berechtigung eben entzogen: ungefähr
                alarmManager.setAndAllowWhileIdle(type, at, pending)
            }
        }.onFailure { Timber.w(it, "Wecker für das Ende der Widget-Sperre nicht gestellt") }
    }
}
