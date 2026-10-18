import Foundation

// Abgeleitete Werte und Bereinigung einer Portfolio-Transaktion (Teil von `PortfolioTx`): Herkunftsschlüssel,
// Gebühr in einem dritten Coin, Tausch, Börsen-Route, Gebühren und erhaltene Menge.

extension PortfolioTx {
    /// Höchstlänge des Herkunftsschlüssels — wie `PortfolioJson.MAX_IMPORT_KEY` (Android).
    static let maxImportKey = 200

    /// Herkunftsschlüssel: getrimmt, höchstens `maxImportKey` Zeichen (länger = ungültig); leer = nil.
    static func cleanImportKey(_ raw: String?) -> String? {
        guard let t = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty, t.count <= maxImportKey else { return nil }
        return t
    }

    /// Höchstlänge eines Gebühr-Coins — wie `PortfolioJson.MAX_FEE_COIN` (Android).
    static let maxFeeCoin = 15

    /// Gebühr im dritten Coin bereinigt: Coin normalisiert (höchstens `maxFeeCoin` Zeichen, nicht `coin`)
    /// und Menge > 0 — sonst nil. Wie `PortfolioJson.cleanFeeCoin` (Android).
    static func cleanFeeCoin(_ feeCoin: String?, coin: String, amount: Decimal?) -> (coin: String, amount: Decimal)? {
        guard let feeCoin else { return nil }
        let c = PortfolioCalculator.normalizeCoin(feeCoin)
        guard !c.isEmpty, c.count <= maxFeeCoin, c != PortfolioCalculator.normalizeCoin(coin),
              let amount, amount > 0 else { return nil }
        return (c, amount)
    }

    /// Gebühr-Coin (normalisiert), wenn eine gültige Gebühr in einem anderen Coin erfasst ist — wie
    /// `PortfolioTrade.thirdFeeCoin` (Android). Beim Tausch ist eine Gebühr im erhaltenen Coin keine dritte (`toCoinFee`).
    var thirdFeeCoin: String? {
        guard let feeCoin else { return nil }
        let c = PortfolioCalculator.normalizeCoin(feeCoin)
        guard !c.isEmpty, c != PortfolioCalculator.normalizeCoin(coin), c != swapToCoin,
              let a = feeCoinAmountExact, a > 0 else { return nil }
        return c
    }

    /// Tausch: erhaltener Coin (normalisiert, ≠ `coin`, mit `toAmountExact` > 0); sonst nil — wie `PortfolioTrade.swapToCoin`.
    var swapToCoin: String? {
        guard type == .SWAP, let toCoin else { return nil }
        let c = PortfolioCalculator.normalizeCoin(toCoin)
        guard !c.isEmpty, c != PortfolioCalculator.normalizeCoin(coin), let a = toAmountExact, a > 0 else { return nil }
        return c
    }

    /// Tausch: Gebühr im erhaltenen Coin (`feeCoin` = `toCoin`, > 0), sonst 0.
    var toCoinFee: Decimal {
        guard let to = swapToCoin, let feeCoin, PortfolioCalculator.normalizeCoin(feeCoin) == to,
              let a = feeCoinAmountExact, a > 0 else { return 0 }
        return a
    }

    /// Tausch: erhaltene Menge − Gebühr im erhaltenen Coin (nie negativ); ohne gültigen Zugang 0.
    var swapReceived: Decimal {
        guard swapToCoin != nil, let a = toAmountExact else { return 0 }
        return max(0, a - toCoinFee)
    }

    /// Tausch: Wert in USDT (gesamt, ohne Gebühren) — `swapValueExact` (≥ 0), sonst Preis × Menge, sonst Stablecoin-Regel
    /// (USDT abgegeben: Menge; USDT erhalten: erhaltene Menge); nil = unbekannt. Andere Arten: nil. Wie `PortfolioTrade.swapValue`.
    var swapValue: Decimal? {
        guard type == .SWAP else { return nil }
        if let v = swapValueExact, v >= 0 { return v }
        if let p = validPriceExact { return amountExact * p }
        if PortfolioStables.isValuation(PortfolioCalculator.normalizeCoin(coin)) { return amountExact }
        if let to = swapToCoin, PortfolioStables.isValuation(to) { return toAmountExact }
        return nil
    }

