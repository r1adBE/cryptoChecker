import Foundation

// Gewinn über mehrere Tage («Gewinn 7 T» im Gesamtwert-Kopf) — wie `PortfolioPeriodChange.kt` (Android).

/// Gewinn/Verlust über einen Zeitraum für den Gesamtwert-Kopf des Portfolio-Tabs («Gewinn 7 T»,
/// Zeile «Gewinn 1 J», «Gewinn heute») und das Portfolio-Widget. Wie `PortfolioPeriodChange.kt`
/// (gleiche Regeln, auch dort getestet).
///
/// `gain` kennt die Transaktionen im Zeitraum:
///   Gewinn = Wert am Ende − Wert am Anfang − Zuflüsse im Zeitraum,
/// Zuflüsse nach `PortfolioLedger.flow`: Käufe, Belohnungen, Einzahlungen zu ihrem Einstand (Menge × Preis
/// + Gebühren), Verkäufe zu ihrem Erlös, Auszahlungen zum erfassten Preis (sonst zum Marktkurs ihres Zeitpunkts:
/// «7 T»/«1 J» Tagesschluss ihres UTC-Tags, nach dem letzten Schluss der aktuelle Kurs; «Heute» der aktuelle Kurs;
/// ohne Marktkurs fehlt der Coin), Umbuchungen
/// neutral (nur ihre Gebühren in USDT/drittem Coin), Gebühr-Abgänge zum Gebühr-Preis. Eine Gebühr im Coin
/// verringert die Menge und damit den Wert am Ende. Einzahlung, Belohnung und Zugang aus Tausch ohne Wert sowie eine
/// Gebühr im dritten Coin ohne Preis zählen zum MARKTKURS ihres Zeitpunkts (neutral); ist ein Zufluss trotzdem
/// unbekannt (Kauf/Verkauf ohne Preis, kein Marktkurs des Tags), fehlt der Coin und steht in `skipped`. Prozent =
/// Gewinn ÷ (Wert am Anfang + Zugänge im Zeitraum).
/// Wert am Anfang = Bestand am Stichtag (Transaktionen bis Ende des UTC-Tags) × Schluss der
/// UTC-Tageskerze am Kalendertag `days` Tage vor heute oder der letzte davor — EINE Tagesgrenze (UTC) wie der
/// Wertverlauf.
///
/// `change` ist die reine Kursänderung des HEUTIGEN Bestands; ohne Käufe/Verkäufe im Zeitraum
/// sind beide gleich.
///
/// Ein Coin ohne Kurs am Anfang fehlt, die übrigen zählen (wie «Seit Kauf» und der Wertverlauf): fehlen
/// seine Kerzen, passen sie nicht zum aktuellen Kurs oder beginnen sie erst nach dem Stichtag, fällt nur
/// dieser Coin heraus und steht in `PortfolioChange.skipped`. Stablecoins ohne Schluss am Stichtag gelten
/// 1, solange ihr aktueller Kurs höchstens 0.1 % von 1 abweicht. Coins ohne aktuellen Kurs fehlen wie im
/// Gesamtwert. nil nur, wenn kein Coin zählt. Bestände exakt summiert (wie die Position).
///
/// Adress-Guthaben: Adress-Einträge in `trades` zählen NICHT (nie rückwirkend); nur die beobachtete Reihe
/// `AddressBalanceHistory` (`addresses`) ab ihrer ersten Beobachtung, Änderungen zum Marktkurs. Für «Heute» muss der
/// Bestand des Aufrufers dazu passen: eigener Bestand (`CoinPosition.ledgerHoldingsExact`) plus zuletzt beobachtetes
/// Guthaben (`AddressBalanceHistory.latestByCoin`) — ohne Reihe nur der eigene. Wie Android.
enum PortfolioPeriodChange {
    /// «7 T»: so viele Tage vor heute liegt der Stichtag.
    static let days = 7

    /// «1 J» (Zeile unter dem Gesamtwert, wie das Widget mit Zeitraum «1 J»): Stichtag vor so vielen
    /// Tagen. Wie `PortfolioPeriodChange.YEAR_DAYS` (Android).
    static let yearDays = 365

