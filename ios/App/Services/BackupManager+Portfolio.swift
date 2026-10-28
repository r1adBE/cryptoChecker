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

    /// Jedes Feld von `portfolioAlarmToJson` muss da sein (`BackupFields.portfolioAlarm`), sonst `Failure.invalid`.
    /// nil = ungültig (unbekannte Art einer neueren Version, ungültiger Schwellwert, Wert-Alarm ohne Währung), gezählt.
    /// «fällt um ≥ 100 %»: übernommen, aber ausgeschaltet (`PortfolioAlarmLogic.exceedsFall`, wie die Paar-Alarme).
    static func jsonToPortfolioAlarm(_ o: [String: Any]) throws -> PortfolioAlarm? {
        let name: String = try part(o, "kind")
        let threshold: Double = try part(o, "threshold")
        guard let kind = PortfolioAlarmKind(rawValue: name),
              PortfolioAlarmLogic.isValidThreshold(kind, threshold) || PortfolioAlarmLogic.exceedsFall(kind, threshold)
        else { return nil }
        let currency = try textOrNull(o, "currency").flatMap { Alarm.validCurrency($0) }
        if kind.isValue && currency == nil { return nil }
        let id: Int64 = try part(o, "id")
        let enabled: Bool = try part(o, "enabled")
        return try PortfolioAlarm(id: max(0, id), kind: kind, threshold: threshold,
                                  currency: kind.isValue ? currency : nil,
                                  enabled: enabled && !PortfolioAlarmLogic.exceedsFall(kind, threshold),
                                  repeating: part(o, "repeating"))
    }

    // MARK: Beobachtete Adressen

    /// Wie `WatchedAddressRules.toJson` (Android): `[{"chain","address","label"?,"addedAt","observations"?,"countTokens"?,
    /// "tokenObservations"?,"networks"?,"countInPortfolio"?}]` — gemeinsam mit den Regeln (`WatchedAddressRules.json`).
    static func watchedToJson(_ entries: [WatchedAddressBackupEntry]) -> [[String: Any]] {
        WatchedAddressRules.json(entries)
    }

    /// Adressen der Sicherung; fehlt der Schlüssel oder ist er keine Liste: `Failure.invalid`.
    static func watchedEntries(_ root: [String: Any]) throws -> [WatchedAddressBackupEntry] {
        try WatchedAddressRules.entries(fromJSON: part(root, WatchedAddressRules.backupKey) as [Any])
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
        o["priceText"] = t.priceExact.map { PortfolioDecimal.priceText($0) as Any } ?? NSNull()
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
        o["feeCoinPriceText"] = t.feeCoinPriceExact.map { PortfolioDecimal.priceText($0) as Any } ?? NSNull()
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
        // Gebühr-Zeile, Zins und Staking-Umbuchung als eigene Merker (wie Android)
        o["feeRow"] = t.feeRow && t.type == .SELL
        o["interest"] = t.interest && t.type == .REWARD
        o["stakingMove"] = t.stakingMove && t.type == .TRANSFER
        return o
    }

    /// Wie `jsonToTx` (Android): jedes Feld von `txToJson` muss da sein (`BackupFields.tx`), sonst `Failure.invalid`;
    /// ungültige Werte (ohne Coin, unbekannte Art, keine gültige Menge, Tausch ohne erhaltenen Coin) → nil, gezählt.
    /// Mengen, Gebühren und Werte gelesen wie gespeichert (`storedAmount`: 18 Nachkommastellen) — eine Menge, die dabei
    /// 0 wird (unter 10⁻¹⁸), ist ungültig wie eine fehlende.
    static func jsonToTx(_ o: [String: Any]) throws -> PortfolioTx? {
        let coin = PortfolioCalculator.normalizeCoin(try part(o, "coin"))
        guard let type = PortfolioTxType(rawValue: try part(o, "type")) else { return nil }
        guard let amount = try storedAmount(o, "amountText"), amount > 0 else { return nil }
        guard !coin.isEmpty else { return nil }
        let price: Decimal? = try exact(o, "priceText").flatMap { $0 >= 0 ? $0 : nil }
        // Höchstens 120 Zeichen wie die Notiz in der Merkliste
        let note: String? = try textOrNull(o, "note").flatMap { Watch.validNote($0) }
        // null oder ungültig: keine Gebühr
        let fee: Decimal? = PortfolioTx.validFee(try storedAmount(o, "feeText"))
        let feeUnit: PortfolioFeeUnit? = try fee == nil ? nil : PortfolioFeeUnit.from(name: textOrNull(o, "feeUnit"))
        let id: Int64 = try part(o, "id")
        let time: Int64 = try part(o, "time")
        var tx = try PortfolioTx(
            id: max(id, 0),
            coin: coin,
            type: type,
            amountExact: amount,
            priceExact: price,
            time: time,
            note: note,
            feeExact: fee,
            feeUnit: feeUnit,
            exchange: PortfolioTx.cleanExchange(textOrNull(o, "exchange"))
        )
        // Gebühr im dritten Coin nur mit Coin und Menge > 0
        tx.transferFrom = PortfolioTx.cleanExchange(try textOrNull(o, "transferFrom"))
        if let third = PortfolioTx.cleanFeeCoin(try textOrNull(o, "feeCoin"), coin: coin,
                                                amount: try storedAmount(o, "feeCoinAmountText")) {
            tx.feeCoin = third.coin
            tx.feeCoinAmountExact = third.amount
            tx.feeCoinPriceExact = try exact(o, "feeCoinPriceText").flatMap { $0 >= 0 ? $0 : nil }
        }
        tx.transferTo = PortfolioTx.cleanExchange(try textOrNull(o, "transferTo"))
        let gift: Bool = try part(o, "giftToSpouse")
        tx.giftToSpouse = gift && type == .GIFT
        // Zweiter Schlüssel nur mit erstem, Zustand nur mit Verknüpfung
        tx.importKey = PortfolioTx.cleanImportKey(try textOrNull(o, "importKey"))
        tx.linkedImportKey = try tx.importKey == nil ? nil : PortfolioTx.cleanImportKey(textOrNull(o, "linkedImportKey"))
        tx.importRestoreState = tx.linkedImportKey == nil ? nil : CsvRestoreState.from(json: o["importRestoreState"])
        let acquired: Int64? = try o["acquiredTime"] is NSNull ? nil : part(o, "acquiredTime") as Int64
        tx.acquiredTime = PortfolioTx.cleanAcquiredTime(type, acquired)
        // Tausch: ohne gültigen erhaltenen Coin und Menge ungültig (gezählt) — wie Android
        if type == .SWAP {
            guard let to = PortfolioTx.cleanToCoin(try textOrNull(o, "toCoin"), coin: coin),
                  let toAmount = try storedAmount(o, "toAmountText"), toAmount > 0 else { return nil }
            tx.toCoin = to
            tx.toAmountExact = toAmount
            tx.swapValueExact = try storedAmount(o, "swapValueText").flatMap { $0 >= 0 ? $0 : nil }
        }
        tx.importBatch = PortfolioImportBatch.clean(try textOrNull(o, "importBatch"))
        let feeRow: Bool = try part(o, "feeRow")
        let interest: Bool = try part(o, "interest")
        let stakingMove: Bool = try part(o, "stakingMove")
        tx.feeRow = feeRow && type == .SELL
        tx.interest = interest && type == .REWARD
        tx.stakingMove = stakingMove && type == .TRANSFER
        return tx
    }

    /// Exakte Zahl aus dem Text `key` — wie `PortfolioJson.decimal` (Android). null/ungültig = nil; fehlt das Feld oder
    /// ist es kein Text: `Failure.invalid`.
    private static func exact(_ o: [String: Any], _ key: String) throws -> Decimal? {
        try textOrNull(o, key).flatMap { PortfolioDecimal.parse($0) }
    }

    /// Menge, Gebühr oder Wert aus dem Text `key`, gerundet wie gespeichert (`PortfolioDecimal.amount`) — wie Android
    /// `PortfolioJson.amountOf`. null/ungültig = nil.
    private static func storedAmount(_ o: [String: Any], _ key: String) throws -> Decimal? {
        try exact(o, key).map { PortfolioDecimal.amount($0) }
    }
}
