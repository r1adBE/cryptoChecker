// Nach dem Durchlauf ausserhalb der Sperre: Kurs-Meldungen abwarten, Widget-Charts und Nebenprüfungen (Teil von
// `PriceRefresher`, gespiegelt in `PriceRefresher+After.swift`). Erweiterungsfunktionen; Abhängigkeiten in der Klasse.
package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.refresh.PriceRefresher.Companion.SIDE_CHECK_RESERVE_MILLIS
import com.cryptochecker.app.domain.refresh.PriceRefresher.Companion.SIDE_CHECK_TIMEOUT_MILLIS
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Nach [PriceRefresher.refreshAll] unter der Sperre: [prices] = Job der zurückgestellten Kurs-Meldungen (hält die
 * Sperre bis zum Ende, null = keine). Im WorkManager-Job und mit Frist ([awaitPortfolioSnapshot] bzw. [deadlineAt])
 * wartet der Lauf auf sie — sonst kann Android den Prozess vorher anhalten; im Vordergrund endet er vorher.
 * Nebenprüfungen (melden selbst: Aktivität, Gas, Depeg) beginnen erst nach den Kurs-Meldungen, damit deren Drosselung
 * (höchstens 5 je Sekunde) gilt.
 */
internal suspend fun PriceRefresher.afterRefresh(prices: Job?, awaitPortfolioSnapshot: Boolean, deadlineAt: Long?) {
    if (awaitPortfolioSnapshot || deadlineAt != null) prices?.join()
    // Widget-Charts: unter der Sperre nur aus dem Zwischenspeicher gezeichnet — jetzt, ohne
    // Sperre, nachladen; mit Frist nur, wenn noch genug Zeit bleibt (sonst bleibt der alte Chart)
    val chartTimeout = if (chartRefresher.takePending()) {
        CheckBudget.chartFetchMillis(deadlineAt, System.currentTimeMillis())
    } else {
        null
    }
    // Aktivität, Gas, Depeg, Portfolio: erst nach gespeicherten Kursen und gezeichneten Widgets,
    // ausserhalb der Sperre ([RefreshSideChecks])
    if (awaitPortfolioSnapshot) {
        // WorkManager-Job: abwarten, parallel und mit Zeitgrenze.
        // Mit Frist: nur die verbleibende Zeit (Reserve für Ansagen und Vermerk)
        val sideTimeout = CheckBudget.waitMillis(
            deadlineAt, System.currentTimeMillis(), SIDE_CHECK_TIMEOUT_MILLIS, reserve = SIDE_CHECK_RESERVE_MILLIS
        )
        sideChecks.awaitAll(
            sideTimeout,
            chartTimeout?.let { RefreshSideChecks.Extra("Widget-Charts", it) { chartRefresher.refreshUnlocked() } },
        )
    } else {
        if (prices == null || prices.isCompleted) sideChecks.launchDetached()
        else lateFillScope.launch { prices.join(); sideChecks.launchDetached() }
        if (chartTimeout != null) chartRefresher.launch(chartTimeout)
    }
}

/**
 * Zahl der gezeigten Kurs-Meldungen in den Bericht [report] nachtragen — nur, solange er der zuletzt gespeicherte ist
 * (Nachträge wie die Wartezeit bleiben erhalten). 0 steht schon drin.
 */
internal fun PriceRefresher.noteShownPrices(report: RefreshReport, shown: Int) {
    if (shown <= 0) return
    val current = refreshStats.lastReport.value ?: return
    if (current.at == report.at) refreshStats.setLastReport(current.copy(notifications = shown))
}
