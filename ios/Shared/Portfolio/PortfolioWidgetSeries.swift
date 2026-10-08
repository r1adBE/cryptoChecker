import Foundation

/// Kurs eines Coins (USDT) zu einer Zeit (ms).
struct PortfolioTimedPrice: Codable, Equatable, Sendable {
    var at: Int64
    var price: Double
}

/// Veränderung über 24 Stunden in der Anzeigewährung; `percent` nil ohne Ausgangswert.
struct PortfolioChange: Equatable, Sendable {
    var amount: Double
    var percent: Double?
}

/// Stündlicher 24-h-Wertverlauf des Portfolio-Widgets — Gegenstück zu
/// `PortfolioWidgetSeries.kt` (gleiche Regeln, dort getestet).
///
/// Quelle sind Stundenkurse, die ohnehin schon vorliegen — Mini-Charts der Merkliste
/// (`DayReferenceStore`), Kerzen der Einzel-Widgets (`WidgetSparkline`) und die eigenen
/// Kursaufnahmen —, je Coin gemerkt (`merge`, höchstens einer je Stunde, 26 h lang).
/// Fehlen sie, lädt die Aktualisierung die Stundenkerzen des Coins (siehe unten).
///
/// Wert je Stunde = Σ heutiger Bestand × Kurs zu dieser Stunde. Fehlt einem Coin der Kurs,
/// zählt er mit dem aktuellen Kurs (flach). Eine Stunde gilt nur, wenn Coins mit Kurs mindestens
/// 80 % des heutigen Werts ausmachen. Bleiben weniger als 6 Punkte, gibt es keinen Stundenverlauf
/// (dann die gespeicherten Aufnahmen bzw. «Verlauf folgt»); bei der Tages-Basis reichen 2.
/// Fehlen einem Coin die Stundenkurse (`needsCandles`), lädt `PortfolioWidgetStore.refresh` einmal
/// je Stunde seine Stundenkerzen nach (`candleCoins`).
enum PortfolioWidgetSeries {
    static let hourMillis: Int64 = 3_600_000
    static let windowMillis: Int64 = 24 * hourMillis
    /// So lange werden Kurse je Coin gemerkt.
    static let keepMillis: Int64 = 26 * hourMillis
    /// Ein Kurs zählt für eine Stunde, wenn er höchstens so viel älter ist.
    static let toleranceMillis: Int64 = 90 * 60_000
    /// Mindestanteil des Werts mit echtem Kurs, damit eine Stunde zählt.
    static let minCoverage = 0.8
    /// Darunter kein Flächen-Chart (statt eines groben Dreiecks).
    static let minPoints = 6
    /// Tages-Basis: ab so vielen Punkten (Tagesbeginn und jetzt) wächst der Chart über den Tag.
    static let minDayPoints = 2
    /// Weniger Stunden mit Kurs in den letzten 24 h: Stundenkerzen für den Coin laden.
    static let candleMinHours = 20
    /// Je Coin höchstens ein Kerzen-Abruf in dieser Zeit.
    static let candleRetryMillis: Int64 = hourMillis
    /// Höchstens so viele Coins je Aktualisierung, die grössten zuerst.
    static let candleMaxCoins = 8
    /// Erst ab dieser Spanne gilt der Verlauf als «24 h».
    static let dayMinSpanMillis: Int64 = 20 * hourMillis

    private static func valid(_ price: Double?) -> Double? {
        guard let price, price > 0, price.isFinite else { return nil }
        return price
    }

    /// Schlusskurse ohne Zeiten (letzter zur Abrufzeit, davor je eine Stunde früher).
    static func fromHourlyCloses(_ closes: [Double], fetchedAt: Int64) -> [PortfolioTimedPrice] {
        guard fetchedAt > 0 else { return [] }
        let last = closes.count - 1
        var out: [PortfolioTimedPrice] = []
        for (i, close) in closes.enumerated() {
            guard let price = valid(close) else { continue }
            out.append(PortfolioTimedPrice(at: fetchedAt - Int64(last - i) * hourMillis, price: price))
        }
        return out
    }

    /// Kerzen (Startzeit, Schluss): Schluss am Ende der Stunde, die laufende Kerze zur Abrufzeit.
    static func fromCandles(_ candles: [(open: Int64, close: Double)], fetchedAt: Int64) -> [PortfolioTimedPrice] {
        var out: [PortfolioTimedPrice] = []
        for candle in candles {
            guard let price = valid(candle.close) else { continue }
            let end = candle.open + hourMillis
            out.append(PortfolioTimedPrice(at: fetchedAt > 0 ? Swift.min(end, fetchedAt) : end, price: price))
        }
        return out
    }

