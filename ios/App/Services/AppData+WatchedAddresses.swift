import Foundation

/// Ergebnis von `AppData.addWatchedAddress` — wie `PortfolioRepository.AddWatchedResult` (Android).
enum AddWatchedResult: Equatable {
    case added(Int64)
    /// Ungültig (Prüfsumme, Netz, privater Schlüssel …).
    case invalid(AddressValidation.Problem)
    case duplicate
    /// Schon `WatchedAddressRules.maxAddresses` Adressen.
    case limitReached
    /// Gespeichertes Portfolio gerade gesperrt (`PortfolioStorageLock`): nichts geschrieben.
    case locked

    /// Haptik zum Ergebnis: angelegt = Erfolg, Eingabe- bzw. Grenzfehler = Warnung, nicht geschrieben = Fehler —
    /// wie Android `addWatchedHaptic`.
    var haptic: HapticEvent {
        switch self {
        case .added: return .success
        case .invalid, .duplicate, .limitReached: return .warning
        case .locked: return .error
        }
    }
}

/// Ergebnis von `AppData.removeWatchedAddress` — wie Android `removeWatchedAddress` (Zeile bzw. Sperre).
enum RemoveWatchedResult: Equatable {
    /// Entfernt; die ganze Adresse (Eröffnungsposten, Verlauf, Token-Reihen) für «Rückgängig».
    case removed(WatchedAddress)
    /// Gespeichertes Portfolio gerade nicht lesbar (`PortfolioStorageLock`): nichts entfernt.
    case locked
    /// Nicht geschrieben (Datei nicht beschreibbar) — die Oberfläche nennt es (wie Android `onFailed`).
    case failed
    /// Nicht (mehr) da, z. B. schon entfernt: nichts zu melden.
    case notFound
}

/// Beobachtete Adressen (nur lesen; Guthaben = Bestand) — wie die Adress-Funktionen in
/// `PortfolioRepository` (Android). Gespeichert in `watched_addresses.json` (`WatchedAddressStore`),
/// Backup-Merkmal nach «Portfolio in Systemsicherung».
@MainActor
extension AppData {

    /// Reihenfolge des Hinzufügens, dann Id — wie `ORDER BY addedAt, id`.
    nonisolated static func sortedWatched(_ list: [WatchedAddress]) -> [WatchedAddress] {
        list.sorted { ($0.addedAt, $0.id) < ($1.addedAt, $1.id) }
    }

    /// Gespeichertes Portfolio gerade gesperrt (`PortfolioStorageLock`): Adressen hinzufügen, ändern, entfernen und
    /// Einstandsposten ablehnen — wie Android `PortfolioRepository.ensureWritable`. Guthaben-Abrufe nicht.
    var portfolioStorageLocked: Bool {
        let files = FileManager.default
        let stored = files.fileExists(atPath: PortfolioStore.url.path) || files.fileExists(atPath: WatchedAddressStore.url.path)
        return PortfolioStorageLock.isLocked(keyAvailable: StorageKey.shared.isAvailable, storedFileExists: stored)
    }

    /// Prüft und normalisiert `address` (`AddressValidation.check` mit `chain`), dann höchstens
    /// `WatchedAddressRules.maxAddresses` und keine doppelte Adresse. Bezeichnung zugeschnitten.
    @discardableResult
    func addWatchedAddress(chain: AddressChain, address: String, label: String?,
                           countInPortfolio: Bool = true, overlapChecked: Bool = false) -> AddWatchedResult {
        guard !portfolioStorageLocked else { return .locked }
        let check = AddressValidation.check(address, expected: chain)
        guard check.problem == nil, let normalized = check.normalized else {
            return .invalid(check.problem ?? .UNKNOWN_FORMAT)
        }
        var file = currentWatchedAddressFile()
        if WatchedAddressRules.contains(file.addresses, chain: chain, address: normalized) { return .duplicate }
        guard WatchedAddressRules.canAdd(file.addresses) else { return .limitReached }
        // «Nur anzeigen» bei Überschneidung mit eigenen Transaktionen (Frage beim Hinzufügen)
        let item = WatchedAddress(chain: chain, address: normalized,
                                  label: WatchedAddressRules.cleanLabel(label), addedAt: EpochTime.nowMillis,
                                  countInPortfolio: countInPortfolio, overlapChecked: overlapChecked)
        guard let id = WatchedAddressStore.insert(item, into: &file) else { return .duplicate }
        guard setWatchedAddressFile(file) else { return .limitReached }
        return .added(id)
    }

