import Foundation
import SwiftUI
import UniformTypeIdentifiers

// Merkliste und Alarme in der Sicherung (Teil von `BackupManager`): Paare und Kurs-Alarme als JSON und zurück
// (mit Prüfung ungültiger Alarme). Eigene Datei, weil jedes Teilformat für sich lesbar sein soll.

extension BackupManager {
    // MARK: Merkliste & Alarme

    static func watchToJson(_ w: Watch) -> [String: Any] {
        [
            "id": NSNumber(value: w.id),
            "exchangeKey": w.exchangeKey,
            "exchangeName": w.exchangeName,
            "baseAsset": w.baseAsset,
            "quoteAsset": w.quoteAsset,
            "contractType": w.contractType.backupName,
            "pairId": w.pairId.map { $0 as Any } ?? NSNull(),
            "sortOrder": w.sortOrder,
            "notificationEnabled": w.notificationEnabled,
            "ttsEnabled": w.ttsEnabled,
            "favorite": w.favorite,
            "group": w.groupName.map { $0 as Any } ?? NSNull(),
            "note": w.note.map { $0 as Any } ?? NSNull(),
            "disabled": w.disabled,
        ]
    }

    /// Ein Paar; jedes Feld von `watchToJson` muss da sein (`BackupFields.watch`), sonst `Failure.invalid`. Wie Android.
    static func jsonToWatch(_ o: [String: Any]) throws -> Watch {
        try Watch(
            id: part(o, "id"),
            exchangeKey: part(o, "exchangeKey"),
            exchangeName: part(o, "exchangeName"),
            baseAsset: part(o, "baseAsset"),
            quoteAsset: part(o, "quoteAsset"),
            contractType: FuturesContractType(backupName: part(o, "contractType")),
            pairId: textOrNull(o, "pairId"),
            sortOrder: part(o, "sortOrder"),
            notificationEnabled: part(o, "notificationEnabled"),
            ttsEnabled: part(o, "ttsEnabled"),
            favorite: part(o, "favorite"),
            groupName: textOrNull(o, "group").flatMap { Watch.validGroupName($0) },
            note: textOrNull(o, "note").flatMap { Watch.validNote($0) },
            disabled: part(o, "disabled")
        )
    }

    /// Text oder nil (JSON null); fehlt das Feld oder ist es kein Text: `Failure.invalid`.
    static func textOrNull(_ o: [String: Any], _ key: String) throws -> String? {
        try o[key] is NSNull ? nil : part(o, key) as String
    }

    static func alarmToJson(_ a: Alarm) -> [String: Any] {
        [
            "id": NSNumber(value: a.id),
            "watchId": NSNumber(value: a.watchId),
            "condition": a.condition.rawValue,
            "threshold": a.threshold,
            "enabled": a.enabled,
            "repeating": a.repeating,
            "sound": a.sound,
            "vibrate": a.vibrate,
            "speak": a.speak,
            "referencePrice": a.referencePrice.map { $0 as Any } ?? NSNull(),
            "windowHours": a.windowHours,
            "currency": a.currency.map { $0 as Any } ?? NSNull(),
        ]
    }

    /// Alarmarten, deren Schwelle grösser als 0 sein muss (Kursmarke, Prozent seit Referenz, Bewegung im Zeitraum,
    /// Volumen-Sprung, Open Interest); nicht
    /// Funding (darf negativ sein) und «Nahe am Hoch/Tief» (0 % = nur neues Hoch/Tief). Wie Android `AlarmJson.needsPositiveThreshold`.
    static func needsPositiveThreshold(_ condition: AlarmCondition) -> Bool {
        switch condition {
        case .PRICE_ABOVE, .PRICE_BELOW, .CHANGE_PERCENT_UP, .CHANGE_PERCENT_DOWN, .MOVE_PERCENT_WINDOW,
             .VOLUME_SPIKE, .OI_UP, .OI_DOWN: return true
        default: return false
        }
    }

    /// Fallende Prozent-Alarme («fällt um x %», Open Interest fällt) ab 100 % lösen nie aus — der Editor
    /// nimmt nur Werte unter 100 an. Aus einer Sicherung AUSGESCHALTET übernommen (nicht als ungültig
    /// gezählt) und in der Alarmliste gekennzeichnet. Wie Android `AlarmJson.exceedsFall`.
    static func exceedsFall(_ condition: AlarmCondition, _ threshold: Double) -> Bool {
        condition.isFallingPercent && threshold >= AlarmCondition.maxFallPercent
    }

    /// Schalter auf `enable` stellen erlaubt? Einschalten eines `exceedsFall`-Alarms
    /// nie (löst nie aus, die Karte zeigt «Schwelle ungültig») — erst im Editor anpassen; ausschalten immer. Wie Android
    /// `AlarmJson.toggleAllowed` (Fälle `alarm_toggle.json`).
    static func toggleAllowed(_ condition: AlarmCondition, _ threshold: Double, enable: Bool) -> Bool {
        !enable || !exceedsFall(condition, threshold)
    }