    /// Gemerkte und neue Kurse zusammenführen: je Coin (Grossschreibung) höchstens ein Kurs je
    /// Stunde (der jüngste), nur die letzten 26 h, nichts aus der Zukunft; aufsteigend.
    static func merge(stored: [String: [PortfolioTimedPrice]], fresh: [String: [PortfolioTimedPrice]],
                      now: Int64) -> [String: [PortfolioTimedPrice]] {
        var byCoin: [String: [Int64: PortfolioTimedPrice]] = [:]
        for source in [stored, fresh] {
            for (coin, prices) in source {
                let key = coin.trimmingCharacters(in: .whitespaces).uppercased()
                guard !key.isEmpty else { continue }
                var hours = byCoin[key] ?? [:]
                for p in prices {
                    guard valid(p.price) != nil, p.at <= now, now - p.at <= keepMillis else { continue }
                    let hour = Int64((Double(p.at) / Double(hourMillis)).rounded(.down))
                    if let current = hours[hour], current.at > p.at { continue }
                    hours[hour] = p
                }
                byCoin[key] = hours
            }
        }
        var out: [String: [PortfolioTimedPrice]] = [:]
        for (coin, hours) in byCoin where !hours.isEmpty {
            out[coin] = hours.values.sorted { $0.at < $1.at }
        }
        return out
    }

    /// Jüngster Kurs höchstens 90 Minuten vor `time` (nicht danach); sonst nil.
    static func price(_ prices: [PortfolioTimedPrice]?, at time: Int64) -> Double? {
        guard let prices else { return nil }
        var best: PortfolioTimedPrice?
        for p in prices where p.at <= time && time - p.at <= toleranceMillis {
            if best == nil || p.at > (best?.at ?? 0) { best = p }
        }
        return valid(best?.price)
    }

    /// Stündlicher Wertverlauf des heutigen Bestands (Anzeigewährung): Stunden jetzt − 24 h …
    /// jetzt − 1 h (nur mit Abdeckung ≥ 80 %) und als letzter Punkt der aktuelle Wert.
    /// `stables` gelten als abgedeckt (flach ist richtig).
    static func hourly(holdings: [String: Double], current: [String: Double],
                       prices: [String: [PortfolioTimedPrice]], now: Int64, factor: Double,
                       stables: Set<String>) -> [PortfolioWidgetPoint] {
        points(at: stride(from: 24, through: 1, by: -1).map { now - Int64($0) * hourMillis },
               holdings: holdings, current: current, prices: prices, now: now, factor: factor, stables: stables)
    }

    /// Höchstens so viele Stunden hat ein Tag (Ende der Sommerzeit: 25).
    private static let maxDayHours = 25

    /// Tages-Basis der %-Änderung: Wertverlauf seit Tagesbeginn `dayStart` — Punkte zu `dayStart`,
    /// `dayStart` + 1 h, … vor `now` (gleiche Abdeckungsregel) und der aktuelle Wert. Der erste
    /// Punkt fehlt, wenn zum Tagesbeginn kein Kurs bekannt ist. Wie `hourlySince` in Android.
    static func hourlySince(holdings: [String: Double], current: [String: Double],
                            prices: [String: [PortfolioTimedPrice]], dayStart: Int64, now: Int64, factor: Double,
                            stables: Set<String>) -> [PortfolioWidgetPoint] {
        guard dayStart <= now else { return [] }
        var times: [Int64] = []
        var t = dayStart
        while t < now && times.count < maxDayHours {
            times.append(t)
            t += hourMillis
        }
        return points(at: times, holdings: holdings, current: current, prices: prices, now: now, factor: factor,
                      stables: stables)
    }

    /// Wertpunkte zu den Zeiten `times` (nur mit Abdeckung ≥ 80 %) und der aktuelle Wert.
    private static func points(at times: [Int64], holdings: [String: Double], current: [String: Double],
                               prices: [String: [PortfolioTimedPrice]], now: Int64, factor: Double,
                               stables: Set<String>) -> [PortfolioWidgetPoint] {
        var open: [(coin: String, amount: Double, price: Double)] = []
        for (coin, amount) in holdings {
            guard amount > PortfolioCalculator.eps, amount.isFinite, let price = valid(current[coin]) else { continue }
            open.append((coin, amount, price))
        }
        let total = open.reduce(0.0) { $0 + $1.amount * $1.price }
        guard !open.isEmpty, total > 0 else { return [] }
        var points: [PortfolioWidgetPoint] = []
        for t in times {
            var covered = 0.0
            var value = 0.0
            for item in open {
                let key = item.coin.uppercased()
                let then = stables.contains(key) ? item.price : price(prices[key], at: t)
                if let then {
                    covered += item.amount * item.price
                    value += item.amount * then
                } else {
                    value += item.amount * item.price
                }
            }
            if covered / total >= minCoverage { points.append(PortfolioWidgetPoint(at: t, value: value * factor)) }
        }
        points.append(PortfolioWidgetPoint(at: now, value: total * factor))
        return points
    }

    /// Verlauf gross genug für den Flächen-Chart?
    static func drawable(_ points: [PortfolioWidgetPoint]) -> Bool {
        points.filter { $0.value.isFinite }.count >= minPoints
    }

