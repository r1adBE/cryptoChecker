import Foundation

// Ausweich-Kette der Tagesschlüsse (Teil von `HistoricPriceSource`): eigene Börse des Coins und Kraken/OKX/Bybit über
// deren eigene Tageskerzen (`OwnCandleDataSource`: Zeitgrenze, Sperre/Pause je Host, kurz im Speicher). Eigene Datei,
// weil die Kette neben Binance/Coinbase lesbar bleiben soll. Wie HistoricPriceSourceChain.kt (Android).
extension HistoricPriceSource {
    /// Tageskerzen je Abfrage: immer gleich viele, damit alle Tage desselben Paars denselben Eintrag im Speicher der
    /// Kerzen treffen (höchstens 1000; Kraken liefert 720, OKX und Coinbase etwa 600 — ältere Tage bleiben ohne Schluss).
    private static let chainDays = 1000

    /// Paar einer Kettenquelle (`HistoricCloseChain.pairs`) für die eigenen Kerzen. USDC gilt wie USDT (≈ 1 USD, wie
    /// bisher Coinbase USDC); die Kennung bestimmt das Paar, die Gegenwährung nur die Umrechnung im Verlauf.
    static func chainCandlePair(_ source: String, base: String, _ p: HistoricCloseChain.ChainPair) -> CandlePair {
        CandlePair(exchangeKey: source, exchangeName: HistoricCloseChain.provider(source), base: base,
                   quote: p.quote == "USDC" ? "USDT" : p.quote, pairId: p.pairId)
    }

    /// Eingetragene Börse je Coin aus der App Group (`PortfolioExchangeRouteStore`, ohne Netz). Binance, freier Text und
    /// Coins ohne Paar fehlen (dann die Kette ohne eigene Börse).
    static func ownRoutes(_ coins: [String]) -> [String: CandlePair] {
        let wanted = Set(coins)
        var out: [String: CandlePair] = [:]
        for (coin, route) in PortfolioExchangeRouteStore.load().routes where wanted.contains(coin) {
            out[coin] = CandlePair(exchangeKey: route.exchangeKey, exchangeName: route.exchangeName, base: route.base,
                                   quote: route.quote, pairId: route.pairId)
        }
        return out
    }

    /// Schluss der Tageskerze, die genau bei `start` beginnt, und jüngster Schluss derselben Reihe (Identitätsprüfung);
    /// nil = keine laufende Reihe oder der Tag fehlt (z. B. älter als die Börse liefert).
    static func seriesClose(_ pair: CandlePair, start: Int64) async -> (close: Double, latest: Double?)? {
        guard let candles = await OwnCandleDataSource.shared.candles(pair, interval: .d1, limit: chainDays)?.value,
              let close = candles.first(where: { $0.openTime == start })?.close, close > 0, close.isFinite
        else { return nil }
        return (close, candles.last?.close)
    }

    /// Eigene Börse `own` des Coins: deren Tageskerze des Tags, Fiat mit dem Devisenkurs des Tags (jüngster Schluss mit
    /// dem heutigen) in USDT. Prüfung wie Binance-Spot (1/3…3). Ist die Börse zugleich ein Glied der Kette und hat keinen
    /// Schluss, folgt deren Kettenabfrage (Coinbase: jedes Alter).
    static func ownOutcome(_ own: CandlePair, base b: String, day: CutoffDay, start: Int64, reference: Double,
                           ownName: String?) async -> HistoricCloseChain.Outcome {
        let names = TokenIdentityStore.shared
        let nameOk = HistoricCloseChain.nameAllowed(base: b, own: true, exchangeKey: own.exchangeKey, ownName: ownName,
                                                    binanceName: names.binanceName(b),
                                                    providerName: names.pairName(exchangeKey: own.exchangeKey, base: b))
        var outcome = HistoricCloseChain.Outcome(price: nil, rejected: true)
        if nameOk { outcome = await ownDayClose(own, day: day, start: start, reference: reference) }
        if outcome.price == nil, HistoricCloseChain.chain.contains(own.exchangeKey), own.exchangeKey != HistoricCloseChain.binance {
            var chained: HistoricCloseChain.Outcome
            if own.exchangeKey == HistoricCloseChain.coinbase {
                chained = await coinbaseOutcome(b, day: day, start: start, reference: reference)
            } else {
                chained = await chainOutcome(own.exchangeKey, base: b, start: start, reference: reference, ownName: ownName)
            }
            chained.rejected = chained.rejected || outcome.rejected
            outcome = chained
        }
        return outcome
    }