    /// Entfernen (nach Rückfrage); gibt die ganze Adresse für «Rückgängig» zurück (`restoreWatchedAddress`). Gesperrt:
    /// `.locked` — die Oberfläche nennt es, statt still nichts zu tun.
    @discardableResult
    func removeWatchedAddress(_ id: Int64) -> RemoveWatchedResult {
        guard !portfolioStorageLocked else { return .locked }
        var file = currentWatchedAddressFile()
        guard let removed = file.addresses.first(where: { $0.id == id }) else { return .notFound }
        WatchedAddressStore.remove(id: id, from: &file)
        return setWatchedAddressFile(file) ? .removed(removed) : .failed
    }

    /// «Rückgängig» nach `removeWatchedAddress`: dieselbe Adresse mit alter Id samt Eröffnungsposten, Verlauf und
    /// Token-Reihen (`WatchedAddressUndo.restore`) — wie Android `restoreWatchedAddress`. Gesperrt: `.locked`.
    /// Mit `pendingSince` = jetzt: wieder volle 24 h zum Laden, bevor sie als fehlgeschlagen gilt.
    @discardableResult
    func restoreWatchedAddress(_ removed: WatchedAddress) -> RemoveWatchedResult {
        guard !portfolioStorageLocked else { return .locked }
        var file = currentWatchedAddressFile()
        var again = removed
        again.pendingSince = EpochTime.nowMillis
        guard WatchedAddressUndo.restore(again, into: &file), setWatchedAddressFile(file) else { return .failed }
        return .removed(removed)
    }

    /// «Tokens mitzählen» einer Adresse (`WatchedAddressRules.withCountTokens`: aus löscht die Token-Reihen, an macht
    /// die Adresse sofort fällig) — wie `PortfolioRepository.setWatchedAddressCountTokens` (Android).
    func setWatchedAddressCountTokens(_ id: Int64, on: Bool) {
        changeWatchedAddress(id) { WatchedAddressRules.withCountTokens($0, on) }
    }

    /// Gewählte EVM-Netze einer 0x-Adresse (`WatchedAddressRules.withNetworks`) — wie Android.
    func setWatchedAddressNetworks(_ id: Int64, networks: [String]) {
        changeWatchedAddress(id) { WatchedAddressRules.withNetworks($0, networks) }
    }

    /// «Nur anzeigen, nicht zum Portfolio zählen» (false) bzw. zählen (true) — wie Android.
    func setWatchedAddressCountInPortfolio(_ id: Int64, on: Bool) {
        changeWatchedAddress(id) { WatchedAddressRules.withCountInPortfolio($0, on) }
    }

    /// Später aufgetauchte Überschneidung mit eigenen Transaktionen (`ownCoins`): einmal automatisch «Nur anzeigen»
    /// (`WatchedAddressRules.overlapAutoShowOnly`); Rückgabe = umgestellte Adressen (Stand vorher) — wie Android
    /// `PortfolioViewModel` mit `setWatchedAddressOverlapShowOnly`.
    func applyWatchedAddressOverlapShowOnly(txs: [PortfolioTx]) -> [WatchedAddress] {
        var file = currentWatchedAddressFile()
        let due = WatchedAddressRules.overlapAutoShowOnly(file.addresses.filter { !$0.overlapLegacy }, txs: txs)
        guard !due.isEmpty else { return [] }
        for a in due { WatchedAddressStore.change(id: a.id, in: &file) { WatchedAddressRules.withOverlapShowOnly($0) } }
        guard setWatchedAddressFile(file) else { return [] }
        return due
    }

