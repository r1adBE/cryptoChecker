package com.cryptochecker.app.domain.starter

/**
 * Stablecoins, Doppelgänger (verpackt, gestakt, gebrückt) und Börsen-Belege — die eine Regel für die
 * Start-Coins ([StarterCoins]), «Heute auffällig» ([MarketUniverse]), die Marktbreite («Top 30») im Pulse
 * und «Gewinner und Verlierer 24 h» ([com.cryptochecker.app.domain.exchange.MarketMovers]).
 * Gleich wie `CoinExclusion.swift`; gemeinsame Testfälle in `testdata/parity/stable_exclusion.json`.
 *
 * Listen und Namensregeln sind die Vereinigung beider Plattformen (lieber einmal zu viel ausschliessen als
 * einen Stablecoin in der Marktbreite mitzählen). Alle Vergleiche ohne Rücksicht auf Gross-/Kleinschreibung.
 */
object CoinExclusion {

    /** Stablecoins (an Dollar, Euro oder Gold gebunden), Kürzel in Grossbuchstaben. */
    val STABLECOINS: Set<String> = setOf(
        "USDT", "USDC", "DAI", "USDE", "USDS", "FDUSD", "TUSD", "PYUSD", "USD1", "BUSD", "USDD",
        "FRAX", "GUSD", "USDP", "EURC", "EURT", "RLUSD", "USDG", "USD0", "USDTB", "BFUSD", "SUSDS",
        "SUSDE", "USDT0", "USDX", "USDB", "USDY", "USDF", "LUSD", "CRVUSD", "GHO", "DOLA", "FXUSD",
        "EURS", "EURE", "XAUT", "PAXG", "USTC", "USR", "DEUSD", "SRUSD", "USDO", "AUSD", "USYC",
        "BUIDL", "USDM", "USDL", "MIM", "ALUSD", "SUSD", "CUSD", "ZUSD", "HUSD", "USDK", "USDJ",
        "USDAI", "OUSG", "XSGD",
    )

    /** Verpackte, gestakte und gebrückte Doppelgänger sowie Börsen-Belege. */
    val WRAPPED: Set<String> = setOf(
        "WBTC", "WETH", "STETH", "WSTETH", "WEETH", "EETH", "CBBTC", "CBETH", "RETH", "METH",
        "EZETH", "RSETH", "LSETH", "WBETH", "BETH", "BNSOL", "JITOSOL", "MSOL", "JUPSOL", "STSOL",
        "BBSOL", "WBNB", "BTCB", "TBTC", "LBTC", "SOLVBTC", "CLBTC", "FBTC", "UNIBTC", "WTRX",
        "WAVAX", "SAVAX", "WMATIC", "WPOL", "WSOL", "WHYPE", "KHYPE", "STHYPE", "OSETH", "SFRXETH",
        "FRXETH", "PUFETH", "SWETH", "ANKRETH", "TETH", "WEETHS", "RSWETH", "EBTC", "PUMPBTC",
        "BSC-USD", "BGBTC", "OKBTC", "BBTC", "BTC.B", "SOLVBTC.BBN", "WEETH.BASE",
    )

    /** Namen, die so beginnen, sind Doppelgänger (kleingeschrieben verglichen). */
    private val WRAPPED_NAME_PREFIXES = listOf("wrapped ", "staked ", "bridged ", "liquid staked ")

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Stablecoin, Doppelgänger, Börsen-Beleg oder vom Namen her ein Dollar/Euro-Abbild? */
    fun isExcluded(symbol: String, name: String): Boolean {
        val s = symbol.trim().uppercase()
        val n = name.trim().lowercase()
        if (s.isEmpty()) return true
        if (s in STABLECOINS || s in WRAPPED) return true
        // Heuristik Stablecoins: Kürzel «USD…» / «…USD», Name mit «usd» oder «dollar» irgendwo
        // (auch «USDe», «sUSD», «PayPal USD»), Euro nur als ganzes Wort («Neuron» ist kein Euro)
        if (s.startsWith("USD") || s.endsWith("USD")) return true
        if (n.contains("usd") || n.contains("dollar")) return true
        if (n.split(NON_WORD).any { it == "euro" || it == "eur" }) return true
        // Heuristik Doppelgänger
        if (WRAPPED_NAME_PREFIXES.any { n.startsWith(it) }) return true
        if (n.contains("binance-peg") || n.contains("restaked")) return true
        // Nur einfache Börsen-Kürzel (keine Punkte, Bindestriche, Sonderzeichen …)
        return !s.all { it in 'A'..'Z' || it in '0'..'9' }
    }
}
