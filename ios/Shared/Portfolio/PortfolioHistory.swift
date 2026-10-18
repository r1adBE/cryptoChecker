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

// MARK: Gewinn über mehrere Tage («Gewinn 7 T» im Gesamtwert-Kopf)

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
        // Gezählte Coins (z. B. ganz heute gekauft, ohne Veränderung) fehlen nicht mehr
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
        // Adress-Einträge der Eingabe nie rückwirkend; nur die beobachtete Reihe (`AddressBalanceHistory`)
        let valid = trades.filter { $0.amountExact > 0 && !AddressBalance.isAddressTrade($0.id) }
            + AddressBalanceHistory.trades(addresses)
        // Alle Arten und Gebühren im dritten Coin über `PortfolioLedger` (Bestand und Zuflüsse wie der Rechner)
        for coin in PortfolioLedger.coins(valid) {
            if let only, coin != only { continue }
            let events = PortfolioLedger.events(coin, trades: valid)
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

// MARK: Kerzen und Achsen des Wertverlaufs

/// Wie viele Tage eine Portfolio-Kerze umfasst — wie `PortfolioCandleBucket` (Android).
enum PortfolioCandleBucket: Sendable {
    case day, week, month
}

/// Eine Kerze des Portfolio-Werts über die Tage `firstDay`...`lastDay` (Tage seit 1970-01-01, lokal).
/// Werte in der Einheit des Verlaufs (Währung oder USDT) — wie `PortfolioCandle` (Android).
struct PortfolioCandle: Equatable, Sendable {
    let firstDay: Int
    let lastDay: Int
    let open: Double
    let high: Double
    let low: Double
    let close: Double

    /// Steigend (Schluss ≥ Eröffnung) = Kursfarbe «steigend».
    var up: Bool { close >= open }
}

/// Kerzen aus dem täglichen Wertverlauf — Spiegel von `PortfolioCandles` (Android):
///  - Tageskerze: Eröffnung = Wert am Ende des Vortags (erster Tag: seine eigene Eröffnung aus den
///    Coin-Kerzen, sonst sein Schluss), Schluss = Wert am Tagesende — dieselbe Reihe wie die Linie
///    (samt Umrechnung mit dem Tageskurs und Stablecoin-Regel).
///  - Hoch/Tief: aus `PortfolioHistoryPoint.high`/`low` (Σ Bestand × Hoch bzw. Tief der Coins — eine
///    Schranke, siehe `PortfolioHistory.build`); immer so begrenzt, dass Tief ≤ min(Eröffnung, Schluss)
///    und Hoch ≥ max(Eröffnung, Schluss). Ohne diese Werte Hoch/Tief = max/min(Eröffnung, Schluss).
///  - Lange Zeiträume werden zusammengefasst (höchstens rund 90 Kerzen): bis `maxDailyDays` Tage
///    Tageskerzen, bis `maxWeeklyDays` Tage Wochenkerzen (ab Montag), darüber Monatskerzen. Die Linie
///    behält ihre Tagespunkte.
enum PortfolioCandles {
    /// Bis zu so vielen Tagen eine Kerze je Tag (30 T, 7 T).
    static let maxDailyDays = 92
    /// Bis zu so vielen Tagen Wochenkerzen (1 J = 53, höchstens 90); darüber Monatskerzen (5 J = 61).
    static let maxWeeklyDays = 630

    /// Zusammenfassung für einen Verlauf über `dayCount` Tage.
    static func bucket(dayCount: Int) -> PortfolioCandleBucket {
        if dayCount <= maxDailyDays { return .day }
        if dayCount <= maxWeeklyDays { return .week }
        return .month
    }

    /// Erster Tag der Gruppe von `day`: der Tag selbst, sein Montag oder der Monatserste.
    static func bucketStart(_ day: Int, bucket: PortfolioCandleBucket) -> Int {
        switch bucket {
        case .day:
            return day
        case .week:
            // 1970-01-01 war ein Donnerstag: Montag = Tag − (Tag + 3) mod 7
            let offset = ((day + 3) % 7 + 7) % 7
            return day - offset
        case .month:
            let d = LocalDay(epochDay: day)
            return LocalDay(d.year, d.month, 1).epochDay
        }
    }

    /// Eine Kerze je Punkt (aufsteigend nach Tag); leer ohne Punkte.
    static func daily(_ points: [PortfolioHistoryPoint]) -> [PortfolioCandle] {
        var out: [PortfolioCandle] = []
        out.reserveCapacity(points.count)
        for (i, p) in points.enumerated() {
            let close = p.value
            let open: Double
            if i == 0 {
                open = p.open.flatMap { $0.isFinite ? $0 : nil } ?? close
            } else {
                open = points[i - 1].value
            }
            let top = Swift.max(open, close)
            let bottom = Swift.min(open, close)
            let high = p.high.flatMap { $0.isFinite ? Swift.max($0, top) : nil } ?? top
            let low = p.low.flatMap { $0.isFinite ? Swift.min($0, bottom) : nil } ?? bottom
            out.append(PortfolioCandle(firstDay: p.epochDay, lastDay: p.epochDay, open: open, high: high,
                                       low: low, close: close))
        }
        return out
    }

    /// Fasst aufeinanderfolgende Kerzen derselben Gruppe zusammen: Eröffnung der ersten, Schluss der
    /// letzten, höchstes Hoch, tiefstes Tief.
    static func aggregate(_ candles: [PortfolioCandle], bucket: PortfolioCandleBucket) -> [PortfolioCandle] {
        guard bucket != .day else { return candles }
        var out: [PortfolioCandle] = []
        var current: PortfolioCandle?
        var currentKey = Int.min
        for c in candles {
            let key = bucketStart(c.firstDay, bucket: bucket)
            if let open = current, key == currentKey {
                current = PortfolioCandle(firstDay: open.firstDay, lastDay: c.lastDay, open: open.open,
                                          high: Swift.max(open.high, c.high), low: Swift.min(open.low, c.low),
                                          close: c.close)
            } else {
                if let open = current { out.append(open) }
                currentKey = key
                current = c
            }
        }
        if let current { out.append(current) }
        return out
    }

    /// Fertige Kerzen für den Verlauf `points`: Tageskerzen, je nach Länge zusammengefasst.
    static func build(_ points: [PortfolioHistoryPoint]) -> [PortfolioCandle] {
        guard let first = points.first, let last = points.last else { return [] }
        return aggregate(daily(points), bucket: bucket(dayCount: last.epochDay - first.epochDay + 1))
    }
}

/// Abstand der senkrechten Gitterlinien im Wertverlauf — wie `PortfolioGridUnit` (Android).
enum PortfolioGridUnit: Sendable {
    case day, week, month, quarter, year
}

/// Achsen und Lage im Wertverlauf-Chart — Spiegel von `PortfolioChartAxis` (Android). Wie im
/// Aktionsblatt eines Paars: drei waagrechte Stufen (Tief, Mitte, Hoch) mit Beschriftung rechts, feine
/// senkrechte Linien an Kalendergrenzen. Jeder Punkt bzw. jede Kerze belegt einen gleich breiten Slot.
enum PortfolioChartAxis {
    /// Unterteilung nach Länge: 7 T je Tag, 30 T je Woche, 1 J je Monat, länger je Quartal bzw. Jahr.
    static func gridUnit(dayCount: Int) -> PortfolioGridUnit {
        if dayCount <= 10 { return .day }
        if dayCount <= 62 { return .week }
        if dayCount <= 400 { return .month }
        if dayCount <= 1100 { return .quarter }
        return .year
    }

    /// Tage, an denen eine senkrechte Linie beginnt (Tagesbeginn, Montag, Monats-, Quartals- bzw.
    /// Jahreserster), strikt nach `firstDay` und höchstens `lastDay`.
    static func gridDays(firstDay: Int, lastDay: Int) -> [Int] {
        guard lastDay > firstDay else { return [] }
        let unit = gridUnit(dayCount: lastDay - firstDay + 1)
        switch unit {
        case .day:
            return Array((firstDay + 1)...lastDay)
        case .week:
            return Array(stride(from: PortfolioCandles.bucketStart(firstDay, bucket: .week) + 7, through: lastDay, by: 7))
        case .month, .quarter, .year:
            let step = unit == .quarter ? 3 : (unit == .year ? 12 : 1)
            let start = LocalDay(epochDay: firstDay)
            // Erster Monat der Unterteilung (Januar/April/Juli/Oktober bzw. Januar) bis zum Starttag
            var year = start.year
            var month = (start.month - 1) / step * step + 1
            var out: [Int] = []
            while true {
                let d = LocalDay(year, month, 1).epochDay
                if d > lastDay { break }
                if d > firstDay { out.append(d) }
                month += step
                while month > 12 {
                    month -= 12
                    year += 1
                }
            }
            return out
        }
    }

    /// Lage des Tagesbeginns `day` in Slots ab dem linken Rand: Slot i umfasst die Tage `spans[i]`
    /// (lückenlos aufsteigend); innerhalb eines Slots anteilig. nil, wenn kein Slot den Tag enthält.
    static func slotPosition(_ spans: [ClosedRange<Int>], day: Int) -> Double? {
        var lo = 0
        var hi = spans.count - 1
        while lo <= hi {
            let mid = (lo + hi) / 2
            let span = spans[mid]
            if day < span.lowerBound {
                hi = mid - 1
            } else if day > span.upperBound {
                lo = mid + 1
            } else {
                let length = Double(span.upperBound - span.lowerBound + 1)
                return Double(mid) + Double(day - span.lowerBound) / length
            }
        }
        return nil
    }

    /// Index des Slots, der `day` enthält; nil ausserhalb.
    static func slotOfDay(_ spans: [ClosedRange<Int>], day: Int) -> Int? {
        slotPosition(spans, day: day).map { Int($0) }
    }

    /// Index des höchsten Werts (bei Gleichstand der erste); nil ohne gültige Werte.
    static func maxIndex(_ values: [Double]) -> Int? {
        var best: Int?
        for (i, v) in values.enumerated() where v.isFinite {
            if let b = best, v <= values[b] { continue }
            best = i
        }
        return best
    }

    /// Waagrechte Stufen (Tief, Mitte, Hoch) einer Skala von `low` bis `high`.
    static func levels(low: Double, high: Double) -> (low: Double, mid: Double, high: Double) {
        (low, (low + high) / 2, high)
    }

    /// Bereich des Gewinn/Verlust-Charts: schliesst 0 immer ein (Nulllinie sichtbar). Wie Android.
    static func pnlBounds(low: Double, high: Double) -> (low: Double, high: Double) {
        (Swift.min(low, 0), Swift.max(high, 0))
    }

    /// Skala der Kerzen: tiefstes Tief bis höchstes Hoch; nil ohne Kerzen.
    static func candleBounds(_ candles: [PortfolioCandle]) -> (low: Double, high: Double)? {
        guard let low = candles.map(\.low).min(), let high = candles.map(\.high).max() else { return nil }
        return (low, high)
    }
}
