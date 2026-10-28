import Foundation

/// Entscheide von `AppData.retryPendingPortfolioSaves` ohne Dateien (testbar).
///
/// Wartende Portfolio-Alarme gehen über die Schreib-Warteschlange hinaus (`SharedStorage.saveSnapshotInBackground`, mit
/// `mergeNewerPrices`/`mergeNewerAlarmTriggers`) — ein synchrones `saveSnapshot` schriebe den älteren Stand im
/// Speicher über Auslösungen von Widget bzw. Hintergrund-Aufgabe, und der Alarm meldete sich ein zweites Mal.
enum PortfolioSaveRetry {
    /// Portfolio-Alarme erneut schreiben? Nur mit einmal gelesener Merkliste (sonst stünde sie leer im Speicher und
    /// der ganze Stand ginge mit hinaus).
    static func queuesAlarms(alarmsPending: Bool, watchlistReadOnce: Bool) -> Bool {
        alarmsPending && watchlistReadOnce
    }

    /// Darf die Merkliste danach von der Platte gelesen werden (`prepareDiskReload`)? Nur Portfolio-Datei und Adressen
    /// (synchron geschrieben) halten das Lesen auf. Portfolio-Alarme nicht: `readSnapshot` wartet ihr eingereihtes
    /// Schreiben ab, und warten sie danach noch auf den Schlüssel, behält `AppData.applyDiskRead` sie aus dem Speicher.
    static func allowsDiskRead(portfolioDone: Bool, addressesPending: Bool) -> Bool {
        portfolioDone && !addressesPending
    }

    /// Gelesenen Stand übernehmen: Warten Portfolio-Alarme auf den Schlüssel (`alarmsPending`), gelten die im Speicher
    /// (die Datei hat einen älteren Stand); die nächste Id läuft nie zurück. Sonst gilt die Datei.
    static func merged(disk: SharedStorage.Snapshot, memory: SharedStorage.Snapshot,
                       alarmsPending: Bool) -> SharedStorage.Snapshot {
        guard alarmsPending, let alarms = memory.portfolioAlarms else { return disk }
        var s = disk
        s.portfolioAlarms = alarms
        s.nextPortfolioAlarmId = max(disk.nextPortfolioAlarmId ?? 1, memory.nextPortfolioAlarmId ?? 1)
        return s
    }

    /// Hinweis «wartet» nach dem Schreiben der eingereihten Portfolio-Alarme (Haupt-Thread).
    static func stillPending(portfolioDone: Bool, addressesPending: Bool, alarmsPending: Bool) -> Bool {
        !portfolioDone || addressesPending || alarmsPending
    }
}
