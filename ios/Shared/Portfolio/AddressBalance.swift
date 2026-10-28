import Foundation

/// Guthaben öffentlicher Adressen ohne Schlüssel und ohne Konto beim Anbieter — gleich wie `AddressBalance.kt`.
///
/// Bitcoin: Esplora-REST `GET https://{host}/api/address/{adr}` — zuerst mempool.space, sonst
/// blockstream.info (gleiche Antwort). Guthaben = chain_stats (funded − spent) + mempool_stats
/// (funded − spent), in Satoshi; unbestätigte Eingänge zählen also schon mit.
/// Ethereum: JSON-RPC `eth_getBalance(adr, "latest")` an öffentliche Knoten ohne Schlüssel —
/// zuerst PublicNode, sonst LlamaRPC (beide schon für die Netzwerkgebühren im Einsatz). Wei → ETH exakt.
/// ERC-20-Token (`Erc20Tokens`, abschaltbar je Adresse): `eth_call balanceOf` an dieselben Knoten, mit dem ETH-Guthaben
/// in einer Stapel-Anfrage.
/// xpub/ypub/zpub (`Bip32`, `XpubScan`): Adressen auf dem Gerät abgeleitet; jede abgeleitete Adresse einzeln wie oben
/// (Esplora, `chain_stats.tx_count` für die Gap-Regel); Guthaben = Summe.
///
/// Datenschutz: Jede Abfrage nennt dem Anbieter die Adresse zusammen mit der IP des Geräts
/// (`privacyHosts`); nur diese Hosts bekommen Adressen.
/// Abruf höchstens alle `cacheMillis` je Adresse (manuell frühestens nach `minForcedMillis`).
enum AddressBalance {

    struct ParseError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// Guthaben so lange gültig; danach lädt die nächste Portfolio-Aktualisierung neu.
    static let cacheMillis: Int64 = 10 * 60_000

    /// Manuelles Aktualisieren (Ziehen) frühestens so lange nach dem letzten Versuch.
    static let minForcedMillis: Int64 = 60_000

    /// Zeitgrenze je Anfrage (Sekunden).
    static let timeoutSeconds: TimeInterval = 15

    /// Bitcoin-Hosts (Esplora-API) in dieser Reihenfolge.
    static let btcHosts = ["mempool.space", "blockstream.info"]

    /// Ethereum-Knoten (JSON-RPC) in dieser Reihenfolge.
    static let ethRpcs = ["https://ethereum-rpc.publicnode.com", "https://eth.llamarpc.com"]

    /// Alle Hosts, die eine Adresse zu sehen bekommen (Datenschutzerklärung), Reihenfolge der Netze.
    static var privacyHosts: [String] {
        var out: [String] = []
        for host in AddressChain.allCases.flatMap({ ChainBalance.hosts($0) }) where !out.contains(host) { out.append(host) }
        return out
    }

    static func btcUrl(host: String, address: String) -> String { "https://\(host)/api/address/\(address)" }

    /// Anfrage-Text für `eth_getBalance` (Adresse klein; geprüft, also nur Hex).
    static func ethRequest(_ address: String) -> String {
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_getBalance\",\"params\":[\"\(address.lowercased())\",\"latest\"]}"
    }

    /// Satoshi bestätigt und unbestätigt (kann negativ sein: Ausgabe wartet); `txCount` = Transaktionen bestätigt +
    /// unbestätigt (xpub: benutzt, wenn > 0).
    struct BtcStats: Equatable, Sendable {
        var confirmedSats: Int64
        var pendingSats: Int64
        var txCount: Int = 0
        var totalSats: Int64 { max(0, confirmedSats + pendingSats) }
    }

    private static func object(_ body: String) throws -> [String: Any] {
        guard let data = body.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { throw ParseError(message: "kein JSON-Objekt") }
        return o
    }

