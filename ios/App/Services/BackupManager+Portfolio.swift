import Foundation
import SwiftUI
import UniformTypeIdentifiers

// Portfolio in der Sicherung (Teil von `BackupManager`): Transaktionen, Portfolio-Alarme und beobachtete Adressen
// als JSON und zurück. Eigene Datei, weil das Portfolio-Format eigene Felder und Regeln hat.

extension BackupManager {
    // MARK: Portfolio-Alarme

    /// Gleiches Format wie Android; Zustand (gemeldet, zuletzt) wird nicht gesichert.
    static func portfolioAlarmToJson(_ a: PortfolioAlarm) -> [String: Any] {
        [
            "id": NSNumber(value: a.id),
            "kind": a.kind.rawValue,
            "threshold": a.threshold,
            "currency": a.currency.map { $0 as Any } ?? NSNull(),
            "enabled": a.enabled,
            "repeating": a.repeating,
        ]
    }

    /// Unbekannte Art (neuere Version), ungültiger Schwellwert oder fehlende Währung: überspringen.
    /// «fällt um ≥ 100 %»: übernommen, aber ausgeschaltet (`PortfolioAlarmLogic.exceedsFall`, wie die Paar-Alarme).
    static func jsonToPortfolioAlarm(_ o: [String: Any]) -> PortfolioAlarm? {
        guard let name = string(o, "kind"), let kind = PortfolioAlarmKind(rawValue: name),
              let threshold = double(o, "threshold"),
              PortfolioAlarmLogic.isValidThreshold(kind, threshold) || PortfolioAlarmLogic.exceedsFall(kind, threshold)
        else { return nil }
        let currency = isNull(o, "currency") ? nil : Alarm.validCurrency(string(o, "currency"))
        if kind.isValue && currency == nil { return nil }
        return PortfolioAlarm(id: max(0, int64(o, "id") ?? 0), kind: kind, threshold: threshold,
                              currency: kind.isValue ? currency : nil,
                              enabled: (bool(o, "enabled") ?? true) && !PortfolioAlarmLogic.exceedsFall(kind, threshold),
                              repeating: bool(o, "repeating") ?? false)
    }

    // MARK: Beobachtete Adressen

    /// Wie `WatchedAddressRules.toJson` (Android): `[{"chain","address","label"?,"addedAt","observations"?,"countTokens"?,
    /// "tokenObservations"?,"networks"?,"countInPortfolio"?}]` — gemeinsam mit den Regeln (`WatchedAddressRules.json`).
    static func watchedToJson(_ entries: [WatchedAddressBackupEntry]) -> [[String: Any]] {
        WatchedAddressRules.json(entries)
    }

    /// Adressen der Sicherung; nil = Schlüssel fehlt (bestehende bleiben).
    static func watchedEntries(_ root: [String: Any]) -> [WatchedAddressBackupEntry]? {
        guard root[WatchedAddressRules.backupKey] != nil else { return nil }
        return WatchedAddressRules.entries(fromJSON: root[WatchedAddressRules.backupKey] as? [Any])
    }

    // MARK: Portfolio

