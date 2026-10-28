package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository
import com.cryptochecker.app.domain.alarm.DepegAlarm
import com.cryptochecker.app.domain.alarm.DepegAlarmChecker
import com.cryptochecker.app.domain.alarm.DepegReadingStore
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.notification.alarmsAllowed
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

// Kleine Bausteine des Portfolios, damit [PortfolioViewModel] je Aufgabe nur einen Baustein braucht.

/**
 * USDT zum Marktkurs: letzte Messung USDT/USD (Live-Kurs der Bewertung, Zeile «USDT = 1» der Gesamtkarte) und die
 * Messung selbst, sobald ein Portfolio da ist (höchstens alle 5 Minuten, wie nach einer Kursaktualisierung).
 */
class UsdtPeg @Inject constructor(
    private val readings: DepegReadingStore,
    private val checker: DepegAlarmChecker,
) {
    /** Letzte Messung; null = noch keine. */
    val reading: StateFlow<DepegAlarm.Reading?> get() = readings.reading

    /** USDT in USD, solange die Messung frisch ist; sonst null (dann gilt USDT = 1). */
    fun liveUsdtUsd(now: Long = System.currentTimeMillis()): Double? = readings.liveUsdtUsd(now)

    /** Neu messen, wenn fällig (auch der Depeg-Alarm prüft dabei). */
    suspend fun checkIfDue() {
        checker.checkIfDue()
    }
}

/** Portfolio-Alarm anlegen und sagen, ob Alarme als Mitteilung erscheinen dürfen («· Mitteilungen aus»). */
class PortfolioAlarmSaving @Inject constructor(
    private val repository: PortfolioAlarmRepository,
    private val notifier: AppNotifier,
) {
    /** Legt den Alarm an; Beträge in [currency] (null bei Prozent-Alarmen). Liefert die neue Id. */
    suspend fun add(kind: PortfolioAlarmKind, threshold: Double, currency: String?, repeating: Boolean): Long =
        repository.add(kind, threshold, currency, repeating)

    /** Dürfen Alarme als Mitteilung erscheinen? */
    fun alarmsAllowed(): Boolean = notifier.alarmsAllowed()
}
