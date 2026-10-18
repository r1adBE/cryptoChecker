import Foundation

/// Zeitraum des Wertverlaufs im Portfolio-Tab — wie `PortfolioHistoryRange` (Android).
/// `days` = so viele Tage vor heute beginnt er höchstens («Seit 1. Kauf»: 0, kein fester Zeitraum).
enum PortfolioHistoryRange: String, CaseIterable, Identifiable, Codable, Sendable {
    case week
    case month
    case year
    /// «Seit 1. Kauf»: ab der ersten Transaktion, ohne feste Zahl Tage — frühestens ab
    /// `PortfolioHistory.dataStartEpochDay`, dem ersten Tag mit Kursdaten (siehe `PortfolioHistory.startDay`).
    case sinceFirst

    var id: String { rawValue }

    var days: Int {
        switch self {
        case .week: return 7
        case .month: return 30
        case .year: return 365
        case .sinceFirst: return 0
        }
    }
}

/// Ein Tag des Verlaufs: Wert am Tagesende (heute: jetzt). `epochDay` = Tage seit 1970-01-01.
/// `open`, `high` und `low` nur, wenn Tageskerzen mit Eröffnung/Hoch/Tief vorlagen (für die
/// Kerzen-Ansicht, siehe `PortfolioHistory.build` und `PortfolioCandles`); sonst nil.
struct PortfolioHistoryPoint: Equatable, Sendable {
    let epochDay: Int
    let value: Double
    var open: Double? = nil
    var high: Double? = nil
    var low: Double? = nil
}

/// Eröffnung, Hoch und Tief einer UTC-Tageskerze in USDT (der Schluss steht bei den Schlusskursen)
/// — wie `PortfolioDayRange` (Android).
struct PortfolioDayRange: Equatable, Sendable, Codable {
    let open: Double
    let high: Double
    let low: Double

    /// Alle drei Werte endlich und grösser 0.
    var valid: Bool {
        open > 0 && high > 0 && low > 0 && open.isFinite && high.isFinite && low.isFinite
    }
}

/// Fertiger Verlauf eines Zeitraums — wie `PortfolioHistorySeries` (Android).
struct PortfolioHistorySeries: Equatable, Sendable {
    /// Aufsteigend nach Tag; leer, wenn kein Coin einen Verlauf hat.
    let points: [PortfolioHistoryPoint]
    /// Coins mit Bestand im Zeitraum, aber ohne Tageskurse (fehlen im Chart, «ohne …»).
    let skipped: [String]
    /// Letzter minus erster Wert; nil mit weniger als zwei Punkten.
    let change: Double?
    /// Änderung in Prozent des ersten Werts; nil ohne Ausgangswert.
    let changePercent: Double?
    /// Im Zeitraum (nach dem ersten Tag) wurde gekauft oder verkauft.
    let tradesInRange: Bool
    /// «Seit 1. Kauf» reicht nicht bis zum ersten Kauf zurück (Kursdaten erst ab `PortfolioHistory.dataStartEpochDay`).
    var capped: Bool = false
    /// Zuflüsse im Zeitraum (nach dem ersten Tag) der gezeichneten Coins, in der Einheit der Punkte:
    /// Kauf + (Menge × Preis + Gebühr USDT), Verkauf − (Erlös − Gebühr USDT), wie `PortfolioPeriodChange.gain`.
    /// nil = unbekannt (Kauf/Verkauf ohne Preis im Zeitraum).
    var cashFlows: [PortfolioCashFlow]? = []
    /// Gewinn/Verlust: Wert der Coins, die dafür zählen (je Punkt, gleiche Tage wie `points`, mit
    /// Eröffnung/Hoch/Tief wie dort) — ohne `pnlSkipped`. Daraus mit `pnlFlows` die Kurve `pnlPoints`.
    var pnlValues: [PortfolioHistoryPoint] = []
    /// Zuflüsse dieser Coins (`PortfolioLedger.flow`) je lokalem Tag: «Seit 1. Kauf» alle (vor dem ersten
    /// Punkt auf dessen Tag gelegt), sonst die nach dem Ende des ersten Tags.
    var pnlFlows: [PortfolioCashFlow] = []
    /// Coins im Wertverlauf, die im Gewinn/Verlust fehlen (Zufluss unbekannt oder Bestand ohne Kurs).
    var pnlSkipped: [String] = []
    /// «Seit 1. Kauf»: Gewinn/Verlust ab 0 vor dem ersten Kauf (Wert − investiert), sonst ab dem ersten Tag.
    var pnlSinceFirst: Bool = false
    /// Im Zeitraum der Gewinn/Verlust-Kurve liegen eigene Auszahlungen ihrer Coins: Die Kurve enthält die Wertänderung
    /// der ausgebuchten Coins, solange sie gehalten wurden (Abfluss zum Marktwert) — die Karte («Seit Kauf»,
    /// «realisiert») nicht. Kopf/Hinweis: «inkl. Wertänderung ausgebuchter Coins». Wie Android.
    var withdrawalsInRange: Bool = false

