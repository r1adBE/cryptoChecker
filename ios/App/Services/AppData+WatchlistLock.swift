import Foundation

// Sperre der Merkliste (seit Build 103, Review F-08): Steht die gespeicherte Merkliste nur leer im Speicher
// (`watchlistLocked`), lehnt AppData jeden Schreibweg des Nutzers ab und meldet es kurz — wie Android
// `WatchRepository.userWriteRefused` mit `WatchlistWriteRefusal`; Hinweis: `WatchlistLockedHint.swift` (Components).

/// Drosselung des Hinweises: mehrere Ablehnungen kurz hintereinander (z. B. Startauswahl) ergeben einen — wie Android
/// `WatchlistWriteRefusal`.
enum WatchlistWriteRefusal {
    /// Mindestabstand zwischen zwei Hinweisen (ms).
    static let minGapMillis: Int64 = 2_000

    /// Hinweis nach `lastAt` jetzt (`now`) zeigen? Erster immer, danach erst nach `minGapMillis`; rückwärts laufende Zeit
    /// zählt nicht als «zu früh».
    static func shouldNotify(lastAt: Int64?, now: Int64) -> Bool {
        guard let lastAt else { return true }
        return now < lastAt || now - lastAt >= minGapMillis
    }

    /// Monotone Zeit in ms (Uhrzeit-Änderungen des Geräts verschieben die Drosselung nicht). Seit Build 105 (SEC3-01)
    /// dieselbe Quelle wie `PortfolioLockPolicy.monotonicSeconds` statt `systemUptime` (Required-Reason-API «System
    /// boot time», nicht im Datenschutz-Manifest deklariert).
    static var nowMillis: Int64 { Int64(PortfolioLockPolicy.monotonicSeconds() * 1000) }
}

extension AppData {
    /// Schreibwege des Nutzers gerade gesperrt (`WatchlistStorageLock.blocksWrites`)?
    var watchlistWritesBlocked: Bool { WatchlistStorageLock.blocksWrites(locked: watchlistLocked) }

    /// Schreibweg des Nutzers ablehnen? true = gesperrt, der Aufrufer schreibt nichts. Mit `notify` zählt die Ablehnung
    /// als Hinweis (gedrosselt, `watchlistLockedHints`). Nur Schreibwege des Nutzers fragen hier; Kurse, Alarmzustand und
    /// Widget schreiben weiter (`saveSnapshotInBackground` lässt die gesperrte Datei ohnehin unberührt).
    /// Seit Build 105 (F-08, Review UX3-02, IOS3-R04): wird die Merkliste gerade nicht gespeichert, fragt die AKTION
    /// vorher (`confirmWatchlistWrite`); hier nur die Sicherung — ohne Bestätigung ablehnen (true), ohne Hinweis, ohne
    /// Frage und ohne erneutes Ausführen. Nur mit `notify` (Folge-Schreibwege laufen nur nach einem bestätigten).
    @discardableResult
    func watchlistWriteRefused(notify: Bool = true, now: Int64 = WatchlistWriteRefusal.nowMillis) -> Bool {
        guard watchlistWritesBlocked else {
            guard notify else { return false }
            return WatchlistNotSavedGate.shared.needsConfirmation(notSaved: watchlistGateNotSaved, canAsk: watchlistGateCanAsk)
        }
        if notify, WatchlistWriteRefusal.shouldNotify(lastAt: watchlistLockedHintAt, now: now) {
            watchlistLockedHintAt = now
            watchlistLockedHints &+= 1
        }
        return true
    }

    /// Lage für die Rückfrage F-08: wird die Merkliste gerade nicht gespeichert?
    var watchlistGateNotSaved: Bool { watchlistStorageNotice == .notSaved }

    /// Kann die App jetzt fragen (aktiv, Fenster für die Frage vorhanden, `WatchlistNotSavedGatePresenter`)?
    var watchlistGateCanAsk: Bool { appActive && WatchlistNotSavedGatePresenter.shared.attached }

    /// Seit Build 105 (Review UX3-02/05, IOS3-R04): Rückfrage «Merkliste wird nicht gespeichert» EINMAL je Nutzeraktion,
    /// VOR der Aktion; false = «Abbrechen» (nichts tun, kein Banner, keine Haptik). Danach läuft der normale Weg mit
    /// Banner, Rückgängig, Ansage und Erst-Alarm. Gesperrte Merkliste fragt nicht (true): Dort lehnt der Schreibweg selbst
    /// ab und zeigt den Hinweis. Wie Android `WatchRepository.confirmUserWrite`.
    func confirmWatchlistWrite() async -> Bool {
        if watchlistWritesBlocked { return true }
        return await WatchlistNotSavedGate.shared.confirm(notSaved: watchlistGateNotSaved, canAsk: watchlistGateCanAsk)
    }

    /// Wie `confirmWatchlistWrite()`, für Aktionen der Bildschirme: ohne nötige Frage läuft `action` sofort (synchron,
    /// Animationen und Rückgaben wie bisher), sonst erst nach «Trotzdem»; nach «Abbrechen» nie, dann `cancelled` (z. B.
    /// einen umgelegten Schalter zurückzeigen).
    func withWatchlistConfirm(_ action: @escaping @MainActor () -> Void, cancelled: (@MainActor () -> Void)? = nil) {
        guard !watchlistWritesBlocked,
              WatchlistNotSavedGate.shared.needsConfirmation(notSaved: watchlistGateNotSaved, canAsk: watchlistGateCanAsk)
        else { return action() }
        Task { @MainActor in
            if await confirmWatchlistWrite() { action() } else { cancelled?() }
        }
    }
}
