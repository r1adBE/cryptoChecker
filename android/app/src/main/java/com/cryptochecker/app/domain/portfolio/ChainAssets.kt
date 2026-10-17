package com.cryptochecker.app.domain.portfolio

/**
 * Was eine beobachtete Adresse ausser ihrem Netz-Guthaben ([WatchedAddress.lastBalance]) halten kann — feste,
 * kuratierte Listen (nur verifizierte Verträge/Mints), gleich wie `ChainAssets.swift`.
 *
 *  - Ethereum-Adresse (0x): ERC-20 auf Ethereum ([Erc20Tokens.LIST], Schlüssel = Symbol) und dieselbe Adresse auf
 *    weiteren EVM-Netzen ([EVM]): natives Guthaben und Token, Schlüssel «NETZ:SYMBOL» (z. B. «ARBITRUM:ETH»,
 *    «BSC:USDT»). Welche Netze zählen, wählt der Nutzer je Adresse ([WatchedAddress.networks]).
 *  - TRON (T…): TRC-20 ([TRON_TOKENS]), Schlüssel = Symbol.
 *  - Solana: SPL-Token (Token-Programm und Token-2022) nach Mint ([SOLANA_TOKENS]); unbekannte Mints zählen nicht.
 *  - TON: Jettons nach Master-Vertrag ([TON_JETTONS]).
 * [Asset.coin] = Coin im Portfolio (Kurs). Gekapselte/gebrückte Coins (WBTC, WETH, BTCB, BTC.b, WBNB, WAVAX, wSOL)
 * bleiben eigene Coins ([WRAPPED]): Kurs aus einem eigenen Börsenpaar, sonst ersatzweise der Kurs der Basis mit
 * Hinweis «≈ BTC-Kurs (gebrückt/verpackt)» ([priceViaBase]). Gebrückte Stablecoins (USDC.e, USDbC, USDT.e) zählen als
 * ihr Basis-Stablecoin; die Adress-Zeile zeigt Symbol und Netz.
 *
 * Quellen (übernommen 14.10.2026): Uniswap Default Token List (github.com/Uniswap/default-token-list, Commit 583cb69,
 * src/tokens/{bnb,polygon,arbitrum,base,optimism,avalanche,solana}.json), SushiSwap Default Token List
 * (github.com/sushiswap/list, Commit 088f547, lists/token-lists/default-token-list/tokens/<netz>.json) und Circle
 * «USDC Contract Addresses» (developers.circle.com/stablecoins/usdc-contract-addresses) für natives USDC; TRON-USDT
 * laut Tether/TRON-Dokumentation (6 Dezimalstellen). Jede Adresse steht in mindestens einer dieser Listen mit diesen
 * Dezimalstellen (Testfall `address_balance.json` → «chainAssets»).
 */
object ChainAssets {

    /** EVM-Netz mit derselben 0x-Adresse; [native] = Coin des Netz-Guthabens, [rpcs] öffentliche Knoten ohne Schlüssel. */
    data class EvmNetwork(
        val id: String,
        val name: String,
        val native: String,
        val rpcs: List<String>,
        val tokens: List<Erc20Tokens.Token>,
    )

    private fun t(symbol: String, contract: String, decimals: Int, coin: String = symbol) =
        Erc20Tokens.Token(symbol, contract, decimals, coin)