    /// Genug für eine Linie.
    var hasChart: Bool { points.count >= 2 }

    /// Gewinn/Verlust je Tag in der Einheit der Punkte: Wert(d) − netto investiert(d). Netto investiert =
    /// Σ `pnlFlows` bis Tag d, im Zeitraum zusätzlich der Wert am ersten Tag (die Kurve beginnt dort bei 0);
    /// «Seit 1. Kauf» ab 0 vor dem ersten Kauf. Käufe/Verkäufe verschieben die Kurve also nicht, nur
    /// Kursbewegungen der gehaltenen Coins. Eröffnung/Hoch/Tief um denselben Betrag verschoben. Wie Android.
    var pnlPoints: [PortfolioHistoryPoint] {
        guard let first = pnlValues.first else { return [] }
        let flows = pnlFlows.sorted { $0.epochDay < $1.epochDay }
        var next = 0
        var invested = pnlSinceFirst ? 0 : first.value
        return pnlValues.map { p in
            while next < flows.count && flows[next].epochDay <= p.epochDay {
                invested += flows[next].amount
                next += 1
            }
            return PortfolioHistoryPoint(epochDay: p.epochDay, value: p.value - invested,
                                         open: p.open.map { $0 - invested }, high: p.high.map { $0 - invested },
                                         low: p.low.map { $0 - invested })
        }
    }

    /// Genug Gewinn/Verlust-Punkte für eine Linie.
    var hasPnlChart: Bool { pnlValues.count >= 2 }

    /// Gewinn/Verlust über den Zeitraum (letzter Punkt von `pnlPoints`); nil ohne Kurve.
    var pnlChange: Double? { hasPnlChart ? pnlPoints.last?.value : nil }

    /// Basis des Prozents, wie «Heute» und die Zeilen (`PortfolioPeriodChange`): im Zeitraum Wert am ersten Tag +
    /// Zugänge danach (positive Zuflüsse: Käufe, Belohnungen, Einzahlungen samt Gebühren; Verkäufe NICHT
    /// abgezogen), «Seit 1. Kauf» Σ aller Zugänge (alles eingesetzte Geld). nil ohne Kurve. Wie Android.
    var pnlCapital: Double? {
        guard hasPnlChart, let first = pnlValues.first else { return nil }
        return (pnlSinceFirst ? 0 : first.value) + pnlFlows.filter { $0.amount > 0 }.reduce(0) { $0 + $1.amount }
    }

    /// `pnlChange` in Prozent von `pnlCapital`; nil, wenn diese Basis höchstens ~0 ist — dann nur der Betrag.
    var pnlPercent: Double? {
        guard let change = pnlChange, let base = pnlCapital, base > PortfolioCalculator.eps else { return nil }
        return change / base * 100
    }

    /// Änderung ohne Käufe/Verkäufe (wie der Gesamtwert-Kopf): `change` − Σ `cashFlows`; nil ohne
    /// Verlauf oder wenn ein Zufluss unbekannt ist.
    var netChange: Double? {
        guard let change, let cashFlows else { return nil }
        return change - cashFlows.reduce(0) { $0 + $1.amount }
    }

    /// `netChange` in Prozent von Wert am Anfang + Käufe im Zeitraum (wie «7 T»); nil ohne Basis.
    var netChangePercent: Double? {
        guard let net = netChange, let first = points.first?.value else { return nil }
        let base = first + (cashFlows ?? []).filter { $0.amount > 0 }.reduce(0) { $0 + $1.amount }
        return base > PortfolioCalculator.eps ? net / base * 100 : nil
    }

    static let empty = PortfolioHistorySeries(points: [], skipped: [], change: nil, changePercent: nil, tradesInRange: false)
}

/// Zufluss am (UTC-)Tag `epochDay` (Kauf positiv, Verkauf negativ), in der Einheit des Verlaufs —
/// wie `PortfolioCashFlow` (Android).
struct PortfolioCashFlow: Equatable, Sendable {
    let epochDay: Int
    let amount: Double
}

