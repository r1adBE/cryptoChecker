// Zustellung der Mitteilungen (Teil von `AppNotifier`): geprüft melden, Gruppen mit Zusammenfassung, Platz
// für einen Alarm schaffen, Kanal wählen, auf die Anzeige warten und die Tipp-Ziele (App, Ziel-Seite, «Was bewegt
// sich mit?»). Die Entscheidungen dazu trifft `AppNotifierDeliveryPlan` (ohne Android-Typen).
// Eigene Datei, weil alle Arten von Mitteilungen diesen Weg teilen; Erweiterungsfunktionen.
package com.cryptochecker.app.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.ui.MainActivity
import kotlinx.coroutines.delay
import timber.log.Timber
import com.cryptochecker.app.notification.AppNotifier.Companion.ALARM_SUMMARY_ID
import com.cryptochecker.app.notification.AppNotifier.Companion.VERIFY_ATTEMPTS
import com.cryptochecker.app.notification.AppNotifier.Companion.VERIFY_STEP_MILLIS
import com.cryptochecker.app.notification.AppNotifier.Companion.ACTION_OPEN_NOTIFICATION
import com.cryptochecker.app.notification.AppNotifier.Companion.WHY_REQUEST_BASE
import com.cryptochecker.app.notification.AppNotifier.Group
import com.cryptochecker.app.ui.withOpenToken

/**
 * Platz für den Alarm [id] schaffen — welche Mitteilungen weichen, entscheidet
 * [AppNotifierDeliveryPlan.alarmRoom]; hier wird der Plan in seiner Reihenfolge ausgeführt.
 */
internal fun AppNotifier.makeRoomForAlarm(id: Int) {
    // Gleiche Sperre wie die @Synchronized-Funktionen der Klasse (@Synchronized auf Datei-Ebene sperrte die Datei-Klasse)
    synchronized(this) {
        val active = activeState() ?: return
        val plan = AppNotifierDeliveryPlan.alarmRoom(
            AppNotifierDeliveryPlan.AlarmRoomInput(
                total = active.total,
                alarmShown = id in active.ids,
                summaryShown = ALARM_SUMMARY_ID in active.ids,
                prices = active.prices.entries.map { it.key to it.value },
                overflow = active.overflow,
                overflowShown = active.overflowShown,
                activity = active.activity,
                alarms = active.alarms,
                newId = id,
            )
        ) ?: return
        plan.demote.forEach { cancelPriceSingle(it) }
        plan.overflowLines?.let { postOverflow(it, ongoing = settingsRepository.cached.ongoingNotifications) }
        plan.cancelActivity.forEach { manager.cancel(it) }
        if (plan.removeOverflow) postOverflow(LinkedHashMap(), ongoing = false)
        plan.evictAlarms.forEach { manager.cancel(it) }
        Timber.d(
            "Platz für Alarm: %d Kurs-Mitteilungen verschoben, Sammelmeldung entfernt: %b, ältere Alarme entfernt: %d",
            plan.demote.size, plan.removeOverflow, plan.evictAlarms.size,
        )
    }
}

/**
 * Steht die Mitteilung [id] unter den offenen — und zwar die NEUE, gemeldet ab [postedAfter]
 * (Zeit direkt vor dem Melden)? Steht schon eine ältere Meldung mit derselben Id da (gleicher
 * Alarm), genügte die Id allein nicht: eine verworfene neue Meldung (Drosselung) zählte sonst als
 * gezeigt. Der Systemdienst führt eine eben gemeldete evtl. erst kurz danach — darum einige Male
 * kurz nachsehen; nicht abfragbar = ja ([NotificationBudget.confirmedPost]).
 */
internal suspend fun AppNotifier.awaitActive(id: Int, postedAfter: Long): Boolean {
    repeat(VERIFY_ATTEMPTS) { attempt ->
        if (NotificationBudget.confirmedPost(activePostTimes(), id, postedAfter)) return true
        if (attempt < VERIFY_ATTEMPTS - 1) delay(VERIFY_STEP_MILLIS)
    }
    Timber.w("Mitteilung %d nicht unter den offenen", id)
    return false
}

