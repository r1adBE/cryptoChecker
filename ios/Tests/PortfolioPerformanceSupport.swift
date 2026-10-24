import Foundation
@testable import CryptoChecker

/// Testdaten der Leistungstests fürs Portfolio: `trades` erzeugt bis zu 10 000 Transaktionen mit festem Seed über
/// sieben Jahre (2019–2025) — 12 Coins, 4 Wallets, etwa 40 % Käufe (Gebühr in USDT, im Coin oder in BNB), 20 %
/// Verkäufe, 8 % Tausch, 10 % Belohnungen (jede zehnte ohne Wert), 10 % Umbuchungen (Netzwerkgebühr bzw. Gebühr USDT),
/// 4 % Einzahlungen (teils mit früherem Kaufdatum, teils ohne Preis), 4 % Auszahlungen und je 2 % «Damit bezahlt» und
/// «Verschenkt». Abgänge nehmen 5–50 % des Bestands der Wallet (begrenzt auf einen Wert in USDT); ohne Bestand wird
/// daraus ein Kauf. Gerechnet wird ganzzahlig (Mengen in 10⁻⁸, Kurse in 10⁻⁶ USDT), darum dieselben Daten wie
/// `PortfolioPerformanceData.kt` (Android). Kurse, Tagesschlüsse und Devisenkurse kommen aus festen Tabellen — kein
/// Netz.
enum PortfolioPerformanceData {
    static let coins = ["BTC", "ETH", "SOL", "ADA", "XRP", "DOT", "LINK", "AVAX", "DOGE", "LTC", "ATOM", "BNB"]

    /// Grundkurs je Coin in 10⁻⁶ USDT.
    private static let baseMicro: [Int] = [
        30_000_000_000, 2_000_000_000, 60_000_000, 500_000, 550_000, 7_000_000,
        12_000_000, 25_000_000, 90_000, 80_000_000, 9_000_000, 300_000_000,
    ]

    /// Kursniveau je Jahr 2019…2025 in Prozent des Grundkurses.
    private static let yearPercent: [Int] = [30, 60, 160, 90, 110, 180, 220]

    static let wallets = ["Binance", "Kraken", "Coinbase", "Ledger"]

    fileprivate static let bnbIndex = 11

    /// 2019-01-01 und 2026-01-01, 00:00 UTC.
    static let startMillis: Int64 = 1_546_300_800_000
    static let endMillis: Int64 = 1_767_225_600_000
    private static let yearMillis: Int64 = 31_557_600_000
    fileprivate static let dayMillis: Int64 = 86_400_000
    private static let hourMillis = 3_600_000
    private static let unitsPerCoin = 100_000_000
    fileprivate static let usdt = 1_000_000

    /// Kleinster Bestand (10⁻⁸) für einen Abgang.
    fileprivate static let minHeld = 10_000

    /// Letzter Tag der Devisen- und USDT-Tabellen (2026-12-31, Tage seit 1970).
    private static let lastFxDay = 20_818

    private static func yearIndex(_ time: Int64) -> Int {
        Int(min(max((time - startMillis) / yearMillis, 0), Int64(yearPercent.count - 1)))
    }

    /// Tagesschluss des Coins `coin` (Index) in 10⁻⁶ USDT: Grundkurs × Niveau des Jahres.
    fileprivate static func closeMicro(_ coin: Int, _ time: Int64) -> Int {
        baseMicro[coin] * yearPercent[yearIndex(time)] / 100
    }

    fileprivate static func units(_ valueMicro: Int, _ priceMicro: Int) -> Int {
        valueMicro * unitsPerCoin / priceMicro
    }

    fileprivate static func valueMicro(_ units: Int, _ priceMicro: Int) -> Int { units / 100 * priceMicro / usdt }

    fileprivate static func amount(_ units: Int) -> Decimal {
        Decimal(sign: .plus, exponent: -8, significand: Decimal(units))
    }

    fileprivate static func money(_ micro: Int) -> Decimal {
        Decimal(sign: micro < 0 ? .minus : .plus, exponent: -6, significand: Decimal(abs(micro)))
    }

    /// Tagesschluss wie `HistoricPriceSource` (Gebühr-Zeilen, Belohnungen ohne Wert, Netzwerkgebühren); fremd = nil.
    static func priceAt(_ coin: String, _ time: Int64) -> Decimal? {
        guard let i = coins.firstIndex(of: coin) else { return nil }
        return money(closeMicro(i, time))
    }