    /// - Parameters:
    ///   - holdings: Menge je offenem Coin (Grossschreibung)
    ///   - current: aktuelle USDT-Kurse (Stablecoin-Regel schon angewandt, siehe `PortfolioCalculator`)
    ///   - closes: Tagesschluss in USDT je Coin und UTC-Tag
    ///   - baseDay: Stichtag (Tage seit 1970-01-01)
    /// - Returns: Veränderung in USDT und Prozent; nil = unbekannt
    static func change(holdings: [String: Double], current: [String: Double],
                       closes: [String: [Int: Double]], baseDay: Int) -> PortfolioChange? {
        var then = 0.0
        var now = 0.0
        var counted = 0
        var skipped: [String] = []
        for (rawCoin, amount) in holdings {
            guard amount > PortfolioCalculator.eps, !amount.isInfinite else { continue }
            let coin = PortfolioCalculator.normalizeCoin(rawCoin)
            guard let live = current[coin] ?? current[rawCoin], live > 0, live.isFinite else { continue }
            // Kurs am Anfang unbekannt: nur dieser Coin fehlt
            guard let past = pastPrice(coin, live: live, closes: closes[coin] ?? [:], baseDay: baseDay) else {
                skipped.append(coin)
                continue
            }
            then += amount * past
            now += amount * live
            counted += 1
        }
        guard counted > 0 else { return nil }
        let change = now - then
        return PortfolioChange(amount: change, percent: then > PortfolioCalculator.eps ? change / then * 100 : nil,
                               skipped: skipped.sorted())
    }

    private static let dayMillis: Int64 = 86_400_000

    /// Beginn des Zeitraums für `baseDay`: Ende des UTC-Tags (Schluss der Tageskerze).
    static func windowStart(baseDay: Int) -> Int64 { Int64(baseDay + 1) * dayMillis }

    /// Gewinn/Verlust seit dem Stichtag `baseDay`, der Käufe und Verkäufe kennt (siehe oben).
    /// `fx`: Umrechnung in die Anzeigewährung je Tag (`PortfolioHistoryFx.rateFunction`); nil = USDT. Dann wie der
    /// umgerechnete Wertverlauf: Wert am Anfang zum Kurs des Stichtags, jeder Zufluss (und die Belohnungen) zum Kurs seines
    /// UTC-Tags, Wert jetzt zum heutigen Kurs, Prozent auf die ebenso umgerechnete Basis — Kopf und Kurve zeigen dieselbe
    /// «7 T»-Zahl. Wie Android.
    /// - Returns: Gewinn in USDT (bzw. mit `fx` in der Anzeigewährung) und Prozent; nil = unbekannt
    static func gain(trades: [PortfolioTx], current: [String: Double],
                     closes: [String: [Int: Double]], baseDay: Int,
                     addresses: [AddressBalanceHistory] = [],
                     method: CostMethod = .defaultMethod,
                     fx: ((Int) -> Double)? = nil) -> PortfolioChange? {
        let rates = fx.map { rate in
            Rates(start: rate(baseDay), end: rate(.max)) { time in rate(PortfolioHistory.epochDay(utcMillis: time)) }
        } ?? Rates.none
        // Kauf/Verkauf ohne Preis im Zeitraum: Zufluss unbekannt → Coin auslassen (wie Android)
        let parts = parts(trades: trades, current: current, windowStart: windowStart(baseDay: baseDay),
                          addresses: addresses, method: method,
                          marketPrice: { coin, live, time in
                              marketClose(coin, live: live, closes: closes[coin] ?? [:],
                                          day: PortfolioHistory.epochDay(utcMillis: time))
                          }, rates: rates) { coin, live in
            startClose(coin, live: live, closes: closes[coin] ?? [:], baseDay: baseDay)
        }
        return parts.result
    }

