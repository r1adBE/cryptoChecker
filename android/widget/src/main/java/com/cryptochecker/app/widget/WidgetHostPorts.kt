package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot

// Was die Widgets von der App brauchen (Modul :widget liegt unter :app): umgesetzt in der App, verbunden in
// `di/WidgetHostModule.kt`.

/** Hintergrund-Aktualisierung: Widgets hinzugefügt/entfernt, Tipp auf «Aktualisieren». */
interface WidgetScheduler {
    fun onWidgetsChanged()
    fun refreshNow()
}

/** Portfolio-Alarme nach einer neuen Momentaufnahme prüfen. */
interface PortfolioAlarmCheck {
    suspend fun hasActiveAlarms(): Boolean
    suspend fun check(snapshot: PortfolioSnapshot)
}

/** Uhr-App: neuer Portfolio-Wert gespeichert. */
fun interface PortfolioWatchFeed {
    fun portfolioRecorded()
}

/** Nach Rückkehr des Netzes einmal die Kurse prüfen ([WidgetReconnectWorker]; Lauf der Hintergrund-Prüfung). */
fun interface WidgetReconnectCheck {
    suspend fun runAfterReconnect(ttsWaitMillis: Long)
}