    /// Wie `priceAt` als Zahl (Kurs zum Zeitpunkt einer Auszahlung in der Übersicht).
    static func priceAtDouble(_ coin: String, _ time: Int64) -> Double? {
        priceAt(coin, time).map(PortfolioDecimal.double)
    }

    /// Aktueller Kurs je Coin (Niveau 2025).
    static func prices() -> [String: Double] {
        var out: [String: Double] = [:]
        for (i, c) in coins.enumerated() { out[c] = Double(closeMicro(i, endMillis - 1)) / Double(usdt) }
        return out
    }

    /// USD → `currency` je Tag (Tage seit 1970) vom Herbst 2018 bis Ende 2026; USD leer (wie die App).
    static func rates(_ currency: String) -> [Int: Decimal] {
        let base: Int
        switch currency {
        case "USD": return [:]
        case "CHF": base = 900_000
        case "GBP": base = 790_000
        default: base = 920_000
        }
        var out: [Int: Decimal] = [:]
        for day in Int(startMillis / dayMillis) - 40...lastFxDay {
            out[day] = money(base + (day * 7_919) % 40_001 - 20_000)
        }
        return out
    }

    /// USDT/USD-Tagesschluss je Tag (0.999…1.001).
    static func usdtUsd() -> [Int: Decimal] {
        var out: [Int: Decimal] = [:]
        for day in Int(startMillis / dayMillis) - 40...lastFxDay { out[day] = money(usdt + (day * 31) % 2_001 - 1_000) }
        return out
    }

    /// `count` Transaktionen (Id 1…count) zeitlich aufsteigend über 2019–2025; gleicher Seed, gleiche Folge.
    static func trades(_ count: Int) -> [PortfolioTx] {
        var rng = PerformanceRandom(seed: PerformanceData.seed + 10)
        var held = [[Int]](repeating: [Int](repeating: 0, count: wallets.count), count: coins.count)
        var out: [PortfolioTx] = []
        out.reserveCapacity(count)
        for i in 0..<count {
            let roll = rng.nextInt(100)
            let c = rng.nextInt(coins.count)
            let w = rng.nextInt(wallets.count)
            let value = (20 + rng.nextInt(1_981)) * usdt
            let jitter = 85 + rng.nextInt(31)
            let pct = 5 + rng.nextInt(46)
            let variant = rng.nextInt(10)
            let slot = startMillis + (endMillis - startMillis) * Int64(i) / Int64(count)
            let time = slot + Int64(rng.nextInt(hourMillis))
            let price = closeMicro(c, time) * jitter / 100
            let draw = PortfolioDraw(id: Int64(i + 1), c: c, w: w, value: value, price: price, pct: pct,
                                     variant: variant, time: time)
            out.append(draw.trade(roll, &held))
        }
        return out
    }
}

/// Zufallswerte einer Transaktion; `price` in 10⁻⁶ USDT. Wie `Draw` in `PortfolioPerformanceData.kt`.
private struct PortfolioDraw {
    private typealias D = PortfolioPerformanceData
    let id: Int64
    let c: Int
    let w: Int
    let value: Int
    let price: Int
    let pct: Int
    let variant: Int
    let time: Int64

    var coin: String { D.coins[c] }
    var wallet: String { D.wallets[w] }

    /// Abgang: 5–50 % des Bestands, höchstens `maxValue` USDT wert; 0 = kein Abgang möglich.
    func outgoing(_ held: Int, _ maxValue: Int) -> Int {
        held < D.minHeld ? 0 : min(held * pct / 100, D.units(maxValue, price))
    }

    func trade(_ roll: Int, _ held: inout [[Int]]) -> PortfolioTx {
        let h = held[c][w]
        switch roll {
        case ..<40: return buy(&held)
        case ..<60:
            let u = outgoing(h, value * 2)
            return u > 0 ? sell(u, &held) : buy(&held)
        case ..<68: return swap(&held) ?? buy(&held)
        case ..<78: return reward(&held)
        case ..<88:
            let u = outgoing(h, value * 5)
            return u > 0 ? transfer(u, &held) : buy(&held)
        case ..<92: return deposit(&held)
        case ..<96:
            let u = outgoing(h, value)
            return u > 0 ? withdraw(u, &held) : buy(&held)
        default:
            let u = outgoing(h, value / 4)
            return u > 0 ? spend(u, payment: roll < 98, &held) : buy(&held)
        }
    }