/// Wertverlauf des Portfolios aus den Transaktionen — Spiegel von `PortfolioHistory.kt`:
///  - EINE Tagesgrenze: der UTC-Tag, wie die Tageskerzen und «7 T/1 J». Bestand je Coin und Tag = alle
///    Transaktionen bis zum Ende des UTC-Tags (`utcDayEnd`), Zuflüsse auf dem UTC-Tag ihrer Zeit (ein Kauf um 00:30
///    Zürich-Sommerzeit = 22:30 UTC gehört zum Vortag — sonst Scheingewinn gegenüber «7 T»). `dayEndMillis` der Aufrufer
///    zählt für die Zuordnung nicht mehr. Verkäufe werden wie in `PortfolioCalculator` beim Bestand gekappt.
///  - Wert = Σ Bestand × Tagesschlusskurs (UTC-Tageskerze desselben Kalendertags) × `fxRate`.
///    Fehlt an einem Tag die Kerze, gilt der letzte bekannte Schluss davor; vor der ersten
///    Kerze zählt der Coin nicht.
///  - Heute: Bestand nach allen Transaktionen × aktueller Kurs. Ein heute gehaltener Coin ohne
///    aktuellen Kurs fehlt (Token nicht bestätigt, steht unter «ohne …»); nur Coins ohne heutigen
///    Bestand behalten ihre Reihe ungeprüft (kein aktueller Kurs zum Vergleichen, heute kein Wert).
///  - Stablecoins nach `PortfolioStables`: USDT = 1, andere Stablecoins ihr Tagesschluss (ohne
///    Schluss kein Wert, wie jeder Coin).
///    Andere Coins ganz ohne Kerzen fehlen und stehen in `skipped` — ebenso, wenn der jüngste
///    Schluss so weit vom aktuellen Kurs abweicht, dass es ein anderer Token sein muss (Faktor 3,
///    `PricePlausibility.closesMatchLive`; keine 25-%-Regel).
///  - `fxRate` gilt für alle Punkte gleich; Tageskurse je Punkt siehe `PortfolioHistoryFx`.
///  - Beginn = erster Transaktionstag oder Beginn des Zeitraums, je nachdem, was später ist
///    (`startDay`); «Seit 1. Kauf» ohne Begrenzung auf eine Zahl Jahre, nur frühestens ab
///    `dataStartEpochDay` — davor gibt es keine Tageskerzen (`isCapped`).
///  - Tageskerzen: mindestens `maxDays` je Coin, für «Seit 1. Kauf» so viele wie nötig
///    (`candleDays`), abgefragt in Stücken von höchstens `maxCandlesPerRequest` (`candleChunks`).
enum PortfolioHistory {
    /// Tageskerzen je Coin mindestens (eine Abfrage mit diesem Limit deckt «1 J» samt heute ab).
    static let maxDays = 366
    /// Erster Tag mit Kursdaten (17.08.2017, Tage seit 1970-01-01): Binance-Tageskerzen gibt es erst ab
    /// da (BTCUSDT) — «Seit 1. Kauf» beginnt frühestens hier. Mehr Tage brauchen nur mehr Abfragen zu
    /// je `maxCandlesPerRequest` Kerzen (`candleChunks`); später gelistete Coins brechen früher ab.
    static let dataStartEpochDay = 17_395
    /// Höchstens so viele Tageskerzen liefert die Kerzen-Quelle je Abfrage.
    static let maxCandlesPerRequest = 1000

    /// «Heute» als Kursänderung des heutigen Bestands aus der Veränderung je Coin `coinChanges` (gleiche
    /// %-Basis): Kurs am Anfang = Kurs ÷ (1 + %/100). Coins ohne Veränderung fehlen ganz — weder im Betrag
    /// noch in der Basis (kein flach aufgefüllter Kurs, der das Prozent verwässert) — und stehen in
    /// `skipped`; USDT zählt 1 → 1. Danach `todayAdjusted`: EINE Zahl für Karte, Widget und Portfolio-Alarm.
    /// nil = kein Coin mit Veränderung. Wie Android.
    static func todayFromCoinChanges(holdings: [String: Double], current: [String: Double],
                                     coinChanges: [String: Double]) -> PortfolioChange? {
        var then = 0.0
        var now = 0.0
        var counted = 0
        var skipped: [String] = []
        for (rawCoin, amount) in holdings {
            guard amount > PortfolioCalculator.eps, !amount.isInfinite else { continue }
            let coin = PortfolioCalculator.normalizeCoin(rawCoin)
            guard let live = current[coin] ?? current[rawCoin], live > 0, live.isFinite else { continue }
            let start: Double
            if !PortfolioStables.needsQuote(coin) {
                start = 1
            } else if let pct = coinChanges[coin] ?? coinChanges[rawCoin], pct.isFinite, pct > -100 {
                start = live / (1 + pct / 100)
            } else {
                skipped.append(coin)
                continue
            }
            then += amount * start
            now += amount * live
            counted += 1
        }
        guard counted > 0 else { return nil }
        let change = now - then
        return PortfolioChange(amount: change, percent: then > PortfolioCalculator.eps ? change / then * 100 : nil,
                               skipped: skipped.sorted())
    }

    private static let dayMillis: Int64 = 86_400_000

    /// Stablecoins: die eine Liste aus `PortfolioStables` (Tagesschluss, sonst kein Wert; USDT immer 1).
    static var stables: Set<String> { PortfolioStables.coins }

    static func isStable(_ coin: String) -> Bool { PortfolioStables.isStable(coin) }

    /// UTC-Tag einer Kerzen-Eröffnungszeit (ms), auch vor 1970 korrekt abgerundet.
    static func epochDay(utcMillis millis: Int64) -> Int {
        var days = millis / dayMillis
        if millis % dayMillis < 0 { days -= 1 }
        return Int(days)
    }

    /// Letzte Millisekunde des UTC-Tags `day` — die EINE Tagesgrenze von Verlauf und «7 T/1 J» (wie die Tageskerzen).
    /// Das Steuerjahr (`TaxExport.localDay`) und der Stichtag (`CutoffDay.endOfDayMillis`) nehmen dagegen das rechtliche
    /// Datum in der Zeitzone des Geräts. Wie Android.
    static func utcDayEnd(_ day: Int) -> Int64 { Int64(day + 1) * dayMillis - 1 }

