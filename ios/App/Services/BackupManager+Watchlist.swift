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

    /// Ein Paar-Alarm; nil = ungültig (Id/Paar fehlt, unbekannte Alarmart einer neueren Version, Schwelle nicht
    /// endlich) — nie zu «Kurs über …» mit fremder Schwelle umgedeutet. Wie Android `AlarmJson.fromJson`.
    static func alarmFromJson(_ o: [String: Any]) -> Alarm? {
        guard let id = int64(o, "id"), let watchId = int64(o, "watchId"),
              let threshold = double(o, "threshold"), threshold.isFinite,
              let condition = AlarmCondition(rawValue: string(o, "condition") ?? "")
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
            enabled: bool(o, "enabled") ?? true,
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
}
