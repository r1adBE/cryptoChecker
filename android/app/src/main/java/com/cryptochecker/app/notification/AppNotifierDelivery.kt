// Zustellung der Mitteilungen (Teil von `AppNotifier`): geprüft melden, Gruppen mit Zusammenfassung, Platz
// für einen Alarm schaffen, auf die Anzeige warten und die Tipp-Ziele (App, Ziel-Seite, «Was bewegt sich mit?»).
// Eigene Datei, weil alle Arten von Mitteilungen diesen Weg teilen; Erweiterungsfunktionen.
package com.cryptochecker.app.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.cryptochecker.app.ui.MainActivity
import kotlinx.coroutines.delay
import timber.log.Timber
import com.cryptochecker.app.notification.AppNotifier.Companion.ALARM_SUMMARY_ID
import com.cryptochecker.app.notification.AppNotifier.Companion.VERIFY_ATTEMPTS
import com.cryptochecker.app.notification.AppNotifier.Companion.VERIFY_STEP_MILLIS
import com.cryptochecker.app.notification.AppNotifier.Companion.ACTION_OPEN_NOTIFICATION
import com.cryptochecker.app.notification.AppNotifier.Companion.WHY_REQUEST_BASE
import com.cryptochecker.app.notification.AppNotifier.Group
import com.cryptochecker.app.notification.AppNotifier.GroupState

/**
 * Alarme haben Vorrang: Nahe an der Grenze von Android (rund 50 offene Mitteilungen je App)
 * wandern zuerst Kurs-Mitteilungen (die ältesten) in die Sammelmeldung, danach weichen
 * Aktivitäts-Meldungen und zuletzt die Sammelmeldung selbst.
 */
internal fun AppNotifier.makeRoomForAlarm(id: Int) {
    // Gleiche Sperre wie die @Synchronized-Funktionen der Klasse (@Synchronized auf Datei-Ebene sperrte die Datei-Klasse)
    synchronized(this) {
        val active = activeState() ?: return
        val need = NotificationBudget.neededForAlarm(active.total, id in active.ids, ALARM_SUMMARY_ID in active.ids)
        if (need == 0) return
        val room = NotificationBudget.roomForAlarm(need, active.prices.size, active.overflowShown)
        var overflowGone = false
        if (room.demote > 0) {
            val moved = active.prices.entries.toList().takeLast(room.demote)
            moved.forEach { cancelPriceSingle(it.key) }
            if (room.merge) {
                val lines = LinkedHashMap<Long, String>()
                moved.filter { it.value.isNotEmpty() }.forEach { lines[it.key] = it.value }
                lines.putAll(active.overflow)
                postOverflow(lines, ongoing = settingsRepository.cached.ongoingNotifications)
            }
        }
        var remaining = room.remaining
        for (activityId in active.activity) {
            if (remaining <= 0) break
            manager.cancel(activityId)
            remaining--
        }
        if (remaining > 0 && active.overflowShown) {
            postOverflow(LinkedHashMap(), ongoing = false)
            overflowGone = true
        }
        Timber.d("Platz für Alarm: %d Kurs-Mitteilungen verschoben, Sammelmeldung entfernt: %b", room.demote, overflowGone)
    }
}

/**
 * Steht die Mitteilung [id] unter den offenen? Der Systemdienst führt eine eben gemeldete
 * evtl. erst kurz danach — darum einige Male kurz nachsehen; nicht abfragbar = ja.
 */
internal suspend fun AppNotifier.awaitActive(id: Int): Boolean {
    repeat(VERIFY_ATTEMPTS) { attempt ->
        val ids = activeNotificationIds() ?: return true
        if (id in ids) return true
        if (attempt < VERIFY_ATTEMPTS - 1) delay(VERIFY_STEP_MILLIS)
    }
    Timber.w("Mitteilung %d nicht unter den offenen", id)
    return false
}

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
    if (state.children >= 1 && state.summaryOngoing != ongoing) {
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
    if (activeInGroup(group, exclude = removed) == 0) manager.cancel(group.summaryId)
}

/**
 * Offene Mitteilungen der Gruppe ohne Zusammenfassung und ohne [exclude] (eben gezeigt bzw.
 * entfernt — der Systemdienst führt sie evtl. noch nicht); null, wenn das nicht abfragbar ist.
 */
internal fun AppNotifier.activeInGroup(group: Group, exclude: Set<Int>): Int? = groupState(group, exclude)?.children

internal fun AppNotifier.groupState(group: Group, exclude: Set<Int>): GroupState? = runCatching {
    val list = context.getSystemService(android.app.NotificationManager::class.java)?.activeNotifications
        ?: return@runCatching null
    var children = 0
    var summaryOngoing: Boolean? = null
    for (sbn in list) {
        val n = sbn.notification
        when {
            sbn.id == group.summaryId -> summaryOngoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
            sbn.id !in exclude && n.group == group.key && (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0 -> children++
        }
    }
    GroupState(children, summaryOngoing)
}.getOrNull()

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