    /// Letzte Millisekunde des Tags `epochDay` in der Zeitzone `timeZone`.
    static func dayEndMillis(_ epochDay: Int, timeZone: TimeZone = .current) -> Int64 {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let next = LocalDay(epochDay: epochDay + 1)
        let start = cal.date(from: DateComponents(year: next.year, month: next.month, day: next.day)) ?? Date()
        return Int64((start.timeIntervalSince1970 * 1000).rounded()) - 1
    }

    /// Bestand eines Coins nach jeder Transaktion: (Zeit, Bestand danach), zeitlich aufsteigend.
    /// Alle Arten und Gebühren im dritten Coin wie der Rechner (`PortfolioHoldingsBook` nach der Einstandsmethode
    /// `method`, FIFO je Wallet je Wallet gekappt), exakt gezählt und als Double für die Bewertung — wie Android.
    static func holdingsTimeline(_ coin: String, trades: [PortfolioTx],
                                 method: CostMethod = .defaultMethod) -> [(time: Int64, holdings: Double)] {
        holdingsTimelineExact(coin, trades: trades, method: method).map { (time: $0.time, holdings: PortfolioDecimal.double($0.holdings)) }
    }

    /// `holdingsTimeline` exakt: der Verlauf multipliziert damit, ohne Gleitkomma-Rundung der Menge — wie Android.
    static func holdingsTimelineExact(_ coin: String, trades: [PortfolioTx],
                                      method: CostMethod = .defaultMethod) -> [(time: Int64, holdings: Decimal)] {
        let events = PortfolioLedger.events(coin, trades: trades)
        var book = PortfolioHoldingsBook(method: method)
        var out: [(time: Int64, holdings: Decimal)] = []
        out.reserveCapacity(events.count)
        for e in events {
            guard e.trade.amountExact > 0 else { continue }
            book.apply(e)
            out.append((time: e.trade.time, holdings: book.total))
        }
        return out
    }

    /// Exakter Bestand zum Zeitpunkt `atMillis` (einschliesslich) aus `holdingsTimelineExact`.
    private static func holdingsAtExact(_ timeline: [(time: Int64, holdings: Decimal)], _ atMillis: Int64) -> Decimal {
        var result: Decimal = 0
        for entry in timeline {
            if entry.time > atMillis { break }
            result = entry.holdings
        }
        return result
    }

    /// Bestand zum Zeitpunkt `atMillis` (einschliesslich).
    static func holdingsAt(_ timeline: [(time: Int64, holdings: Double)], _ atMillis: Int64) -> Double {
        var result = 0.0
        for entry in timeline {
            if entry.time > atMillis { break }
            result = entry.holdings
        }
        return result
    }