    /// Ein Paar-Alarm; jedes Feld von `alarmToJson` muss da sein (`BackupFields.alarm`), sonst `Failure.invalid`.
    /// nil = ungültig (unbekannte Alarmart einer neueren Version, Schwelle ≤ 0, wo sie positiv sein muss) — nie zu
    /// «Kurs über …» mit fremder Schwelle umgedeutet. Wie Android `AlarmJson.fromJson`.
    static func alarmFromJson(_ o: [String: Any]) throws -> Alarm? {
        let threshold: Double = try part(o, "threshold")
        let conditionName: String = try part(o, "condition")
        guard threshold.isFinite, let condition = AlarmCondition(rawValue: conditionName),
              // Kursmarke, Prozent und Bewegung mit Schwelle ≤ 0 lösten bei jeder Prüfung aus
              !(needsPositiveThreshold(condition) && threshold <= 0)
        else { return nil }
        let referencePrice: Double? = try o["referencePrice"] is NSNull ? nil : part(o, "referencePrice") as Double
        return try Alarm(
            id: part(o, "id"),
            watchId: part(o, "watchId"),
            condition: condition,
            threshold: threshold,
            // Fallend ab 100 % löst nie aus — ausgeschaltet übernehmen
            enabled: part(o, "enabled") && !exceedsFall(condition, threshold),
            repeating: part(o, "repeating"),
            sound: part(o, "sound"),
            vibrate: part(o, "vibrate"),
            speak: part(o, "speak"),
            referencePrice: referencePrice.flatMap { $0.isFinite ? $0 : nil },
            windowHours: Alarm.clampWindowHours(part(o, "windowHours")),
            // nil = Quote-Währung
            currency: textOrNull(o, "currency").flatMap { Alarm.validCurrency($0) }
        )
    }

    /// Paar-Alarme der Sicherung; ungültige (unbekannte Alarmart, ungültige Schwelle) werden gezählt.
    static func parsedAlarms(_ root: [String: Any]) throws -> (items: [Alarm], invalid: Int) {
        try parsedEntries(root, "alarms", alarmFromJson)
    }

    /// Merkliste und Paar-Alarme einer Sicherung, so wie sie übernommen würden (wie Android
    /// `BackupWatchlistCodec.readWatchlist`): `invalidAlarms` = ungültige Alarme, `droppedPairs`/`droppedAlarms` =
    /// wegen `BackupRestoreLimits` weggelassen.
    struct RestoredWatchlist {
        let watches: [Watch]
        let alarms: [Alarm]
        let invalidAlarms: Int
        let droppedPairs: Int
        let droppedAlarms: Int
    }

    /// Lesen, bereinigen und begrenzen — für Rückfrage (`preview`) und Wiederherstellen gleich, damit die Rückfrage genau
    /// die Zahlen nennt, die danach wegfallen. Paare mit unzulässigen Kennungen fallen weg, ihre Alarme mit ihnen
    /// (`BackupRestoreLimits.accept`); dann `WatchlistRestoreCleanup` (gleiche id → letzter gilt; gleiches Paar unter
    /// mehreren ids → erstes bleibt, seine Alarme übernehmen die der weggelassenen; Alarme ohne Paar fallen weg), dann
    /// `BackupRestoreLimits.limit`. `limits: false` beim Zurückrollen des eigenen Stands (Journal): der kommt unverändert
    /// zurück, auch über den Grenzen — wie Android `previousFromJournal`.
    static func restoredWatchlist(_ root: [String: Any], limits: Bool = true) throws -> RestoredWatchlist {
        let parsedWatches = try parsedEntries(root, "watches") { o -> Watch? in try jsonToWatch(o) }.items
        let alarmsRead = try parsedAlarms(root)
        let accepted = limits ? BackupRestoreLimits.accept(watches: parsedWatches, alarms: alarmsRead.items)
            : BackupRestoreLimits.Limited(watches: parsedWatches, alarms: alarmsRead.items)
        let cleaned = WatchlistRestoreCleanup.clean(watches: accepted.watches, alarms: accepted.alarms)
        let limited = limits ? BackupRestoreLimits.limit(watches: cleaned.watches, alarms: cleaned.alarms)
            : BackupRestoreLimits.Limited(watches: cleaned.watches, alarms: cleaned.alarms)
        return RestoredWatchlist(watches: limited.watches, alarms: limited.alarms, invalidAlarms: alarmsRead.invalid,
                                 droppedPairs: accepted.droppedPairs + limited.droppedPairs,
                                 droppedAlarms: accepted.droppedAlarms + limited.droppedAlarms)
    }
}
