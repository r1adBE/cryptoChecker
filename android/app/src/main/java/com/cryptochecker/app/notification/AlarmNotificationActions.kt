// Knöpfe und Bild der Paar-Alarm-Meldung (Teil von `AppNotifier`): «1 h später», «Anpassen» und das Coin-Logo als
// grosses Bild. Eigene Datei, damit `AppNotifierAlarms.kt` beim Aufbau der Meldung bleibt; Erweiterungsfunktionen.
package com.cryptochecker.app.notification

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.alarm.AlarmSnooze
import com.cryptochecker.app.domain.alarm.AlarmSnoozeStore
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.notification.AppNotifier.Companion.EDIT_REQUEST_BASE
import com.cryptochecker.app.notification.AppNotifier.Companion.SNOOZE_REQUEST_BASE
import com.cryptochecker.app.ui.navigation.AlarmEditTarget
import kotlinx.coroutines.withTimeoutOrNull

/** Längstens so lange wartet die Alarm-Meldung auf das Logo aus der Datei; danach ohne Bild. */
private const val LOGO_WAIT_MILLIS = 300L

/**
 * «1 h später» (nur wo die Pause wirkt: wiederholende Prozent- und Bewegungsalarme, [AlarmSnooze.offeredFor]) und
 * «Anpassen» (öffnet den Editor dieses Alarms). Zusammen mit «Was bewegt sich mit?» höchstens drei Knöpfe, wie
 * Android sie zeigt.
 */
internal fun AppNotifier.addAlarmActions(builder: NotificationCompat.Builder, watch: WatchEntity, alarm: AlarmEntity) {
    if (AlarmSnooze.offeredFor(alarm.condition, alarm.repeating)) {
        builder.addAction(0, context.getString(R.string.notification_alarm_action_snooze), snoozeIntent(alarm.id))
    }
    builder.addAction(
        0,
        context.getString(R.string.notification_alarm_action_edit),
        openTargetIntent(AlarmEditTarget.of(watch.id, alarm.id), (EDIT_REQUEST_BASE + alarm.id).toInt()),
    )
}

/**
 * Broadcast an den eigenen, nicht exportierten [AlarmActionReceiver] (expliziter Empfänger, darum IMMUTABLE):
 * vermerkt die Pause, ohne die App zu öffnen.
 */
private fun AppNotifier.snoozeIntent(alarmId: Long): PendingIntent {
    val intent = Intent(context, AlarmActionReceiver::class.java).apply {
        action = AlarmActionReceiver.ACTION_SNOOZE
        putExtra(AlarmActionReceiver.EXTRA_ALARM_ID, alarmId)
    }
    return PendingIntent.getBroadcast(
        context,
        (SNOOZE_REQUEST_BASE + alarmId).toInt(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/** Ende einer laufenden «1 h später»-Pause von [alarmId] (Melde-Schritt, RefreshEffects); null = keine. */
fun AppNotifier.alarmSnoozeUntil(alarmId: Long): Long? = AlarmSnoozeStore(context).snoozeUntil(alarmId)

/**
 * Coin-Logo für die Alarm-Meldung — nur, wenn es schon auf dem Gerät liegt ([com.cryptochecker.app.data.CoinLogoRepository.logo]
 * liest Speicher, Datei oder mitgeliefertes Icon, nie das Netz). Ohne «Coin-Logos», für DEX-Pools oder nach
 * [LOGO_WAIT_MILLIS]: null (Meldung ohne Bild).
 */
internal suspend fun AppNotifier.alarmLargeIcon(watch: WatchEntity): Bitmap? {
    if (!settingsRepository.cached.coinLogos || !CoinLogos.allowedFor(watch.marketKey)) return null
    return runCatchingCancellable {
        val repository = coinLogos.get()
        val key = repository.logoKey(watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name)
        withTimeoutOrNull(LOGO_WAIT_MILLIS) { repository.logo(key) }
    }.getOrNull()
}
