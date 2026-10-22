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

    /// Monotone Zeit in ms (Uhrzeit-Änderungen des Geräts verschieben die Drosselung nicht).
    static var nowMillis: Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }
}

extension AppData {
    /// Schreibwege des Nutzers gerade gesperrt (`WatchlistStorageLock.blocksWrites`)?
    var watchlistWritesBlocked: Bool { WatchlistStorageLock.blocksWrites(locked: watchlistLocked) }

    /// Schreibweg des Nutzers ablehnen? true = gesperrt, der Aufrufer schreibt nichts. Mit `notify` zählt die Ablehnung
    /// als Hinweis (gedrosselt, `watchlistLockedHints`). Nur Schreibwege des Nutzers fragen hier; Kurse, Alarmzustand und
    /// Widget schreiben weiter (`saveSnapshotInBackground` lässt die gesperrte Datei ohnehin unberührt).
    @discardableResult
    func watchlistWriteRefused(notify: Bool = true, now: Int64 = WatchlistWriteRefusal.nowMillis) -> Bool {
        guard watchlistWritesBlocked else { return false }
        if notify, WatchlistWriteRefusal.shouldNotify(lastAt: watchlistLockedHintAt, now: now) {
            watchlistLockedHintAt = now
            watchlistLockedHints &+= 1
        }
        return true
    }
}