/** Meldezeit (postTime) je offener Mitteilung der App; null, wenn das nicht abfragbar ist. */
internal fun AppNotifier.activePostTimes(): Map<Int, Long>? = runCatching {
    context.getSystemService(android.app.NotificationManager::class.java)
        ?.activeNotifications?.associate { it.id to it.postTime }
}.getOrNull()

internal fun AppNotifier.notify(id: Int, notification: Notification) {
    notifyChecked(id, notification)
}

/**
 * Wie [notifyChecked]; ab der zweiten Mitteilung der Gruppe kommt die Zusammenfassung dazu —
 * nur, wenn sie noch fehlt bzw. «dauerhaft» wechselt (nicht bei jeder Meldung neu: Android
 * drosselt zu viele Meldungen je Sekunde).
 */
internal fun AppNotifier.notifyInGroup(id: Int, notification: Notification, group: Group): Boolean {
    val posted = notifyChecked(id, notification)
    if (!posted) return false
    val state = groupState(group, exclude = setOf(id)) ?: return true
    val ongoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
    if (AppNotifierDeliveryPlan.repostSummary(state, ongoing)) {
        postSummary(group, notification.channelId, ongoing = ongoing)
    }
    return true
}

/**
 * Zusammenfassung der Gruppe: selbst nie laut (GROUP_ALERT_CHILDREN — es meldet nur die einzelne
 * Mitteilung), gleicher Kanal wie die eben gezeigte Mitteilung; Tipp öffnet die App.
 */
internal fun AppNotifier.postSummary(group: Group, channelId: String, ongoing: Boolean) {
    val title = context.getString(group.titleRes)
    val summary = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(group.iconRes)
        .setColor(settingsRepository.cached.accentColor.seed)
        .setContentTitle(title)
        .setGroup(group.key)
        .setGroupSummary(true)
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        .setOnlyAlertOnce(true)
        .setOngoing(ongoing)
        .setAutoCancel(!ongoing)
        .setShowWhen(false)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
        .setContentIntent(openAppIntent(null))
        .build()
    notifyChecked(group.summaryId, summary)
}

/** Zusammenfassung entfernen, wenn ausser [removed] keine Mitteilung der Gruppe mehr offen ist. */
internal fun AppNotifier.cancelSummaryIfEmpty(group: Group, removed: Set<Int>) {
    if (AppNotifierDeliveryPlan.cancelSummary(activeInGroup(group, exclude = removed))) manager.cancel(group.summaryId)
}

/**
 * Offene Mitteilungen der Gruppe ohne Zusammenfassung und ohne [exclude] (eben gezeigt bzw.
 * entfernt — der Systemdienst führt sie evtl. noch nicht); null, wenn das nicht abfragbar ist.
 */
internal fun AppNotifier.activeInGroup(group: Group, exclude: Set<Int>): Int? = groupState(group, exclude)?.children

/** Offene Mitteilungen der Gruppe ([AppNotifierDeliveryPlan.groupCount]); null, wenn das nicht abfragbar ist. */
internal fun AppNotifier.groupState(
    group: Group,
    exclude: Set<Int>,
): AppNotifierDeliveryPlan.GroupCount? = runCatching {
    val list = context.getSystemService(android.app.NotificationManager::class.java)?.activeNotifications
        ?: return@runCatching null
    val entries = list.map { sbn ->
        val n = sbn.notification
        AppNotifierDeliveryPlan.GroupEntry(
            id = sbn.id,
            group = n.group,
            summary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
        )
    }
    AppNotifierDeliveryPlan.groupCount(entries, group.key, group.summaryId, exclude)
}.getOrNull()

