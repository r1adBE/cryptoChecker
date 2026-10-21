import Foundation

// Datei der beobachteten Adressen in der App Group — Typen in WatchedAddress.swift.

/// Inhalt von `watched_addresses.json`.
struct WatchedAddressFile: Codable, Equatable {
    var addresses: [WatchedAddress] = []
    var nextId: Int64 = 1

    init(addresses: [WatchedAddress] = [], nextId: Int64 = 1) {
        self.addresses = addresses
        self.nextId = nextId
    }

    private enum CodingKeys: String, CodingKey { case addresses, nextId }

    /// Ein unlesbarer Eintrag (z. B. unbekanntes Netz einer neueren Version) wird übersprungen, statt die ganze Datei
    /// unlesbar zu machen — wie Android (`mapNotNull`); die Id läuft nie hinter vergebene Ids zurück.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        addresses = try SharedStorage.lenientArray(c, .addresses, decoder: decoder) ?? []
        let stored = (try? c.decodeIfPresent(Int64.self, forKey: .nextId)) ?? 1
        nextId = SharedStorage.nextId(stored: stored, used: addresses.map(\.id))
    }
}

/// Speicher der beobachteten Adressen: eigene JSON-Datei in der App Group (wie Android die Tabelle
/// `watched_addresses` in der Portfolio-Datenbank). Zugriff über `AppData` (`watchedAddresses`).
///
/// Sicherung: Adressen sind Nutzerdaten und gehören in die App-Sicherung (`WatchedAddressRules.backupEntries`
/// unter `backupKey`). Für iCloud-/Geräte-Backups gilt wie für `portfolio.json` der Schalter «Portfolio in
/// Systemsicherung» (`url` steht in `PortfolioStore.backupPolicyURLs`).
enum WatchedAddressStore {
    static var url: URL { SharedStorage.directory.appendingPathComponent("watched_addresses.json") }

    /// Fehlt die Datei: leer. Unlesbar: leer und `intact` false (dann nicht überschreiben). Einzelne unlesbare Einträge
    /// (z. B. Netz einer neueren Version): die lesbaren zeigen, aber `intact` false — sonst ginge der übersprungene Eintrag
    /// beim nächsten Speichern endgültig verloren (Android behält die Zeile in der Datenbank ohnehin).
    static func loadChecked() -> (file: WatchedAddressFile, intact: Bool) {
        guard FileManager.default.fileExists(atPath: url.path) else { return (WatchedAddressFile(), true) }
        let dropped = SharedStorage.DropCounter()
        let decoder = JSONDecoder()
        decoder.userInfo[SharedStorage.DropCounter.key] = dropped
        guard let data = try? ProtectedFile.read(url),
              let file = try? decoder.decode(WatchedAddressFile.self, from: data)
        else { return (WatchedAddressFile(), false) }
        return (file, dropped.count == 0)
    }

    static func load() -> WatchedAddressFile { loadChecked().file }

    /// Schreibt atomar und versiegelt (`requireSealed`, wie `PortfolioStore.save`): Adressen und xpub verraten Bestände,
    /// darum ohne Schlüssel `needsKey` statt Klartext — der Aufrufer behält den Stand im Speicher und versucht es erneut.
    /// Setzt danach das Backup-Merkmal neu (`includeInBackup` = «Portfolio in Systemsicherung»).
    @discardableResult
    static func save(_ file: WatchedAddressFile, includeInBackup: Bool, to target: URL = url,
                     keys: StorageKey = .shared) -> PortfolioStore.SaveResult {
        guard let data = try? JSONEncoder().encode(file) else { return .failed }
        do {
            try ProtectedFile.write(data, to: target, keys: keys, requireSealed: true)
        } catch ProtectedFile.ReadError.locked {
            return .needsKey
        } catch {
            return .failed
        }
        BackupExclusion.set(excluded: !includeInBackup, for: target)
        return .saved
    }

