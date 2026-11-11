import Foundation

// Schritte von `BackupManager.restore` — wie `readRestoreInput`, `previousState` und `writeRestored` in `BackupManager.kt`
// (Android): erst alles lesen und prüfen, bisherigen Stand merken, dann schreiben (mit Journal und Zurückrollen).

extension BackupManager {
    /// Inhalt der Sicherung, gelesen und geprüft (`readRestoreInput`).
    struct RestoreInput {
        let watchlist: RestoredWatchlist
        let favorites: [FavoriteKind: Set<String>]
        let settingsJson: [String: Any]
        let newSettings: AppSettings
        let lock: BackupRestorePolicy.AppLockDecision
        let serverNote: BackupRestorePolicy.AddressServerNote?
        let portfolio: [PortfolioTx]
        let portfolioAlarms: [PortfolioAlarm]
        let watched: [WatchedAddressBackupEntry]
    }

    /// Stand vor dem Wiederherstellen, für das Zurückrollen (`previousState`).
    struct PreviousState {
        let snapshot: SharedStorage.Snapshot
        let settings: AppSettings
        let favorites: [FavoriteKind: Set<String>]
        let columnSort: String?
        let portfolio: PortfolioFile
        let watched: WatchedAddressFile
        /// Unlesbare Datei: Rückrollen legt sie unverändert zurück statt den leeren Stand zu schreiben.
        let watchedIntact: Bool
    }

    /// Liest die ganze Sicherung, bevor etwas geschrieben wird; mehr ungültige Einträge als bestätigt →
    /// `Failure.unconfirmedInvalid`. `limits`: mit `BackupRestoreLimits` (aus beim Zurückrollen eines abgebrochenen Laufs).
    @MainActor
    static func readRestoreInput(_ root: [String: Any], data: AppData, acceptInvalid: Int, encrypted: Bool,
                                 limits: Bool) throws -> RestoreInput {
        // Merkliste und Alarme lesen, bereinigen und begrenzen (`restoredWatchlist`, wie Android): Paare mit unzulässigen
        // Kennungen und über den Grenzen fallen weg, gezählt für die Erfolgsmeldung
        let watchlist = try restoredWatchlist(root, limits: limits)

        // Je Art eine Liste von Texten (`BackupFields.checkParts`)
        var favorites: [FavoriteKind: Set<String>] = [:]
        let fav: [String: Any] = try part(root, "favorites")
        for kind in FavoriteKind.allCases {
            let list: [String] = try part(fav, kind.rawValue)
            favorites[kind] = Set(list)
        }

        let settingsJson: [String: Any] = try part(root, "settings")
        var newSettings = try applySettings(settingsJson, onto: data.settings, trusted: encrypted)
        // Portfolio-Sperre: ohne Passwort keine Echtheitsprüfung — nie ausschalten, solange sie an ist
        let lock = BackupRestorePolicy.appLock(wanted: try part(settingsJson, "appLock"),
                                               current: data.settings.appLock, encrypted: encrypted,
                                               canAuthenticate: AppLock.canAuthenticate())
        if let v = lock.apply { newSettings.appLock = v }
        // Eigener Bitcoin-Server: Hinweis in der Meldung (nicht übernommen, entfernt oder ersetzt)
        let serverNote = BackupRestorePolicy.addressServerNote(
            value: settingsJson["addressServerBtc"] as? String,
            current: data.settings.addressServerBtc, trusted: encrypted)

        // Ungültige Einträge nur mit Bestätigung weglassen (nie still)
        let parsedTx = try parsedPortfolio(root)
        let parsedPfAlarms = try parsedPortfolioAlarms(root)
        let invalid = watchlist.invalidAlarms + parsedTx.invalid + parsedPfAlarms.invalid
        if invalid > acceptInvalid { throw Failure.unconfirmedInvalid(invalid) }
        // Adressen ersetzen wie die Transaktionen (ungültige fallen weg, `WatchedAddressRules.merge`)
        let watched = try watchedEntries(root)
        return RestoreInput(watchlist: watchlist, favorites: favorites, settingsJson: settingsJson, newSettings: newSettings,
                            lock: lock, serverNote: serverNote, portfolio: parsedTx.items, portfolioAlarms: parsedPfAlarms.items,
                            watched: watched)
    }