    /// Beteiligte Coins: `coin` und beim Tausch `toCoin` (Kursabfragen, Zählungen) — wie `PortfolioTxEntity.coins`.
    var coins: [String] {
        if type == .SWAP, let to = toCoin, !to.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return [coin, to] }
        return [coin]
    }

    /// Eintrag für den Börsenweg (`PortfolioExchangeRoute.exchangeByLedger`).
    var routeEntry: PortfolioExchangeRoute.RouteEntry {
        PortfolioExchangeRoute.RouteEntry(coin: coin, exchange: exchange, type: type, transferTo: transferTo, time: time, id: id)
    }

    /// Einträge für den Börsenweg: beim Tausch auch für den erhaltenen Coin (gleiche Börse) — wie `routeEntries` (Android).
    var routeEntries: [PortfolioExchangeRoute.RouteEntry] {
        guard type == .SWAP, let to = toCoin, !to.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [routeEntry] }
        return [routeEntry,
                PortfolioExchangeRoute.RouteEntry(coin: to, exchange: exchange, type: type, transferTo: nil, time: time, id: id)]
    }

    /// Tausch: erhaltener Coin normalisiert (höchstens `maxFeeCoin` Zeichen, nicht `coin`); sonst nil — wie
    /// `PortfolioJson.cleanToCoin` (Android).
    static func cleanToCoin(_ toCoin: String?, coin: String) -> String? {
        guard let toCoin else { return nil }
        let c = PortfolioCalculator.normalizeCoin(toCoin)
        guard !c.isEmpty, c.count <= maxFeeCoin, c != PortfolioCalculator.normalizeCoin(coin) else { return nil }
        return c
    }

    /// Menge der Gebühr im dritten Coin, sonst 0.
    var thirdFeeAmount: Decimal { thirdFeeCoin != nil ? (feeCoinAmountExact ?? 0) : 0 }

    /// USDT je Gebühr-Coin: erfasster Preis (≥ 0), für USDT immer 1; nil = unbekannt oder keine Gebühr.
    var thirdFeePrice: Decimal? {
        guard let c = thirdFeeCoin else { return nil }
        if let p = feeCoinPriceExact, p >= 0 { return p }
        return PortfolioStables.isValuation(c) ? 1 : nil
    }

    /// Wert der Gebühr im dritten Coin in USDT: 0 ohne solche Gebühr, nil = Preis unbekannt.
    var thirdFeeValue: Decimal? {
        guard thirdFeeCoin != nil else { return 0 }
        return thirdFeePrice.map { thirdFeeAmount * $0 }
    }

    /// Höchstlänge der Börse — wie `PortfolioJson.MAX_EXCHANGE` (Android).
    static let maxExchange = 40

    /// Börse: getrimmt, höchstens `maxExchange` Zeichen; leer = nil.
    static func cleanExchange(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let text = String(raw.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxExchange))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : text
    }

    /// Gültige Gebühr (> 0, endlich), sonst nil.
    static func validFee(_ value: Double?) -> Double? { value.flatMap { $0.isFinite && $0 > 0 ? $0 : nil } }

    /// Gültige Gebühr (> 0), sonst nil — exakt.
    static func validFee(_ value: Decimal?) -> Decimal? { value.flatMap { $0 > 0 ? $0 : nil } }

    /// Gebühr in USDT exakt, sonst 0 — wie `PortfolioTrade.quoteFee`.
    var quoteFeeExact: Decimal { (feeUnit ?? .QUOTE) == .QUOTE ? (Self.validFee(feeExact) ?? 0) : 0 }

    /// Gebühr im Coin exakt, sonst 0 — wie `PortfolioTrade.coinFee`.
    var coinFeeExact: Decimal { feeUnit == .COIN ? (Self.validFee(feeExact) ?? 0) : 0 }

    /// Bei einem Kauf erhaltene Menge exakt: Menge − Gebühr im Coin (nie negativ).
    var receivedExact: Decimal { max(0, amountExact - coinFeeExact) }

    /// Gültiger Preis (≥ 0) exakt, sonst nil.
    var validPriceExact: Decimal? { priceExact.flatMap { $0 >= 0 ? $0 : nil } }

    /// Gebühr in USDT als Double, sonst 0.
    var quoteFee: Double { PortfolioDecimal.double(quoteFeeExact) }

    /// Gebühr im Coin als Double, sonst 0.
    var coinFee: Double { PortfolioDecimal.double(coinFeeExact) }

    /// Erhaltene Menge als Double.
    var received: Double { PortfolioDecimal.double(receivedExact) }
}
