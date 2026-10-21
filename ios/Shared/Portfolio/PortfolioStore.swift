import Foundation

/// Speicher der Portfolio-Transaktionen: eigene JSON-Datei in der App Group
/// (getrennt vom Merklisten-Snapshot, den auch die Widgets lesen und schreiben).
/// Entspricht `PortfolioDao` + Teilen von `PortfolioRepository`.
enum PortfolioStore {
    static var url: URL { SharedStorage.directory.appendingPathComponent("portfolio.json") }

    /// Merker: alter Bestand der Merkliste wurde übernommen (wie Android «holdings_migrated»).
    private static let migratedKey = "holdings_migrated"

    static func load() -> PortfolioFile { loadChecked().file }

    /// Wie `load`, dazu ob die Datei ganz gelesen wurde: fehlt sie, ist das ein leeres Portfolio (`intact`);
    /// ist sie da, aber gerade nicht lesbar (z. B. Datenschutz vor dem ersten Entsperren), kaputt oder mit
    /// verworfenen Einträgen, ist `intact` false — dann darf sie nicht einfach überschrieben werden.
    static func loadChecked() -> (file: PortfolioFile, intact: Bool) {
        guard FileManager.default.fileExists(atPath: url.path) else { return (PortfolioFile(), true) }
        // Verschlüsselt (Build 59): ohne Schlüssel oder beschädigt ebenfalls «nicht intakt»
        guard let data = try? ProtectedFile.read(url),
              let file = try? JSONDecoder().decode(PortfolioFile.self, from: data)
        else { return (PortfolioFile(), false) }
        return (file, file.dropped == 0)
    }

    /// Nicht (ganz) lesbare Datei vor dem Überschreiben daneben sichern (`portfolio.unreadable.json`, gleiche
    /// Backup-Regel). false = nicht einmal die Bytes lesbar (gesperrt) — dann nicht schreiben.
    @discardableResult
    static func preserveUnreadable(includeInBackup: Bool) -> Bool {
        let data: Data
        do {
            data = try ProtectedFile.read(url)
        } catch ProtectedFile.ReadError.damaged(let raw) {
            data = raw // beschädigt verschlüsselt: Bytes unverändert sichern
        } catch {
            // Nicht lesbar (gesperrt, ohne Schlüssel) oder weg
            return !FileManager.default.fileExists(atPath: url.path)
        }
        let copy = SharedStorage.unreadableCopyURL(of: url)
        do {
            try ProtectedFile.writeCopy(data, to: copy)
        } catch {
            return false
        }
        BackupExclusion.set(excluded: !includeInBackup, for: copy)
        return true
    }

    /// Ergebnis von `save`.
    enum SaveResult: Equatable {
        case saved
        /// Kein Schlüssel (Schlüsselbund gerade nicht lesbar): nicht geschrieben, nie im Klartext — der Aufrufer behält
        /// den Stand und versucht es beim nächsten Speichern bzw. wenn die App wieder vorne ist.
        case needsKey
        case failed
    }

    /// Ohne Schlüssel (`needsKey`) den Stand nur im Speicher behalten und als gespeichert melden? Nur, solange keine Datei
    /// da ist (nichts gespeichert — wie Android «nur im Arbeitsspeicher» mit Hinweis). Liegt ein gespeicherter Stand in der
    /// Datei, nicht: die Änderung gälte als gespeichert und ginge mit dem Prozess verloren (wie Android `PortfolioStorageLock`).
    static func keepsUnsealed(storedFileExists: Bool) -> Bool { !storedFileExists }

    /// Schreibt die Datei versiegelt (`requireSealed`) und setzt danach das Backup-Merkmal neu (das atomare Schreiben
    /// ersetzt die Datei und verliert es). `includeInBackup` = Einstellung «Portfolio in Systemsicherung».
    @discardableResult
    static func save(_ file: PortfolioFile, includeInBackup: Bool, keys: StorageKey = .shared) -> SaveResult {
        guard let data = try? JSONEncoder().encode(file) else { return .failed }
        do {
            try ProtectedFile.write(data, to: url, keys: keys, requireSealed: true)
        } catch ProtectedFile.ReadError.locked {
            return .needsKey
        } catch {
            return .failed
        }
        BackupExclusion.set(excluded: !includeInBackup, for: url)
        return .saved
    }

    /// Dateien mit Portfolio-Daten, die dem Schalter folgen: Transaktionen, Portfolio-Alarme
    /// (`SharedStorage`), die gesicherte Kopie einer unlesbaren Alarm-Datei und die beobachteten
    /// Adressen (`WatchedAddressStore`, wie Android in der Portfolio-Datenbank).
    static var backupPolicyURLs: [URL] {
        let alarms = SharedStorage.portfolioAlarmsURL
        return [url, SharedStorage.unreadableCopyURL(of: url), alarms, SharedStorage.unreadableCopyURL(of: alarms),
                WatchedAddressStore.url]
    }

