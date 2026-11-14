import Foundation

// Journal des Wiederherstellens und Ablehnen bei nicht lesbarem Stand (Teil von `BackupManager`). Das Journal ist das
// Sicherungsformat samt eigenem Server und dem Alarm-Zustand (gemeldet, Bezug, zuletzt), den die Sicherungsdatei nicht
// enthält: Ein Zurückrollen nach einem Absturz schreibt den genauen Stand zurück und schaltet keinen schon gemeldeten
// Alarm wieder scharf — wie Android (`BackupJournalCodec`, Einträge samt Zustand).

extension BackupManager {
    /// Schlüssel des Alarm-Zustands im Journal (nicht in Sicherungsdateien; beim Einlesen übergangen).
    static let journalStateKey = "journalAlarmState"

    /// Zustand der Paar- und Portfolio-Alarme je Id, wie er vor dem Wiederherstellen war.
    struct JournalAlarmState: Codable, Equatable {
        struct Pair: Codable, Equatable {
            var id: Int64
            var referenceAt: Int64
            var lastTriggeredAt: Int64
            var lastTriggeredPrice: Double?
        }

        struct Portfolio: Codable, Equatable {
            var id: Int64
            var referenceAt: Int64
            var lastTriggeredAt: Int64
            var lastTriggeredValue: Double?
        }

        var alarms: [Pair] = []
        var portfolioAlarms: [Portfolio] = []

        init(alarms: [Alarm], portfolioAlarms: [PortfolioAlarm]) {
            self.alarms = alarms.map {
                Pair(id: $0.id, referenceAt: $0.referenceAt, lastTriggeredAt: $0.lastTriggeredAt,
                     lastTriggeredPrice: $0.lastTriggeredPrice)
            }
            self.portfolioAlarms = portfolioAlarms.map {
                Portfolio(id: $0.id, referenceAt: $0.referenceAt, lastTriggeredAt: $0.lastTriggeredAt,
                          lastTriggeredValue: $0.lastTriggeredValue)
            }
        }
    }

    /// Gespeicherter Stand gerade nicht (ganz) lesbar — Merkliste gesperrt, `portfolio.json`, `watched_addresses.json`
    /// bzw. `portfolio_alarms.json` da, aber nicht lesbar? Dann weder sichern (die Datei enthielte ihn nicht und sähe
    /// gültig aus) noch einspielen (das Journal hielte einen leeren Stand) — wie Android `ensureStoredReadable`.
    /// Einzelne übersprungene Einträge (neuere Version) zählen nicht: Die Datei ist gelesen, ihre Kopie liegt daneben.
    @MainActor
    static func storageKept(_ data: AppData) -> Bool {
        let alarmsUnreadable = data.snapshot.portfolioAlarms == nil
            && FileManager.default.fileExists(atPath: SharedStorage.portfolioAlarmsURL.path)
        return storageKept(watchlistLocked: data.watchlistLocked,
                           portfolioUnreadable: !data.portfolioIntact && data.portfolioFile.dropped == 0,
                           addressesUnreadable: !data.watchedAddressIntact && data.watchedAddresses.isEmpty,
                           portfolioAlarmsUnreadable: alarmsUnreadable)
    }

    /// Rein (testbar): ein Teil nicht lesbar → abgelehnt.
    static func storageKept(watchlistLocked: Bool, portfolioUnreadable: Bool, addressesUnreadable: Bool,
                            portfolioAlarmsUnreadable: Bool) -> Bool {
        watchlistLocked || portfolioUnreadable || addressesUnreadable || portfolioAlarmsUnreadable
    }

    /// Meldung zu einem gescheiterten Wiederherstellen (Schlüssel des Texts) — wie Android `BackupMessage`.
    static func restoreFailureKey(_ error: Error) -> String {
        switch error {
        case BackupVersion.Failure.newer: return "backup_restore_newer_version"
        case Failure.journalUnavailable: return "backup_restore_journal_unavailable" // nichts ersetzt: später erneut
        case Failure.storageKept: return "backup_storage_kept"
        // Speicher nicht beschreibbar: zurückgerollt, Daten unverändert
        case let error where isStorageFailure(error): return "backup_restore_storage_unavailable"
        default: return "backup_failed"
        }
    }

    /// Journal: bisheriger Stand im Sicherungsformat (mit eigenem Server) samt Alarm-Zustand.
    @MainActor
    static func journalData(_ data: AppData) throws -> Data {
        let input = exportInput(data)
        var root = exportRoot(input, encrypted: true)
        let state = JournalAlarmState(alarms: input.alarms, portfolioAlarms: data.snapshot.portfolioAlarms ?? [])
        root[journalStateKey] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(state))
        return try encode(root)
    }

    /// Alarm-Zustand aus dem Journal; nil = keiner (Sicherungsdatei) oder unlesbar.
    static func journalAlarmState(_ root: [String: Any]) -> JournalAlarmState? {
        guard let value = root[journalStateKey], JSONSerialization.isValidJSONObject(value),
              let json = try? JSONSerialization.data(withJSONObject: value) else { return nil }
        return try? JSONDecoder().decode(JournalAlarmState.self, from: json)
    }

    /// Zustand aus dem Journal auf die zurückgeschriebenen Alarme legen (je Id; ohne Eintrag bleibt der Alarm, wie er ist).
    static func applyJournalAlarmState(_ state: JournalAlarmState?, to snapshot: inout SharedStorage.Snapshot) {
        guard let state else { return }
        let pairs = Dictionary(state.alarms.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        for i in snapshot.alarms.indices {
            guard let s = pairs[snapshot.alarms[i].id] else { continue }
            snapshot.alarms[i].referenceAt = s.referenceAt
            snapshot.alarms[i].lastTriggeredAt = s.lastTriggeredAt
            snapshot.alarms[i].lastTriggeredPrice = s.lastTriggeredPrice
        }
        let portfolio = Dictionary(state.portfolioAlarms.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        guard var list = snapshot.portfolioAlarms else { return }
        for i in list.indices {
            guard let s = portfolio[list[i].id] else { continue }
            list[i].referenceAt = s.referenceAt
            list[i].lastTriggeredAt = s.lastTriggeredAt
            list[i].lastTriggeredValue = s.lastTriggeredValue
        }
        snapshot.portfolioAlarms = list
    }
}
