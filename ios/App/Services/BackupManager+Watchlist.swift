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
            "marketKey": w.marketKey,
            "marketName": w.marketName,
            "baseAsset": w.baseAsset,
            "quoteAsset": w.quoteAsset,
            "contractType": w.contractType.backupName,
            "pairId": w.pairId.map { $0 as Any } ?? NSNull(),
            "sortOrder": w.sortOrder,
            "notificationEnabled": w.notificationEnabled,
            "ttsEnabled": w.ttsEnabled,
            "favorite": w.favorite,
            "holdings": w.holdings.map { $0 as Any } ?? NSNull(),
            "group": w.groupName.map { $0 as Any } ?? NSNull(),
            "note": w.note.map { $0 as Any } ?? NSNull(),
            "disabled": w.disabled,
        ]
    }

    static func jsonToWatch(_ o: [String: Any]) throws -> Watch {
        guard let id = int64(o, "id") else { throw Failure.invalid("watch.id") }
        guard let marketKey = string(o, "marketKey"),
              let marketName = string(o, "marketName"),
              let baseAsset = string(o, "baseAsset"),
              let quoteAsset = string(o, "quoteAsset")
        else { throw Failure.invalid("watch") }
        return Watch(
            id: id,
            marketKey: marketKey,
            marketName: marketName,
            baseAsset: baseAsset,
            quoteAsset: quoteAsset,
            contractType: FuturesContractType(backupName: string(o, "contractType") ?? ""),
            pairId: isNull(o, "pairId") ? nil : string(o, "pairId"),
            sortOrder: int(o, "sortOrder") ?? 0,
            // Ohne Angabe: an wie in der Version der Sicherung (nicht der Standard neuer Paare)
            notificationEnabled: bool(o, "notificationEnabled") ?? Watch.legacyNotificationEnabled,
            ttsEnabled: bool(o, "ttsEnabled") ?? false,
            favorite: bool(o, "favorite") ?? false,
            // Ältere Sicherungen kennen Bestand und Gruppe noch nicht.
            holdings: isNull(o, "holdings") ? nil : Watch.validHoldings(double(o, "holdings")),
            groupName: isNull(o, "group") ? nil : Watch.validGroupName(string(o, "group")),
            // Ältere Sicherungen haben keine Notiz
            note: isNull(o, "note") ? nil : Watch.validNote(string(o, "note")),
            // Ältere Sicherungen kennen «deaktiviert» nicht: Paar aktiv
            disabled: bool(o, "disabled") ?? false
        )
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

    /// Seit Build 103: fallende Prozent-Alarme («fällt um x %», Open Interest fällt) ab 100 % lösen nie aus — der Editor
    /// nimmt nur Werte unter 100 an. Seit Build 104 aus einer Sicherung AUSGESCHALTET übernommen (nicht als ungültig
    /// gezählt) und in der Alarmliste gekennzeichnet. Wie Android `AlarmJson.exceedsFall`.
    static func exceedsFall(_ condition: AlarmCondition, _ threshold: Double) -> Bool {
        condition.isFallingPercent && threshold >= AlarmCondition.maxFallPercent
    }

    /// Ein Paar-Alarm; nil = ungültig (Id/Paar fehlt, unbekannte Alarmart einer neueren Version, Schwelle nicht
    /// endlich bzw. ≤ 0, wo sie positiv sein muss) — nie zu «Kurs über …» mit fremder Schwelle umgedeutet. Wie Android `AlarmJson.fromJson`.
    static func alarmFromJson(_ o: [String: Any]) -> Alarm? {
        guard let id = int64(o, "id"), let watchId = int64(o, "watchId"),
              let threshold = double(o, "threshold"), threshold.isFinite,
              let condition = AlarmCondition(rawValue: string(o, "condition") ?? ""),
              // Seit Build 102: Kursmarke, Prozent und Bewegung mit Schwelle ≤ 0 lösten bei jeder Prüfung aus
              !(needsPositiveThreshold(condition) && threshold <= 0)
        else { return nil }
        let referencePrice: Double?
        if isNull(o, "referencePrice") {
            referencePrice = nil
        } else if let p = double(o, "referencePrice"), p.isFinite {
            referencePrice = p
        } else {
            referencePrice = nil
        }
        return Alarm(
            id: id,
            watchId: watchId,
            condition: condition,
            threshold: threshold,
            // Seit Build 104: fallend ab 100 % löst nie aus — ausgeschaltet übernehmen
            enabled: (bool(o, "enabled") ?? true) && !exceedsFall(condition, threshold),
            repeating: bool(o, "repeating") ?? false,
            sound: bool(o, "sound") ?? true,
            vibrate: bool(o, "vibrate") ?? true,
            speak: bool(o, "speak") ?? false,
            referencePrice: referencePrice,
            windowHours: Alarm.clampWindowHours(int(o, "windowHours") ?? 1),
            // Ältere Sicherungen kennen die Alarmwährung nicht (= Quote-Währung)
            currency: isNull(o, "currency") ? nil : Alarm.validCurrency(string(o, "currency"))
        )
    }

    /// Paar-Alarme der Sicherung; ungültige (unbekannte Alarmart, Schwelle nicht endlich) werden gezählt.
    static func parsedAlarms(_ root: [String: Any]) -> (items: [Alarm], invalid: Int) {
        let list = root["alarms"] as? [Any] ?? []
        let items = list.compactMap { ($0 as? [String: Any]).flatMap(alarmFromJson) }
        return (items, list.count - items.count)
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
        var parsedWatches: [Watch] = []
        for item in root["watches"] as? [Any] ?? [] {
            guard let o = item as? [String: Any] else { throw Failure.invalid("watches") }
            parsedWatches.append(try jsonToWatch(o))
        }
        let alarmsRead = parsedAlarms(root)
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