    /// Verlauf für `range` bis `todayEpochDay` — Parameter wie in Android.
    /// `ranges`: Eröffnung/Hoch/Tief der Tageskerzen je Coin und UTC-Tag (optional). Damit bekommt
    /// jeder Punkt `open`/`high`/`low` = Σ Bestand am Tagesende × Eröffnung/Hoch/Tief des Coins.
    /// Näherung: Hoch und Tief der Coins fallen kaum je auf denselben Zeitpunkt — die Summe ist eine
    /// obere bzw. untere SCHRANKE des tatsächlichen Tageshochs/-tiefs des Portfolios, kein gemessener
    /// Wert. Coins ohne Kerze an dem Tag (oder USDT) zählen mit ihrem Tageswert in allen dreien.
    /// `addresses`: beobachtete Adress-Guthaben (`AddressBalanceHistory`) — Adress-Einträge in `trades`
    /// (`AddressBalance.withAddresses`) zählen nie rückwirkend, nur die Reihe ab ihrer ersten Beobachtung;
    /// Änderungen und Auszahlungen ohne Preis zum Marktkurs ihres Tags. Wie Android.
    static func build(trades: [PortfolioTx],
                      closes: [String: [Int: Double]],
                      livePrices: [String: Double],
                      range: PortfolioHistoryRange,
                      todayEpochDay: Int,
                      dayEndMillis: (Int) -> Int64 = { PortfolioHistory.utcDayEnd($0) },
                      fxRate: Double = 1,
                      ranges: [String: [Int: PortfolioDayRange]] = [:],
                      addresses: [AddressBalanceHistory] = [],
                      method: CostMethod = .defaultMethod) -> PortfolioHistorySeries {
        // EINE Tagesgrenze: UTC-Tag (wie Tageskerzen und «7 T/1 J»), nicht die lokale Zeitzone des Aufrufers
        let dayEndMillis: (Int) -> Int64 = { PortfolioHistory.utcDayEnd($0) }
        // Adress-Einträge nie rückwirkend; nur die beobachtete Reihe
        let valid = trades.filter { $0.amount > 0 && !$0.amount.isInfinite && !AddressBalance.isAddressTrade($0.id) }
            + AddressBalanceHistory.trades(addresses)
        guard let firstTime = valid.map(\.time).min() else { return .empty }
        let rate = fxRate > 0 && fxRate.isFinite ? fxRate : 1

        let start = startDay(range: range, firstTradeMillis: firstTime, todayEpochDay: todayEpochDay,
                             dayEndMillis: dayEndMillis)
        let capped = isCapped(range: range, firstTradeMillis: firstTime, todayEpochDay: todayEpochDay,
                              dayEndMillis: dayEndMillis)

        let coins = PortfolioLedger.coins(valid).sorted()
        // Bestände exakt (Wert = Menge × Kurs ohne Gleitkomma-Rundung der Menge); als Double nur für die Prüfungen «gehalten»
        var exactTimelines: [String: [(time: Int64, holdings: Decimal)]] = [:]
        var timelines: [String: [(time: Int64, holdings: Double)]] = [:]
        for coin in coins {
            let exact = holdingsTimelineExact(coin, trades: valid, method: method)
            exactTimelines[coin] = exact
            timelines[coin] = exact.map { (time: $0.time, holdings: PortfolioDecimal.double($0.holdings)) }
        }

        let startMillis = dayEndMillis(start - 1) + 1
        let held = coins.filter { heldWithin(timelines[$0] ?? [], from: startMillis) }

        // Je Coin die Kerzen aufsteigend (USDT braucht keine). Identitätsprüfung: liegt der jüngste
        // Schluss um mehr als Faktor 3 neben dem aktuellen Kurs, zählt die Reihe nicht (grosse echte
        // Tagesbewegungen bleiben drin; der heutige Punkt nimmt den aktuellen Kurs).
        var sortedCloses: [String: [(day: Int, close: Double)]] = [:]
        for coin in held where PortfolioStables.needsQuote(coin) {
            let series = (closes[coin] ?? [:])
                .filter { $0.value > 0 && $0.value.isFinite }
                .sorted { $0.key < $1.key }
                .map { (day: $0.key, close: $0.value) }
            // Heute gehaltener Coin ohne aktuellen Kurs: Token nicht bestätigt → fehlt (strenge Prüfung);
            // ohne heutigen Bestand gibt es keinen Kurs zum Vergleichen, die Reihe bleibt.
            let live = livePrices[coin].flatMap { $0 > 0 && $0.isFinite ? $0 : nil }
            let heldToday = (timelines[coin]?.last?.holdings ?? 0) > 0
            sortedCloses[coin] = (live == nil && !heldToday) || PricePlausibility.closesMatchLive(series, live: live)
                ? series : []
        }
        // Nur USDT fehlt nie (immer 1); alle anderen Coins ohne (passende) Kerzen schon — auch
        // Stablecoins wie USDC (kein stilles 1)
        let skipped = held.filter { PortfolioStables.needsQuote($0) && (sortedCloses[$0]?.isEmpty ?? true) }
        let included = held.filter { !skipped.contains($0) }
        guard !included.isEmpty else {
            return PortfolioHistorySeries(points: [], skipped: skipped, change: nil, changePercent: nil,
                                          tradesInRange: false, capped: capped)
        }

        let dayCount = Swift.max(1, todayEpochDay - start + 1)
        // Je Coin und Tag der Wert samt Eröffnung/Hoch/Tief; nil = nicht gehalten oder ohne Kurs
        var cells: [String: [DayCell?]] = [:]
        // Gehalten, aber an einem Tag ohne Kurs (vor der ersten Kerze): fehlt im Gewinn/Verlust
        var unpriced: Set<String> = []
        for coin in included {
            let timeline = exactTimelines[coin] ?? []
            let series = sortedCloses[coin] ?? []
            var row = [DayCell?](repeating: nil, count: dayCount)
            for index in 0..<dayCount {
                let day = start + index
                let isToday = day == todayEpochDay
                let amount = isToday ? (timeline.last?.holdings ?? 0) : holdingsAtExact(timeline, dayEndMillis(day))
                guard amount > 0 else { continue }
                let dayMarket: Double?
                if isToday, let live = livePrices[coin], live > 0, live.isFinite {
                    dayMarket = live
                } else {
                    dayMarket = closeOnOrBefore(series, day: day)
                }
                // Stablecoin-Regel: USDT 1, andere Stablecoins Kurs des Tags, sonst kein Wert
                guard let price = PortfolioStables.price(coin, market: dayMarket) else {
                    unpriced.insert(coin)
                    continue
                }
                // Kerze genau dieses Tags (nicht fortgeschrieben); Hoch/Tief schliessen den Tageswert ein
                // Menge × Kurs exakt (Kurs über seine kürzeste Darstellung); Double erst in der Summe je Tag (`sumCells`)
                func times(_ p: Double) -> Decimal { amount * (PortfolioDecimal.of(p) ?? 0) }
                if PortfolioStables.needsQuote(coin), !series.isEmpty, let r = ranges[coin]?[day], r.valid {
                    row[index] = DayCell(value: times(price), open: times(r.open),
                                         high: times(Swift.max(r.high, price)), low: times(Swift.min(r.low, price)),
                                         withRange: true)
                } else {
                    let v = times(price)
                    row[index] = DayCell(value: v, open: v, high: v, low: v, withRange: false)
                }
            }
            cells[coin] = row
        }

        let points = sumCells(included, cells: cells, start: start, dayCount: dayCount, rate: rate)
        let first = points.first?.value ?? 0
        let last = points.last?.value ?? 0
        let change: Double? = points.count >= 2 ? last - first : nil
        let percent: Double? = change.flatMap { first > PortfolioCalculator.eps ? $0 / first * 100 : nil }
        let firstDayEnd = dayEndMillis(start)
        let tradesInRange = valid.contains { $0.time > firstDayEnd }
        // Marktkurs eines Tags (Auszahlung/Belohnung ohne Preis, Adress-Änderung, Gebühr im dritten Coin ohne Preis):
        // heute der aktuelle Kurs, sonst der Schluss. Ein Gebühr-Coin ohne Bestand im Zeitraum: seine Kerzen mit
        // derselben Identitätsprüfung
        let market: (String, Int) -> Double? = { coin, day in
            let live = livePrices[coin].flatMap { $0 > 0 && $0.isFinite ? $0 : nil }
            let series: [(day: Int, close: Double)]
            if let known = sortedCloses[coin] {
                series = known
            } else {
                let raw = (closes[coin] ?? [:]).filter { $0.value > 0 && $0.value.isFinite }
                    .sorted { $0.key < $1.key }.map { (day: $0.key, close: $0.value) }
                series = live == nil || PricePlausibility.closesMatchLive(raw, live: live) ? raw : []
            }
            if day == todayEpochDay, let live { return PortfolioStables.price(coin, market: live) }
            return PortfolioStables.price(coin, market: closeOnOrBefore(series, day: day))
        }
        let flows = cashFlows(valid, coins: included, start: start, todayEpochDay: todayEpochDay,
                              dayEndMillis: dayEndMillis, rate: rate, market: market, method: method)

        // Gewinn/Verlust: nur Coins mit Kurs an jedem gehaltenen Tag und bekannten Zuflüssen
        let sinceFirst = range == .sinceFirst
        var pnlFlowList: [PortfolioCashFlow] = []
        var pnlSkipped: [String] = []
        var pnlCoins: [String] = []
        for coin in included {
            let coinFlows = unpriced.contains(coin) ? nil
                : pnlFlows(coin, trades: valid, start: start, todayEpochDay: todayEpochDay, dayEndMillis: dayEndMillis,
                           rate: rate, sinceFirst: sinceFirst, market: market, method: method)
            if let coinFlows {
                pnlCoins.append(coin)
                pnlFlowList += coinFlows
            } else {
                pnlSkipped.append(coin)
            }
        }
        let pnlValues = pnlCoins.isEmpty ? []
            : sumCells(pnlCoins, cells: cells, start: start, dayCount: dayCount, rate: rate)
        let pnlRangeEnd = dayEndMillis(start)
        let withdrawalsInRange = valid.contains { t in
            t.type.isWithdrawalLike && !AddressBalance.isAddressTrade(t.id)
                && pnlCoins.contains(PortfolioCalculator.normalizeCoin(t.coin)) && (sinceFirst || t.time > pnlRangeEnd)
        }
        return PortfolioHistorySeries(points: points, skipped: skipped, change: change,
                                      changePercent: percent, tradesInRange: tradesInRange, capped: capped,
                                      cashFlows: flows, pnlValues: pnlValues,
                                      pnlFlows: pnlFlowList.sorted { $0.epochDay < $1.epochDay },
                                      pnlSkipped: pnlSkipped.sorted(), pnlSinceFirst: sinceFirst,
                                      withdrawalsInRange: withdrawalsInRange)
    }

