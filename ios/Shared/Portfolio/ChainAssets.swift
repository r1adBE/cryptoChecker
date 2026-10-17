import Foundation

/// Was eine beobachtete Adresse ausser ihrem Netz-Guthaben (`WatchedAddress.lastBalance`) halten kann — feste,
/// kuratierte Listen (nur verifizierte Verträge/Mints).
///
///  - Ethereum-Adresse (0x): ERC-20 auf Ethereum (`Erc20Tokens.list`, Schlüssel = Symbol) und dieselbe Adresse auf
///    weiteren EVM-Netzen (`evm`): natives Guthaben und Token, Schlüssel «NETZ:SYMBOL» (z. B. «ARBITRUM:ETH»,
///    «BSC:USDT»). Welche Netze zählen, wählt der Nutzer je Adresse (`WatchedAddress.networks`).
///  - TRON (T…): TRC-20 (`tronTokens`), Schlüssel = Symbol.
///  - Solana: SPL-Token (Token-Programm und Token-2022) nach Mint (`solanaTokens`); unbekannte Mints zählen nicht.
///  - TON: Jettons nach Master-Vertrag (`tonJettons`).
/// `Asset.coin` = Coin im Portfolio (Kurs). Gekapselte/gebrückte Coins (WBTC, WETH, BTCB, BTC.b, WBNB, WAVAX, wSOL)
/// bleiben eigene Coins (`wrapped`): Kurs aus einem eigenen Börsenpaar, sonst ersatzweise der Kurs der Basis mit
/// Hinweis «≈ BTC-Kurs (gebrückt/verpackt)» (`priceViaBase`). Gebrückte Stablecoins (USDC.e, USDbC, USDT.e) zählen als
/// ihr Basis-Stablecoin; die Adress-Zeile zeigt Symbol und Netz.
///
/// Quellen (übernommen 14.10.2026): Uniswap Default Token List (github.com/Uniswap/default-token-list, Commit 583cb69,
/// src/tokens/{bnb,polygon,arbitrum,base,optimism,avalanche,solana}.json), SushiSwap Default Token List
/// (github.com/sushiswap/list, Commit 088f547, lists/token-lists/default-token-list/tokens/<netz>.json) und Circle
/// «USDC Contract Addresses» (developers.circle.com/stablecoins/usdc-contract-addresses) für natives USDC; TRON-USDT
/// laut Tether/TRON-Dokumentation (6 Dezimalstellen). Jede Adresse steht in mindestens einer dieser Listen mit diesen
/// Dezimalstellen (Testfall `address_balance.json` → «chainAssets»).
/// Gleich wie `ChainAssets.kt`.
enum ChainAssets {

    /// EVM-Netz mit derselben 0x-Adresse; `native` = Coin des Netz-Guthabens, `rpcs` öffentliche Knoten ohne Schlüssel.
    struct EvmNetwork: Equatable, Sendable {
        let id: String
        let name: String
        let native: String
        let rpcs: [String]
        let tokens: [Erc20Tokens.Token]
    }

