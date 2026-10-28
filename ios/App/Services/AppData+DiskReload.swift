import Foundation

// Merkliste beim Wechsel nach vorne im Hintergrund neu lesen (Teil von `AppData`): Lesen, Entschlüsseln und Dekodieren
// von `watchlist.json` liefen sonst auf dem Haupt-Thread und könnten über `NSFileCoordinator` auf das Widget warten.
// Vor dem Lesen beim Start (AppData+SnapshotLoad.swift) wartet ein Neulesen darauf: erst danach gibt es einen Stand im
// Speicher, mit dem verglichen und zusammengeführt wird.

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
        // Stand beim Start noch nicht gelesen: danach (sonst läse `prepareDiskReload` ihn hier sofort auf Main)
        guard snapshotLoaded else {
            whenSnapshotLoaded { [weak self] in self?.reloadFromDiskInBackground(retries: retries) }
            return
        }
        guard prepareDiskReload() else { return }
        // Einstellungen und geschützte Dateien (Verschlüsselung nachholen) gleich hier, vor dem Lesen: Die Aufrufe nach dem
        // Wechsel nach vorne nutzen die Einstellungen sofort
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

    /// Stand von der Platte neu laden und abwarten (z. B. nachdem Hintergrund oder Widget gespeichert haben),
    /// ohne den Haupt-Thread zu blockieren: `readSnapshot` wartet die Schreib-Warteschlange synchron ab, und
    /// ein Schreiben kann über `NSFileCoordinator` auf das Widget warten — darum Abwarten und Lesen auf `diskReader`,
    /// übernommen auf dem Haupt-Thread. Wird inzwischen im Speicher geändert, gilt der Speicher (einmal neu gelesen),
    /// wie beim Wechsel nach vorne. Vor dem Lesen beim Start wartet es dieses ab (`waitForSnapshot`).
    func reloadFromDisk() async {
        await waitForSnapshot()
        for attempt in 0...1 {
            // Vor jedem Lesen (auch dem zweiten): ausstehende Speicherungen nachholen, wie `reloadFromDiskInBackground`
            guard prepareDiskReload() else { return }
            diskReloadSequence &+= 1
            let ticket = DiskReloadTicket(sequence: diskReloadSequence, generation: snapshotGeneration, reset: resetGeneration)
            let (read, keyAvailable) = await Self.readOffMain()
            switch ticket.verdict(sequence: diskReloadSequence, generation: snapshotGeneration, reset: resetGeneration) {
            case .apply:
                applyDiskRead(read, keyAvailable: keyAvailable)
                return
            case .stale:
                if attempt == 1 { applyDiskRead(nil, keyAvailable: keyAvailable) }
            case .superseded:
                return
            }
        }
    }

    /// Merkliste auf `diskReader` lesen (wartet dort die eingereihten Schreibvorgänge ab, nie auf dem Haupt-Thread).
    nonisolated private static func readOffMain() async -> (SharedStorage.SnapshotRead, Bool) {
        await withCheckedContinuation { continuation in
            diskReader.async {
                continuation.resume(returning: (SharedStorage.readSnapshot(), StorageKey.shared.isAvailable))
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