    /// Esplora-Antwort lesen; wirft bei fehlenden/negativen Feldern oder wenn die Antwort zu einer
    /// anderen Adresse gehört. Plausibilität je Netz (`maxSats`, Litecoin hat 84 Mio. Coins): geprüft wird das Guthaben,
    /// nicht die Summen über alle Zeit (funded_txo_sum einer Börsen-Adresse übersteigt die Geldmenge leicht). Wie Android.
    static func parseBtc(_ body: String, address: String, chain net: AddressChain = .BTC) throws -> BtcStats {
        let json = try object(body)
        if let value = json["address"], !(value is NSNull) {
            guard let text = value as? String, text.lowercased() == address.lowercased() else {
                throw ParseError(message: "Antwort für andere Adresse")
            }
        }
        guard let chain = json["chain_stats"] as? [String: Any] else { throw ParseError(message: "chain_stats fehlt") }
        let limit = maxSats(net)
        let confirmed = try sats(chain, "funded_txo_sum") - sats(chain, "spent_txo_sum")
        if confirmed < 0 { throw ParseError(message: "negatives Guthaben") }
        if confirmed > limit { throw ParseError(message: "Guthaben ungültig") }
        var pending: Int64 = 0
        var txCount = txCountOf(chain)
        if let mempool = json["mempool_stats"] as? [String: Any] {
            pending = try sats(mempool, "funded_txo_sum") - sats(mempool, "spent_txo_sum")
            txCount += txCountOf(mempool)
        }
        if pending > limit || pending < -limit { throw ParseError(message: "Guthaben ungültig") }
        return BtcStats(confirmedSats: confirmed, pendingSats: pending, txCount: txCount)
    }

    /// Mehr als alle je möglichen Coins des Netzes in kleinsten Einheiten ist unplausibel (BTC 21 Mio., LTC 84 Mio. × 10⁸).
    static func maxSats(_ chain: AddressChain) -> Int64 {
        chain == .LTC ? 8_400_000_000_000_000 : 2_100_000_000_000_000
    }