    /**
     * Weitere EVM-Netze (Ethereum selbst immer). Knoten: zuerst PublicNode, sonst der offizielle bzw. bekannte
     * öffentliche Knoten des Netzes (beide in der DefiLlama-Chainlist, github.com/DefiLlama/chainlist).
     */
    val EVM: List<EvmNetwork> = listOf(
        EvmNetwork(
            "BSC", "BNB Smart Chain", "BNB",
            listOf("https://bsc-rpc.publicnode.com", "https://bsc-dataseed.bnbchain.org"),
            listOf(
                t("USDT", "0x55d398326f99059ff775485246999027b3197955", 18),
                // Binance-Peg (gebrückt); natives USDC gibt es auf BSC nicht
                t("USDC", "0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d", 18),
                t("BTCB", "0x7130d2a12b9bcbfae4f2634d864a1ee1ce3ead9c", 18),
                // Binance-Peg Ethereum
                t("ETH", "0x2170ed0880ac9a755fd29b2688956bd959f933f8", 18),
                t("CAKE", "0x0e09fabb73bd3ade0a17ecc321fd13a19e81ce82", 18),
                t("WBNB", "0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c", 18),
            ),
        ),
        EvmNetwork(
            "POLYGON", "Polygon PoS", "POL",
            listOf("https://polygon-bor-rpc.publicnode.com", "https://polygon-rpc.com"),
            listOf(
                t("USDT", "0xc2132d05d31c914a87c6611c10748aeb04b58e8f", 6),
                t("USDC", "0x3c499c542cef5e3811e1192ce70d8cc03d5c3359", 6),
                t("USDC.e", "0x2791bca1f2de4661ed88a30c99a7a9449aa84174", 6, coin = "USDC"),
                t("WBTC", "0x1bfd67037b42cf73acf2047067bd4f2c47d9bfd6", 8),
                t("WETH", "0x7ceb23fd6bc0add59e62ac25578270cff1b9f619", 18),
                t("LINK", "0x53e0bca35ec356bd5dddfebbd1fc0fd03fabad39", 18),
                t("AAVE", "0xd6df932a45c0f255f85145f286ea0b292b21c90b", 18),
                t("DAI", "0x8f3cf7ad23cd3cadbd9735aff958023239c6a063", 18),
            ),
        ),
        EvmNetwork(
            "ARBITRUM", "Arbitrum One", "ETH",
            listOf("https://arbitrum-one-rpc.publicnode.com", "https://arb1.arbitrum.io/rpc"),
            listOf(
                t("USDT", "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9", 6),
                t("USDC", "0xaf88d065e77c8cc2239327c5edb3a432268e5831", 6),
                t("USDC.e", "0xff970a61a04b1ca14834a43f5de4533ebddb5cc8", 6, coin = "USDC"),
                t("ARB", "0x912ce59144191c1204e64559fe8253a0e49e6548", 18),
                t("WBTC", "0x2f2a2543b76a4166549f7aab2e75bef0aefc5b0f", 8),
                t("LINK", "0xf97f4df75117a78c1a5a0dbb814af92458539fb4", 18),
                t("DAI", "0xda10009cbd5d07dd0cecc66161fc93d7c9000da1", 18),
            ),
        ),
        EvmNetwork(
            "BASE", "Base", "ETH",
            listOf("https://base-rpc.publicnode.com", "https://mainnet.base.org"),
            listOf(
                t("USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", 6),
                t("USDbC", "0xd9aaec86b65d86f6a7b5b1b0c42ffa531710b6ca", 6, coin = "USDC"),
                t("DAI", "0x50c5725949a6f0c72e6c4a641f24049a917db0cb", 18),
                t("WETH", "0x4200000000000000000000000000000000000006", 18),
            ),
        ),
        EvmNetwork(
            "OPTIMISM", "OP Mainnet", "ETH",
            listOf("https://optimism-rpc.publicnode.com", "https://mainnet.optimism.io"),
            listOf(
                t("USDT", "0x94b008aa00579c1307b0ef2c499ad98a8ce58e58", 6),
                t("USDC", "0x0b2c639c533813f4aa9d7837caf62653d097ff85", 6),
                t("USDC.e", "0x7f5c764cbc14f9669b88837ca1490cca17c31607", 6, coin = "USDC"),
                t("OP", "0x4200000000000000000000000000000000000042", 18),
                t("WBTC", "0x68f180fcce6836688e9084f035309e29bf0a2095", 8),
                t("DAI", "0xda10009cbd5d07dd0cecc66161fc93d7c9000da1", 18),
                t("LINK", "0x350a791bfc2c21f9ed5d10980dad2e2638ffa7f6", 18),
            ),
        ),
        EvmNetwork(
            "AVALANCHE", "Avalanche C-Chain", "AVAX",
            listOf("https://avalanche-c-chain-rpc.publicnode.com", "https://api.avax.network/ext/bc/C/rpc"),
            listOf(
                t("USDT", "0x9702230a8ea53601f5cd2dc00fdbc13d4df4a8c7", 6),
                t("USDT.e", "0xc7198437980c041c805a1edcba50c1ce5db95118", 6, coin = "USDT"),
                t("USDC", "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e", 6),
                t("USDC.e", "0xa7d7079b0fead91f3e65f86e8915cb59c1a4c664", 6, coin = "USDC"),
                t("BTC.b", "0x152b9d0fdc40c096757f570a51e494bd4b943e50", 8, coin = "BTC.B"),
                t("WAVAX", "0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7", 18),
                t("JOE", "0x6e84a6216ea6dacc71ee8e6b0a5b7322eebc0fdd", 18),
            ),
        ),
    )

