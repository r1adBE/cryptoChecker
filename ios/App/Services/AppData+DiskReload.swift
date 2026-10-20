import Foundation

// Merkliste beim Wechsel nach vorne im Hintergrund neu lesen (Teil von `AppData`): Lesen, Entschlüsseln und Dekodieren
// von `watchlist.json` liefen bisher auf dem Haupt-Thread und konnten über `NSFileCoordinator` auf das Widget warten.
// `AppData.init` liest weiter synchron: Bis zum ersten Lesen gäbe es sonst keinen Stand, und jedes frühe Speichern
// (Hintergrund-Aufgabe, Widget-Aktion) überschriebe die Datei mit einer leeren Merkliste.

/// Marke eines Lesens im Hintergrund: Übernommen wird nur, was noch zum Stand im Speicher passt.
struct DiskReloadTicket: Equatable {
    /// `AppData.diskReloadSequence` beim Start: Ein später begonnenes Lesen ersetzt dieses.
    let sequence: Int
    /// `AppData.snapshotGeneration` beim Start: Jede Übernahme seither (Ändern, Live-Kurse, synchrones Lesen) macht den
    /// gelesenen Stand alt — er ersetzte sonst die neueren Änderungen im Speicher, und das nächste Speichern verlöre sie.
    let generation: Int
    /// `AppData.resetGeneration` beim Start: «App zurücksetzen» seither → nichts übernehmen.
    let reset: Int

    enum Verdict: Equatable {
        /// Übernehmen.
        case apply
        /// Seither geändert: nicht übernehmen (neu lesen bzw. nur die übrigen Werte).
        case stale
        /// Neueres Lesen läuft oder zurückgesetzt: nichts tun.
        case superseded
    }

    func verdict(sequence currentSequence: Int, generation currentGeneration: Int, reset currentReset: Int) -> Verdict {
        guard sequence == currentSequence, reset == currentReset else { return .superseded }
        return generation == currentGeneration ? .apply : .stale
    }
}

extension AppData {
    /// Eigene Warteschlange zum Lesen; nie `snapshotWriter` (`readSnapshot` wartet dessen Schreiben ab).
    nonisolated private static let diskReader = DispatchQueue(label: "com.cryptochecker.disk-reader", qos: .userInitiated)

    /// Wie `reloadFromDisk`, aber Lesen, Entschlüsseln und Dekodieren auf `diskReader`; übernommen wird auf dem
    /// Haupt-Thread (`applyDiskRead`). Bis dahin zeigt die App den Stand im Speicher. Reihenfolge: `readSnapshot` wartet
    /// alle schon eingereihten Schreibvorgänge ab; wird inzwischen geändert, gilt der Speicher (einmal neu gelesen).
    func reloadFromDiskInBackground(retries: Int = 1) {
        guard prepareDiskReload() else { return }
        // Einstellungen und geschützte Dateien (Verschlüsselung nachholen) gleich hier, vor dem Lesen: Die Aufrufe nach dem
        // Wechsel nach vorne nutzen die Einstellungen sofort (bisher lief das synchron im selben Schritt)
        reloadSettingsIfInaccessible()
        diskReloadSequence &+= 1
        let ticket = DiskReloadTicket(sequence: diskReloadSequence, generation: snapshotGeneration, reset: resetGeneration)
        Self.diskReader.async {
            let read = SharedStorage.readSnapshot()
            let keyAvailable = StorageKey.shared.isAvailable
            Task { @MainActor in
                AppData.shared.finishBackgroundReload(read, keyAvailable: keyAvailable, ticket: ticket, retries: retries)
            }
        }
    }

    private func finishBackgroundReload(_ read: SharedStorage.SnapshotRead, keyAvailable: Bool, ticket: DiskReloadTicket,
                                        retries: Int) {
        switch ticket.verdict(sequence: diskReloadSequence, generation: snapshotGeneration, reset: resetGeneration) {
        case .apply:
            applyDiskRead(read, keyAvailable: keyAvailable)
        case .stale:
            if retries > 0 {
                reloadFromDiskInBackground(retries: retries - 1)
            } else {
                applyDiskRead(nil, keyAvailable: keyAvailable)
            }
        case .superseded:
            return
        }
    }
}
