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
        let serverHost: String?
        let lowered: BackupRestorePolicy.LoweredProtection
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
        /// Beschädigte Datei (R4-02): Rückrollen legt sie unverändert zurück statt den leeren Stand zu schreiben.
        var portfolioIntact = true
        let watched: WatchedAddressFile
        /// Unlesbare Datei: Rückrollen legt sie unverändert zurück statt den leeren Stand zu schreiben.
        let watchedIntact: Bool
        /// `portfolio_alarms.json` beschädigt (R4-02): vor dem Ersetzen unverändert daneben gelegt; Rückrollen schreibt
        /// keine Portfolio-Alarme (leer, wie das Journal), die Datei bleibt daneben.
        var alarmsDamaged = false

        /// Ein beschädigter Teil wurde ersetzt und liegt daneben (Hinweis in der Meldung).
        var damagedSetAside: Bool { !portfolioIntact || !watchedIntact || alarmsDamaged }
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
        // Eigener Bitcoin-Server: Hinweis in der Meldung (nicht übernommen, entfernt, ersetzt oder neu gesetzt — mit Hostnamen)
        let serverValue = settingsJson["addressServerBtc"] as? String
        let serverNote = BackupRestorePolicy.addressServerNote(
            value: serverValue, current: data.settings.addressServerBtc, trusted: encrypted)
        let serverHost = BackupRestorePolicy.addressServerHost(
            value: serverValue, current: data.settings.addressServerBtc, trusted: encrypted)
        // Mit Passwort abgeschalteter Schutz kommt in die Meldung (nie still, wie Android)
        let lowered = BackupRestorePolicy.loweredProtection(
            wantedSystemBackup: try part(settingsJson, "portfolioSystemBackup"),
            currentSystemBackup: data.settings.portfolioSystemBackup,
            wantedHideAmounts: try part(settingsJson, "hidePortfolioAmounts"),
            currentHideAmounts: data.settings.hidePortfolioAmounts, trusted: encrypted,
            wantedAppLock: try part(settingsJson, "appLock"), currentAppLock: data.settings.appLock)

        // Ungültige Einträge nur mit Bestätigung weglassen (nie still)
        let parsedTx = try parsedPortfolio(root)
        let parsedPfAlarms = try parsedPortfolioAlarms(root)
        let invalid = watchlist.invalidAlarms + parsedTx.invalid + parsedPfAlarms.invalid
        if invalid > acceptInvalid { throw Failure.unconfirmedInvalid(invalid) }
        // Adressen ersetzen wie die Transaktionen (ungültige fallen weg, `WatchedAddressRules.merge`)
        let watched = try watchedEntries(root)
        return RestoreInput(watchlist: watchlist, favorites: favorites, settingsJson: settingsJson, newSettings: newSettings,
                            lock: lock, serverNote: serverNote, serverHost: serverHost, lowered: lowered,
                            portfolio: parsedTx.items, portfolioAlarms: parsedPfAlarms.items, watched: watched)
    }

    /// Bisherigen Stand merken: scheitert ein späterer Schritt (Portfolio, Adressen), wird alles Geschriebene
    /// zurückgesetzt (`RestoreSteps`, wie Android) — nie halb ersetzt.
    @MainActor
    static func previousState(_ data: AppData) -> PreviousState {
        PreviousState(snapshot: data.snapshot, settings: data.settings, favorites: data.favorites,
                      columnSort: UserDefaults.standard.string(forKey: ColumnSort.storageKey),
                      portfolio: data.currentPortfolioFile(), portfolioIntact: data.portfolioIntact,
                      watched: data.currentWatchedAddressFile(), watchedIntact: data.watchedAddressIntact,
                      alarmsDamaged: portfolioAlarmsDamaged(data))
    }

    /// `portfolio_alarms.json` da, aber nicht gelesen (`portfolioAlarms` nil) und beschädigt statt nur gesperrt?
    @MainActor
    static func portfolioAlarmsDamaged(_ data: AppData) -> Bool {
        let url = SharedStorage.portfolioAlarmsURL
        return data.snapshot.portfolioAlarms == nil && FileManager.default.fileExists(atPath: url.path)
            && ProtectedFile.unreadableIsDamaged(url)
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

    /// Schreibt die gelesene Sicherung; scheitert ein Schritt oder kommt die Merkliste nicht auf die Platte, wird alles
    /// Geschriebene zurückgesetzt und der Fehler weitergereicht (`RestoreSteps.execute`). Das Journal fällt erst weg,
    /// wenn der neue (bzw. der zurückgeschriebene) Stand der Merkliste wirklich geschrieben ist.
    @MainActor
    static func writeRestored(_ input: RestoreInput, snapshot: SharedStorage.Snapshot, previous: PreviousState,
                              into data: AppData, journal: Bool) throws {
        // Journal vor dem ersten Schreiben: bricht die App mittendrin ab, rollt der nächste Start zurück (wie Android).
        // Nie ohne: ein mittendrin beendeter Lauf liesse sonst einen halben Stand ohne Rückweg
        if journal { try requireJournal(try? journalData(data), write: { RestoreJournal.write($0) }) }
        do {
            try RestoreSteps.execute({ steps in
                try runRestoreSteps(&steps, input, snapshot: snapshot, previous: previous, into: data)
            }, settled: { restoreWritesSettled() }, deleteJournal: { if journal { RestoreJournal.delete() } })
        } catch is RestoreSteps.NotSettled {
            throw Failure.invalid(watchlistStep)
        }
        // Gespeicherte Daten liessen sich nicht entsperren (`StorageRecoveryNotice`): mit dieser Sicherung erledigt
        if journal { StorageRecoveryNotice.acknowledge(.setAside) }
    }

    /// Name des Schritts «Merkliste auf die Platte» in `Failure.invalid` (`recoveryOutcome`: Journal behalten).
    static let watchlistStep = "watchlistWrite"

    /// Scheiterte das Wiederherstellen am Speicher der App (Merkliste bzw. App-Gruppe nicht beschreibbar, `settled`
    /// false)? Dann ist alles Geschriebene zurückgerollt — oder das Journal bleibt und der nächste Start rollt zurück
    /// (`RestoreSteps.execute`): die bisherigen Daten bleiben unverändert. Meldung `backup_restore_storage_unavailable`
    /// statt `backup_failed` — wie Android `BackupRestoreStorageException`.
    static func isStorageFailure(_ error: Error) -> Bool {
        if case Failure.invalid(let step) = error { return step == watchlistStep }
        return false
    }

    /// Alle eingereihten Schreibvorgänge abwarten; true = die Merkliste ist auf der Platte (`RestoreSteps.execute`).
    /// Nur im UI-Test (Debug-Build mit `-uiTesting`) zählt allein das Abwarten: Der unsignierte Simulator hat keinen
    /// Schlüsselbund der App Group, `watchlist.json` wird dort nie versiegelt geschrieben (wie `WatchlistNotSavedGate`,
    /// `RestoreJournal.journalKey`) — sonst scheiterte dort jedes Wiederherstellen.
    @MainActor
    private static func restoreWritesSettled() -> Bool {
        let written = SharedStorage.flushSnapshotWrites()
        #if DEBUG
        if UITesting.isActive { return true }
        #endif
        return written
    }

    /// Die drei Schreibschritte in fester Reihenfolge: Merkliste/Alarme/Einstellungen/Favoriten, Portfolio, Adressen.
    @MainActor
    private static func runRestoreSteps(_ steps: inout RestoreSteps, _ input: RestoreInput, snapshot: SharedStorage.Snapshot,
                                        previous: PreviousState, into data: AppData) throws {
        // Beschädigte Portfolio-Alarme: unverändert daneben legen, bevor die Sicherung sie ersetzt (Bytes exakt)
        if previous.alarmsDamaged {
            guard WatchedAddressStore.setAsideUnreadable(at: SharedStorage.portfolioAlarmsURL) else {
                throw Failure.invalid("portfolioAlarms")
            }
        }
        steps.run({
            data.replaceAll(snapshot: snapshot, settings: input.newSettings, favorites: input.favorites)
            restoreColumnSort(input.settingsJson)
        }, undo: {
            // Beschädigte Portfolio-Alarme liegen daneben: leer zurück (wie das Journal), nie die eingespielten behalten
            var old = previous.snapshot
            if previous.alarmsDamaged && old.portfolioAlarms == nil { old.portfolioAlarms = [] }
            data.replaceAll(snapshot: old, settings: previous.settings, favorites: previous.favorites)
            if let oldColumnSort = previous.columnSort {
                UserDefaults.standard.set(oldColumnSort, forKey: ColumnSort.storageKey)
            } else {
                UserDefaults.standard.removeObject(forKey: ColumnSort.storageKey)
            }
        })
        // Portfolio ersetzen
        // Nicht still: konnte das Portfolio nicht geschrieben werden, meldet das Wiederherstellen einen Fehler
        try steps.run({
            guard restorePortfolio(input.portfolio, into: data) else { throw Failure.invalid("portfolio") }
        }, undo: { _ = rollBackPortfolio(previous.portfolio, wasIntact: previous.portfolioIntact, into: data) })
        // Adressen ersetzen wie die Transaktionen (ungültige fallen weg, `WatchedAddressRules.merge`)
        try steps.run({
            guard data.replaceWatchedAddresses(input.watched) != nil else { throw Failure.invalid(WatchedAddressRules.backupKey) }
        }, undo: { data.rollBackWatchedAddresses(previous.watched, wasIntact: previous.watchedIntact) })
    }
}