    fun network(id: String?): EvmNetwork? = EVM.firstOrNull { it.id == id }

    /** TRC-20 (Vertrag Base58). USDC auf TRON: von Circle seit Februar 2024 eingestellt — nicht aufgenommen. */
    data class TronToken(val symbol: String, val contract: String, val decimals: Int, val coin: String = symbol)

    val TRON_TOKENS: List<TronToken> = listOf(
        TronToken("USDT", "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", 6),
    )

    /** SPL-Token nach Mint (Token-Programm oder Token-2022). */
    data class SplToken(val symbol: String, val mint: String, val decimals: Int, val coin: String = symbol)

    val SOLANA_TOKENS: List<SplToken> = listOf(
        SplToken("USDC", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", 6),
        SplToken("USDT", "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", 6),
        // Gekapseltes SOL (eigene Token-Konten)
        SplToken("wSOL", "So11111111111111111111111111111111111111112", 9, coin = "WSOL"),
        SplToken("JUP", "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN", 6),
        SplToken("BONK", "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263", 5),
        SplToken("WIF", "EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm", 6),
        SplToken("PYTH", "HZ1JovNiVvGrGNiiYvEozEVgZ58xaU3RKwX8eACQBCt3", 6),
        SplToken("JTO", "jtojtomepa8beP8AuQc6eXt5FriJwfFMwQx2v2f9mCL", 9),
        SplToken("RAY", "4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R", 6),
        SplToken("ORCA", "orcaEKTdK7LKz57vaAYr9QeNsVEPfiu6QeMU1kektZE", 6),
        SplToken("RENDER", "rndrizKT3MK1iimdxRdWabcF7Zg7AR5T4nud4EkHBof", 8),
        SplToken("HNT", "hntyVP6YFm1Hg25TN9WGLqM12b8TQmcknKrdu1oxWux", 8),
        SplToken("TRUMP", "6p6xgHyF7AeE6TZkSmFsko444wqoP15icUSqi2jfGiPN", 6),
        // Wormhole-ETH
        SplToken("WETH", "7vfCXTUXx5WJV5JADk17DUJ4ksgau7utNKj4b963voxs", 8),
    )

    /**
     * TON-Jettons (TEP-74) nach Master-Vertrag; [rawHex] = 32 Byte der Master-Adresse (Workchain 0) zum Vergleich mit der
     * Antwort von TON Center v3. USDT: docs.ton.org «USDT Processing» und BitGo-Tokenliste (6 Dezimalstellen).
     */
    data class Jetton(val symbol: String, val master: String, val rawHex: String, val decimals: Int, val coin: String = symbol)

    val TON_JETTONS: List<Jetton> = listOf(
        Jetton("USDT", "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs", "b113a994b5024a16719f69139328eb759596c38a25f59028b146fecdc3621dfe", 6),
    )

    /**
     * Ein Eintrag von [WatchedAddress.tokenBalances]: [label] für die Anzeige, [coin] für Kurs/Bestand, [native] =
     * Netz-Guthaben eines weiteren EVM-Netzes (zählt auch ohne «Tokens mitzählen»), [network] = EVM-Netz (null =
     * Netz der Adresse selbst), [order] = Reihenfolge der Anzeige.
     */
    data class Asset(val key: String, val label: String, val coin: String, val native: Boolean, val network: String?, val order: Int)

    fun key(network: String, symbol: String): String = "$network:$symbol"

    /** Schlüssel → Eintrag; unbekannt (alte/fremde Daten) = null. */
    fun resolve(chain: AddressChain, key: String): Asset? {
        when (chain) {
            AddressChain.ETH -> {
                val sep = key.indexOf(':')
                if (sep < 0) {
                    val i = Erc20Tokens.LIST.indexOfFirst { it.symbol == key }
                    if (i < 0) return null
                    return Asset(key, key, Erc20Tokens.LIST[i].coin, native = false, network = null, order = i)
                }
                val ni = EVM.indexOfFirst { it.id == key.substring(0, sep) }
                if (ni < 0) return null
                val net = EVM[ni]
                val symbol = key.substring(sep + 1)
                val base = 1000 * (ni + 1)
                if (symbol == net.native) return Asset(key, symbol, net.native, native = true, network = net.id, order = base)
                val ti = net.tokens.indexOfFirst { it.symbol == symbol }
                if (ti < 0) return null
                return Asset(key, symbol, net.tokens[ti].coin, native = false, network = net.id, order = base + 1 + ti)
            }
            AddressChain.TRX -> {
                val i = TRON_TOKENS.indexOfFirst { it.symbol == key }
                return if (i < 0) null else Asset(key, key, TRON_TOKENS[i].coin, native = false, network = null, order = i)
            }
            AddressChain.SOL -> {
                val i = SOLANA_TOKENS.indexOfFirst { it.symbol == key }
                return if (i < 0) null else Asset(key, key, SOLANA_TOKENS[i].coin, native = false, network = null, order = i)
            }
            AddressChain.TON -> {
                val i = TON_JETTONS.indexOfFirst { it.symbol == key }
                return if (i < 0) null else Asset(key, key, TON_JETTONS[i].coin, native = false, network = null, order = i)
            }
            else -> return null
        }
    }

    /**
     * Gekapselte/gebrückte Coins mit eigenem Kürzel (Grossschreibung) → Basis-Coin. Sie verschmelzen NICHT mit der
     * Basis (anderes Risiko: Brücke/Verwahrer); nur wenn kein eigener Kurs da ist, gilt ersatzweise der Kurs der Basis.
     */
    val WRAPPED: Map<String, String> = mapOf(
        "WBTC" to "BTC", "BTCB" to "BTC", "BTC.B" to "BTC",
        "WETH" to "ETH", "WBNB" to "BNB", "WAVAX" to "AVAX", "WSOL" to "SOL",
    )

    /** Basis eines gekapselten Coins ([WRAPPED]); null = kein gekapselter Coin. */
    fun wrappedBase(coin: String): String? = WRAPPED[coin.trim().uppercase()]

    /** Abfrage-Liste: [coins] (Grossschreibung) plus die Basis jedes gekapselten Coins — für den Ersatzkurs. */
    fun withBases(coins: Collection<String>): List<String> {
        val upper = coins.map { it.trim().uppercase() }
        return (upper + upper.mapNotNull { wrappedBase(it) }).distinct()
    }

    /**
     * Gekapselte Coins aus [coins] ohne eigenen Wert in [available] (Schlüssel gross), deren Basis einen hat: Coin →
     * Basis. Diese Coins werden mit dem Wert der Basis gezeigt — immer markiert («≈ BTC-Kurs (gebrückt/verpackt)»).
     */
    fun priceViaBase(coins: Collection<String>, available: Set<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in coins) {
            val coin = raw.trim().uppercase()
            val base = wrappedBase(coin) ?: continue
            if (coin !in available && base in available) out[coin] = base
        }
        return out
    }

    /** Wie [priceViaBase], übernimmt aber gleich den Eintrag der Basis in [values] (z. B. Tageskurse). */
    fun <T> fillFromBase(coins: Collection<String>, values: Map<String, T>): Map<String, T> {
        val via = priceViaBase(coins, values.keys)
        if (via.isEmpty()) return values
        val out = LinkedHashMap(values)
        for ((coin, base) in via) values[base]?.let { out[coin] = it }
        return out
    }

    /** Kann diese Kette Token/weitere Netze halten (Schalter «Tokens mitzählen»)? */
    fun hasTokens(chain: AddressChain): Boolean =
        chain == AddressChain.ETH || chain == AddressChain.TRX || chain == AddressChain.SOL || chain == AddressChain.TON
}