/** Kanal-Id zur Wahl [channel]; [signal] gilt nur für den Kanal des «Alarm-Signals» (legt Kanäle bei Bedarf an). */
internal fun AppNotifier.channelIdFor(channel: AppNotifierDeliveryPlan.Channel, signal: AlarmSignal): String =
    when (channel) {
        AppNotifierDeliveryPlan.Channel.QUIET_ALARM -> NotificationChannels.ensureQuietAlarmChannel(context)
        AppNotifierDeliveryPlan.Channel.ALARM_SIGNAL -> alarmChannel(signal)
        AppNotifierDeliveryPlan.Channel.MARKET -> NotificationChannels.MARKET
    }

/** Ist der Kanal [channelId] nicht stummgeschaltet ([AppNotifierDeliveryPlan.channelDelivers])? */
internal fun AppNotifier.channelDelivers(channelId: String): Boolean {
    val channel = context.getSystemService(android.app.NotificationManager::class.java)
        ?.getNotificationChannel(channelId)
    return AppNotifierDeliveryPlan.channelDelivers(channel?.importance, android.app.NotificationManager.IMPORTANCE_NONE)
}

/** Wie [notify]; true, wenn die Meldung ans System ging (Erlaubnis da, kein Fehler). */
// Erlaubnis prüft hasPermission() direkt davor (Android 13+); SecurityException fängt runCatching
@SuppressLint("MissingPermission")
internal fun AppNotifier.notifyChecked(id: Int, notification: Notification): Boolean {
    if (!hasPermission()) {
        Timber.d("Benachrichtigung unterdrückt: keine Berechtigung")
        return false
    }
    return runCatching { manager.notify(id, notification) }
        .onFailure { Timber.w(it, "Benachrichtigung fehlgeschlagen") }
        .isSuccess
}

internal fun AppNotifier.openAppIntent(watchId: Long?): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        // Eigene Action: Extras zählen nicht zur Identität eines PendingIntent; ohne sie
        // teilten sich Mitteilungen und Widgets gleiche Request-Codes (0, 1, 2 …)
        action = ACTION_OPEN_NOTIFICATION
        // SINGLE_TOP: eine laufende Activity bekommt onNewIntent (öffnet das Paar), statt neu aufgebaut zu werden
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        if (watchId != null) putExtra(MainActivity.EXTRA_WATCH_ID, watchId)
    }
    return PendingIntent.getActivity(
        context,
        (watchId ?: 0L).toInt(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/** Öffnet die App mit dem Ziel [target] (wie die App-Verknüpfungen, siehe MainActivity.EXTRA_OPEN). */
internal fun AppNotifier.openTargetIntent(target: String, requestCode: Int): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        // Eigene Action je Ziel: sonst teilten sich verschiedene Ziele mit gleichem Request-Code ein PendingIntent
        action = "$ACTION_OPEN_NOTIFICATION.$target"
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(MainActivity.EXTRA_OPEN, target)
        // Geheimnis dieser Installation: sonst gilt das Ziel nicht (OpenTargetGuard)
        withOpenToken(context)
    }
    return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/** «Was bewegt sich mit?» aus einem Alarm: App öffnen, Merkliste, «Was bewegt sich mit?» des Paars. */
internal fun AppNotifier.openWhyIntent(watchId: Long): PendingIntent =
    openTargetIntent(MainActivity.openWhyTarget(watchId), (WHY_REQUEST_BASE + watchId).toInt())

/**
 * Kurzzeitige Meldung, solange eine angestossene Aktualisierung im
 * Hintergrund läuft. Nur bis Android 11 sichtbar — dort braucht
 * WorkManager dafür einen Vordergrunddienst.
 */
fun AppNotifier.refreshNotification(): Notification =
    NotificationCompat.Builder(context, NotificationChannels.SERVICE)
        .setSmallIcon(R.drawable.ic_stat_logo)
        .setColor(settingsRepository.cached.accentColor.seed)
        .setContentTitle(context.getString(R.string.notification_refreshing))
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .build()