    /// Weitere EVM-Netze (Ethereum selbst immer). Knoten: zuerst PublicNode, sonst der offizielle bzw. bekannte öffentliche
    /// Knoten des Netzes (beide in der DefiLlama-Chainlist, github.com/DefiLlama/chainlist).
    static let evm: [EvmNetwork] = [
        EvmNetwork(id: "BSC", name: "BNB Smart Chain", native: "BNB",
                   rpcs: ["https://bsc-rpc.publicnode.com", "https://bsc-dataseed.bnbchain.org"],
                   tokens: [
                Erc20Tokens.Token(symbol: "USDT", contract: "0x55d398326f99059ff775485246999027b3197955", decimals: 18),
                // Binance-Peg (gebrückt); natives USDC gibt es auf BSC nicht
                Erc20Tokens.Token(symbol: "USDC", contract: "0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d", decimals: 18),
                Erc20Tokens.Token(symbol: "BTCB", contract: "0x7130d2a12b9bcbfae4f2634d864a1ee1ce3ead9c", decimals: 18, coin: "BTCB"),
                // Binance-Peg Ethereum
                Erc20Tokens.Token(symbol: "ETH", contract: "0x2170ed0880ac9a755fd29b2688956bd959f933f8", decimals: 18),
                Erc20Tokens.Token(symbol: "CAKE", contract: "0x0e09fabb73bd3ade0a17ecc321fd13a19e81ce82", decimals: 18),
                Erc20Tokens.Token(symbol: "WBNB", contract: "0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c", decimals: 18, coin: "WBNB"),
                   ]),
        EvmNetwork(id: "POLYGON", name: "Polygon PoS", native: "POL",
                   rpcs: ["https://polygon-bor-rpc.publicnode.com", "https://polygon-rpc.com"],
                   tokens: [
                Erc20Tokens.Token(symbol: "USDT", contract: "0xc2132d05d31c914a87c6611c10748aeb04b58e8f", decimals: 6),
                Erc20Tokens.Token(symbol: "USDC", contract: "0x3c499c542cef5e3811e1192ce70d8cc03d5c3359", decimals: 6),
                Erc20Tokens.Token(symbol: "USDC.e", contract: "0x2791bca1f2de4661ed88a30c99a7a9449aa84174", decimals: 6, coin: "USDC"),
                Erc20Tokens.Token(symbol: "WBTC", contract: "0x1bfd67037b42cf73acf2047067bd4f2c47d9bfd6", decimals: 8),
                Erc20Tokens.Token(symbol: "WETH", contract: "0x7ceb23fd6bc0add59e62ac25578270cff1b9f619", decimals: 18, coin: "WETH"),
                Erc20Tokens.Token(symbol: "LINK", contract: "0x53e0bca35ec356bd5dddfebbd1fc0fd03fabad39", decimals: 18),
                Erc20Tokens.Token(symbol: "AAVE", contract: "0xd6df932a45c0f255f85145f286ea0b292b21c90b", decimals: 18),
                Erc20Tokens.Token(symbol: "DAI", contract: "0x8f3cf7ad23cd3cadbd9735aff958023239c6a063", decimals: 18),
                   ]),
        EvmNetwork(id: "ARBITRUM", name: "Arbitrum One", native: "ETH",
                   rpcs: ["https://arbitrum-one-rpc.publicnode.com", "https://arb1.arbitrum.io/rpc"],
                   tokens: [
                Erc20Tokens.Token(symbol: "USDT", contract: "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9", decimals: 6),
                Erc20Tokens.Token(symbol: "USDC", contract: "0xaf88d065e77c8cc2239327c5edb3a432268e5831", decimals: 6),
                Erc20Tokens.Token(symbol: "USDC.e", contract: "0xff970a61a04b1ca14834a43f5de4533ebddb5cc8", decimals: 6, coin: "USDC"),
                Erc20Tokens.Token(symbol: "ARB", contract: "0x912ce59144191c1204e64559fe8253a0e49e6548", decimals: 18),
                Erc20Tokens.Token(symbol: "WBTC", contract: "0x2f2a2543b76a4166549f7aab2e75bef0aefc5b0f", decimals: 8),
                Erc20Tokens.Token(symbol: "LINK", contract: "0xf97f4df75117a78c1a5a0dbb814af92458539fb4", decimals: 18),
                Erc20Tokens.Token(symbol: "DAI", contract: "0xda10009cbd5d07dd0cecc66161fc93d7c9000da1", decimals: 18),
                   ]),
        EvmNetwork(id: "BASE", name: "Base", native: "ETH",
                   rpcs: ["https://base-rpc.publicnode.com", "https://mainnet.base.org"],
                   tokens: [
                Erc20Tokens.Token(symbol: "USDC", contract: "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", decimals: 6),
                Erc20Tokens.Token(symbol: "USDbC", contract: "0xd9aaec86b65d86f6a7b5b1b0c42ffa531710b6ca", decimals: 6, coin: "USDC"),
                Erc20Tokens.Token(symbol: "DAI", contract: "0x50c5725949a6f0c72e6c4a641f24049a917db0cb", decimals: 18),
                Erc20Tokens.Token(symbol: "WETH", contract: "0x4200000000000000000000000000000000000006", decimals: 18, coin: "WETH"),
                   ]),
        EvmNetwork(id: "OPTIMISM", name: "OP Mainnet", native: "ETH",
                   rpcs: ["https://optimism-rpc.publicnode.com", "https://mainnet.optimism.io"],
                   tokens: [
                Erc20Tokens.Token(symbol: "USDT", contract: "0x94b008aa00579c1307b0ef2c499ad98a8ce58e58", decimals: 6),
                Erc20Tokens.Token(symbol: "USDC", contract: "0x0b2c639c533813f4aa9d7837caf62653d097ff85", decimals: 6),
                Erc20Tokens.Token(symbol: "USDC.e", contract: "0x7f5c764cbc14f9669b88837ca1490cca17c31607", decimals: 6, coin: "USDC"),
                Erc20Tokens.Token(symbol: "OP", contract: "0x4200000000000000000000000000000000000042", decimals: 18),
                Erc20Tokens.Token(symbol: "WBTC", contract: "0x68f180fcce6836688e9084f035309e29bf0a2095", decimals: 8),
                Erc20Tokens.Token(symbol: "DAI", contract: "0xda10009cbd5d07dd0cecc66161fc93d7c9000da1", decimals: 18),
                Erc20Tokens.Token(symbol: "LINK", contract: "0x350a791bfc2c21f9ed5d10980dad2e2638ffa7f6", decimals: 18),
                   ]),
        EvmNetwork(id: "AVALANCHE", name: "Avalanche C-Chain", native: "AVAX",
                   rpcs: ["https://avalanche-c-chain-rpc.publicnode.com", "https://api.avax.network/ext/bc/C/rpc"],
                   tokens: [
                Erc20Tokens.Token(symbol: "USDT", contract: "0x9702230a8ea53601f5cd2dc00fdbc13d4df4a8c7", decimals: 6),
                Erc20Tokens.Token(symbol: "USDT.e", contract: "0xc7198437980c041c805a1edcba50c1ce5db95118", decimals: 6, coin: "USDT"),
                Erc20Tokens.Token(symbol: "USDC", contract: "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e", decimals: 6),
                Erc20Tokens.Token(symbol: "USDC.e", contract: "0xa7d7079b0fead91f3e65f86e8915cb59c1a4c664", decimals: 6, coin: "USDC"),
                Erc20Tokens.Token(symbol: "BTC.b", contract: "0x152b9d0fdc40c096757f570a51e494bd4b943e50", decimals: 8, coin: "BTC.B"),
                Erc20Tokens.Token(symbol: "WAVAX", contract: "0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7", decimals: 18, coin: "WAVAX"),
                Erc20Tokens.Token(symbol: "JOE", contract: "0x6e84a6216ea6dacc71ee8e6b0a5b7322eebc0fdd", decimals: 18),
                   ]),
    ]

