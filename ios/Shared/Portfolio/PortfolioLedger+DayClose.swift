import Foundation

// Gebühr-Zeilen und Werte zum Tagesschluss (Teil von `PortfolioLedger`): Verkaufspreis, Gebühr-Zeilen,
// Verschenkt/bezahlt und Belohnungen zum Tageskurs, nötige Tageskurse — wie `PortfolioLedger.kt` (Android).

extension PortfolioLedger {
    /// Gebühr-Zeile (CSV «Fee», `CsvImportMapping`), gespeichert als Verkauf mit eigenem Merker (`feeRow`;
    /// vorher aus «Preis 0 mit Import-Herkunft» geschlossen, `LegacyStorageMigration.legacyFeeRow` — von Hand erfasst ist
    /// ein Verkauf zum Preis 0 ein Verkauf eines wertlosen Coins): Veräusserung zum
    /// TAGESKURS — Erlös = Menge × Tagesschluss ihres Tags (`valueFeeDisposals`), realisiert = Marktwert − Einstand; ohne
    /// Kurs Erlös unbekannt («unvollständig», nie 0). Steuer-Export: Vermerk «Gebühr» (`TaxGainRow.feeRow`). Verlauf und
    /// «Heute/7 T/1 J» (`flow`): Abfluss zum Marktkurs — über die ganze Haltedauer Kurve = «Seit Kauf» + realisiert, kein
    /// Sprung am Gebühr-Tag. Wie Android.
    static func isFeeDisposal(_ t: PortfolioTx) -> Bool { t.type == .SELL && t.feeRow }

    /// Verkaufspreis für Einstand/Erlös: Gebühr-Zeile ohne Tageskurs = unbekannt (nil), sonst `validPriceExact`.
    static func salePrice(_ t: PortfolioTx) -> Decimal? { isFeeDisposal(t) ? nil : t.validPriceExact }

    /// Gebühr-Zeilen (`isFeeDisposal`) zum Tageskurs: Preis = `price` (Coin, Zeit) > 0, sonst nil (Erlös unbekannt).
    /// Danach rechnen alle Methoden sie wie einen Verkauf zu diesem Preis. Ohne Gebühr-Zeilen dieselbe Liste. Wie Android.
    static func valueFeeDisposals(_ trades: [PortfolioTx], price: ((String, Int64) -> Decimal?)?) -> [PortfolioTx] {
        guard trades.contains(where: { isFeeDisposal($0) }) else { return trades }
        return trades.map { t in
            guard isFeeDisposal(t) else { return t }
            var v = t
            v.priceExact = price?(PortfolioCalculator.normalizeCoin(t.coin), t.time).flatMap { $0 > 0 ? $0 : nil }
            // Bewertet ist sie ein gewöhnlicher Verkauf zu diesem Preis (der Merker gilt nur für die Rohdaten)
            v.feeRow = false
            return v
        }
    }

    /// Braucht `t` einen Tagesschluss für `valueAtDayClose`? Gebühr-Zeile, Damit bezahlt ohne Marktwert, Tausch mit gültigem
    /// Zugang ohne Wert (Verschenkt erst nach `TaxEngines.applyRules`). Wie Android.
    static func needsDayClose(_ t: PortfolioTx) -> Bool {
        isFeeDisposal(t) || (t.type == .PAYMENT && t.validPriceExact == nil)
            || (t.type == .SWAP && t.swapToCoin != nil && t.swapValue == nil)
    }