    /// «Portfolio in Systemsicherung» (wie Android, Standard aus): aus = Portfolio-Daten nicht in
    /// iCloud-/Geräte-Backups (`backupPolicyURLs`). Beim Start und beim Umschalten aufrufen; die
    /// Schreibstellen setzen das Merkmal nach jedem Schreiben selbst neu.
    /// Die Momentaufnahme des Portfolio-Widgets ist nie im Backup (wie Android `portfolio_widget`); beim
    /// Start zieht sie zudem aus den App-Group-Einstellungen (die sich nicht ausnehmen lassen) in ihre Datei.
    /// Ebenso die gemerkten Kurs-Stände, Stundenkurse, Kerzen-Abrufe und Tageskurse des Widgets (`historyURL`,
    /// `hourlyURL`, `candleAttemptsURL`, `PortfolioWidgetDailyStore.url`; Letztere auch nach jedem Schreiben).
    static func applyBackupPolicy(includeInBackup: Bool) {
        PortfolioWidgetStore.migrateSnapshotToFile()
        PortfolioWidgetStore.migrateCachesToFiles()
        // Dazu die gemerkten Kurse des Portfolio-Tabs (`PortfolioPriceSource.storeURL`, nennen die Coins)
        let widgetFiles = [PortfolioWidgetStore.snapshotURL, PortfolioWidgetStore.historyURL,
                           PortfolioWidgetStore.hourlyURL, PortfolioWidgetStore.candleAttemptsURL,
                           PortfolioWidgetDailyStore.url, PortfolioPriceSource.storeURL]
        for file in widgetFiles {
            BackupExclusion.set(excluded: true, for: file)
        }
        // Noch unverschlüsselt (Versiegeln beim Start gescheitert): auch eingeschaltet nicht ins Backup (seit Build 93)
        for file in backupPolicyURLs {
            let plain = FileManager.default.fileExists(atPath: file.path) && !ProtectedStorage.isSealedFile(file)
            BackupExclusion.set(excluded: PlainBackupExclusion.policyExcluded(includeInBackup: includeInBackup, plain: plain),
                                for: file)
        }
    }

    static var holdingsMigrated: Bool {
        get { SharedStorage.defaults.bool(forKey: migratedKey) }
        set { SharedStorage.defaults.set(newValue, forKey: migratedKey) }
    }

    /// Neueste zuerst (Zeit, dann Id absteigend) — wie `ORDER BY time DESC, id DESC`.
    static func newestFirst(_ list: [PortfolioTx]) -> [PortfolioTx] {
        list.sorted { $0.time != $1.time ? $0.time > $1.time : $0.id > $1.id }
    }

    /// Einfügen wie `INSERT … REPLACE`: Id 0 bekommt eine neue Id, eine
    /// vorhandene Id ersetzt den Eintrag. Liefert die Id.
    @discardableResult
    static func insert(_ tx: PortfolioTx, into file: inout PortfolioFile) -> Int64 {
        var item = tx
        let maxId = file.transactions.map(\.id).max() ?? 0
        if item.id <= 0 {
            item.id = max(file.nextId, maxId + 1)
        }
        if let i = file.transactions.firstIndex(where: { $0.id == item.id }) {
            file.transactions[i] = item
        } else {
            file.transactions.append(item)
        }
        file.nextId = max(file.nextId, item.id + 1, maxId + 1)
        return item.id
    }

    /// Ändern wie `@Update`: nur wenn die Id existiert.
    @discardableResult
    static func update(_ tx: PortfolioTx, in file: inout PortfolioFile) -> Bool {
        guard let i = file.transactions.firstIndex(where: { $0.id == tx.id }) else { return false }
        file.transactions[i] = tx
        return true
    }

    /// Wandelt Bestände der Merkliste in Käufe ohne Preis um (Mengen gleicher
    /// Coins addiert) und leert sie — wie `PortfolioRepository.importHoldings`.
    /// Mit `onlyNewCoins` nur für Coins, die noch keine Transaktion haben
    /// (Wiederherstellen einer alten Sicherung — sonst doppelt).
    /// Ohne gültigen Bestand bleibt alles unverändert.
    /// - Returns: Anzahl angelegter Käufe.
    @discardableResult
    static func importHoldings(watches: inout [Watch], into file: inout PortfolioFile,
                               onlyNewCoins: Bool, now: Int64) -> Int {
        var order: [String] = []
        // Exakt summiert (`PortfolioDecimal`); der alte Merklisten-Bestand war ein Double
        var amounts: [String: Decimal] = [:]
        for w in watches {
            guard let amount = w.holdings, amount > 0, !amount.isInfinite,
                  let exact = PortfolioDecimal.of(amount) else { continue }
            let coin = PortfolioCalculator.normalizeCoin(w.baseAsset)
            guard !coin.isEmpty else { continue }
            if amounts[coin] == nil { order.append(coin) }
            amounts[coin, default: 0] += exact
        }
        guard !order.isEmpty else { return 0 }

        let existing: Set<String> = onlyNewCoins
            ? Set(file.transactions.map { PortfolioCalculator.normalizeCoin($0.coin) })
            : []
        var created = 0
        for coin in order where !existing.contains(coin) {
            guard let amount = amounts[coin] else { continue }
            insert(PortfolioTx(coin: coin, type: .BUY, amountExact: amount, priceExact: nil, time: now), into: &file)
            created += 1
        }
        for i in watches.indices { watches[i].holdings = nil }
        return created
    }
}