    static func network(_ id: String?) -> EvmNetwork? { evm.first { $0.id == id } }

    /// TRC-20 (Vertrag Base58). USDC auf TRON: von Circle seit Februar 2024 eingestellt — nicht aufgenommen.
    struct TronToken: Equatable, Sendable {
        let symbol: String
        let contract: String
        let decimals: Int
        let coin: String
    }

    static let tronTokens: [TronToken] = [
        TronToken(symbol: "USDT", contract: "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", decimals: 6, coin: "USDT"),
    ]

    /// SPL-Token nach Mint (Token-Programm oder Token-2022).
    struct SplToken: Equatable, Sendable {
        let symbol: String
        let mint: String
        let decimals: Int
        let coin: String

        init(symbol: String, mint: String, decimals: Int, coin: String? = nil) {
            self.symbol = symbol
            self.mint = mint
            self.decimals = decimals
            self.coin = coin ?? symbol
        }
    }

    static let solanaTokens: [SplToken] = [
        SplToken(symbol: "USDC", mint: "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", decimals: 6),
        SplToken(symbol: "USDT", mint: "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", decimals: 6),
        // Gekapseltes SOL (eigene Token-Konten)
        SplToken(symbol: "wSOL", mint: "So11111111111111111111111111111111111111112", decimals: 9, coin: "WSOL"),
        SplToken(symbol: "JUP", mint: "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN", decimals: 6),
        SplToken(symbol: "BONK", mint: "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263", decimals: 5),
        SplToken(symbol: "WIF", mint: "EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm", decimals: 6),
        SplToken(symbol: "PYTH", mint: "HZ1JovNiVvGrGNiiYvEozEVgZ58xaU3RKwX8eACQBCt3", decimals: 6),
        SplToken(symbol: "JTO", mint: "jtojtomepa8beP8AuQc6eXt5FriJwfFMwQx2v2f9mCL", decimals: 9),
        SplToken(symbol: "RAY", mint: "4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R", decimals: 6),
        SplToken(symbol: "ORCA", mint: "orcaEKTdK7LKz57vaAYr9QeNsVEPfiu6QeMU1kektZE", decimals: 6),
        SplToken(symbol: "RENDER", mint: "rndrizKT3MK1iimdxRdWabcF7Zg7AR5T4nud4EkHBof", decimals: 8),
        SplToken(symbol: "HNT", mint: "hntyVP6YFm1Hg25TN9WGLqM12b8TQmcknKrdu1oxWux", decimals: 8),
        SplToken(symbol: "TRUMP", mint: "6p6xgHyF7AeE6TZkSmFsko444wqoP15icUSqi2jfGiPN", decimals: 6),
        // Wormhole-ETH
        SplToken(symbol: "WETH", mint: "7vfCXTUXx5WJV5JADk17DUJ4ksgau7utNKj4b963voxs", decimals: 8, coin: "WETH"),
    ]

