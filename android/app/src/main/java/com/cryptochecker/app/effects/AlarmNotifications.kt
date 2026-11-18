package com.cryptochecker.app.effects

import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.ui.features.alarms.retractAlarm
import javax.inject.Inject

/** Mitteilungen von Alarmen entfernen (Alarm-Übersicht). */
interface AlarmNotifications {
    /** Mitteilungen der Paar-Alarme [alarmIds] und der Portfolio-Alarme [portfolioAlarmIds] entfernen. */
    fun cancelAlarms(alarmIds: Collection<Long>, portfolioAlarmIds: Collection<Long>)

    /** Mitteilung eines ausgeschalteten Paar-Alarms entfernen; Fehler werden nur protokolliert. */
    suspend fun retractAlarm(alarmId: Long)
}

/** Über den [AppNotifier]. */
class AppNotifierAlarmNotifications @Inject constructor(private val notifier: AppNotifier) : AlarmNotifications {
    override fun cancelAlarms(alarmIds: Collection<Long>, portfolioAlarmIds: Collection<Long>) =
        notifier.cancelAlarms(alarmIds, portfolioAlarmIds)

    override suspend fun retractAlarm(alarmId: Long) = notifier.retractAlarm(alarmId)
}