    private func base(_ type: PortfolioTxType, _ units: Int, priced: Bool) -> PortfolioTx {
        PortfolioTx(id: id, coin: coin, type: type, amountExact: D.amount(units),
                    priceExact: priced ? D.money(price) : nil, time: time, exchange: wallet)
    }

    private func buy(_ held: inout [[Int]]) -> PortfolioTx {
        let units = D.units(value, price)
        var t = base(.BUY, units, priced: true)
        var coinFee = 0
        switch variant {
        case 0...3:
            t.feeExact = D.money(value / 1_000)
            t.feeUnit = .QUOTE
        case 4:
            coinFee = units / 1_000
            t.feeExact = D.amount(coinFee)
            t.feeUnit = .COIN
        case 5:
            let bnbPrice = D.closeMicro(D.bnbIndex, time)
            let fee = D.units(value / 1_000, bnbPrice)
            if c != D.bnbIndex && fee > 0 && held[D.bnbIndex][w] >= fee {
                held[D.bnbIndex][w] -= fee
                t.feeCoin = D.coins[D.bnbIndex]
                t.feeCoinAmountExact = D.amount(fee)
                t.feeCoinPriceExact = D.money(bnbPrice)
            }
        default: break
        }
        held[c][w] += units - coinFee
        return t
    }

    private func sell(_ units: Int, _ held: inout [[Int]]) -> PortfolioTx {
        held[c][w] -= units
        var t = base(.SELL, units, priced: true)
        if variant < 5 {
            t.feeExact = D.money(D.valueMicro(units, price) / 1_000)
            t.feeUnit = .QUOTE
        }
        return t
    }

    private func swap(_ held: inout [[Int]]) -> PortfolioTx? {
        let units = outgoing(held[c][w], value * 2)
        guard units > 0 else { return nil }
        let to = (c + 1 + variant) % D.coins.count
        let swapValue = D.valueMicro(units, price)
        let toUnits = D.units(swapValue, D.closeMicro(to, time))
        guard swapValue > 0, toUnits > 0 else { return nil }
        held[c][w] -= units
        held[to][w] += toUnits
        var t = base(.SWAP, units, priced: false)
        t.toCoin = D.coins[to]
        t.toAmountExact = D.amount(toUnits)
        t.swapValueExact = D.money(swapValue)
        return t
    }

    private func reward(_ held: inout [[Int]]) -> PortfolioTx {
        let units = D.units(value / 20, price)
        held[c][w] += units
        return base(.REWARD, units, priced: variant != 9)
    }

    private func transfer(_ units: Int, _ held: inout [[Int]]) -> PortfolioTx {
        let to = (w + 1 + variant % 3) % D.wallets.count
        let coinFee = variant < 6 ? units / 2_000 : 0
        held[c][w] -= units + coinFee
        held[c][to] += units
        var t = PortfolioTx(id: id, coin: coin, type: .TRANSFER, amountExact: D.amount(units), time: time)
        t.transferFrom = wallet
        t.transferTo = D.wallets[to]
        if coinFee > 0 {
            t.feeExact = D.amount(coinFee)
            t.feeUnit = .COIN
        } else if variant < 8 {
            t.feeExact = D.money(D.usdt)
            t.feeUnit = .QUOTE
        }
        return t
    }

    private func deposit(_ held: inout [[Int]]) -> PortfolioTx {
        let units = D.units(value, price)
        held[c][w] += units
        var t = base(.DEPOSIT, units, priced: variant < 7)
        if variant % 2 == 0 { t.acquiredTime = time - Int64(30 + pct * 7) * D.dayMillis }
        return t
    }

    private func withdraw(_ units: Int, _ held: inout [[Int]]) -> PortfolioTx {
        held[c][w] -= units
        var t = base(.WITHDRAWAL, units, priced: true)
        if variant < 5 {
            t.feeExact = D.money(D.usdt)
            t.feeUnit = .QUOTE
        }
        return t
    }

    private func spend(_ units: Int, payment: Bool, _ held: inout [[Int]]) -> PortfolioTx {
        held[c][w] -= units
        if payment { return base(.PAYMENT, units, priced: true) }
        var t = base(.GIFT, units, priced: true)
        t.giftToSpouse = variant == 0
        return t
    }
}