    /// Wert eines Coins an einem Tag (USDT) mit Eröffnung/Hoch/Tief; `withRange` = aus einer Tageskerze.
    private struct DayCell {
        let value: Decimal
        let open: Decimal
        let high: Decimal
        let low: Decimal
        let withRange: Bool
    }

    /// Punkte je Tag als Summe der `coins` (× `rate`); Eröffnung/Hoch/Tief nur, wenn ein Coin eine Kerze hatte.
    private static func sumCells(_ coins: [String], cells: [String: [DayCell?]], start: Int, dayCount: Int,
                                 rate: Double) -> [PortfolioHistoryPoint] {
        var out: [PortfolioHistoryPoint] = []
        out.reserveCapacity(dayCount)
        for index in 0..<dayCount {
            // Exakt summiert, als Double erst für den Punkt (Anzeige, Chart)
            var total: Decimal = 0
            var bounds: (open: Decimal, high: Decimal, low: Decimal) = (0, 0, 0)
            var withRange = false
            for coin in coins {
                guard let cell = cells[coin]?[index] else { continue }
                total += cell.value
                bounds.open += cell.open
                bounds.high += cell.high
                bounds.low += cell.low
                if cell.withRange { withRange = true }
            }
            let day = start + index
            if withRange {
                out.append(PortfolioHistoryPoint(epochDay: day, value: PortfolioDecimal.double(total) * rate,
                                                 open: PortfolioDecimal.double(bounds.open) * rate,
                                                 high: PortfolioDecimal.double(bounds.high) * rate,
                                                 low: PortfolioDecimal.double(bounds.low) * rate))
            } else {
                out.append(PortfolioHistoryPoint(epochDay: day, value: PortfolioDecimal.double(total) * rate))
            }
        }
        return out
    }

