import Foundation

// CSV-Import (`CsvImportParser`): Format aus der Kopfzeile erkennen und Arten aus dem Text einer Zeile einordnen — wie `CsvImportFormatDetect.kt` (Android).

extension CsvImportParser {

    static func detect(_ h: Header) -> CsvImportFormat {
        let names = Set(h.names)
        // Kraken «Ledgers»-Export: txid, refid, time, type, subtype, aclass, asset, (wallet,) amount, fee, balance
        let krakenCore: Set<String> = ["refid", "txid", "asset", "amount"]
        if krakenCore.isSubset(of: names) && (names.contains("aclass") || names.contains("balance")) { return .KRAKEN }
        // Börsen-Exporte mit eigenem Aufbau vor der allgemeinen Erkennung (Coinbase hätte auch Datum/Art/Coin)
        if CsvImportBinance.matches(names) { return .BINANCE }
        if CsvImportCoinbase.matches(names) { return .COINBASE }
        if CsvImportBitpanda.matches(names) { return .BITPANDA }
        let hasAmount = h.has(.amount) || h.has(.amountSats)
        guard h.has(.date), h.has(.kind), hasAmount else { return .UNKNOWN }
        let relai = !names.isDisjoint(with: ["btcamount", "btcprice", "satsamount", "amountsats"])
            || (names.contains("operationid") && names.contains("counterparty"))
        if relai { return .RELAI }
        let order = !names.isDisjoint(with: ["order", "auftrag", "ordre"])
        if h.has(.isin) && order && h.has(.price) { return .SWISSQUOTE }
        if h.has(.coin) { return .GENERIC }
        return .UNKNOWN
    }

    /// Wörter einer Art — wie Android `words`: wie `normalize`, aber an allem ausser a–z/0–9 getrennt.
    static func words(_ text: String) -> [String] {
        var joined = ""
        for ch in text {
            let n = normalize(String(ch))
            joined += n.isEmpty ? " " : n
        }
        return joined.split(separator: " ").map(String.init)
    }

    /// Muster gegen `words` — wie Android `matches`: «wort» genau, «wort*» Anfang, «*wort» Ende, «*wort*» enthalten,
    /// «zwei wörter» nacheinander oder zusammengeschrieben.
    private static func matches(_ words: [String], _ pattern: String) -> Bool {
        if pattern.contains(" ") {
            let parts = pattern.split(separator: " ").map(String.init)
            let glued = parts.joined()
            if words.contains(glued) { return true }
            guard words.count >= parts.count else { return false }
            for i in 0...(words.count - parts.count) where parts.indices.allSatisfy({ words[i + $0] == parts[$0] }) {
                return true
            }
            return false
        }
        let head = pattern.hasPrefix("*")
        let tail = pattern.hasSuffix("*")
        let core = pattern.trimmingCharacters(in: CharacterSet(charactersIn: "*"))
        return words.contains { w in
            if head && tail { return w.contains(core) }
            if tail { return w.hasPrefix(core) }
            if head { return w.hasSuffix(core) }
            return w == core
        }
    }

    private static let unstakeWords = ["unstak*", "redeem*", "redemption*", "entstak*", "stake", "staked"]
    private static let stakingWords = ["staking*", "stake", "staked"]
    private static let moveWords = ["withdraw*", "*auszahlung*", "retrait*", "deposit*", "*einzahlung*", "depot", "transfer*",
                                    "send*", "receive*", "unlock*", "lock*"]
    private static let swapWords = ["*tausch*", "swap*", "convert*", "conversion*", "konvert*", "echange*"]
    private static let sellWords = ["sell*", "sold", "*verkauf*", "vente*", "vendu", "vendita"]
    private static let buyWords = ["buy*", "bought", "*kauf*", "achat*", "purchase*", "savingsplan*", "savings plan", "sparplan*",
                                   "dca", "recurring*", "acquisto"]
    private static let rewardWords = ["reward*", "*belohnung*", "praemie*", "bonus*", "referral*", "cashback*", "airdrop*",
                                      "interest*", "interet*", "recompense*"]
    private static let feeWords = ["fee", "fees", "*gebuehr*", "frais"]
    private static let withdrawalWords = ["withdraw*", "send*", "sent", "*auszahlung*", "transfer out", "retrait*", "outgoing",
                                          "*ausgang*", "envoi*"]
    private static let depositWords = ["deposit*", "receive*", "*einzahlung*", "transfer in", "depot", "incoming", "*eingang*",
                                       "reception*"]

    /// Art aus dem Text der Datei — wie Android `classify` (Wörter statt Teiltext, feste Reihenfolge): Unstaking/Redeem/
    /// Staking-Auszahlung → `.TRANSFER` (ohne Belohnungswort), dann Tausch, Verkauf, Kauf, Belohnung, Gebühr, Auszahlung,
    /// Einzahlung; nil = nicht unterstützt (Dividende, Forex …).
    static func classify(_ text: String) -> CsvImportKind? {
        let w = words(text)
        if w.isEmpty { return nil }
        func any(_ patterns: [String]) -> Bool { patterns.contains { matches(w, $0) } }
        let reward = any(rewardWords)
        if !reward && (any(unstakeWords) || (any(stakingWords) && any(moveWords))) { return .TRANSFER }
        if any(swapWords) { return .SWAP }
        if any(sellWords) { return .SELL }
        if any(buyWords) { return .BUY }
        if reward || any(stakingWords) { return .REWARD }
        if any(feeWords) { return .FEE }
        if any(withdrawalWords) { return .WITHDRAWAL }
        if any(depositWords) { return .DEPOSIT }
        return nil
    }
}
