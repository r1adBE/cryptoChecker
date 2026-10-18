import Foundation

/// Stablecoins, Doppelgänger (verpackt, gestakt, gebrückt) und Börsen-Belege — die eine Regel für die
/// Start-Coins (`StarterCoins`, nur App), die Marktbreite («Top 30») in «Was gerade auffällt»
/// (`CryptoPulseSource`, auch im Widget) und «Gewinner und Verlierer 24 h» (`MarketMovers`).
/// Gleich wie Android `CoinExclusion.kt`; gemeinsame Testfälle in `Tests/Parity/stable_exclusion.json`.
///
/// Listen und Namensregeln sind die Vereinigung beider Plattformen (lieber einmal zu viel ausschliessen als
/// einen Stablecoin in der Marktbreite mitzählen). Alle Vergleiche ohne Rücksicht auf Gross-/Kleinschreibung.
enum CoinExclusion {

    /// Stablecoins (Symbol in Grossbuchstaben).
    static let stablecoins: Set<String> = [
        "USDT", "USDC", "DAI", "USDE", "USDS", "FDUSD", "TUSD", "PYUSD", "USD1", "BUSD", "USDD",
        "FRAX", "GUSD", "USDP", "EURC", "EURT", "RLUSD", "USDG", "USD0", "USDTB", "BFUSD", "SUSDS",
        "SUSDE", "USDT0", "USDX", "USDB", "USDY", "USDF", "LUSD", "CRVUSD", "GHO", "DOLA", "FXUSD",
        "EURS", "EURE", "XAUT", "PAXG", "USTC", "USR", "DEUSD", "SRUSD", "USDO", "AUSD", "USYC",
        "BUIDL", "USDM", "USDL", "MIM", "ALUSD", "SUSD", "CUSD", "ZUSD", "HUSD", "USDK", "USDJ",
        "USDAI", "OUSG", "XSGD",
    ]

    /// Verpackte, gestakte und gebrückte Doppelgänger sowie Börsen-Belege.
    static let wrapped: Set<String> = [
        "WBTC", "WETH", "STETH", "WSTETH", "WEETH", "EETH", "CBBTC", "CBETH", "RETH", "METH",
        "EZETH", "RSETH", "LSETH", "WBETH", "BETH", "BNSOL", "JITOSOL", "MSOL", "JUPSOL", "STSOL",
        "BBSOL", "WBNB", "BTCB", "TBTC", "LBTC", "SOLVBTC", "CLBTC", "FBTC", "UNIBTC", "WTRX",
        "WAVAX", "SAVAX", "WMATIC", "WPOL", "WSOL", "WHYPE", "KHYPE", "STHYPE", "OSETH", "SFRXETH",
        "FRXETH", "PUFETH", "SWETH", "ANKRETH", "TETH", "WEETHS", "RSWETH", "EBTC", "PUMPBTC",
        "BSC-USD", "BGBTC", "OKBTC", "BBTC", "BTC.B", "SOLVBTC.BBN", "WEETH.BASE",
    ]

    /// Namen, die so beginnen, sind Doppelgänger (kleingeschrieben verglichen).
    private static let wrappedNamePrefixes = ["wrapped ", "staked ", "bridged ", "liquid staked "]

    /// Stablecoin, Doppelgänger, Börsen-Beleg oder vom Namen her ein Dollar/Euro-Abbild?
    static func isExcluded(symbol: String, name: String) -> Bool {
        let s = symbol.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if s.isEmpty { return true }
        if stablecoins.contains(s) || wrapped.contains(s) { return true }
        // Heuristik Stablecoins: Kürzel «USD…» / «…USD», Name mit «usd» oder «dollar» irgendwo
        // (auch «USDe», «sUSD», «PayPal USD»), Euro nur als ganzes Wort («Neuron» ist kein Euro)
        if s.hasPrefix("USD") || s.hasSuffix("USD") { return true }
        if n.contains("usd") || n.contains("dollar") { return true }
        let words = n.split { !($0.isLetter || $0.isNumber) }
        if words.contains(where: { $0 == "euro" || $0 == "eur" }) { return true }
        // Heuristik Doppelgänger
        if wrappedNamePrefixes.contains(where: { n.hasPrefix($0) }) { return true }
        if n.contains("binance-peg") || n.contains("restaked") { return true }
        // Nur einfache Börsen-Kürzel (keine Punkte, Bindestriche, Sonderzeichen …)
        return !s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }
}
