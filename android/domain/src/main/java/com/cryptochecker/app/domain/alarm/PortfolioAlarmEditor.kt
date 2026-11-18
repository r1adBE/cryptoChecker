package com.cryptochecker.app.domain.alarm

/** Portfolio-Alarme wie [PortfolioAlarmStore], dazu anlegen (Umsetzung in :data). */
interface PortfolioAlarmEditor : PortfolioAlarmStore {
    /** Legt den Alarm an; Beträge in [currency] (null bei Prozent-Alarmen). Liefert die neue Id. */
    suspend fun add(kind: PortfolioAlarmKind, threshold: Double, currency: String?, repeating: Boolean): Long
}
