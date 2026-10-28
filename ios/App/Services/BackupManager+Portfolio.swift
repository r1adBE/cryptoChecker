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

    /// Adressen der Sicherung; nil = Schlüssel fehlt (ältere Sicherung — bestehende bleiben).
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
        // Zahl für ältere Versionen (lesen nur sie), exakter Text für diese («amountText»: «0.00000001»)
        o["amount"] = t.amount
        o["amountText"] = PortfolioDecimal.text(t.amountExact)
        o["priceUsdt"] = t.priceUsdt.map { $0 as Any } ?? NSNull()
        o["priceText"] = t.priceExact.map { PortfolioDecimal.text($0) as Any } ?? NSNull()
        o["time"] = NSNumber(value: t.time)
        o["note"] = t.note.map { $0 as Any } ?? NSNull()
        // Seit Gebühr/Börse; ältere Sicherungen haben sie nicht (= keine Angabe)
        o["fee"] = PortfolioTx.validFee(t.fee).map { $0 as Any } ?? NSNull()
        o["feeText"] = PortfolioTx.validFee(t.feeExact).map { PortfolioDecimal.text($0) as Any } ?? NSNull()
        o["feeUnit"] = PortfolioTx.validFee(t.fee) == nil ? NSNull() as Any : (t.feeUnit ?? .QUOTE).rawValue as Any
        o["exchange"] = t.exchange.map { $0 as Any } ?? NSNull()
        // Wie Android; ältere Versionen kennen sie nicht (und die neuen Arten nicht:
        // sie zählen sie als ungültig, Rückfrage)
        o["transferFrom"] = t.transferFrom.map { $0 as Any } ?? NSNull()
        o["transferTo"] = t.transferTo.map { $0 as Any } ?? NSNull()
        o["feeCoin"] = t.feeCoin.map { $0 as Any } ?? NSNull()
        o["feeCoinAmountText"] = t.feeCoinAmountExact.map { PortfolioDecimal.text($0) as Any } ?? NSNull()
        o["feeCoinPriceText"] = t.feeCoinPriceExact.map { PortfolioDecimal.text($0) as Any } ?? NSNull()
        // CSV-Import: Herkunft der Zeile — nach dem Wiederherstellen weiterhin als doppelt erkannt
        o["importKey"] = t.importKey.map { $0 as Any } ?? NSNull()
        // Einzahlung: ursprüngliches Kaufdatum; fehlt = Datum der Einzahlung
        o["acquiredTime"] = PortfolioTx.cleanAcquiredTime(t.type, t.acquiredTime).map { $0 as Any } ?? NSNull()
        // Tausch: erhaltener Coin, Menge und Wert exakt als Text; ältere Versionen kennen die Art nicht
        let swap = t.type == .SWAP
        let toCoin: String? = swap ? t.toCoin : nil
        let toAmount: String? = swap ? t.toAmountExact.map(PortfolioDecimal.text) : nil
        let swapValue: String? = swap ? t.swapValueExact.map(PortfolioDecimal.text) : nil
        o["toCoin"] = toCoin.map { $0 as Any } ?? NSNull()
        o["toAmountText"] = toAmount.map { $0 as Any } ?? NSNull()
        o["swapValueText"] = swapValue.map { $0 as Any } ?? NSNull()
        // CSV-Importlauf: «Import rückgängig» auch nach dem Wiederherstellen
        o["importBatch"] = t.importBatch.map { $0 as Any } ?? NSNull()
        // Eigene Felder statt der alten Formen in «transferTo»/«importKey» (wie Android). Ältere Versionen
        // ignorieren unbekannte Felder und lesen den Eintrag trotzdem — ohne Partner-Merker, mit nur dem Schlüssel der
        // Auszahlung und ohne «Rückgängig» der Verknüpfung (kein Absturz, nichts ungültig)
        o["giftToSpouse"] = t.isGiftToSpouse
        o["linkedImportKey"] = t.linkedImportKey.map { $0 as Any } ?? NSNull()
        o["importRestoreState"] = t.importRestoreState?.json().map { $0 as Any } ?? NSNull()
        // Gebühr-Zeile und Zins als eigene Merker (wie Android); ältere Versionen ignorieren sie
        o["feeRow"] = t.feeRow && t.type == .SELL
        o["interest"] = t.interest && t.type == .REWARD
        return o
    }

    /// Wie `jsonToTx` (Android): ohne Coin, Art oder gültige Menge → nil.
    static func jsonToTx(_ o: [String: Any]) -> PortfolioTx? {
        let coin = PortfolioCalculator.normalizeCoin(string(o, "coin") ?? "")
        guard let type = PortfolioTxType(rawValue: string(o, "type") ?? "") else { return nil }
        guard let amount = exact(o, text: "amountText", number: "amount"), amount > 0 else { return nil }
        guard !coin.isEmpty else { return nil }
        let price: Decimal? = exact(o, text: "priceText", number: "priceUsdt").flatMap { $0 >= 0 ? $0 : nil }
        let note: String? = isNull(o, "note") ? nil
            : Watch.validNote(string(o, "note"))  // höchstens 120 Zeichen wie in der Merkliste
        // Fehlt (ältere Sicherung) oder ungültig: keine Gebühr
        let fee: Decimal? = PortfolioTx.validFee(exact(o, text: "feeText", number: "fee"))
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
        // Ältere Sicherungen tragen Partner-Merker und Verknüpfung noch in «transferTo»/«importKey» (alte Formen)
        let stored = LegacyStorageMigration.convert(type, LegacyStorageMigration.Fields(
            transferTo: isNull(o, "transferTo") ? nil : PortfolioTx.cleanExchange(string(o, "transferTo")),
            giftToSpouse: o["giftToSpouse"] as? Bool ?? false,
            importKey: isNull(o, "importKey") ? nil : PortfolioTx.cleanImportKey(string(o, "importKey")),
            linkedImportKey: isNull(o, "linkedImportKey") ? nil : PortfolioTx.cleanImportKey(string(o, "linkedImportKey")),
            importRestoreState: CsvRestoreState.from(json: o["importRestoreState"])))
        tx.transferTo = stored.transferTo
        tx.giftToSpouse = stored.giftToSpouse && type == .GIFT
        tx.importKey = stored.importKey
        tx.linkedImportKey = stored.importKey == nil ? nil : stored.linkedImportKey
        tx.importRestoreState = tx.linkedImportKey == nil ? nil : stored.importRestoreState
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
        // Fehlen die Merker (ältere Sicherung), einmal nach der früheren Regel ableiten — wie Android
        if let flag = o["feeRow"] as? Bool {
            tx.feeRow = flag && type == .SELL
        } else {
            tx.feeRow = LegacyStorageMigration.legacyFeeRow(type, importKey: tx.importKey, price: price)
        }
        if let flag = o["interest"] as? Bool {
            tx.interest = flag && type == .REWARD
        } else {
            tx.interest = LegacyStorageMigration.legacyInterest(type, note: note)
        }
        return tx
    }

    /// Exakte Zahl: zuerst der Text `text` (seit Runde 35), sonst die Zahl `number` älterer
    /// Sicherungen (über ihre kürzeste Darstellung). Fehlt/ungültig = nil. Die Zahl unterliegt denselben
    /// Grenzen wie der Text (`PortfolioDecimal.withinLimits`: < 10³⁰, höchstens 40 Nachkommastellen) —
    /// wie `PortfolioJson.decimal` (Android).
    private static func exact(_ o: [String: Any], text: String, number: String) -> Decimal? {
        if !isNull(o, text), let raw = string(o, text), let value = PortfolioDecimal.parse(raw) { return value }
        if isNull(o, number) { return nil }
        // Gleiche Grenzen wie der Text (absurde Grössen = ungültig)
        return PortfolioDecimal.of(double(o, number)).flatMap { PortfolioDecimal.withinLimits($0) ? $0 : nil }
    }
}
