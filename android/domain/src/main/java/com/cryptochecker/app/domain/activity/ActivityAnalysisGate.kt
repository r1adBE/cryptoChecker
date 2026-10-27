package com.cryptochecker.app.domain.activity

/**
 * Lohnt die Auswertung «Ungewöhnliche Aktivität» (Kerzen je Paar, höchstens alle 10 Min.)
 * nach einer Aktualisierung? Nur, wenn jemand das Ergebnis braucht: die Meldung
 * «Ungewöhnliche Aktivität» ist eingeschaltet, oder die App ist sichtbar (Merkliste mit
 * Aktivitätskarte und ⚡, «Warum bewegt sich das?»). Kein Widget zeigt diese Signale.
 *
 * Reine Logik ohne Android (getestet in ActivityAnalysisGateTest, Swift-Spiegel
 * ActivityAnalysisGate.swift).
 */
object ActivityAnalysisGate {
    fun shouldRun(alertsEnabled: Boolean, appVisible: Boolean): Boolean = alertsEnabled || appVisible
}