    /// «Gewinn heute»: die Kursänderung des heutigen Bestands `change` (USDT, aus dem Stand) um die
    /// Käufe und Verkäufe seit `windowStart` berichtigt — gleiche Regel wie `gain`. Kurs am Anfang je
    /// Coin aus der Kursveränderung `coinChanges` (gleiche %-Basis): Kurs ÷ (1 + %/100). Coins ohne
    /// diesen Kurs fehlen wie in der Kursänderung (ausser ein ganz im Zeitraum gekaufter Coin: Wert
    /// jetzt − Einstand). Ein Kauf/Verkauf ohne Preis im Zeitraum lässt den Coin ganz fehlen (wie «7 T»:
    /// Zufluss unbekannt) — er kommt in `skipped`, seine Kursänderung fällt aus dem Betrag. Ohne
    /// Transaktionen im Zeitraum unverändert. `only`: nur dieser Coin (`todayByCoin`).
    static func todayAdjusted(_ change: PortfolioChange?, trades: [PortfolioTx], current: [String: Double],
                              coinChanges: [String: Double], windowStart: Int64,
                              only: String? = nil, addresses: [AddressBalanceHistory] = [],
                              method: CostMethod = .defaultMethod) -> PortfolioChange? {
        guard let change else { return nil }
        // Marktkurs einer Auszahlung ohne Preis / Adress-Änderung heute: der aktuelle Kurs
        let parts = parts(trades: trades, current: current, windowStart: windowStart, only: only, addresses: addresses,
                          method: method,
                          marketPrice: { coin, live, _ in PortfolioStables.needsQuote(coin) ? live : 1 }) { coin, live in
            guard PortfolioStables.needsQuote(coin) else { return 1 }
            guard let live, let pct = coinChanges[coin], pct.isFinite, pct > -100 else { return nil }
            return live / (1 + pct / 100)
        }
        guard parts.windowTrades > 0 else { return change }
        guard parts.counted > 0 else { return nil }
        // Ausgelassene Coins (Transaktion ohne Preis) auch aus der Kursänderung nehmen
        let amount = change.amount + parts.gain - parts.held - parts.skippedHeld
        let base = parts.start + parts.buys
        // Gezählte Coins (z. B. ganz heute gekauft, ohne Veränderung) fehlen nicht
        let skipped = Array(Set(change.skipped.filter { !parts.countedCoins.contains($0) } + parts.skipped)).sorted()
        return PortfolioChange(amount: amount, percent: base > PortfolioCalculator.eps ? amount / base * 100 : nil,
                               skipped: skipped, rewards: parts.rewards)
    }

    /// «Heute» aus der Veränderung je Coin — Rechnung in `PortfolioHistory.todayFromCoinChanges`; hier wie
    /// Android (`PortfolioPeriodChange.todayFromCoinChanges`), für Widget-Stand und Tests.
    static func todayFromCoinChanges(holdings: [String: Double], current: [String: Double],
                                     coinChanges: [String: Double]) -> PortfolioChange? {
        PortfolioHistory.todayFromCoinChanges(holdings: holdings, current: current, coinChanges: coinChanges)
    }

    /// «24h» je Position (Zeilen im Portfolio-Tab und im Widget): MEIN Gewinn/Verlust auf diesem Coin seit
    /// `windowStart` — dieselbe Rechnung wie «Gewinn heute» (`todayFromCoinChanges` + `todayAdjusted`), für
    /// jeden offenen Coin allein: Wert jetzt − Wert am Anfang − Zuflüsse seither (ein Kauf heute zählt zu
    /// seinem Preis, kein Gewinn; ein Verkauf heute zu seinem Erlös). Prozent = Betrag ÷ (Wert am Anfang +
    /// Zugänge). Die Summe über alle Coins ist «Gewinn heute». Fehlt der Kurs am Anfang (keine Veränderung,
    /// ausser ganz im Zeitraum gekauft), der aktuelle Kurs oder ein Zufluss (Transaktion ohne Preis), fehlt
    /// der Coin («—»). Ergebnis je Coin in Grossschreibung, Betrag in USDT. Wie Android.
    static func todayByCoin(holdings: [String: Double], trades: [PortfolioTx], current: [String: Double],
                            coinChanges: [String: Double], windowStart: Int64,
                            addresses: [AddressBalanceHistory] = [],
                            method: CostMethod = .defaultMethod) -> [String: PortfolioChange] {
        var out: [String: PortfolioChange] = [:]
        for (rawCoin, amount) in holdings {
            guard amount > PortfolioCalculator.eps, !amount.isInfinite else { continue }
            let coin = PortfolioCalculator.normalizeCoin(rawCoin)
            // Ohne Veränderung: 0 mit «fehlt» — zählt nur, wenn der Coin ganz im Zeitraum dazukam
            let raw = PortfolioHistory.todayFromCoinChanges(holdings: [coin: amount], current: current, coinChanges: coinChanges)
                ?? PortfolioChange(amount: 0, percent: nil, skipped: [coin])
            guard let adjusted = todayAdjusted(raw, trades: trades, current: current, coinChanges: coinChanges,
                                               windowStart: windowStart, only: coin, addresses: addresses,
                                               method: method),
                  !adjusted.skipped.contains(coin), adjusted.amount.isFinite else { continue }
            out[coin] = adjusted
        }
        return out
    }