    /// Alte Überschneidungs-Entscheide (früher genügte derselbe Coin) einmal übernehmen: «Nur anzeigen» ohne passende Menge
    /// zählt wieder (`WatchedAddressRules.overlapLegacyRevert`); Rückgabe = wieder zählende Adressen (Stand vorher) — wie
    /// Android `PortfolioViewModel` mit `migrateWatchedAddressOverlap`.
    func migrateWatchedAddressOverlap(txs: [PortfolioTx]) -> [WatchedAddress] {
        var file = currentWatchedAddressFile()
        let legacy: [WatchedAddress] = file.addresses.filter(\.overlapLegacy)
        guard !legacy.isEmpty else { return [] }
        var restored: [WatchedAddress] = []
        for a in legacy {
            let revert: Bool = WatchedAddressRules.overlapLegacyRevert(a, txs: txs)
            if revert { restored.append(a) }
            WatchedAddressStore.change(id: a.id, in: &file) { WatchedAddressRules.withOverlapMigrated($0, revert: revert) }
        }
        guard setWatchedAddressFile(file) else { return [] }
        return restored
    }

    /// Eröffnungsposten einer Adresse ersetzen (geprüft, `AddressCostLots.clean`) — wie
    /// `PortfolioRepository.setWatchedAddressCostLots` (Android). Gleiche Datei und Versiegelung wie die Adressen.
    func setWatchedAddressCostLots(_ id: Int64, lots: [AddressCostLot]) {
        changeWatchedAddress(id) { a in
            var out = a
            out.costLots = AddressCostLots.clean(lots)
            return out
        }
    }

    /// Nur Änderungen des Nutzers (gesperrt abgelehnt); automatische Umstellungen schreiben selbst.
    private func changeWatchedAddress(_ id: Int64, _ transform: (WatchedAddress) -> WatchedAddress) {
        guard !portfolioStorageLocked else { return }
        var file = currentWatchedAddressFile()
        WatchedAddressStore.change(id: id, in: &file, transform)
        setWatchedAddressFile(file)
    }

    func setWatchedAddressLabel(_ id: Int64, label: String?) {
        guard !portfolioStorageLocked else { return }
        var file = currentWatchedAddressFile()
        WatchedAddressStore.setLabel(id: id, label: label, in: &file)
        setWatchedAddressFile(file)
    }

    /// Mehrere Abrufe auf einmal übernehmen (eine Schreibung).
    func applyWatchedAddressUpdates(_ updates: [WatchedAddressSource.Update]) {
        guard !updates.isEmpty else { return }
        var file = currentWatchedAddressFile()
        for update in updates { WatchedAddressStore.apply(update, to: &file) }
        setWatchedAddressFile(file)
    }

    /// Wiederherstellen (eigene Sicherung): ersetzen — alle bisherigen Adressen weg, dann die geprüften neuen.
    /// Auch über eine unlesbare Datei (`replaceWatchedAddressFile`), sonst scheiterte jedes Wiederherstellen.
    /// - Returns: Anzahl übernommener Adressen; nil, wenn nicht geschrieben.
    @discardableResult
    func replaceWatchedAddresses(_ entries: [WatchedAddressBackupEntry]) -> Int? {
        guard !portfolioStorageLocked else { return nil }
        let current = currentWatchedAddressFile()
        var file = WatchedAddressFile(addresses: [], nextId: current.nextId)
        let added = insertWatched(WatchedAddressRules.merge([], entries), into: &file)
        return replaceWatchedAddressFile(file) ? added : nil
    }

