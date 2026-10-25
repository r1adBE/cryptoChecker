import Foundation
import Observation

/// Status-Werte ohne Bezug zu den Zeilen der Merkliste, getrennt von `AppData` gehalten (`AppData.status`): Eine
/// Änderung meldet sich nur bei den Ansichten, die den Wert im `body` lesen (Beobachtung je Eigenschaft) —
/// `AppData.objectWillChange` bleibt still, die übrigen Ansichten mit `AppData` (Merkliste mit ihren Zeilen, Markt,
/// Portfolio, Einstellungen) rechnen ihren `body` nicht neu. Gelesen wird weiter über `AppData` (`online`,
/// `appStartMillis`, `watchlistSavePending`, `watchlistLockedHints`); geschrieben nur von `AppData`.
@MainActor
@Observable
final class AppStatus {
    /// Gerät hat Internet? Ohne: ruhige Statuszeile «Offline · Stand …», keine Aktualisierung (wechselt mit dem Netz).
    var online: Bool
    /// App-Start bis zum ersten Bild der Merkliste (zuletzt gemessen), für den Bericht «Ablauf» (einmal je Start).
    var appStartMillis: Int64?
    /// `watchlist.json` konnte nicht versiegelt gespeichert werden (Schlüsselbund gerade nicht lesbar, nie im Klartext):
    /// Merkliste und Alarme warten im Speicher — `reloadFromDisk` ersetzt sie dann nicht durch den älteren Stand der Datei,
    /// versucht das Speichern erneut, und jedes weitere Speichern (`mutate`) schreibt den ganzen Stand. Nach jedem
    /// Schreiben geprüft.
    var watchlistSavePending = false
    /// Zähler der gemeldeten Ablehnungen (`watchlistWriteRefused`, gedrosselt): Bildschirme zeigen je Erhöhung den kurzen
    /// Hinweis «watchlist_storage_locked» (`.watchlistLockedHint`).
    var watchlistLockedHints = 0

    init(online: Bool, appStartMillis: Int64?) {
        self.online = online
        self.appStartMillis = appStartMillis
    }
}

extension AppData {
    /// Gerät hat Internet (`AppStatus.online`).
    var online: Bool { status.online }

    /// App-Start bis zum ersten Bild der Merkliste (`AppStatus.appStartMillis`).
    var appStartMillis: Int64? { status.appStartMillis }

    /// Merkliste wartet auf das versiegelte Speichern (`AppStatus.watchlistSavePending`).
    var watchlistSavePending: Bool { status.watchlistSavePending }

    /// Zähler der gemeldeten Ablehnungen (`AppStatus.watchlistLockedHints`).
    var watchlistLockedHints: Int {
        get { status.watchlistLockedHints }
        set { status.watchlistLockedHints = newValue }
    }
}