    /// Bisherigen Stand merken: scheitert ein späterer Schritt (Portfolio, Adressen), wird alles Geschriebene
    /// zurückgesetzt (`RestoreSteps`, wie Android) — nie halb ersetzt.
    @MainActor
    static func previousState(_ data: AppData) -> PreviousState {
        PreviousState(snapshot: data.snapshot, settings: data.settings, favorites: data.favorites,
                      columnSort: UserDefaults.standard.string(forKey: ColumnSort.storageKey),
                      portfolio: data.portfolioFileForRollback(), watched: data.currentWatchedAddressFile(),
                      watchedIntact: data.watchedAddressIntact)
    }

    /// Neuer Stand von Merkliste und Alarmen aus der Sicherung; Zähler der Ids über dem bisherigen Stand `old`.
    @MainActor
    static func restoredSnapshot(_ input: RestoreInput, old: SharedStorage.Snapshot) -> SharedStorage.Snapshot {
        let watches = input.watchlist.watches
        let alarms = input.watchlist.alarms
        let portfolioAlarms = input.portfolioAlarms
        var snapshot = SharedStorage.Snapshot()
        snapshot.watches = watches
        snapshot.alarms = alarms
        // Wiederhergestellte Portfolio-Alarme sind scharf und ohne letzte Meldung
        snapshot.portfolioAlarms = portfolioAlarms.map { a in
            var copy = a
            copy.referenceAt = 0
            copy.lastTriggeredAt = 0
            copy.lastTriggeredValue = nil
            return copy
        }
        // Ids nie wiederverwenden: über dem bisherigen Zähler und über allen neuen ids
        // (`nextId` ohne Überlauf — eine id Int64.max aus der Datei brach mit «max + 1» ab)
        snapshot.nextPortfolioAlarmId = SharedStorage.nextId(stored: old.nextPortfolioAlarmId ?? 1,
                                                             used: (snapshot.portfolioAlarms ?? []).map(\.id))
        snapshot.nextWatchId = SharedStorage.nextId(stored: old.nextWatchId, used: watches.map(\.id))
        snapshot.nextAlarmId = SharedStorage.nextId(stored: old.nextAlarmId, used: alarms.map(\.id))
        return snapshot
    }

    /// Schreibt die gelesene Sicherung; scheitert ein Schritt, wird alles Geschriebene zurückgesetzt und der Fehler
    /// weitergereicht.
    @MainActor
    static func writeRestored(_ input: RestoreInput, snapshot: SharedStorage.Snapshot, previous: PreviousState,
                              into data: AppData, journal: Bool) throws {
        // Journal vor dem ersten Schreiben: bricht die App mittendrin ab, rollt der nächste Start zurück (wie Android).
        // Nie ohne: ein mittendrin beendeter Lauf liesse sonst einen halben Stand ohne Rückweg
        if journal { try requireJournal(try? plainExportData(data), write: { RestoreJournal.write($0) }) }
        var steps = RestoreSteps()
        do {
            steps.run({
                data.replaceAll(snapshot: snapshot, settings: input.newSettings, favorites: input.favorites)
                restoreColumnSort(input.settingsJson)
            }, undo: {
                data.replaceAll(snapshot: previous.snapshot, settings: previous.settings, favorites: previous.favorites)
                if let oldColumnSort = previous.columnSort {
                    UserDefaults.standard.set(oldColumnSort, forKey: ColumnSort.storageKey)
                } else {
                    UserDefaults.standard.removeObject(forKey: ColumnSort.storageKey)
                }
            })
            // Portfolio ersetzen
            // Nicht still: konnte das Portfolio nicht geschrieben werden, meldet das Wiederherstellen einen Fehler
            try steps.run({
                guard data.restorePortfolio(input.portfolio) else { throw Failure.invalid("portfolio") }
            }, undo: { _ = data.rollBackPortfolioFile(previous.portfolio) })
            // Adressen ersetzen wie die Transaktionen (ungültige fallen weg, `WatchedAddressRules.merge`)
            try steps.run({
                guard data.replaceWatchedAddresses(input.watched) != nil else { throw Failure.invalid(WatchedAddressRules.backupKey) }
            }, undo: { data.rollBackWatchedAddresses(previous.watched, wasIntact: previous.watchedIntact) })
        } catch {
            steps.rollBack()
            if journal { RestoreJournal.delete() }
            throw error
        }
        if journal {
            RestoreJournal.delete()
            // Gespeicherte Daten liessen sich nicht entsperren (`StorageRecoveryNotice`): mit dieser Sicherung erledigt
            StorageRecoveryNotice.acknowledge(.setAside)
        }
    }
}