    /// (Coin, Zeit), für die `valueAtDayClose` einen Tagesschluss braucht (Coins normalisiert): Gebühr-Zeile und
    /// Damit bezahlt und Verschenkt (Schenkung als Veräusserung, `TaxEngines.applyRules`) ohne Marktwert der Coin selbst,
    /// Tausch beide Coins.
    /// Wie Android `dayCloseNeeds`.
    static func dayCloseNeeds(_ trades: [PortfolioTx]) -> [(coin: String, time: Int64)] {
        var seen = Set<String>()
        var out: [(coin: String, time: Int64)] = []
        func add(_ coin: String, _ time: Int64) {
            if seen.insert("\(coin)|\(time)").inserted { out.append((coin: coin, time: time)) }
        }
        for t in trades where t.amountExact > 0 && (needsDayClose(t) || (t.type == .GIFT && t.validPriceExact == nil) || rewardNeedsDayClose(t)) {
            add(PortfolioCalculator.normalizeCoin(t.coin), t.time)
            if t.type == .SWAP, let to = t.swapToCoin { add(to, t.time) }
        }
        return out
    }

    /// Werte zum Tagesschluss `price` (Coin, Zeit; > 0, sonst unbekannt) — dieselbe Regel für Karte und Steuer-Export:
    /// Gebühr-Zeile (Preis = Tagesschluss), Verschenkt/bezahlt ohne Marktwert (Preis = Tagesschluss, `valueEstimated`),
    /// Tausch ohne Wert (Wert = abgegebene Menge × Tagesschluss des abgegebenen Coins, sonst erhaltene Menge × Tagesschluss
    /// des erhaltenen Coins, `valueEstimated`). Mit erfasstem Wert unverändert; ohne Kurs unbekannt. Wie Android.
    static func valueAtDayClose(_ trades: [PortfolioTx], price: ((String, Int64) -> Decimal?)?) -> [PortfolioTx] {
        guard trades.contains(where: { needsDayClose($0) }) else { return trades }
        func close(_ coin: String, _ time: Int64) -> Decimal? {
            price?(PortfolioCalculator.normalizeCoin(coin), time).flatMap { $0 > 0 ? $0 : nil }
        }
        return trades.map { t in
            guard needsDayClose(t) else { return t }
            var v = t
            if isFeeDisposal(t) {
                // Bewertet ist sie ein gewöhnlicher Verkauf (der Merker gilt nur für die Rohdaten, wie bis v4 der Preis 0)
                v.priceExact = close(t.coin, t.time)
                v.feeRow = false
            } else if t.type == .PAYMENT {
                guard let p = close(t.coin, t.time) else { return t }
                v.priceExact = p
                v.valueEstimated = true
            } else {
                var value: Decimal?
                if let p = close(t.coin, t.time) {
                    value = t.amountExact * p
                } else if let to = t.swapToCoin, let p = close(to, t.time), let amount = t.toAmountExact {
                    value = amount * p
                }
                guard let value else { return t }
                v.swapValueExact = value
                v.valueEstimated = true
            }
            return v
        }
    }

    /// Belohnung ohne Wert (Kraken-/Binance-Import kennen keinen Preis), gültige Menge — wie Android.
    static func rewardNeedsDayClose(_ t: PortfolioTx) -> Bool {
        t.type == .REWARD && t.validPriceExact == nil && t.amountExact > 0
    }

    /// Nur Steuer-Export (`TaxEngines.disposals`, `TaxExport.incomeRows`): Belohnung ohne Wert zum Tagesschluss ihres Tags
    /// `price` (> 0) — Wert je Coin = Kurs, `valueEstimated` (Vermerk «Wert geschätzt»). Einkünfte zählen, der spätere
    /// Verkauf hat einen Einstand. Ohne Kurs bleibt sie ohne Wert («unvollständig»). Karte und Wertverlauf unverändert.
    /// Wie Android `valueRewardsAtDayClose`.
    static func valueRewardsAtDayClose(_ trades: [PortfolioTx], price: ((String, Int64) -> Decimal?)?) -> [PortfolioTx] {
        guard let price, trades.contains(where: { rewardNeedsDayClose($0) }) else { return trades }
        return trades.map { t in
            guard rewardNeedsDayClose(t), let p = price(PortfolioCalculator.normalizeCoin(t.coin), t.time), p > 0 else { return t }
            var v = t
            v.priceExact = p
            v.valueEstimated = true
            return v
        }
    }
}