    static func txToJson(_ t: PortfolioTx) -> [String: Any] {
        // Einzeln zugewiesen: ein grosses Literal mit vielen `??` war für den Type-Checker zu teuer
        var o: [String: Any] = [:]
        o["id"] = NSNumber(value: t.id)
        o["coin"] = t.coin
        o["type"] = t.type.rawValue
        // Zahlen exakt als Text («amountText»: «0.00000001»)
        o["amountText"] = PortfolioDecimal.text(t.amountExact)
        o["priceText"] = t.priceExact.map { PortfolioDecimal.text($0) as Any } ?? NSNull()
        o["time"] = NSNumber(value: t.time)
        o["note"] = t.note.map { $0 as Any } ?? NSNull()
        // Gebühr und Börse; fehlen = keine Angabe
        o["feeText"] = PortfolioTx.validFee(t.feeExact).map { PortfolioDecimal.text($0) as Any } ?? NSNull()
        o["feeUnit"] = PortfolioTx.validFee(t.fee) == nil ? NSNull() as Any : (t.feeUnit ?? .QUOTE).rawValue as Any
        o["exchange"] = t.exchange.map { $0 as Any } ?? NSNull()
        // Umbuchung und Gebühr im dritten Coin (wie Android); fehlen = keine Angabe
        o["transferFrom"] = t.transferFrom.map { $0 as Any } ?? NSNull()
        o["transferTo"] = t.transferTo.map { $0 as Any } ?? NSNull()
        o["feeCoin"] = t.feeCoin.map { $0 as Any } ?? NSNull()
        o["feeCoinAmountText"] = t.feeCoinAmountExact.map { PortfolioDecimal.text($0) as Any } ?? NSNull()
        o["feeCoinPriceText"] = t.feeCoinPriceExact.map { PortfolioDecimal.text($0) as Any } ?? NSNull()
        // CSV-Import: Herkunft der Zeile — nach dem Wiederherstellen weiterhin als doppelt erkannt
        o["importKey"] = t.importKey.map { $0 as Any } ?? NSNull()
        // Einzahlung: ursprüngliches Kaufdatum; fehlt = Datum der Einzahlung
        o["acquiredTime"] = PortfolioTx.cleanAcquiredTime(t.type, t.acquiredTime).map { $0 as Any } ?? NSNull()
        // Tausch: erhaltener Coin, Menge und Wert exakt als Text
        let swap = t.type == .SWAP
        let toCoin: String? = swap ? t.toCoin : nil
        let toAmount: String? = swap ? t.toAmountExact.map(PortfolioDecimal.text) : nil
        let swapValue: String? = swap ? t.swapValueExact.map(PortfolioDecimal.text) : nil
        o["toCoin"] = toCoin.map { $0 as Any } ?? NSNull()
        o["toAmountText"] = toAmount.map { $0 as Any } ?? NSNull()
        o["swapValueText"] = swapValue.map { $0 as Any } ?? NSNull()
        // CSV-Importlauf: «Import rückgängig» auch nach dem Wiederherstellen
        o["importBatch"] = t.importBatch.map { $0 as Any } ?? NSNull()
        // Partner-Merker und Verknüpfung (zweiter Herkunftsschlüssel, Zustand davor) als eigene Felder (wie Android)
        o["giftToSpouse"] = t.isGiftToSpouse
        o["linkedImportKey"] = t.linkedImportKey.map { $0 as Any } ?? NSNull()
        o["importRestoreState"] = t.importRestoreState?.json().map { $0 as Any } ?? NSNull()
        // Gebühr-Zeile und Zins als eigene Merker (wie Android)
        o["feeRow"] = t.feeRow && t.type == .SELL
        o["interest"] = t.interest && t.type == .REWARD
        return o
    }

