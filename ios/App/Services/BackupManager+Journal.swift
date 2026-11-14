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

    /// Journal: bisheriger Stand im Sicherungsformat (mit eigenem Server) samt Alarm-Zustand und den beschädigten Dateien,
    /// die das Wiederherstellen daneben legt (`journalDamagedKey`).
    @MainActor
    static func journalData(_ data: AppData) throws -> Data {
        let input = exportInput(data)
        var root = exportRoot(input, encrypted: true)
        let state = JournalAlarmState(alarms: input.alarms, portfolioAlarms: data.snapshot.portfolioAlarms ?? [])
        root[journalStateKey] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(state))
        let damaged = [data.portfolioIntact ? nil : damagedPortfolio, data.watchedAddressIntact ? nil : damagedAddresses]
        if !damaged.compactMap({ $0 }).isEmpty { root[journalDamagedKey] = damaged.compactMap { $0 } }
        return try encode(root)
    }

    /// Schlüssel im Journal: Teile, deren beschädigte Datei das Wiederherstellen daneben legt (R4-02). Das Zurückrollen nach
    /// einem Abbruch legt sie unverändert zurück, statt den leeren Stand des Journals stehen zu lassen.
    static let journalDamagedKey = "journalDamaged"
    static let damagedPortfolio = "portfolio"
    static let damagedAddresses = "watchedAddresses"

    /// Zurückrollen nach einem Abbruch (`journal` false): die im Journal genannten beschädigten Dateien zurücklegen —
    /// wie das Zurückrollen eines gescheiterten Laufs (`rollBackPortfolio`, `AppData.rollBackWatchedAddresses`).
    @MainActor
    static func putBackJournalDamaged(_ root: [String: Any], into data: AppData) {
        let parts = Set(root[journalDamagedKey] as? [String] ?? [])
        if parts.contains(damagedPortfolio) { rollBackPortfolio(PortfolioFile(), wasIntact: false, into: data) }
        if parts.contains(damagedAddresses) { data.rollBackWatchedAddresses(WatchedAddressFile(), wasIntact: false) }
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