    /// Tages-Basis: Chart schon ab `minDayPoints` Punkten — er beginnt beim Tagesbeginn und füllt
    /// sich über den Tag (feste Zeitachse bis Tagesende) statt bis zum Morgen «Verlauf folgt».
    static func drawableDay(_ points: [PortfolioWidgetPoint]) -> Bool {
        points.filter { $0.value.isFinite }.count >= minDayPoints
    }

    /// Hat `prices` weniger als `candleMinHours` Stunden mit Kurs in den 24 h vor `now`?
    static func needsCandles(_ prices: [PortfolioTimedPrice]?, now: Int64) -> Bool {
        var hours = Set<Int64>()
        for p in prices ?? [] where valid(p.price) != nil && p.at <= now && now - p.at <= windowMillis {
            hours.insert(Int64((Double(p.at) / Double(hourMillis)).rounded(.down)))
        }
        return hours.count < candleMinHours
    }

    /// Coins, für die jetzt Stundenkerzen geladen werden: aus `coins` (Reihenfolge = Vorrang),
    /// ohne `stables`, nur mit Lücken (`needsCandles`) und wenn der letzte Versuch (`lastAttempt`,
    /// Grossschreibung) mindestens `candleRetryMillis` her ist; höchstens `candleMaxCoins`.
    static func candleCoins(_ coins: [String], prices: [String: [PortfolioTimedPrice]],
                            lastAttempt: [String: Int64], now: Int64, stables: Set<String>) -> [String] {
        var out: [String] = []
        for raw in coins {
            let coin = raw.trimmingCharacters(in: .whitespaces).uppercased()
            guard !coin.isEmpty, !stables.contains(coin), !out.contains(coin) else { continue }
            if let last = lastAttempt[coin], now - last >= 0, now - last < candleRetryMillis { continue }
            guard needsCandles(prices[coin], now: now) else { continue }
            out.append(coin)
            if out.count >= candleMaxCoins { break }
        }
        return out
    }

    /// Deckt der Verlauf rund einen Tag ab?
    static func coversDay(_ points: [PortfolioWidgetPoint]) -> Bool {
        guard points.count >= 2, let first = points.first, let last = points.last else { return false }
        return last.at - first.at >= dayMinSpanMillis
    }

    /// Veränderung über den Verlauf (letzter gegen ersten Wert), nur wenn er `coversDay` und
    /// mindestens `minPoints` Punkte hat.
    static func change(_ points: [PortfolioWidgetPoint]) -> PortfolioChange? {
        guard drawable(points), coversDay(points), let first = points.first?.value, let last = points.last?.value,
              first.isFinite, last.isFinite else { return nil }
        let amount = last - first
        return PortfolioChange(amount: amount, percent: first > 0 ? amount / first * 100 : nil)
    }

    /// Kursveränderung je Coin über 24 h: aktueller Kurs gegen den ältesten gemerkten Kurs
    /// zwischen jetzt − 25 h und jetzt − 20 h.
    static func coinChanges(current: [String: Double], prices: [String: [PortfolioTimedPrice]],
                            now: Int64) -> [String: Double] {
        var out: [String: Double] = [:]
        for (coin, price) in current {
            guard let nowPrice = valid(price) else { continue }
            let window = (prices[coin.uppercased()] ?? []).filter {
                now - $0.at >= dayMinSpanMillis && now - $0.at <= windowMillis + hourMillis
            }
            guard let oldest = window.min(by: { $0.at < $1.at }), let then = valid(oldest.price) else { continue }
            out[coin] = (nowPrice / then - 1) * 100
        }
        return out
    }

    /// Tages-Basis: Veränderung seit Tagesbeginn — nur wenn der erste Punkt genau `dayStart` ist
    /// und danach mindestens der aktuelle folgt.
    static func changeSince(_ points: [PortfolioWidgetPoint], dayStart: Int64) -> PortfolioChange? {
        guard points.count >= 2, let first = points.first, first.at == dayStart, let last = points.last,
              first.value.isFinite, last.value.isFinite else { return nil }
        let amount = last.value - first.value
        return PortfolioChange(amount: amount, percent: first.value > 0 ? amount / first.value * 100 : nil)
    }

    /// Kursveränderung je Coin seit Tagesbeginn: aktueller Kurs gegen den Kurs zu `dayStart` (`price(_:at:)`).
    static func coinChangesSince(current: [String: Double], prices: [String: [PortfolioTimedPrice]],
                                 dayStart: Int64) -> [String: Double] {
        var out: [String: Double] = [:]
        for (coin, price) in current {
            guard let nowPrice = valid(price), let then = Self.price(prices[coin.uppercased()], at: dayStart) else { continue }
            out[coin] = (nowPrice / then - 1) * 100
        }
        return out
    }

    /// Richtung für Farbe und Pfeil: 1 steigend, −1 fallend, 0 unverändert (unter einem halben Rappen/Cent).
    static func direction(_ amount: Double) -> Int {
        if abs(amount) < 0.005 { return 0 }
        return amount > 0 ? 1 : -1
    }
}