    /// Teilsummen über alle Coins (USDT) — wie `Parts` (Android).
    /// Umrechnung der Teilsummen von `gain` (`fx`): Wert am Anfang zu `start`, Wert jetzt zu `end`, Zuflüsse zum Kurs
    /// ihres Zeitpunkts (`flow`, ms → Kurs). `.none` = USDT. Wie Android `Rates`.
    private struct Rates {
        let start: Double
        let end: Double
        let flow: (Int64) -> Double
        static var none: Rates { Rates(start: 1, end: 1) { _ in 1 } }
    }

    private struct Parts {
        /// Σ Gewinn je Coin (Wert Ende − Wert Anfang − Zuflüsse).
        var gain = 0.0
        /// Σ Bestand am Stichtag × Kurs am Anfang.
        var start = 0.0
        /// Σ Käufe im Zeitraum (Einstand).
        var buys = 0.0
        /// Σ heutiger Bestand × (Kurs jetzt − Kurs am Anfang) — die reine Kursänderung derselben Coins.
        var held = 0.0
        var counted = 0
        var windowTrades = 0
        /// Coins ohne Kurs am Anfang (fehlen in der Summe).
        var skipped: [String] = []
        /// Gezählte Coins.
        var countedCoins: Set<String> = []
        /// Σ reine Kursänderung der wegen Transaktion ohne Preis ausgelassenen Coins (soweit Kurs am Anfang bekannt).
        var skippedHeld = 0.0
        /// Σ Wert beim Erhalt der Belohnungen im Zeitraum (gezählte Coins).
        var rewards = 0.0

        var result: PortfolioChange? {
            guard counted > 0 else { return nil }
            let base = start + buys
            return PortfolioChange(amount: gain, percent: base > PortfolioCalculator.eps ? gain / base * 100 : nil,
                                   skipped: skipped.sorted(), rewards: rewards)
        }
    }