    /// Zuflüsse eines Coins für den Gewinn/Verlust (`PortfolioLedger.flow`) in der Einheit des Verlaufs; nil =
    /// einer unbekannt (Coin fehlt, «ohne …»). Im Zeitraum die nach dem Ende von `start` (davor zählt der Wert
    /// am ersten Tag); «Seit 1. Kauf» (`sinceFirst`) alle, frühere auf `start` gelegt. Auszahlungen ohne Preis und
    /// Adress-Änderungen zum Marktkurs ihres (lokalen) Tags (`market`). Wie Android.
    private static func pnlFlows(_ coin: String, trades: [PortfolioTx], start: Int, todayEpochDay: Int,
                                 dayEndMillis: (Int) -> Int64, rate: Double, sinceFirst: Bool,
                                 market: (String, Int) -> Double?, method: CostMethod) -> [PortfolioCashFlow]? {
        let rangeEnd = dayEndMillis(start)
        var out: [PortfolioCashFlow] = []
        var book = PortfolioHoldingsBook(method: method)
        for e in PortfolioLedger.events(coin, trades: trades) {
            if sinceFirst || e.trade.time > rangeEnd {
                let day = localDay(e.trade.time, from: start, to: todayEpochDay, dayEndMillis: dayEndMillis)
                let price = PortfolioLedger.needsMarketPrice(e) ? market(coin, day) : nil
                let feePrice = PortfolioLedger.needsFeeMarketPrice(e) ? e.trade.thirdFeeCoin.flatMap { market($0, day) } : nil
                guard let amount = PortfolioLedger.flow(e, holdingsBefore: book.cap(for: e), marketPrice: price,
                                                        feeMarketPrice: feePrice, method: book.method) else { return nil }
                if amount != 0 && amount.isFinite { out.append(PortfolioCashFlow(epochDay: day, amount: amount * rate)) }
            }
            book.apply(e)
        }
        return out
    }

    /// Zuflüsse der `coins` nach dem Ende von `start` (gleiche Regel wie `PortfolioPeriodChange.gain`,
    /// `PortfolioLedger.flow`: ein Verkauf ohne Bestand zählt nicht); nil, sobald einer davon unbekannt ist. Tag = lokaler Tag
    /// der Transaktion, höchstens `todayEpochDay`. Wie Android.
    private static func cashFlows(_ trades: [PortfolioTx], coins: [String], start: Int, todayEpochDay: Int,
                                  dayEndMillis: (Int) -> Int64, rate: Double,
                                  market: (String, Int) -> Double?, method: CostMethod) -> [PortfolioCashFlow]? {
        let rangeEnd = dayEndMillis(start)
        var out: [PortfolioCashFlow] = []
        for coin in coins {
            var book = PortfolioHoldingsBook(method: method)
            for e in PortfolioLedger.events(coin, trades: trades) {
                if e.trade.time > rangeEnd {
                    // Gleiche Zuflüsse wie «7 T» (`PortfolioLedger.flow`); unbekannt → keine Zuflüsse
                    let day = localDay(e.trade.time, from: start, to: todayEpochDay, dayEndMillis: dayEndMillis)
                    let price = PortfolioLedger.needsMarketPrice(e) ? market(coin, day) : nil
                    let feePrice = PortfolioLedger.needsFeeMarketPrice(e) ? e.trade.thirdFeeCoin.flatMap { market($0, day) } : nil
                    guard let amount = PortfolioLedger.flow(e, holdingsBefore: book.cap(for: e), marketPrice: price,
                                                            feeMarketPrice: feePrice, method: book.method) else { return nil }
                    if amount != 0 && amount.isFinite {
                        out.append(PortfolioCashFlow(epochDay: day, amount: amount * rate))
                    }
                }
                book.apply(e)
            }
        }
        return out.sorted { $0.epochDay < $1.epochDay }
    }

    /// Tag (UTC, `dayEndMillis`) von `millis` zwischen `from` und `to` (kleinster Tag, dessen Ende nicht davor liegt).
    private static func localDay(_ millis: Int64, from: Int, to: Int, dayEndMillis: (Int) -> Int64) -> Int {
        var lo = from
        var hi = max(from, to)
        while lo < hi {
            let mid = lo + (hi - lo) / 2
            if dayEndMillis(mid) < millis { lo = mid + 1 } else { hi = mid }
        }
        return lo
    }

