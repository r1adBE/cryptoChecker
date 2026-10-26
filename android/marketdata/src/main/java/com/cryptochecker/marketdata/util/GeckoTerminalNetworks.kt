package com.cryptochecker.marketdata.util

/**
 * Netzwerk-Kennung von GeckoTerminal zur Chain-Kennung von DexScreener (Ausweg der DEX-Kurse, Build 133). Die meisten
 * sind gleich («base», «bsc», «solana»); abweichende stehen in [DIFFERENT]. Wie iOS `GeckoTerminalNetworks`
 * (`testdata/parity/gecko_terminal_network.json`).
 */
object GeckoTerminalNetworks {

    private val DIFFERENT = mapOf(
        "ethereum" to "eth",
        "polygon" to "polygon_pos",
        "avalanche" to "avax",
        "sui" to "sui-network",
        "starknet" to "starknet-alpha",
        "hedera" to "hedera-hashgraph",
        "cronos" to "cro",
        "fantom" to "ftm",
        "gnosis" to "xdai",
        "seiv2" to "sei-evm",
        "xlayer" to "x-layer",
        "worldchain" to "world-chain",
        "polygonzkevm" to "polygon-zkevm",
        "manta" to "manta-pacific",
        "moonbeam" to "glmr",
        "moonriver" to "movr",
        "harmony" to "one",
    )

    /** GeckoTerminal-Netzwerk zu [chainId] (ohne Gross-/Kleinschreibung); unbekannt = dieselbe Kennung, klein. */
    fun network(chainId: String): String {
        val chain = chainId.trim().lowercase()
        return DIFFERENT[chain] ?: chain
    }
}
