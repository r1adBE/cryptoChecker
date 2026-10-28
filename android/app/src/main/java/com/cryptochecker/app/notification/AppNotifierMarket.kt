// Markt-Mitteilungen (Teil von `AppNotifier`): ungewöhnliche Aktivität, Fear & Greed, eigene Grenzen der
// Bitcoin-Kennzahlen und Netzwerkgebühr bzw. Wirtschaftstermine — alle über den Markt-Kanal (in der
// Nachtruhe lautlos). Eigene Datei, weil diese Meldungen dieselben Regeln teilen;
// Erweiterungsfunktionen, der Zustand bleibt in der Klasse.
package com.cryptochecker.app.notification

import androidx.core.app.NotificationCompat
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.notification.AppNotifier.Companion.FNG_NOTIFICATION_ID
import com.cryptochecker.app.notification.AppNotifier.Companion.INDICATOR_NOTIFICATION_BASE_ID
import com.cryptochecker.app.notification.AppNotifier.Companion.TARGET_REQUEST_BASE
import com.cryptochecker.app.notification.AppNotifier.Companion.activityNotificationId

// ---------------- Ungewöhnliche Aktivität ----------------

/**
 * «SOL/USDT · Binance» mit dem wichtigsten Grund als Text; eine Meldung je Paar.
 * @return true, wenn die Meldung ans System ging (ActivityMonitor merkt sie sonst nicht als gemeldet).
 */
fun AppNotifier.showActivity(watch: WatchEntity, signal: ActivitySignal): Boolean {
    val text = ActivityTexts.signal(context, signal)
    val quiet = isQuietNow()
    val notification = NotificationCompat.Builder(context, marketOrQuietChannel(quiet))
        .setSmallIcon(R.drawable.ic_stat_activity)
        .setColor(settingsRepository.cached.accentColor.seed)
        .setContentTitle(context.getString(R.string.notification_activity_title, watch.displayName, watch.exchangeName))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .appBadge()
        .setSilent(quiet)
        .setContentIntent(openAppIntent(watch.id))
        .lockScreenPrivacy(marketOrQuietChannel(quiet), R.drawable.ic_stat_activity, context.getString(R.string.app_name))
        .build()
    return notifyChecked(activityNotificationId(watch.id), notification)
}

// ---------------- Fear & Greed ----------------

/**
 * Fear & Greed hat die eingestellte Grenze unter- bzw. überschritten. In der Nachtruhe
 * lautlos über den stillen Kanal — wie Aktivität, Gas und Wirtschaftstermine.
 * @return true, wenn die Meldung abgesetzt wurde (ZoneCheckWorker merkt den Stand erst dann).
 */
fun AppNotifier.showFearGreed(value: Int, below: Int? = null, above: Int? = null): Boolean {
    val text = when {
        below != null -> context.getString(R.string.notification_fng_below, below)
        else -> context.getString(R.string.notification_fng_above, above ?: 0)
    }
    val quiet = isQuietNow()
    val notification = NotificationCompat.Builder(context, marketOrQuietChannel(quiet))
        .setSmallIcon(R.drawable.ic_stat_fear_greed)
        .setColor(settingsRepository.cached.accentColor.seed)
        .setContentTitle(context.getString(R.string.notification_fng_title, value))
        .setContentText(text)
        .setAutoCancel(true)
        .appBadge()
        .setSilent(quiet)
        .setContentIntent(openAppIntent(null))
        .lockScreenPrivacy(marketOrQuietChannel(quiet), R.drawable.ic_stat_fear_greed, context.getString(R.string.app_name))
        .build()
    return notifyChecked(FNG_NOTIFICATION_ID, notification)
}

/**
 * Eigene Grenze einer Bitcoin-Kennzahl erreicht ([com.cryptochecker.app.domain.exchange.IndicatorAlertKind]): Titel
 * «Mayer Multiple: 2.43×», Text «Über deiner Grenze 2.40× – Messwert, keine Empfehlung.» Eine Meldung je Art.
 */
fun AppNotifier.showIndicatorAlert(kind: com.cryptochecker.app.domain.market.IndicatorAlertKind, name: String, value: String, threshold: String): Boolean {
    val quiet = isQuietNow()
    val notification = NotificationCompat.Builder(context, marketOrQuietChannel(quiet))
        .setSmallIcon(R.drawable.ic_stat_logo)
        .setColor(settingsRepository.cached.accentColor.seed)
        .setContentTitle(context.getString(R.string.notification_indicator_title, name, value))
        .setContentText(
            context.getString(if (kind.above) R.string.notification_indicator_above else R.string.notification_indicator_below, threshold)
        )
        .setAutoCancel(true)
        .appBadge()
        .setSilent(quiet)
        .setContentIntent(openAppIntent(null))
        .lockScreenPrivacy(marketOrQuietChannel(quiet), R.drawable.ic_stat_logo, context.getString(R.string.app_name))
        .build()
    return notifyChecked(INDICATOR_NOTIFICATION_BASE_ID + kind.ordinal, notification)
}

/**
 * Käme eine Marktmeldung (Fear & Greed) jetzt an? Erlaubnis (Android 13+),
 * App-Benachrichtigungen an und der Kanal, über den sie gerade käme (Markt bzw. in der
 * Nachtruhe der lautlose), nicht stummgeschaltet — wie [canShowAlarm]. Ohne: ZoneCheckWorker
 * behält den alten Stand und meldet den Wechsel, sobald die Meldung wieder ankommt.
 */
fun AppNotifier.canShowMarketNotice(): Boolean {
    if (!hasPermission() || !manager.areNotificationsEnabled()) return false
    return runCatching { channelDelivers(marketOrQuietChannel(isQuietNow())) }.getOrDefault(true)
}

// ---------------- Gas ----------------

/**
 * Netzwerkgebühr ist unter die eingestellte Grenze gefallen (#167). [iconRes] = Glocke je Art (ETH-Gas, BTC-Gebühr,
 * Wirtschaftstermine), sonst das App-Logo.
 * @return true, wenn die Meldung ans System ging (GasAlertChecker hält den Alarm sonst scharf).
 */
fun AppNotifier.showGas(
    id: Int,
    title: String,
    text: String,
    openTarget: String? = null,
    iconRes: Int = R.drawable.ic_stat_logo,
): Boolean {
    val quiet = isQuietNow()
    val notification = NotificationCompat.Builder(context, marketOrQuietChannel(quiet))
        .setSmallIcon(iconRes)
        .setColor(settingsRepository.cached.accentColor.seed)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .appBadge()
        .setSilent(quiet)
        // Mit Ziel (z. B. «Wirtschaftstermine» → Markt-Tab), sonst einfach die App
        .setContentIntent(openTarget?.let { openTargetIntent(it, TARGET_REQUEST_BASE + id) } ?: openAppIntent(null))
        .build()
    return notifyChecked(id, notification)
}

/** Markt-Kanal bzw. in der Nachtruhe der lautlose ([AppNotifierDeliveryPlan.marketChannel]). */
private fun AppNotifier.marketOrQuietChannel(quiet: Boolean): String =
    channelIdFor(AppNotifierDeliveryPlan.marketChannel(quiet), settingsRepository.cached.alarmSignal)