    /// `tx_count` (fehlt oder ungültig = 0: dann zählt nur das Guthaben).
    private static func txCountOf(_ o: [String: Any]) -> Int {
        guard let n = o["tx_count"] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return 0 }
        let d = n.doubleValue
        return d.isFinite && d >= 0 && d <= Double(Int32.max) ? Int(d) : 0
    }

    /// Obergrenze der Summen funded/spent (unter Int64.max, die Differenz läuft nie über).
    private static let maxCumulativeSats = 9.0e18

    /// Ganze, nicht negative Zahl im Int64-Bereich (Summen über alle Zeit; kein Text, kein true/false); sonst Fehler.
    private static func sats(_ o: [String: Any], _ key: String) throws -> Int64 {
        guard let n = o[key] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else {
            throw ParseError(message: "\(key) fehlt")
        }
        let d = n.doubleValue
        guard d.isFinite, d >= 0, d == d.rounded(.down), d <= maxCumulativeSats else {
            throw ParseError(message: "\(key) ungültig")
        }
        return n.int64Value
    }

    /// `eth_getBalance`-Antwort → Wei als Dezimalziffern; wirft bei Fehlerantwort oder unlesbarem Wert.
    static func parseEthWei(_ body: String) throws -> String {
        try parseEthWei(try object(body))
    }

    /// Wie oben, schon gelesen (ein Eintrag eines Stapels, `Erc20Tokens.parseBatch`).
    static func parseEthWei(_ json: [String: Any]) throws -> String {
        if let error = json["error"] as? [String: Any] {
            throw ParseError(message: "RPC: " + (error["message"] as? String ?? ""))
        }
        guard let result = json["result"] as? String else { throw ParseError(message: "result fehlt") }
        guard result.hasPrefix("0x"), result.count >= 3, result.count <= 66,
              let digits = hexToDecimal(String(result.dropFirst(2)))
        else { throw ParseError(message: "result ungültig") }
        return digits
    }

    /// Hex (ohne «0x», höchstens 64 Stellen) → Dezimalziffern ohne führende Nullen; ungültig = nil.
    static func hexToDecimal(_ hex: String) -> String? {
        if hex.isEmpty || hex.count > 64 { return nil }
        var digits = [0] // Basis 10, niedrigste Stelle zuerst
        for c in hex {
            guard c.isASCII, let nibble = c.hexDigitValue else { return nil }
            var carry = nibble
            for i in digits.indices {
                let v = digits[i] * 16 + carry
                digits[i] = v % 10
                carry = v / 10
            }
            while carry > 0 {
                digits.append(carry % 10)
                carry /= 10
            }
        }
        while digits.count > 1 && digits.last == 0 { digits.removeLast() }
        return digits.reversed().map { String($0) }.joined()
    }

    /// Ganze kleinste Einheiten (Ziffern) → Text mit `decimals` Stellen, ohne Nullen am Ende («0.0001»).
    static func unitsText(_ digits: String, decimals: Int) -> String {
        let d = String(digits.drop(while: { $0 == "0" }))
        if d.isEmpty { return "0" }
        let missing: Int = max(0, decimals + 1 - d.count)
        let padded: String = String(repeating: "0", count: missing) + d
        let intPart = String(padded.prefix(padded.count - decimals))
        var frac = String(padded.suffix(decimals))
        while frac.hasSuffix("0") { frac.removeLast() }
        return frac.isEmpty ? intPart : intPart + "." + frac
    }

    static func btcText(_ stats: BtcStats) -> String { unitsText(String(stats.totalSats), decimals: AddressChain.BTC.decimals) }

    static func ethText(_ weiDigits: String) -> String { unitsText(weiDigits, decimals: AddressChain.ETH.decimals) }

    /// Ist diese Adresse wieder dran? Nie versucht oder Uhr zurückgestellt: ja.
    static func isDue(lastAttempt: Int64, now: Int64, force: Bool) -> Bool {
        if lastAttempt <= 0 { return true }
        let elapsed = now - lastAttempt
        if elapsed < 0 { return true }
        return elapsed >= (force ? minForcedMillis : cacheMillis)
    }

    /// Abruf verwerfen, weil der Nutzer währenddessen etwas dazugenommen hat, das der Abruf (Einstellungen des Abzugs)
    /// nicht fragte: «Tokens mitzählen» an oder ein EVM-Netz neu gewählt. Netze wie die Abfrage: nil = alle — aus nil
    /// wird nie etwas neu, nil nach einer Liste ohne alle Netze schon. Weniger (abgewählt, Tokens aus) verwirft nicht,
    /// `WatchedAddressRules.applyFetched` übernimmt nur, was noch zählt. Verworfen bleibt die Adresse fällig. Wie Android.
    static func discardFetched(countTokens: Bool, networks: [String]?,
                               snapshotCountTokens: Bool, snapshotNetworks: [String]?) -> Bool {
        if countTokens && !snapshotCountTokens { return true }
        guard let snapshotNetworks else { return false }
        return (networks ?? ChainAssets.evm.map(\.id)).contains { !snapshotNetworks.contains($0) }
    }

    /// Anbieter für die Herkunftsangabe: «mempool.space», «publicnode.com», «llamarpc.com».
    static func providerName(_ hostOrUrl: String) -> String {
        var host = hostOrUrl.hasPrefix("https://") ? String(hostOrUrl.dropFirst(8)) : hostOrUrl
        if let slash = host.firstIndex(of: "/") { host = String(host[..<slash]) }
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        // IPv4 (eigener Server) ganz, sonst die letzten beiden Teile des Namens
        if host.allSatisfy({ $0 == "." || ("0"..."9").contains($0) }) { return host }
        return parts.count > 2 ? parts.suffix(2).joined(separator: ".") : host
    }

    /// Bestand je Coin aus allen Adressen, die zum Portfolio zählen (`countInPortfolio`), mit geladenem Guthaben (exakt
    /// summiert, gleiche Adresse nur einmal; eine einzelne Bitcoin-Adresse in einem beobachteten xpub zählt über den xpub).
    /// Reihenfolge: Netz-Guthaben in der Reihenfolge von `AddressChain`, dann weitere Guthaben > 0 nach Netz und Liste;
    /// derselbe Coin aus mehreren Quellen wird addiert. Wie Android.
    static func holdings(_ addresses: [WatchedAddress]) -> [(coin: String, amount: String)] {
        let counted = addresses.filter(\.countInPortfolio)
        var order: [String] = []
        var sums: [String: Decimal] = [:]
        func add(_ coin: String, _ value: Decimal) {
            if sums[coin] == nil { order.append(coin) }
            sums[coin] = (sums[coin] ?? 0) + value
        }
        let covered = WatchedAddressRules.coveredByXpub(counted)
        for chain in AddressChain.allCases {
            var seen: [String] = []
            var sum: Decimal?
            for a in counted where a.chain == chain {
                if chain == .BTC && !a.isXpub && covered.contains(a.address) { continue }
                guard let value = PortfolioDecimal.parse(a.lastBalance), value >= 0 else { continue }
                if seen.contains(where: { WatchedAddressRules.sameAddress(chain, $0, a.address) }) { continue }
                seen.append(a.address)
                sum = (sum ?? 0) + value
            }
            if let sum { add(chain.coin, sum) }
        }
        // Weitere Guthaben: nach Netz der Adresse, dann Reihenfolge der Liste
        var entries: [(chain: Int, order: Int, coin: String, value: Decimal)] = []
        for (ci, chain) in AddressChain.allCases.enumerated() where ChainAssets.hasTokens(chain) {
            var seen: [String] = []
            for a in counted where a.chain == chain {
                if seen.contains(where: { WatchedAddressRules.sameAddress(chain, $0, a.address) }) { continue }
                seen.append(a.address)
                for h in a.tokenHoldings() {
                    guard let value = PortfolioDecimal.parse(h.amount) else { continue }
                    entries.append((ci, ChainAssets.resolve(chain, h.key)?.order ?? Int.max, h.coin, value))
                }
            }
        }
        // Stabil sortiert wie Android (`sortedWith`)
        let sorted = entries.enumerated().sorted {
            ($0.element.chain, $0.element.order, $0.offset) < ($1.element.chain, $1.element.order, $1.offset)
        }.map(\.element)
        for e in sorted { add(e.coin, e.value) }
        return order.map { (coin: $0, amount: PortfolioDecimal.text(sums[$0] ?? 0)) }
    }

    /// Coins aus `holdings`, deren Summe gebrückte Stablecoins enthält (USDC.e, USDbC → USDC, USDT.e → USDT;
    /// `ChainAssets.isBridgedStable`) → Symbole dieser Varianten (Reihenfolge wie `holdings`, je Symbol einmal). Eine
    /// Brücke kann abkoppeln: die Menge gilt dann nur ungefähr — die Zeile markiert sie mit «≈» wie WBTC
    /// (`ChainAssets.priceViaBase`) statt sie als exakten Bestand des Basis-Stablecoins auszugeben. Wie Android.
    static func bridgedStables(_ addresses: [WatchedAddress]) -> [String: [String]] {
        var out: [String: [String]] = [:]
        for chain in AddressChain.allCases where ChainAssets.hasTokens(chain) {
            var seen: [String] = []
            for a in addresses where a.countInPortfolio && a.chain == chain {
                if seen.contains(where: { WatchedAddressRules.sameAddress(chain, $0, a.address) }) { continue }
                seen.append(a.address)
                for h in a.tokenHoldings() where ChainAssets.isBridgedStable(label: h.label, coin: h.coin) {
                    var list = out[h.coin] ?? []
                    if !list.contains(h.label) { list.append(h.label) }
                    out[h.coin] = list
                }
            }
        }
        return out
    }

    // MARK: Adress-Bestände im Portfolio-Rechner
    //
    // Ehrliche Regel (wie `AddressBalance.kt`): Das Guthaben beobachteter Adressen ist ein EIGENER Teil
    // der Position, kein Posten der Buchhaltung. `withAddresses` hängt je Coin eine Einzahlung ohne Kaufpreis mit
    // negativer Id an; `split` trennt sie wieder ab. Gesamtwert, Positionen, Widget und Portfolio-Alarme zählen das
    // Guthaben mit (`CoinPosition.addressHoldingsExact`); Einstand, Ø, «Seit Kauf», realisiert und Steuer nur aus den
    // eigenen Transaktionen. Heute / 7 T / 1 J und Wertverlauf: Adress-Einträge zählen NICHT rückwirkend — nur eine
    // beobachtete Reihe (`AddressBalanceHistory`), ab ihrer ersten Beobachtung, Änderungen zum Marktkurs.
    // Die echten Transaktionen bleiben unverändert (Liste, Export, Sicherung kennen diese Einträge nicht).
    // Eröffnungsposten (`AddressCostLots`) belegen einen Teil des Guthabens mit Einstand — sie rechnen wie Käufe
    // (Wallet = Adresse), nur der Rest bleibt Adress-Teil ohne Einstand (`isAddressPart`).

    private static let dayMillis: Int64 = 86_400_000

    /// Id der Adress-Einzahlung je Netz (negativ, nie eine gespeicherte Transaktion).
    static func tradeId(_ chain: AddressChain) -> Int64 {
        -Int64((AddressChain.allCases.firstIndex(of: chain) ?? 0) + 1)
    }

    /// Id der Adress-Einzahlung eines Tokens: −101, −102 … nach `Erc20Tokens.list`.
    static func tokenTradeId(_ symbol: String) -> Int64 { -(101 + Int64(Erc20Tokens.order(symbol))) }

    /// Eindeutige negative Ids je Coin: Netz-Coins `tradeId`, Coins der Ethereum-Liste `tokenTradeId`, alle übrigen
    /// −1001, −1002 … in der Reihenfolge von `coins`. Wie Android.
    static func tradeIds(_ coins: [String]) -> [String: Int64] {
        var out: [String: Int64] = [:]
        var next: Int64 = -1001
        for coin in coins where out[coin] == nil {
            if let chain = AddressChain.allCases.first(where: { $0.coin == coin }) {
                out[coin] = tradeId(chain)
            } else if Erc20Tokens.list.contains(where: { $0.symbol == coin }) {
                out[coin] = tokenTradeId(coin)
            } else {
                out[coin] = next
                next -= 1
            }
        }
        return out
    }

    /// Adress-Eintrag statt echter Transaktion (Adress-Einzahlung oder Eröffnungsposten, `AddressCostLots`)? Zeiträume,
    /// Verlauf, Stichtag und Listen lassen beide weg.
    static func isAddressTrade(_ id: Int64) -> Bool { id < 0 }

    /// Adress-Teil OHNE Einstand (Adress-Einzahlung, kein Eröffnungsposten) — nur er steht neben der Buchhaltung; die
    /// Posten rechnen Einstand und Steuer wie Käufe. Wie Android.
    static func isAddressPart(_ id: Int64) -> Bool { isAddressTrade(id) && !AddressCostLots.isLotTrade(id) }

    /// Echte Transaktionen samt Eröffnungsposten und Adress-Guthaben ohne Einstand je Coin (Summe der Adress-Einzahlungen,
    /// `withAddresses`), Coin in Grossschreibung. Wie Android.
    static func split(_ trades: [PortfolioTx]) -> (real: [PortfolioTx], address: [String: Decimal]) {
        guard trades.contains(where: { isAddressPart($0.id) }) else { return (trades, [:]) }
        var real: [PortfolioTx] = []
        real.reserveCapacity(trades.count)
        var address: [String: Decimal] = [:]
        for t in trades {
            if !isAddressPart(t.id) {
                real.append(t)
            } else if t.type == .DEPOSIT && t.amountExact > 0 {
                let coin = PortfolioCalculator.normalizeCoin(t.coin)
                address[coin] = (address[coin] ?? 0) + t.amountExact
            }
        }
        return (real, address)
    }

    /// Datum der Adress-Einzahlung: Beginn des UTC-Tags `todayUtcDay` − 366, oder die erste echte Transaktion, wenn
    /// sie älter ist. Nur noch Sortierung — Zeiträume und Verlauf lassen Adress-Einzahlungen weg.
    static func heldSince(_ trades: [PortfolioTx], todayUtcDay: Int64) -> Int64 {
        let yearAgo = (todayUtcDay - Int64(PortfolioPeriodChange.yearDays) - 1) * dayMillis
        let first = trades.filter { $0.amountExact > 0 && !isAddressTrade($0.id) }.map(\.time).min()
        if let first, first < yearAgo { return first }
        return yearAgo
    }

    /// Je Coin mit geladenem Guthaben > 0 eine Einzahlung ohne Kaufpreis (`holdings`) für den Teil OHNE Eröffnungsposten,
    /// Reihenfolge BTC, ETH, Token; danach die Eröffnungsposten als Käufe (`AddressCostLots.trades`). Erfasste Bewegungen
    /// der Adress-Wallets in `realTrades` stecken schon im Guthaben (`AddressCostLots.accounted`) — nie doppelt.
    static func trades(_ addresses: [WatchedAddress], realTrades: [PortfolioTx], todayUtcDay: Int64) -> [PortfolioTx] {
        let held = holdings(addresses)
        guard !held.isEmpty else { return [] }
        let time = heldSince(realTrades, todayUtcDay: todayUtcDay)
        let ids = tradeIds(held.map(\.coin))
        let accounted = AddressCostLots.accounted(addresses, real: realTrades)
        let parts: [PortfolioTx] = held.compactMap { item in
            guard let total = PortfolioDecimal.parse(item.amount), let id = ids[item.coin] else { return nil }
            let amount: Decimal = total - (accounted[item.coin] ?? 0)
            guard amount > 0 else { return nil }
            return PortfolioTx(id: id, coin: item.coin, type: .DEPOSIT, amountExact: amount,
                               priceExact: nil, time: time)
        }
        return parts + AddressCostLots.trades(addresses, real: realTrades)
    }

    /// Echte Transaktionen + Adress-Einzahlungen — Eingabe für Rechner, Verlauf, Zeiträume, Widget, Alarme (der
    /// Rechner zeigt sie als Adress-Teil, Zeiträume/Verlauf lassen sie weg, siehe oben). Schon zusammengeführt (Einträge mit negativer Id): unverändert — nie doppelt.
    static func withAddresses(_ realTrades: [PortfolioTx], addresses: [WatchedAddress], todayUtcDay: Int64) -> [PortfolioTx] {
        guard !addresses.isEmpty, !realTrades.contains(where: { isAddressTrade($0.id) }) else { return realTrades }
        return realTrades + trades(addresses, realTrades: realTrades, todayUtcDay: todayUtcDay)
    }

    /// Mit dem heutigen UTC-Tag.
    static func withAddresses(_ realTrades: [PortfolioTx], addresses: [WatchedAddress]) -> [PortfolioTx] {
        guard !addresses.isEmpty else { return realTrades }
        return withAddresses(realTrades, addresses: addresses,
                             todayUtcDay: Int64(PortfolioHistory.epochDay(utcMillis: EpochTime.nowMillis)))
    }

    /// Guthaben aller Adressen für `coin` OHNE Eröffnungsposten (Detailansicht «Aus Adresse · ohne Kaufpreis»), mit den
    /// echten Transaktionen `realTrades` wie im Rechner; nil = keins geladen.
    static func amount(for coin: String, addresses: [WatchedAddress], realTrades: [PortfolioTx] = []) -> Decimal? {
        let key = PortfolioCalculator.normalizeCoin(coin)
        let accounted = AddressCostLots.accounted(addresses, real: realTrades)[key] ?? 0
        return holdings(addresses).first(where: { $0.coin == key }).flatMap { PortfolioDecimal.parse($0.amount) }
            .map { max(0, $0 - accounted) }
    }
}