    private static func ownDayClose(_ own: CandlePair, day: CutoffDay, start: Int64,
                                    reference: Double) async -> HistoricCloseChain.Outcome {
        guard let raw = await seriesClose(own, start: start) else { return HistoricCloseChain.Outcome(price: nil) }
        let quote = own.quote.trimmingCharacters(in: .whitespaces).uppercased()
        var close = raw.close
        var latest = raw.latest
        if quote != "USDT" && quote != "USD" && quote != "USDC" {
            // Fiat-Paar: Devisenkurs DES Tags (Wochenende → letzter Geschäftstag) bzw. heute für den jüngsten Schluss
            guard let dayFx = await usdTo(quote, day: day)?.rate, dayFx > 0, dayFx.isFinite else {
                return HistoricCloseChain.Outcome(price: nil)
            }
            var utc = Calendar(identifier: .gregorian)
            utc.timeZone = TimeZone(identifier: "UTC") ?? .current
            let nowFx = await usdTo(quote, day: CutoffDay(Date(), calendar: utc))?.rate
            close = raw.close / dayFx
            if let l = raw.latest, let nowFx, nowFx > 0, nowFx.isFinite { latest = l / nowFx } else { latest = nil }
        }
        switch HistoricCloseChain.decide(close: close, latest: latest, current: reference, primary: true, nameOk: true) {
        case .accept: return HistoricCloseChain.Outcome(price: close, provider: own.exchangeName)
        case .reject: return HistoricCloseChain.Outcome(price: nil, rejected: true)
        case .missing: return HistoricCloseChain.Outcome(price: nil)
        }
    }

    /// Kettenquelle `source` (Kraken, OKX, Bybit): Paare nach `HistoricCloseChain.pairs`, Name nach
    /// `HistoricCloseChain.nameAllowed`, Schluss nur mit ±25 % gegen den aktuellen Kurs (`HistoricCloseChain.decide`).
    static func chainOutcome(_ source: String, base b: String, start: Int64, reference: Double,
                             ownName: String?) async -> HistoricCloseChain.Outcome {
        let pairs = HistoricCloseChain.pairs(source, base: b)
        guard !pairs.isEmpty else { return HistoricCloseChain.Outcome(price: nil) }
        let names = TokenIdentityStore.shared
        let nameOk = HistoricCloseChain.nameAllowed(base: b, own: false, exchangeKey: source, ownName: ownName,
                                                    binanceName: names.binanceName(b),
                                                    providerName: names.pairName(exchangeKey: source, base: b))
        // Klar anderer Name: diese Quelle führt unter dem Kürzel einen anderen Token — keine Abfrage
        guard nameOk else { return HistoricCloseChain.Outcome(price: nil, rejected: true) }
        var rejected = false
        for p in pairs {
            guard let v = await seriesClose(chainCandlePair(source, base: b, p), start: start) else { continue }
            switch HistoricCloseChain.decide(close: v.close, latest: v.latest, current: reference, primary: false, nameOk: true) {
            case .accept: return HistoricCloseChain.Outcome(price: v.close)
            case .reject: rejected = true
            case .missing: break
            }
        }
        return HistoricCloseChain.Outcome(price: nil, rejected: rejected)
    }
}
