import Foundation

/// Wertverlauf des Portfolios aus den Transaktionen — Spiegel von `PortfolioHistory.kt`:
///  - EINE Tagesgrenze: der UTC-Tag, wie die Tageskerzen und «7 T/1 J». Bestand je Coin und Tag = alle
///    Transaktionen bis zum Ende des UTC-Tags (`utcDayEnd`), Zuflüsse auf dem UTC-Tag ihrer Zeit (ein Kauf um 00:30
///    Zürich-Sommerzeit = 22:30 UTC gehört zum Vortag — sonst Scheingewinn gegenüber «7 T»).
///    Verkäufe werden wie in `PortfolioCalculator` beim Bestand gekappt.
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

    /// Heutiger UTC-Tag — EINE Tagesgrenze für «7 T/1 J», Wertverlauf und die Erwerbstage der Karte; das lokale Datum
    /// lag zwischen Mitternacht lokal und UTC einen Tag daneben. Wie Android `utcToday`.
    static func utcToday(now: Int64 = EpochTime.nowMillis) -> Int { epochDay(utcMillis: now) }

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
        holdingsTimelineExact(events: PortfolioLedger.events(coin, trades: trades), method: method)
    }

    /// `holdingsTimelineExact` aus den schon gesammelten Schritten eines Coins (`PortfolioLedger.eventsByCoin`).
    static func holdingsTimelineExact(events: [PortfolioLedgerEvent],
                                      method: CostMethod) -> [(time: Int64, holdings: Decimal)] {
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

    /// Bestand zum Zeitpunkt `atMillis` (einschliesslich).
    static func holdingsAt(_ timeline: [(time: Int64, holdings: Double)], _ atMillis: Int64) -> Double {
        var result = 0.0
        for entry in timeline {
            if entry.time > atMillis { break }
            result = entry.holdings
        }
        return result
    }
}
