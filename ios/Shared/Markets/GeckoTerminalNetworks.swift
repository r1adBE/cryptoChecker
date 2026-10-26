import Foundation

/// Netzwerk-Kennungen von GeckoTerminal (Reserve der DEX-Kurse, `DexScreener.geckoTerminalUrl`). Wie
/// `GeckoTerminalNetworks.kt`.
enum GeckoTerminalNetworks {
    /// DexScreener-Chain → GeckoTerminal-Netzwerk, wo die Namen abweichen; sonst gleich (kleingeschrieben).
    /// Paritätsfälle: `testdata/parity/gecko_terminal_network.json`.
    static let networkByChain: [String: String] = [
        "ethereum": "eth", "polygon": "polygon_pos", "avalanche": "avax", "sui": "sui-network",
        "starknet": "starknet-alpha", "hedera": "hedera-hashgraph", "cronos": "cro", "fantom": "ftm",
        "gnosis": "xdai", "seiv2": "sei-evm", "xlayer": "x-layer", "worldchain": "world-chain",
        "polygonzkevm": "polygon-zkevm", "manta": "manta-pacific", "moonbeam": "glmr", "moonriver": "movr",
        "harmony": "one", "zksync": "zksync",
    ]

    /// Netzwerk-Kennung von GeckoTerminal zur DexScreener-Chain.
    static func network(_ chainId: String) -> String {
        let chain = chainId.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return networkByChain[chain] ?? chain
    }
}
