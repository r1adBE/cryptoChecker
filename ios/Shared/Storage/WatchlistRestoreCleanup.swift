import Foundation

/// Bereinigt Merkliste und Paar-Alarme einer Sicherung, bevor etwas ersetzt wird
/// (`BackupManager.restore`) — wie `WatchlistRestoreCleanup.kt` (Android).
///
/// - Gleiche Paar-Id mehrfach: der letzte Eintrag gilt, an der Stelle des ersten.
/// - Gleiches Paar (Börse, Basis, Quote, Kontrakt, bei DexScreener der Pool — `WatchIdentity`) unter
///   verschiedenen Ids: nur einmal (Android erlaubt es wegen des eindeutigen Index ohnehin nur einmal).
///   Das erste bleibt; Alarme der weggelassenen Ids hängen danach am behaltenen Paar statt verloren zu gehen.
/// - Alarme ohne (verbleibendes) Paar fallen weg; gleiche Alarm-Id mehrfach: der letzte gilt.
///
/// Anders als Android heisst Id 0 (oder kleiner) hier nicht «neu vergeben»: iOS übernimmt die Ids
/// der Sicherung immer, deshalb gelten alle Ids gleich (sonst stünden zwei Paare mit Id 0 da).
///
/// Generisch gehalten, damit die Regeln ohne `Watch`/`Alarm` prüfbar sind.
enum WatchlistRestoreCleanup {

    static func clean<W, A, K: Hashable>(
        watches: [W],
        alarms: [A],
        watchId: (W) -> Int64,
        pairKey: (W) -> K,
        alarmId: (A) -> Int64,
        alarmWatchId: (A) -> Int64,
        withWatchId: (A, Int64) -> A
    ) -> (watches: [W], alarms: [A]) {
        // 1. Gleiche Id: letzter Eintrag gewinnt, Reihenfolge nach erstem Auftreten
        var watchOrder: [Int64] = []
        var watchesById: [Int64: W] = [:]
        for w in watches {
            let id = watchId(w)
            if watchesById[id] == nil { watchOrder.append(id) }
            watchesById[id] = w
        }

        // 2. Gleiches Paar: erstes behalten, Ids der übrigen auf das behaltene umlenken
        var keptIdByPair: [K: Int64] = [:]
        var keptWatches: [W] = []
        var redirect: [Int64: Int64] = [:]
        for id in watchOrder {
            guard let w = watchesById[id] else { continue }
            let key = pairKey(w)
            if let keptId = keptIdByPair[key] {
                redirect[id] = keptId
            } else {
                keptIdByPair[key] = id
                keptWatches.append(w)
            }
        }
        let keptIds = Set(keptIdByPair.values)

        // 3. Alarme umlenken, verwaiste weglassen, gleiche Id: letzter gewinnt (an der Stelle des ersten)
        var alarmOrder: [Int64] = []
        var alarmsById: [Int64: A] = [:]
        for a in alarms {
            let original = alarmWatchId(a)
            let target = redirect[original] ?? original
            guard keptIds.contains(target) else { continue }
            let id = alarmId(a)
            if alarmsById[id] == nil { alarmOrder.append(id) }
            alarmsById[id] = target == original ? a : withWatchId(a, target)
        }
        return (keptWatches, alarmOrder.compactMap { alarmsById[$0] })
    }

    /// Merkliste und Alarme der Sicherung bereinigen (Regeln siehe oben).
    static func clean(watches: [Watch], alarms: [Alarm]) -> (watches: [Watch], alarms: [Alarm]) {
        clean(watches: watches, alarms: alarms,
              watchId: { $0.id },
              // Paar-Schlüssel wie `Watch.samePair(as:)` (`WatchIdentity`: bei DexScreener mit Pool)
              pairKey: { $0.identity },
              alarmId: { $0.id },
              alarmWatchId: { $0.watchId },
              withWatchId: { alarm, id in
                  var moved = alarm
                  moved.watchId = id
                  return moved
              })
    }
}