    /// Wie `jsonToTx` (Android): ohne Coin, Art oder gültige Menge → nil.
    static func jsonToTx(_ o: [String: Any]) -> PortfolioTx? {
        let coin = PortfolioCalculator.normalizeCoin(string(o, "coin") ?? "")
        guard let type = PortfolioTxType(rawValue: string(o, "type") ?? "") else { return nil }
        guard let amount = exact(o, "amountText"), amount > 0 else { return nil }
        guard !coin.isEmpty else { return nil }
        let price: Decimal? = exact(o, "priceText").flatMap { $0 >= 0 ? $0 : nil }
        let note: String? = isNull(o, "note") ? nil
            : Watch.validNote(string(o, "note"))  // höchstens 120 Zeichen wie in der Merkliste
        // Fehlt oder ungültig: keine Gebühr
        let fee: Decimal? = PortfolioTx.validFee(exact(o, "feeText"))
        let feeUnit: PortfolioFeeUnit? = fee == nil ? nil
            : PortfolioFeeUnit.from(name: isNull(o, "feeUnit") ? nil : string(o, "feeUnit"))
        var tx = PortfolioTx(
            id: max(int64(o, "id") ?? 0, 0),
            coin: coin,
            type: type,
            amountExact: amount,
            priceExact: price,
            time: int64(o, "time") ?? EpochTime.nowMillis,
            note: note,
            feeExact: fee,
            feeUnit: feeUnit,
            exchange: isNull(o, "exchange") ? nil : PortfolioTx.cleanExchange(string(o, "exchange"))
        )
        // Fehlen = keine Angabe. Gebühr im dritten Coin nur mit Coin und Menge > 0
        tx.transferFrom = isNull(o, "transferFrom") ? nil : PortfolioTx.cleanExchange(string(o, "transferFrom"))
        let feeCoinAmount = isNull(o, "feeCoinAmountText") ? nil : PortfolioDecimal.parse(string(o, "feeCoinAmountText"))
        if let third = PortfolioTx.cleanFeeCoin(isNull(o, "feeCoin") ? nil : string(o, "feeCoin"), coin: coin,
                                                amount: feeCoinAmount) {
            tx.feeCoin = third.coin
            tx.feeCoinAmountExact = third.amount
            tx.feeCoinPriceExact = isNull(o, "feeCoinPriceText") ? nil
                : PortfolioDecimal.parse(string(o, "feeCoinPriceText")).flatMap { $0 >= 0 ? $0 : nil }
        }
        tx.transferTo = isNull(o, "transferTo") ? nil : PortfolioTx.cleanExchange(string(o, "transferTo"))
        tx.giftToSpouse = (o["giftToSpouse"] as? Bool ?? false) && type == .GIFT
        // Zweiter Schlüssel nur mit erstem, Zustand nur mit Verknüpfung
        tx.importKey = isNull(o, "importKey") ? nil : PortfolioTx.cleanImportKey(string(o, "importKey"))
        tx.linkedImportKey = tx.importKey == nil || isNull(o, "linkedImportKey") ? nil
            : PortfolioTx.cleanImportKey(string(o, "linkedImportKey"))
        tx.importRestoreState = tx.linkedImportKey == nil ? nil : CsvRestoreState.from(json: o["importRestoreState"])
        tx.acquiredTime = isNull(o, "acquiredTime") ? nil : PortfolioTx.cleanAcquiredTime(type, int64(o, "acquiredTime"))
        // Tausch: ohne gültigen erhaltenen Coin und Menge ungültig (gezählt) — wie Android
        if type == .SWAP {
            guard let to = PortfolioTx.cleanToCoin(isNull(o, "toCoin") ? nil : string(o, "toCoin"), coin: coin),
                  let toAmount = (isNull(o, "toAmountText") ? nil : PortfolioDecimal.parse(string(o, "toAmountText"))),
                  toAmount > 0 else { return nil }
            tx.toCoin = to
            tx.toAmountExact = toAmount
            tx.swapValueExact = isNull(o, "swapValueText") ? nil
                : PortfolioDecimal.parse(string(o, "swapValueText")).flatMap { $0 >= 0 ? $0 : nil }
        }
        tx.importBatch = isNull(o, "importBatch") ? nil : PortfolioImportBatch.clean(string(o, "importBatch"))
        // Gebühr-Zeile und Zins: fehlt = nein — wie Android
        tx.feeRow = (o["feeRow"] as? Bool ?? false) && type == .SELL
        tx.interest = (o["interest"] as? Bool ?? false) && type == .REWARD
        return tx
    }

    /// Exakte Zahl aus dem Text `key` — wie `PortfolioJson.decimal` (Android). Fehlt/ungültig = nil.
    private static func exact(_ o: [String: Any], _ key: String) -> Decimal? {
        isNull(o, key) ? nil : PortfolioDecimal.parse(string(o, key))
    }
}