    /// - Parameter startPrice: Kurs am Anfang je Coin (live = aktueller Kurs, nil ohne Bestand/Kurs); nil =
    ///   unbekannt — dann fehlt nur dieser Coin (`Parts.skipped`)
    /// - Parameter marketPrice: Marktkurs zum Zeitpunkt (ms) für Auszahlungen ohne Preis und Adress-Änderungen
    ///   (`PortfolioLedger.needsMarketPrice`); nil = unbekannt (Coin fehlt)
    private static func parts(trades: [PortfolioTx], current: [String: Double], windowStart: Int64,
                              only: String? = nil, addresses: [AddressBalanceHistory] = [],
                              method: CostMethod = .defaultMethod,
                              marketPrice: (String, Double?, Int64) -> Double?,
                              rates: Rates = .none,
                              startPrice: (String, Double?) -> Double?) -> Parts {
        var out = Parts()
        // Adress-Einträge der Eingabe nie rückwirkend; nur die beobachtete Reihe (`AddressBalanceHistory`). Tausch ohne
        // Wert (und die übrigen Fälle von `PortfolioLedger.valueAtDayClose`) zum Marktkurs `marketPrice` wie die Karte:
        // EIN Wert für abgegebenen und erhaltenen Coin statt jede Seite zu ihrem eigenen Kurs
        let raw = trades.filter { $0.amountExact > 0 && !AddressBalance.isAddressTrade($0.id) }
            + AddressBalanceHistory.trades(addresses)
        let valid = withoutActuallyEscaping(marketPrice) { market in
            PortfolioLedger.valueAtDayClose(raw, price: { coin, time in
                guard let p = market(coin, current[coin].flatMap { $0 > 0 && $0.isFinite ? $0 : nil }, time),
                      p > 0, p.isFinite else { return nil }
                return PortfolioDecimal.of(p)
            })
        }
        // Alle Arten und Gebühren im dritten Coin über `PortfolioLedger` (Bestand und Zuflüsse wie der Rechner); die
        // Schritte aller Coins in einem Durchgang, nur für `only` die seines Coins
        var byCoin: [(coin: String, events: [PortfolioLedgerEvent])] = []
        if let only {
            if PortfolioLedger.coins(valid).contains(only) {
                byCoin = [(coin: only, events: PortfolioLedger.events(only, trades: valid))]
            }
        } else {
            byCoin = PortfolioLedger.eventsByCoin(valid)
        }
        for (coin, events) in byCoin {
            // Bestände exakt wie die Position nach der Methode (`PortfolioHoldingsBook`); erst zum Bewerten als Double
            var book = PortfolioHoldingsBook(method: method)
            for e in events where e.trade.time <= windowStart { book.apply(e) }
            let startExact = book.total
            let inWindow = events.filter { $0.trade.time > windowStart }
            let live = current[coin].flatMap { $0 > 0 && $0.isFinite ? $0 : nil }
            // Zuflüsse im Zeitraum; nil = einer davon unbekannt (z. B. Kauf ohne Preis)
            var flows: [(event: PortfolioLedgerEvent, amount: Double)]? = []
            for e in inWindow {
                let market = PortfolioLedger.needsMarketPrice(e) ? marketPrice(coin, live, e.trade.time) : nil
                // Gebühr im dritten Coin ohne Preis: Marktkurs des Gebühr-Coins (sein aktueller Kurs nach dem letzten Schluss)
                var feeMarket: Double?
                if PortfolioLedger.needsFeeMarketPrice(e), let feeCoin = e.trade.thirdFeeCoin {
                    feeMarket = marketPrice(feeCoin, current[feeCoin].flatMap { $0 > 0 && $0.isFinite ? $0 : nil }, e.trade.time)
                }
                // Abgänge gekappt auf den Topf des Schritts (FIFO je Wallet: seine Wallet)
                if let f = PortfolioLedger.flow(e, holdingsBefore: book.cap(for: e), marketPrice: market,
                                                feeMarketPrice: feeMarket, method: book.method) {
                    flows?.append((event: e, amount: f))
                } else {
                    flows = nil
                }
                book.apply(e)
            }
            let endExact = book.total
            let startHoldings = PortfolioDecimal.isDust(startExact) ? 0 : PortfolioDecimal.double(startExact)
            let endHoldings = PortfolioDecimal.isDust(endExact) ? 0 : PortfolioDecimal.double(endExact)
            if startHoldings == 0 && endHoldings == 0 && inWindow.isEmpty { continue }

            // Coins ohne aktuellen Kurs fehlen wie im Gesamtwert
            if endHoldings > 0 && live == nil { continue }
            let p0 = startPrice(coin, live).flatMap { $0 >= 0 && $0.isFinite ? $0 : nil }
            guard let known = flows else {
                // Zufluss unbekannt → Coin auslassen («—»-Hinweis), statt ihn zum Anfangskurs anzunehmen
                out.skipped.append(coin)
                out.windowTrades += inWindow.count
                if let p0, let live { out.skippedHeld += endHoldings * (live - p0) }
                continue
            }
            if startHoldings > 0 && p0 == nil {
                out.skipped.append(coin)
                continue
            }
            // In Schritten mit festen Typen (für den Type-Checker)
            let livePrice: Double = live ?? 0
            let firstPrice: Double = p0 ?? 0
            let endValue: Double = endHoldings * livePrice * rates.end
            let startValue: Double = startHoldings * firstPrice * rates.start
            var gain = endValue - startValue
            for item in known {
                // Zufluss zum Kurs seines Tags (USDT: 1)
                let f = (event: item.event, amount: item.amount * rates.flow(item.event.trade.time))
                gain -= f.amount
                // Basis des Prozents: Zugänge (Kauf, Belohnung, Einzahlung, Zugang aus Tausch, Gebühren einer Umbuchung);
                // die abgebende Seite eines Tauschs zählt wie ein Verkauf nicht
                if !f.event.feeLeg && !f.event.trade.type.isOutflow && (f.event.swapIn || f.event.trade.type != .SWAP) {
                    out.buys += f.amount
                }
                if !f.event.feeLeg && f.event.trade.type == .REWARD { out.rewards += f.amount }
            }
            out.gain += gain
            out.start += startHoldings * (p0 ?? 0) * rates.start
            if let p0, let live { out.held += endHoldings * (live - p0) }
            out.windowTrades += inWindow.count
            out.counted += 1
            out.countedCoins.insert(coin)
        }
        return out
    }

