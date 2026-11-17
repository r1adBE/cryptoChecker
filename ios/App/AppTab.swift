import Foundation

/// Tabs der App — wie die Tabs der Android-Fassung: Merkliste · Markt · (Portfolio) · Einstellungen.
/// `portfolio` gibt es nur, wenn der Bereich unter Einstellungen › Portfolio eingeschaltet ist. «Paar hinzufügen»
/// ist kein Tab, sondern eine Seite über der Merkliste (`showExplorer`).
enum AppTab: Hashable {
    case watchlist, cycle, portfolio, settings
}
