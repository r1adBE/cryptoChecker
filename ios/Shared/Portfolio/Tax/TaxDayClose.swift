import Foundation

/// Welcher Tagesschluss gilt, wenn der Steuer-Export einen Marktkurs schätzen muss: IMMER der Schluss des UTC-Tags der
/// Transaktion — für Gebühr-Zeilen, Verschenkt/bezahlt und Tausch ohne Wert, Belohnungen ohne Wert, Netzwerkgebühren als
/// Veräusserung und den FR-Gesamtwert. Wie `TaxDayClose.kt` (Android, dort die Begründung): Karte, Wertverlauf, Einkünfte
/// und Gebühr-Zeilen rechneten schon so, die Tageskerzen der Quellen schliessen um 24:00 UTC; Netzwerkgebühren
/// und der FR-Gesamtwert nehmen darum nicht den Vortag.
enum TaxDayClose {

    /// UTC-Tag (epochDay) der Transaktion zur Zeit `time` (ms) — dessen Schluss gilt.
    static func day(_ time: Int64) -> Int { LocalDay(epochMillisUTC: time).epochDay }

    /// Coin → Tage (epochDay) für Netzwerkgebühren in Krypto als Veräusserung bis `end` (ms): Gebühr im Coin bzw. im
    /// dritten Coin ohne erfassten Gebühr-Preis einer Umbuchung, Auszahlung oder Schenkung (ohne Adress-Einträge, ohne
    /// Stablecoins). Wie `networkFeeNeeds` (Android).
    static func networkFeeNeeds(_ transactions: [PortfolioTx], end: Int64) -> [String: Set<Int>] {
        var wanted: [String: Set<Int>] = [:]
        for t in transactions where (t.type == .TRANSFER || t.type.isWithdrawalLike) && t.time <= end
            && !AddressBalance.isAddressTrade(t.id) {
            let d = day(t.time)
            let coin = PortfolioCalculator.normalizeCoin(t.coin)
            if t.coinFeeExact > 0 && t.validPriceExact == nil && PortfolioStables.needsQuote(coin) {
                wanted[coin, default: []].insert(d)
            }
            if let third = t.thirdFeeCoin, t.thirdFeePrice == nil, PortfolioStables.needsQuote(third) {
                wanted[third, default: []].insert(d)
            }
        }
        return wanted
    }

    /// Schlüssel eines Schlusses in `lookup`: Coin und UTC-Tag.
    static func key(_ coin: String, _ epochDay: Int) -> String { "\(coin)|\(epochDay)" }

    /// Abfrage (Coin, Zeit) → Schluss des UTC-Tags aus `closes` (Schlüssel `key`); fehlt = nil.
    static func lookup(_ closes: [String: Decimal]) -> (String, Int64) -> Decimal? {
        { coin, time in closes[key(coin, day(time))] }
    }
}
