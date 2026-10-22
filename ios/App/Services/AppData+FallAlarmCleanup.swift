import Foundation

/// Seit Build 104: «fällt um ≥ 100 %»-Alarme (Kurs bzw. Open Interest, `BackupManager.exceedsFall`) konnte Build 102 noch
/// speichern; sie lösen nie aus. Einmal je Installation ausschalten (Merker im App-Group-Speicher); die Alarmliste
/// kennzeichnet sie («Schwelle ungültig – bitte anpassen»). Wie Android `FallAlarmCleanup`.
enum FallAlarmCleanup {
    static let doneKey = "fall_alarm_cleanup_done_b104"

    /// Eingeschaltete Alarme, die nie auslösen können.
    static func idsToDisable(_ alarms: [Alarm]) -> Set<Int64> {
        Set(alarms.filter { $0.enabled && BackupManager.exceedsFall($0.condition, $0.threshold) }.map(\.id))
    }

    /// Seit Build 105 (Review FUN3-01): eigener Merker für die Portfolio-Alarme (der von Build 104 ist schon gesetzt).
    static let portfolioDoneKey = "portfolio_fall_alarm_cleanup_done_b105"

    /// Seit Build 105: eingeschaltete Portfolio-Alarme «fällt um ≥ 100 %» (`PortfolioAlarmLogic.exceedsFall`).
    static func portfolioIdsToDisable(_ alarms: [PortfolioAlarm]) -> Set<Int64> {
        Set(alarms.filter { $0.enabled && PortfolioAlarmLogic.exceedsFall($0.kind, $0.threshold) }.map(\.id))
    }
}

extension AppData {
    /// Beim Start nach dem Lesen des Stands (`CryptoCheckerApp`). Nur mit gelesenem, beschreibbarem Stand — sonst beim
    /// nächsten Start erneut (gesperrte Datei schreibt `saveSnapshotInBackground` ohnehin nicht).
    func disableInvalidFallAlarmsOnce(defaults: UserDefaults = SharedStorage.defaults) {
        guard watchlistReadOnce, !watchlistWritesBlocked else { return }
        disableInvalidPortfolioFallAlarmsOnce(defaults: defaults)
        guard !defaults.bool(forKey: FallAlarmCleanup.doneKey) else { return }
        let ids = FallAlarmCleanup.idsToDisable(snapshot.alarms)
        if !ids.isEmpty {
            mutate({ s in
                for i in s.alarms.indices where ids.contains(s.alarms[i].id) { s.alarms[i].enabled = false }
            }, reloadWidgets: false)
        }
        defaults.set(true, forKey: FallAlarmCleanup.doneKey)
    }

    /// Seit Build 105 (Review FUN3-01): dasselbe für die Portfolio-Alarme (wie Android `PortfolioFallAlarmCleanup`).
    /// Nur aus `disableInvalidFallAlarmsOnce` (gelesener, beschreibbarer Stand).
    private func disableInvalidPortfolioFallAlarmsOnce(defaults: UserDefaults) {
        guard !defaults.bool(forKey: FallAlarmCleanup.portfolioDoneKey) else { return }
        let ids = FallAlarmCleanup.portfolioIdsToDisable(snapshot.portfolioAlarms ?? [])
        if !ids.isEmpty {
            mutate({ s in
                guard var list = s.portfolioAlarms else { return }
                for i in list.indices where ids.contains(list[i].id) { list[i].enabled = false }
                s.portfolioAlarms = list
            }, reloadWidgets: false)
        }
        defaults.set(true, forKey: FallAlarmCleanup.portfolioDoneKey)
    }
}