    /// Marktkurs am UTC-Tag `day` (Auszahlung ohne Preis, Adress-Änderung): Schluss dieses Tags oder der letzte davor;
    /// nach dem letzten Schluss der aktuelle Kurs `live`. Gleiche Identitätsprüfung wie der Kurs am Anfang; nil =
    /// unbekannt. Wie Android.
    private static func marketClose(_ coin: String, live: Double?, closes: [Int: Double], day: Int) -> Double? {
        guard PortfolioStables.needsQuote(coin) else { return 1 }
        let sorted = closes.filter { $0.value > 0 && $0.value.isFinite }
            .sorted { $0.key < $1.key }
            .map { (day: $0.key, close: $0.value) }
        guard let lastDay = sorted.last?.day else { return live.flatMap { PortfolioStables.fallbackWithoutClose(coin, live: $0) } }
        if let live, !PricePlausibility.closesMatchLive(sorted, live: live) { return nil }
        if let live, day > lastDay { return PortfolioStables.price(coin, market: live) }
        return PortfolioStables.price(coin, market: PortfolioHistory.closeOnOrBefore(sorted, day: day))
    }

    /// Marktkurs von `coin` zum Zeitpunkt `time` (ms) aus den Tagesschlusskursen `closes` (nach dem letzten Schluss der
    /// aktuelle Kurs `live`) — gleiche Regel wie eine Auszahlung ohne Preis in `gain`. Für `PortfolioCalculator.summarize`
    /// (`withdrawalPrice`, «Ausgebucht»); nil = unbekannt. Wie `marketPriceAt` (Android).
    static func marketPriceAt(_ coin: String, live: Double?, closes: [String: [Int: Double]], time: Int64) -> Double? {
        let symbol = PortfolioCalculator.normalizeCoin(coin)
        return marketClose(symbol, live: live, closes: closes[symbol] ?? [:],
                           day: PortfolioHistory.epochDay(utcMillis: time))
    }

    /// Kurs am Anfang aus den Tagesschlusskursen (gleiche Regeln wie `change`); nil = unbekannt.
    private static func startClose(_ coin: String, live: Double?, closes: [Int: Double], baseDay: Int) -> Double? {
        guard PortfolioStables.needsQuote(coin) else { return 1 }
        let sorted = closes.filter { $0.value > 0 && $0.value.isFinite }
            .sorted { $0.key < $1.key }
            .map { (day: $0.key, close: $0.value) }
        if sorted.isEmpty && !PortfolioStables.isStable(coin) { return nil }
        if let live, !PricePlausibility.closesMatchLive(sorted, live: live) { return nil }
        guard let close = PortfolioHistory.closeOnOrBefore(sorted, day: baseDay) else {
            return live.flatMap { PortfolioStables.fallbackWithoutClose(coin, live: $0) }
        }
        return PortfolioStables.price(coin, market: close)
    }

    /// Kurs am Stichtag; nil = unbekannt (dann die ganze Veränderung).
    private static func pastPrice(_ coin: String, live: Double, closes: [Int: Double], baseDay: Int) -> Double? {
        guard PortfolioStables.needsQuote(coin) else { return 1 }
        let sorted = closes.filter { $0.value > 0 && $0.value.isFinite }
            .sorted { $0.key < $1.key }
            .map { (day: $0.key, close: $0.value) }
        let stable = PortfolioStables.isStable(coin)
        // Andere Stablecoins ohne Schluss am Stichtag: 1 nur, solange der aktuelle Kurs nah
        // an 1 liegt (`PortfolioStables.fallbackWithoutClose`); sonst unbekannt
        if sorted.isEmpty && !stable { return nil }
        guard PricePlausibility.closesMatchLive(sorted, live: live) else { return nil }
        guard let close = PortfolioHistory.closeOnOrBefore(sorted, day: baseDay) else {
            return PortfolioStables.fallbackWithoutClose(coin, live: live)
        }
        return PortfolioStables.price(coin, market: close)
    }
}
