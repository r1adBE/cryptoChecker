import Foundation

/// Eingaben der Coin-Ansicht (`PortfolioCalculator.ledger`): Coin, Transaktionen samt Adress-Guthaben, aktuelle Kurse
/// (auch für die Gebühr-Zeilen), Einstandsmethode und Ladung der Tagesschlüsse (Zähler) — Schlüssel für `.task(id:)`.
/// Abseits des Hauptthreads gerechnet statt in `body`, wie Android `coinDetailFlow`.
/// PERF3-R1: `prices` nur für den Coin und `feeCoins` — Kurs-Ticks anderer Coins ändern den Schlüssel
/// nicht mehr (wie Android `CoinFeePrices`).
struct PortfolioCoinLedgerKey: Equatable, Sendable {
    let coin: String
    let trades: [PortfolioTx]
    let prices: [String: Double]
    let method: CostMethod
    let closesStamp: Int
    /// Coins, deren Tageskurs die Zeilen von `coin` brauchen (`feeCoins(for:trades:)`).
    var feeCoins: Set<String> = []

    /// Wie Android `coinDetailFeeCoins`: Zeilen, die `symbol` betreffen (Coin, erhaltener Coin oder Gebühr-Coin) und
    /// einen Tagesschluss brauchen (`PortfolioLedger.needsDayClose`) — deren Coin und beim Tausch der erhaltene Coin.
    static func feeCoins(for symbol: String, trades: [PortfolioTx]) -> Set<String> {
        var out = Set<String>()
        for t in trades {
            let coin = PortfolioCalculator.normalizeCoin(t.coin)
            guard coin == symbol || t.swapToCoin == symbol || t.thirdFeeCoin == symbol,
                  PortfolioLedger.needsDayClose(t) else { continue }
            out.insert(coin)
            if let to = t.swapToCoin { out.insert(to) }
        }
        return out
    }
}

/// Eingabe einer Rechnung: Schlüssel und Tagesschlüsse für die Gebühr-Zeilen (wie `PortfolioModel.feePrice`).
struct PortfolioCoinLedgerInput: Sendable {
    let key: PortfolioCoinLedgerKey
    let closes: [String: [Int: Double]]

    /// Gleiche Rechnung wie vorher in `PortfolioCoinDetail.body`; rein, läuft im Hintergrund.
    nonisolated static func ledger(_ input: PortfolioCoinLedgerInput) -> CoinLedger {
        let live = input.key.prices
        let closes = input.closes
        let feeCoins = input.key.feeCoins
        return PortfolioCalculator.ledger(input.key.coin, trades: input.key.trades, currentPrice: live[input.key.coin],
                                          method: input.key.method, feePrice: { coin, time in
            // Nur Coins aus `feeCoins` (andere Zeilen zählen hier nicht), wie Android `CoinFeePrices.priceAt`
            let c = PortfolioCalculator.normalizeCoin(coin)
            guard feeCoins.contains(c) else { return nil }
            return PortfolioPeriodChange.marketPriceAt(c, live: live[c], closes: closes, time: time)
        })
    }
}

extension PortfolioModel {
    /// Schlüssel der Coin-Ansicht für `coin` (normalisiert); billig, unveränderte Arrays teilen ihren Speicher.
    func coinLedgerKey(_ coin: String) -> PortfolioCoinLedgerKey {
        let trades = calcPortfolio
        let feeCoins = PortfolioCoinLedgerKey.feeCoins(for: coin, trades: trades)
        let needed = feeCoins.union([coin])
        return PortfolioCoinLedgerKey(coin: coin, trades: trades, prices: prices.prices.filter { needed.contains($0.key) },
                                      method: data.settings.costMethod, closesStamp: historyClosesStamp,
                                      feeCoins: feeCoins)
    }

    /// Ledger zu `key`, im Hintergrund gerechnet; die Tagesschlüsse werden hier (Hauptthread) einmal gelesen.
    func coinLedger(_ key: PortfolioCoinLedgerKey) async -> CoinLedger {
        let input = PortfolioCoinLedgerInput(key: key, closes: historyCloses.map { routed($0.prices).closes } ?? [:])
        return await Task.detached(priority: .userInitiated) { PortfolioCoinLedgerInput.ledger(input) }.value
    }
}

/// Eingaben des Wallet-Hinweises unter der Gesamtkarte (FIFO je Wallet, `PortfolioCalculator.walletShortfalls`) —
/// abseits des Hauptthreads statt in `body`, wie Android `walletShortfallsFlow`.
struct PortfolioWalletShortfallKey: Equatable, Sendable {
    let trades: [PortfolioTx]
    let method: CostMethod

    /// Gleiche Rechnung wie vorher in `PortfolioScreen.content`, im Hintergrund.
    func compute() async -> [PortfolioWalletShortfall] {
        let key = self
        return await Task.detached(priority: .userInitiated) {
            PortfolioCalculator.walletShortfalls(key.trades, method: key.method)
        }.value
    }
}
