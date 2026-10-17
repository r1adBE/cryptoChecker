import Foundation

/// Stablecoins, Doppelgänger (verpackt, gestakt, gebrückt) und Börsen-Belege — für die
/// Start-Coins (`StarterCoins`, nur App) und die Marktbreite in «Was gerade auffällt»
/// (`CryptoPulseSource`, auch im Widget). Wie Android `StarterCoins.isExcluded`.
enum CoinExclusion {

    /// Stablecoins (Symbol in Grossbuchstaben).
    static let stablecoins: Set<String> = [
        "USDT", "USDC", "DAI", "USDE", "USDS", "FDUSD", "TUSD", "PYUSD", "USD1", "BUSD", "USDD",
        "FRAX", "GUSD", "USDP", "EURC", "EURT", "RLUSD", "USDG", "USD0", "USDTB", "BFUSD", "SUSDS",
        "SUSDE", "USDT0", "USDX", "USDB", "USDY", "USDF", "LUSD", "CRVUSD", "GHO", "DOLA", "FXUSD",
        "EURS", "EURE", "XAUT", "PAXG", "USTC", "USR", "DEUSD", "SRUSD", "USDO", "AUSD", "USYC",
        "BUIDL", "USDM", "USDL", "MIM", "ALUSD", "SUSD", "CUSD", "ZUSD", "HUSD", "USDK", "USDJ",
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

    /// Kein Startpaar: Stablecoin, Doppelgänger oder vom Namen her ein Dollar/Euro-Abbild.
    static func isExcluded(symbol: String, name: String) -> Bool {
        let s = symbol.trimmingCharacters(in: .whitespaces).uppercased()
        let n = name.trimmingCharacters(in: .whitespaces).lowercased()
        if s.isEmpty { return true }
        if stablecoins.contains(s) || wrapped.contains(s) { return true }
        // Heuristik Stablecoins: «USD…», «…USD», Name mit USD/Dollar/Euro
        if s.hasPrefix("USD") || s.hasSuffix("USD") { return true }
        // (je Wort, damit z. B. «Neuron» nicht als «Euro» gilt; «USDe», «sUSD» schon)
        let words = n.split { !($0.isLetter || $0.isNumber) }
        if words.contains(where: { $0.hasPrefix("usd") || $0.hasSuffix("usd") || $0.contains("dollar")
            || $0 == "euro" || $0 == "eur" }) { return true }
        // Heuristik Doppelgänger
        for prefix in ["wrapped ", "staked ", "bridged ", "liquid staked "] where n.hasPrefix(prefix) { return true }
        if n.contains("binance-peg") || n.contains("restaked") { return true }
        // Nur einfache Börsen-Symbole (keine Punkte, Bindestriche …)
        return !s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }
}
