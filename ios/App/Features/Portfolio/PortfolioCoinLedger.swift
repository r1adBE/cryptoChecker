import Foundation

/// Eingaben der Coin-Ansicht (`PortfolioCalculator.ledger`): Coin, Transaktionen samt Adress-Guthaben, aktuelle Kurse
/// (auch für die Gebühr-Zeilen), Einstandsmethode und Ladung der Tagesschlüsse (Zähler) — Schlüssel für `.task(id:)`.
/// Seit Build 103 abseits des Hauptthreads gerechnet statt in `body`, wie Android `coinDetailFlow`.
struct PortfolioCoinLedgerKey: Equatable, Sendable {
    let coin: String
    let trades: [PortfolioTx]
    let prices: [String: Double]
    let method: CostMethod
    let closesStamp: Int
}

/// Eingabe einer Rechnung: Schlüssel und Tagesschlüsse für die Gebühr-Zeilen (wie `PortfolioModel.feePrice`).
struct PortfolioCoinLedgerInput: Sendable {
    let key: PortfolioCoinLedgerKey
    let closes: [String: [Int: Double]]

    /// Gleiche Rechnung wie vorher in `PortfolioCoinDetail.body`; rein, läuft im Hintergrund.
    nonisolated static func ledger(_ input: PortfolioCoinLedgerInput) -> CoinLedger {
        let live = input.key.prices
        let closes = input.closes
        return PortfolioCalculator.ledger(input.key.coin, trades: input.key.trades, currentPrice: live[input.key.coin],
                                          method: input.key.method, feePrice: { coin, time in
            PortfolioPeriodChange.marketPriceAt(coin, live: live[PortfolioCalculator.normalizeCoin(coin)],
                                                closes: closes, time: time)
        })
    }
}

extension PortfolioModel {
    /// Schlüssel der Coin-Ansicht für `coin` (normalisiert); billig, unveränderte Arrays teilen ihren Speicher.
    func coinLedgerKey(_ coin: String) -> PortfolioCoinLedgerKey {
        PortfolioCoinLedgerKey(coin: coin, trades: calcPortfolio, prices: prices.prices,
                               method: data.settings.costMethod, closesStamp: historyClosesStamp)
    }

    /// Ledger zu `key`, im Hintergrund gerechnet; die Tagesschlüsse werden hier (Hauptthread) einmal gelesen.
    func coinLedger(_ key: PortfolioCoinLedgerKey) async -> CoinLedger {
        let input = PortfolioCoinLedgerInput(key: key, closes: historyCloses.map { routed($0.prices).closes } ?? [:])
        return await Task.detached(priority: .userInitiated) { PortfolioCoinLedgerInput.ledger(input) }.value
    }
}

/// Eingaben des Wallet-Hinweises unter der Gesamtkarte (FIFO je Wallet, `PortfolioCalculator.walletShortfalls`) —
/// seit Build 103 abseits des Hauptthreads statt in `body`, wie Android `walletShortfallsFlow`.
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
