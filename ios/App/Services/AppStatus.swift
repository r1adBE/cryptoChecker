import Foundation
import Observation

/// Status-Werte ohne Bezug zu den Zeilen der Merkliste, getrennt von `AppData` gehalten (`AppData.status`): Eine
/// Änderung meldet sich nur bei den Ansichten, die den Wert im `body` lesen (Beobachtung je Eigenschaft) —
/// `AppData.objectWillChange` bleibt still, die übrigen Ansichten mit `AppData` (Merkliste mit ihren Zeilen, Markt,
/// Portfolio, Einstellungen) rechnen ihren `body` nicht neu. Gelesen wird weiter über `AppData` (`online`,
/// `appStartMillis`, `watchlistSavePending`, `watchlistLockedHints`, `exchangePauses`, `changeStamp`, `activityReports`,
/// `portfolioSavePending`); geschrieben nur von `AppData`.
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
    /// Ende der Pause je Börsen-Schlüssel (`RowPause.pauses`): «Binance · pausiert bis 19:45» in der Zeitzeile ihrer
    /// Paare; ob eine Pause noch läuft, entscheidet die Zeile mit der aktuellen Zeit (`RowPause.shownUntil`).
    var exchangePauses: [String: Int64]
    /// %-Basis und Tagesbeginn, mit denen die gespeicherten Veränderungen gerechnet wurden.
    var changeStamp: ChangeStamp?
    /// «Ungewöhnliche Aktivität» je Paar (Watch-Id); abgelaufene Signale filtert `ActivityReport.active(now:)`.
    var activityReports: [Int64: ActivityReport] = [:]
    /// Portfolio bzw. Portfolio-Alarme konnten nicht versiegelt gespeichert werden (Schlüsselbund gerade nicht lesbar) und
    /// warten im Speicher — nie im Klartext geschrieben. Das Portfolio nennt es; das nächste Speichern bzw. die Rückkehr
    /// der App nach vorne versucht es erneut (`retryPendingPortfolioSaves`).
    var portfolioSavePending = false

    init(online: Bool, appStartMillis: Int64?, exchangePauses: [String: Int64] = [:], changeStamp: ChangeStamp? = nil) {
        self.online = online
        self.appStartMillis = appStartMillis
        self.exchangePauses = exchangePauses
        self.changeStamp = changeStamp
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

    /// Ende der Pause je Börsen-Schlüssel (`AppStatus.exchangePauses`).
    var exchangePauses: [String: Int64] {
        get { status.exchangePauses }
        set { status.exchangePauses = newValue }
    }

    /// %-Basis und Tagesbeginn der gespeicherten Veränderungen (`AppStatus.changeStamp`).
    var changeStamp: ChangeStamp? {
        get { status.changeStamp }
        set { status.changeStamp = newValue }
    }

    /// «Ungewöhnliche Aktivität» je Paar (`AppStatus.activityReports`); Änderung einzelner Einträge direkt dort.
    var activityReports: [Int64: ActivityReport] {
        get { status.activityReports }
        set { status.activityReports = newValue }
    }

    /// Portfolio wartet auf das versiegelte Speichern (`AppStatus.portfolioSavePending`).
    var portfolioSavePending: Bool {
        get { status.portfolioSavePending }
        set { status.portfolioSavePending = newValue }
    }
}