    /// Unlesbare Datei vor dem Ersetzen (Wiederherstellen) unverändert daneben legen
    /// (`watched_addresses.unreadable.json`, wie `PortfolioStore.preserveUnreadable`) — verschoben, nicht
    /// gelesen: geht auch bei kaputtem Inhalt, die Bytes bleiben exakt. Eine ältere daneben gelegte Datei
    /// wird ersetzt. false = keine Datei da oder nicht verschiebbar (dann nicht schreiben).
    static func setAsideUnreadable(at file: URL = url) -> Bool {
        let fm = FileManager.default
        guard fm.fileExists(atPath: file.path) else { return false }
        let aside = SharedStorage.unreadableCopyURL(of: file)
        try? fm.removeItem(at: aside)
        return (try? fm.moveItem(at: file, to: aside)) != nil
    }

    /// Rückrollen eines Ersetzens über eine unlesbare Datei: die daneben gelegte Datei unverändert zurück an
    /// ihren Platz (nie den Ersatzstand zurückschreiben). true = zurückgelegt.
    @discardableResult
    static func putBackUnreadable(at file: URL = url) -> Bool {
        let fm = FileManager.default
        let aside = SharedStorage.unreadableCopyURL(of: file)
        guard fm.fileExists(atPath: aside.path) else { return false }
        try? fm.removeItem(at: file)
        return (try? fm.moveItem(at: aside, to: file)) != nil
    }

    /// Hinzufügen (wie `INSERT … IGNORE`): doppelte Adresse oder volle Liste → nil, sonst neue Id.
    @discardableResult
    static func insert(_ address: WatchedAddress, into file: inout WatchedAddressFile) -> Int64? {
        guard WatchedAddressRules.canAdd(file.addresses),
              !WatchedAddressRules.contains(file.addresses, chain: address.chain, address: address.address) else { return nil }
        var item = address
        let maxId = file.addresses.map(\.id).max() ?? 0
        item.id = max(file.nextId, maxId + 1)
        file.addresses.append(item)
        file.nextId = item.id + 1
        return item.id
    }

    static func remove(id: Int64, from file: inout WatchedAddressFile) {
        file.addresses.removeAll { $0.id == id }
    }

    static func setLabel(id: Int64, label: String?, in file: inout WatchedAddressFile) {
        guard let i = file.addresses.firstIndex(where: { $0.id == id }) else { return }
        file.addresses[i].label = WatchedAddressRules.cleanLabel(label)
    }

    /// Ergebnis eines Abrufs übernehmen: Erfolg setzt Guthaben, Zeit und Anbieter und löscht den Fehler;
    /// ein Fehler lässt das letzte Guthaben stehen. Wie `WatchedAddressDao.saveBalance` / `saveError`.
    static func apply(_ update: WatchedAddressSource.Update, to file: inout WatchedAddressFile) {
        guard let i = file.addresses.firstIndex(where: { $0.id == update.id }) else { return }
        if let fetched = update.fetched {
            // Guthaben, Beobachtungen (nur bei geändertem Guthaben), Token und xpub-Zwischenspeicher — wie Android
            file.addresses[i] = WatchedAddressRules.applyFetched(file.addresses[i], time: update.time,
                                                                 balance: fetched.balance, provider: fetched.provider,
                                                                 tokens: fetched.tokens, xpub: fetched.xpub,
                                                                 networks: fetched.networks, unknownTokens: fetched.unknownTokens,
                                                                 keptTokens: fetched.keptTokens)
        } else {
            file.addresses[i] = WatchedAddressRules.applyError(file.addresses[i], time: update.time,
                                                               error: update.error ?? "unknown")
        }
    }

    /// Eine Adresse ändern (Zusatzdaten: «Tokens mitzählen», Netze, «Nur anzeigen»).
    static func change(id: Int64, in file: inout WatchedAddressFile, _ transform: (WatchedAddress) -> WatchedAddress) {
        guard let i = file.addresses.firstIndex(where: { $0.id == id }) else { return }
        file.addresses[i] = transform(file.addresses[i])
    }
}