    /// TON-Jettons (TEP-74) nach Master-Vertrag; `rawHex` = 32 Byte der Master-Adresse (Workchain 0) zum Vergleich mit der
    /// Antwort von TON Center v3. USDT: docs.ton.org «USDT Processing» und BitGo-Tokenliste (6 Dezimalstellen).
    struct Jetton: Equatable, Sendable {
        let symbol: String
        let master: String
        let rawHex: String
        let decimals: Int
        let coin: String
    }

    static let tonJettons: [Jetton] = [
        Jetton(symbol: "USDT", master: "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs",
               rawHex: "b113a994b5024a16719f69139328eb759596c38a25f59028b146fecdc3621dfe", decimals: 6, coin: "USDT"),
    ]

    /// Ein Eintrag von `WatchedAddress.tokenBalances`: `label` für die Anzeige, `coin` für Kurs/Bestand, `native` =
    /// Netz-Guthaben eines weiteren EVM-Netzes (zählt auch ohne «Tokens mitzählen»), `network` = EVM-Netz (nil = Netz der
    /// Adresse selbst), `order` = Reihenfolge der Anzeige.
    struct Asset: Equatable, Sendable {
        let key: String
        let label: String
        let coin: String
        let native: Bool
        let network: String?
        let order: Int
    }

    static func key(_ network: String, _ symbol: String) -> String { "\(network):\(symbol)" }