    /// Erster Tag des Verlaufs: erster Transaktionstag (lokal: kleinster Tag, dessen Ende nicht
    /// vor `firstTradeMillis` liegt) oder Beginn des Zeitraums, je nachdem, was später ist; nie
    /// nach `todayEpochDay`. «Seit 1. Kauf»: Beginn = erster Transaktionstag, frühestens
    /// `dataStartEpochDay`. `dayEndMillis` steigt mit dem Tag (binäre Suche).
    static func startDay(range: PortfolioHistoryRange, firstTradeMillis: Int64, todayEpochDay: Int,
                         dayEndMillis: (Int) -> Int64 = { PortfolioHistory.utcDayEnd($0) }) -> Int {
        // Tage nach UTC (wie `build`); `dayEndMillis` bleibt für ältere Aufrufer
        let dayEndMillis: (Int) -> Int64 = { PortfolioHistory.utcDayEnd($0) }
        var lo: Int
        if range == .sinceFirst {
            // Der lokale Tag liegt höchstens einen Tag neben dem UTC-Tag (Zeitzonen −12 h … +14 h)
            lo = min(todayEpochDay, max(dataStartEpochDay, epochDay(utcMillis: firstTradeMillis) - 2))
        } else {
            lo = todayEpochDay - range.days
        }
        var hi = todayEpochDay
        while lo < hi {
            let mid = lo + (hi - lo) / 2
            if dayEndMillis(mid) < firstTradeMillis {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        return lo
    }

    /// «Seit 1. Kauf» und der erste Kauf liegt vor dem ersten Tag mit Kursdaten (`dataStartEpochDay`).
    static func isCapped(range: PortfolioHistoryRange, firstTradeMillis: Int64, todayEpochDay: Int,
                         dayEndMillis: (Int) -> Int64 = { PortfolioHistory.utcDayEnd($0) }) -> Bool {
        guard range == .sinceFirst, dataStartEpochDay <= todayEpochDay else { return false }
        return firstTradeMillis <= utcDayEnd(dataStartEpochDay - 1)
    }

    /// Tageskerzen je Coin für `range`: `maxDays` für 7 T, 30 T und 1 J (eine gemeinsame Abfrage);
    /// «Seit 1. Kauf» bis zum Starttag samt einem Tag davor (Zeitzone), mindestens `maxDays`.
    /// Ohne Transaktion (`firstTradeMillis` nil) `maxDays`.
    static func candleDays(range: PortfolioHistoryRange, firstTradeMillis: Int64?, todayEpochDay: Int,
                           dayEndMillis: (Int) -> Int64) -> Int {
        guard range == .sinceFirst, let firstTradeMillis else { return maxDays }
        let start = startDay(range: range, firstTradeMillis: firstTradeMillis, todayEpochDay: todayEpochDay,
                             dayEndMillis: dayEndMillis)
        return max(maxDays, todayEpochDay - start + 2)
    }

    /// Abfragen für die letzten `days` UTC-Tage bis einschliesslich `todayUtcDay`: Stücke von
    /// höchstens `perRequest` Tagen, aufsteigend und lückenlos; das letzte endet heute, ein
    /// kürzeres Stück steht vorn. Leer bei `days` ≤ 0.
    static func candleChunks(days: Int, todayUtcDay: Int, perRequest: Int = maxCandlesPerRequest) -> [ClosedRange<Int>] {
        guard days > 0, perRequest > 0 else { return [] }
        let first = todayUtcDay - days + 1
        var out: [ClosedRange<Int>] = []
        var end = todayUtcDay
        while end >= first {
            let start = max(first, end - perRequest + 1)
            out.append(start...end)
            end = start - 1
        }
        return out.reversed()
    }

    /// Bestand > 0 irgendwann ab `from` (Bestand davor oder eine spätere Transaktion mit Bestand).
    private static func heldWithin(_ timeline: [(time: Int64, holdings: Double)], from: Int64) -> Bool {
        if holdingsAt(timeline, from - 1) > 0 { return true }
        return timeline.contains { $0.time >= from && $0.holdings > 0 }
    }

    /// Punkt unter dem Finger beim Ziehen über den Wertverlauf: Punkt i liegt bei
    /// `left` + `width`·i/(count−1) → der nächstgelegene, an den Rändern der erste/letzte.
    /// nil ohne Punkte oder bei ungültigem x (wie Android `PortfolioHistory.scrubIndex`).
    static func scrubIndex(x: Double, left: Double, width: Double, count: Int) -> Int? {
        guard count > 0, !x.isNaN else { return nil }
        guard count > 1, width > 0 else { return 0 }
        let raw = ((x - left) / width * Double(count - 1)).rounded()
        let clamped = Swift.min(Swift.max(raw, 0), Double(count - 1))
        return Int(clamped)
    }

    /// Schluss am Tag `day` oder der letzte davor; nil vor der ersten Kerze.
    static func closeOnOrBefore(_ sorted: [(day: Int, close: Double)], day: Int) -> Double? {
        var lo = 0
        var hi = sorted.count - 1
        var found = -1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if sorted[mid].day <= day {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found >= 0 ? sorted[found].close : nil
    }
}