    /// Ganz ersetzen: braucht den alten Inhalt nicht. Ist die Datei unlesbar, wird sie vorher unverändert
    /// daneben gelegt (`WatchedAddressStore.setAsideUnreadable`) und gilt nach dem Schreiben wieder als intakt;
    /// scheitert das Schreiben, kommt sie unverändert zurück.
    private func replaceWatchedAddressFile(_ file: WatchedAddressFile) -> Bool {
        if watchedAddressIntact { return setWatchedAddressFile(file) }
        guard WatchedAddressStore.setAsideUnreadable() else { return false }
        watchedAddressIntact = true
        guard setWatchedAddressFile(file) else {
            WatchedAddressStore.putBackUnreadable()
            watchedAddressIntact = false
            return false
        }
        return true
    }

    /// Wiederherstellen gescheitert (`RestoreSteps`): War die Datei vorher lesbar (`wasIntact`), den alten Stand
    /// zurückschreiben; war sie unlesbar, die daneben gelegte Datei unverändert zurücklegen — nie den leeren
    /// Ersatzstand als «alten» Stand schreiben. Geht das Zurücklegen nicht, bleibt der wiederhergestellte Stand
    /// (die unlesbare Datei liegt weiter daneben).
    func rollBackWatchedAddresses(_ old: WatchedAddressFile, wasIntact: Bool) {
        if wasIntact {
            setWatchedAddressFile(old)
            return
        }
        guard WatchedAddressStore.putBackUnreadable() else { return }
        watchedAddressIntact = false
        watchedAddressFile = WatchedAddressFile()
        watchedAddresses = []
    }

    /// Wiederherstellen (eigene Sicherung): ergänzen — vorhandene bleiben, neue ohne Doppelte bis zur Höchstzahl.
    @discardableResult
    func mergeWatchedAddresses(_ entries: [WatchedAddressBackupEntry]) -> Int? {
        guard !portfolioStorageLocked else { return nil }
        var file = currentWatchedAddressFile()
        let added = insertWatched(WatchedAddressRules.merge(file.addresses, entries), into: &file)
        guard added > 0 else { return 0 }
        return setWatchedAddressFile(file) ? added : nil
    }

    private func insertWatched(_ list: [WatchedAddress], into file: inout WatchedAddressFile) -> Int {
        list.reduce(0) { count, item in WatchedAddressStore.insert(item, into: &file) == nil ? count : count + 1 }
    }

    /// Stand zum Ändern: war die Datei nicht lesbar, zuerst neu lesen (z. B. jetzt entsperrt).
    func currentWatchedAddressFile() -> WatchedAddressFile {
        if !watchedAddressIntact {
            let loaded = WatchedAddressStore.loadChecked()
            if loaded.intact {
                watchedAddressFile = loaded.file
                watchedAddressIntact = true
                watchedAddresses = Self.sortedWatched(loaded.file.addresses)
                // Datei wieder lesbar: «Portfolio wieder an» nachholen, falls aufgeschoben
                recoverPortfolioIfDeferred()
            }
        }
        return watchedAddressFile
    }

    /// Erst schreiben, dann anzeigen. Nicht lesbare Datei wird nie überschrieben (false). Ohne Schlüssel (nie Klartext,
    /// wie `setPortfolioFile`): Stand im Speicher behalten und das nennen — das nächste Speichern bzw. die Rückkehr der
    /// App nach vorne schreibt ihn (`retryPendingPortfolioSaves`).
    @discardableResult
    func setWatchedAddressFile(_ file: WatchedAddressFile) -> Bool {
        guard watchedAddressIntact else { return false }
        switch WatchedAddressStore.save(file, includeInBackup: settings.portfolioSystemBackup) {
        case .saved:
            watchedAddressSavePending = false
            // Wartete sonst nichts mehr, verschwindet der Hinweis (prüft Portfolio und Portfolio-Alarme erneut)
            if portfolioSavePending { retryPendingPortfolioSaves() }
        case .needsKey:
            watchedAddressSavePending = true
            portfolioSavePending = true
        case .failed:
            return false
        }
        watchedAddressFile = file
        watchedAddresses = Self.sortedWatched(file.addresses)
        return true
    }
}