    /// Schlüssel → Eintrag; unbekannt (alte/fremde Daten) = nil. Wie Android.
    static func resolve(_ chain: AddressChain, _ key: String) -> Asset? {
        switch chain {
        case .ETH:
            guard let sep = key.firstIndex(of: ":") else {
                guard let i = Erc20Tokens.list.firstIndex(where: { $0.symbol == key }) else { return nil }
                return Asset(key: key, label: key, coin: Erc20Tokens.list[i].coin, native: false, network: nil, order: i)
            }
            let netId = String(key[..<sep])
            let symbol = String(key[key.index(after: sep)...])
            guard let ni = evm.firstIndex(where: { $0.id == netId }) else { return nil }
            let net = evm[ni]
            let base = 1000 * (ni + 1)
            if symbol == net.native {
                return Asset(key: key, label: symbol, coin: net.native, native: true, network: net.id, order: base)
            }
            guard let ti = net.tokens.firstIndex(where: { $0.symbol == symbol }) else { return nil }
            return Asset(key: key, label: symbol, coin: net.tokens[ti].coin, native: false, network: net.id, order: base + 1 + ti)
        case .TRX:
            guard let i = tronTokens.firstIndex(where: { $0.symbol == key }) else { return nil }
            return Asset(key: key, label: key, coin: tronTokens[i].coin, native: false, network: nil, order: i)
        case .SOL:
            guard let i = solanaTokens.firstIndex(where: { $0.symbol == key }) else { return nil }
            return Asset(key: key, label: key, coin: solanaTokens[i].coin, native: false, network: nil, order: i)
        case .TON:
            guard let i = tonJettons.firstIndex(where: { $0.symbol == key }) else { return nil }
            return Asset(key: key, label: key, coin: tonJettons[i].coin, native: false, network: nil, order: i)
        default:
            return nil
        }
    }

    /// Gekapselte/gebrückte Coins mit eigenem Kürzel (Grossschreibung) → Basis-Coin. Sie verschmelzen NICHT mit der
    /// Basis (anderes Risiko: Brücke/Verwahrer); nur ohne eigenen Kurs gilt ersatzweise der Kurs der Basis. Wie Android.
    static let wrapped: [String: String] = [
        "WBTC": "BTC", "BTCB": "BTC", "BTC.B": "BTC",
        "WETH": "ETH", "WBNB": "BNB", "WAVAX": "AVAX", "WSOL": "SOL",
    ]

    /// Basis eines gekapselten Coins (`wrapped`); nil = kein gekapselter Coin.
    static func wrappedBase(_ coin: String) -> String? {
        wrapped[coin.trimmingCharacters(in: .whitespaces).uppercased()]
    }

    /// Abfrage-Liste: `coins` (Grossschreibung) plus die Basis jedes gekapselten Coins — für den Ersatzkurs.
    static func withBases(_ coins: [String]) -> [String] {
        let upper: [String] = coins.map { $0.trimmingCharacters(in: .whitespaces).uppercased() }
        let bases: [String] = upper.compactMap { wrappedBase($0) }
        var out: [String] = []
        for coin in upper + bases where !out.contains(coin) {
            out.append(coin)
        }
        return out
    }

    /// Gekapselte Coins aus `coins` ohne eigenen Wert in `available` (Schlüssel gross), deren Basis einen hat: Coin →
    /// Basis. Diese Coins werden mit dem Wert der Basis gezeigt — immer markiert. Wie Android.
    static func priceViaBase(_ coins: [String], available: Set<String>) -> [String: String] {
        var out: [String: String] = [:]
        for raw in coins {
            let coin = raw.trimmingCharacters(in: .whitespaces).uppercased()
            guard let base = wrappedBase(coin) else { continue }
            if !available.contains(coin) && available.contains(base) { out[coin] = base }
        }
        return out
    }

    /// Wie `priceViaBase`, übernimmt aber gleich den Eintrag der Basis in `values` (z. B. Tageskurse).
    static func fillFromBase<T>(_ coins: [String], _ values: [String: T]) -> [String: T] {
        let via = priceViaBase(coins, available: Set(values.keys))
        guard !via.isEmpty else { return values }
        var out = values
        for (coin, base) in via { if let v = values[base] { out[coin] = v } }
        return out
    }

    /// Kann diese Kette Token/weitere Netze halten (Schalter «Tokens mitzählen»)?
    static func hasTokens(_ chain: AddressChain) -> Bool { chain == .ETH || chain == .TRX || chain == .SOL || chain == .TON }
}
