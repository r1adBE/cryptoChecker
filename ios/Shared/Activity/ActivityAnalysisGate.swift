import Foundation

/// Lohnt die Auswertung «Ungewöhnliche Aktivität» (Kerzen je Paar, höchstens alle 10 Min.)
/// nach einer Aktualisierung? Nur mit Abnehmer: Die Mitteilung «Ungewöhnliche Aktivität»
/// ist eingeschaltet, oder die App ist sichtbar (Merkliste mit Aktivitätskarte und ⚡,
/// «Warum bewegt sich das?»). Kein Widget zeigt diese Signale. Wie `ActivityAnalysisGate` in Android.
enum ActivityAnalysisGate {
    static func shouldRun(alertsEnabled: Bool, appVisible: Bool) -> Bool {
        alertsEnabled || appVisible
    }
}
