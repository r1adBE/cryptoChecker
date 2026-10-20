import Foundation

/// Netzwerkgebühren (#167): EVM-Netze über öffentliche JSON-RPC-Knoten
/// (eth_feeHistory, sonst eth_gasPrice), Bitcoin über mempool.space.
/// Alles ohne API-Schlüssel — wie `GasFees.kt` / `GasDataSource.kt`.
enum GasNetwork: String, CaseIterable, Sendable, Codable {
    case ethereum, base, arbitrum, polygon, bnb

    var title: String {
        switch self {
        case .ethereum: "Ethereum"
        case .base: "Base"
        case .arbitrum: "Arbitrum"
        case .polygon: "Polygon"
        case .bnb: "BNB Chain"
        }
    }

    /// Coin, in dem die Gebühr bezahlt wird (für den Preis in USD).
    var coin: String {
        switch self {
        case .ethereum, .base, .arbitrum: "ETH"
        case .polygon: "POL"
        case .bnb: "BNB"
        }
    }

    /// Öffentliche RPC-Knoten der Reihe nach; der erste, der antwortet, gilt.
    var rpcs: [String] {
        switch self {
        case .ethereum: ["https://ethereum-rpc.publicnode.com", "https://eth.llamarpc.com", "https://cloudflare-eth.com"]
        case .base: ["https://base-rpc.publicnode.com", "https://mainnet.base.org"]
        case .arbitrum: ["https://arbitrum-one-rpc.publicnode.com", "https://arb1.arbitrum.io/rpc"]
        case .polygon: ["https://polygon-bor-rpc.publicnode.com", "https://polygon-rpc.com"]
        case .bnb: ["https://bsc-rpc.publicnode.com", "https://bsc-dataseed.bnbchain.org"]
        }
    }
}

/// Gebühr eines EVM-Netzes in gwei; `transferUsd` = einfache Überweisung (21 000 Gas).
struct EvmGas: Sendable, Equatable, Codable {
    let network: GasNetwork
    let slowGwei: Double
    let normalGwei: Double
    let fastGwei: Double
    let transferUsd: Double?
}

/// Bitcoin-Gebühren in sat/vB (mempool.space-Empfehlungen).
struct BtcFees: Sendable, Equatable, Codable {
    let fast: Double
    let normal: Double
    let slow: Double
    /// Einfache Überweisung (~140 vB) zur normalen Gebühr.
    let transferUsd: Double?
}

struct GasReport: Sendable, Equatable, Codable {
    let evm: [EvmGas]
    let btc: BtcFees?
    let time: Int64
}

enum GasFees {
    static let transferGas = 21_000.0
    static let btcTransferVbytes = 140.0
    static let feeHistoryBlocks = 20
    static let percentiles = [10, 50, 90]

    static var feeHistoryRequest: String {
        #"{"jsonrpc":"2.0","id":1,"method":"eth_feeHistory","params":["0x\#(String(feeHistoryBlocks, radix: 16))","latest",[10,50,90]]}"#
    }

    static let gasPriceRequest = #"{"jsonrpc":"2.0","id":1,"method":"eth_gasPrice","params":[]}"#

    /// eth_feeHistory → (langsam, normal, schnell) in gwei: Grundgebühr des
    /// nächsten Blocks plus Median der Trinkgelder je Perzentil.
    static func parseFeeHistory(_ response: String) throws -> (Double, Double, Double) {
        let json = try JObject(string: response)
        if let error = json.optObject("error") { throw JSONError(message: error.optString("message")) }
        let result = try json.object("result")
        let baseFees = try result.array("baseFeePerGas")
        guard baseFees.count > 0 else { throw JSONError(message: "Keine Grundgebühr") }
        let nextBase = try hexToGwei(baseFees.string(baseFees.count - 1))
        var tips = [Double](repeating: 0, count: percentiles.count)
        if let rewards = result.optArray("reward"), rewards.count > 0 {
            for k in percentiles.indices {
                let values = rewards.arrays.compactMap { row -> Double? in
                    let hex = row.optString(k)
                    // `try` darf nicht rechts vom Ternär-Operator stehen
                    guard hex.hasPrefix("0x") else { return nil }
                    return try? hexToGwei(hex)
                }.sorted()
                tips[k] = values.isEmpty ? 0 : values[values.count / 2]
            }
        }
        // Schnell nie unter normal, normal nie unter langsam
        let slow = nextBase + tips[0]
        let normal = max(slow, nextBase + tips[1])
        let fast = max(normal, nextBase + tips[2])
        return (slow, normal, fast)
    }

    /// eth_gasPrice → gwei.
    static func parseGasPrice(_ response: String) throws -> Double {
        let json = try JObject(string: response)
        if let error = json.optObject("error") { throw JSONError(message: error.optString("message")) }
        return try hexToGwei(json.string("result"))
    }

    /// mempool.space /api/v1/fees/recommended → (schnell, normal, langsam).
    static func parseMempool(_ response: String) throws -> (Double, Double, Double) {
        let json = try JObject(string: response)
        let fast = try json.double("fastestFee")
        let normal = json.optDouble("halfHourFee", fast)
        let slow = json.optDouble("hourFee", normal)
        return (fast, normal, slow)
    }

    /// Binance-Preisliste (ticker/price?symbols=…) → Coin → USD.
    static func parseBinancePrices(_ response: String) throws -> [String: Double] {
        var prices: [String: Double] = [:]
        for item in try JArray(string: response).objects {
            let symbol = item.optString("symbol")
            let price = item.optDouble("price")
            if symbol.hasSuffix("USDT"), !price.isNaN, price > 0 { prices[String(symbol.dropLast(4))] = price }
        }
        return prices
    }

    /// Coinbase exchange-rates?currency=USD: 1 USD = x Coin → Preis = 1/x.
    static func parseCoinbaseRates(_ response: String, coins: [String]) throws -> [String: Double] {
        let rates = try JObject(string: response).object("data").object("rates")
        var prices: [String: Double] = [:]
        for coin in coins {
            let rate = rates.optDouble(coin)
            if !rate.isNaN, rate > 0 { prices[coin] = 1 / rate }
        }
        return prices
    }

    static func evmTransferUsd(_ gwei: Double, _ coinUsd: Double?) -> Double? {
        coinUsd.map { gwei * 1e-9 * transferGas * $0 }
    }

    static func btcTransferUsd(_ satPerVb: Double, _ btcUsd: Double?) -> Double? {
        btcUsd.map { satPerVb * btcTransferVbytes * 1e-8 * $0 }
    }

    /// Wei als Hex-String → gwei. Gross genug für jede reale Gebühr.
    static func hexToGwei(_ hex: String) throws -> Double {
        var clean = hex.lowercased()
        if clean.hasPrefix("0x") { clean.removeFirst(2) }
        if clean.isEmpty { return 0 }
        // Bis 16 Hex-Stellen passen in UInt64; längere Werte über Double.
        if clean.count <= 16, let v = UInt64(clean, radix: 16) { return Double(v) / 1e9 }
        var value = 0.0
        for ch in clean {
            guard let d = ch.hexDigitValue else { throw JSONError(message: "Kein Hex: \(hex)") }
            value = value * 16 + Double(d)
        }
        return value / 1e9
    }

    /// «0.012», «1.4», «23» — so kurz wie möglich, aber nie «0». In den Ziffern von `locale`
    /// (App-Sprache; Arabisch «١٫٤»), denn alle Aufrufer zeigen den Text an.
    static func formatGwei(_ gwei: Double, locale: Locale = LocaleNumbers.appLocale) -> String {
        if gwei <= 0 { return LocaleNumbers.integer(0, locale: locale) }
        if gwei < 0.001 { return "<" + LocaleNumbers.decimal(0.001, maxDecimals: 3, locale: locale) }
        if gwei < 1 { return LocaleNumbers.decimal(gwei, maxDecimals: 3, minDecimals: 0, locale: locale) }
        if gwei < 10 { return LocaleNumbers.decimal(gwei, maxDecimals: 1, minDecimals: 0, locale: locale) }
        return LocaleNumbers.decimal(gwei, maxDecimals: 0, locale: locale)
    }

    /// Kosten in USD: «$0.42», «<$0.01» — in den Ziffern von `locale`.
    static func formatUsd(_ usd: Double, locale: Locale = LocaleNumbers.appLocale) -> String {
        if usd < 0.01 { return "<$" + LocaleNumbers.decimal(0.01, maxDecimals: 2, locale: locale) }
        if usd < 100 { return "$" + LocaleNumbers.decimal(usd, maxDecimals: 2, locale: locale) }
        return "$" + LocaleNumbers.decimal(usd, maxDecimals: 0, locale: locale)
    }
}

/// Lädt die Gebühren aller Netze; Ergebnis eine Minute zwischengespeichert,
/// damit Markt-Tab und Gas-Alarm nicht doppelt fragen.
actor GasDataSource {
    static let shared = GasDataSource()
    private var cached: Sourced<GasReport>?

    func fetch(maxAge: Int64 = 60_000) async throws -> GasReport {
        try await fetchSourced(maxAge: maxAge).value
    }

    /// Wie `fetch`; `provider` = Knoten, der Ethereum geliefert hat (z. B. «publicnode.com»),
    /// und «mempool.space» für Bitcoin — je nachdem, was im Bericht steht.
    func fetchSourced(maxAge: Int64 = 60_000) async throws -> Sourced<GasReport> {
        if let cached, TimeUtils.nowMillis - cached.value.time < maxAge { return cached }
        let sourced = await load()
        let report = sourced.value
        if report.evm.isEmpty && report.btc == nil { throw JSONError(message: "Keine Gebührendaten") }
        cached = sourced
        return sourced
    }

    private func load() async -> Sourced<GasReport> {
        async let prices = Self.prices()
        async let btc = Self.mempoolOrNil()
        let (evm, ethNode) = await withTaskGroup(of: (GasNetwork, (Double, Double, Double), String)?.self,
                                                   returning: ([GasNetwork: (Double, Double, Double)], String?).self) { group in
            for network in GasNetwork.allCases {
                group.addTask { (try? await Self.evmGas(network)).map { (network, $0.fees, $0.rpc) } }
            }
            var out: [GasNetwork: (Double, Double, Double)] = [:]
            var node: String?
            for await item in group {
                guard let item else { continue }
                out[item.0] = item.1
                if item.0 == .ethereum { node = item.2 }
            }
            return (out, node)
        }
        let p = await prices
        let list = GasNetwork.allCases.compactMap { network -> EvmGas? in
            guard let g = evm[network] else { return nil }
            return EvmGas(network: network, slowGwei: g.0, normalGwei: g.1, fastGwei: g.2,
                          transferUsd: GasFees.evmTransferUsd(g.1, p[network.coin]))
        }
        let btcRaw = await btc
        let btcFees = btcRaw.map { fees in
            BtcFees(fast: fees.0, normal: fees.1, slow: fees.2, transferUsd: GasFees.btcTransferUsd(fees.1, p["BTC"]))
        }
        // Herkunft wie in der Zeile gezeigt: der Ethereum-Knoten, dazu mempool.space für Bitcoin
        let provider = DataFreshness.providers(ethNode.map { DataFreshness.siteName($0) },
                                               btcFees == nil ? nil : DataFreshness.mempool)
        return Sourced(value: GasReport(evm: list, btc: btcFees, time: TimeUtils.nowMillis), provider: provider)
    }

    private static func jsonPost(_ body: String) -> PostRequestInfo {
        PostRequestInfo(body: body, headers: ["Content-Type": "application/json"])
    }

    /// (langsam, normal, schnell) in gwei und der Knoten, der geantwortet hat; versucht die Knoten der Reihe nach.
    private static func evmGas(_ network: GasNetwork) async throws -> (fees: (Double, Double, Double), rpc: String) {
        var lastError: Error = JSONError(message: "Kein Knoten")
        for rpc in network.rpcs {
            do {
                do {
                    let fees = try GasFees.parseFeeHistory(try await MarketHTTP.call(rpc, post: jsonPost(GasFees.feeHistoryRequest)))
                    return (fees, rpc)
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    // Ohne eth_feeHistory: einfacher Gaspreis für alle drei Stufen
                    let price = try GasFees.parseGasPrice(try await MarketHTTP.call(rpc, post: jsonPost(GasFees.gasPriceRequest)))
                    return ((price, price, price), rpc)
                }
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                lastError = error
            }
        }
        throw lastError
    }

    private static func mempoolOrNil() async -> (Double, Double, Double)? {
        guard let text = try? await MarketHTTP.call("https://mempool.space/api/v1/fees/recommended") else { return nil }
        return try? GasFees.parseMempool(text)
    }

    private static func prices() async -> [String: Double] {
        let coins = Array(Set(GasNetwork.allCases.map(\.coin) + ["BTC"])).sorted()
        let symbols = "[" + coins.map { "\"\($0)USDT\"" }.joined(separator: ",") + "]"
        let url = "https://data-api.binance.vision/api/v3/ticker/price?symbols=" + symbols.urlQueryEncoded
        if let text = try? await MarketHTTP.call(url),
           let prices = try? GasFees.parseBinancePrices(text), !prices.isEmpty {
            return prices
        }
        if let text = try? await MarketHTTP.call("https://api.coinbase.com/v2/exchange-rates?currency=USD"),
           let prices = try? GasFees.parseCoinbaseRates(text, coins: coins) {
            return prices
        }
        return [:]
    }
}

/// Wann ein Gas-Alarm auslöst: sobald die Gebühr auf oder unter die Grenze fällt —
/// aber nur einmal. Erst wenn sie wieder deutlich darüber liegt (`rearmFactor`),
/// ist der Alarm erneut scharf. Wie `GasAlertLogic` / `GasAlertChecker`.
/// Gleiche Regel wie bei den Kursalarmen (`QuietHours.mayFire`): Kann die Mitteilung gerade nicht
/// ankommen oder nimmt das System sie nicht an, bleibt der Alarm scharf.
enum GasAlertCheck {
    static let rearmFactor = 1.25
    private static let keyLastCheck = "gas_last_check"
    static let keyEthArmed = "gas_eth_armed"
    static let keyBtcArmed = "gas_btc_armed"
    /// Grenze, zu der der gemerkte Zustand gehört (wie Android `eth_threshold`/`btc_threshold`); beim Ausschalten
    /// in den Einstellungen auf «aus» (0) gesetzt (`MarketAlertReset`).
    static let keyEthThreshold = "gas_eth_threshold"
    static let keyBtcThreshold = "gas_btc_threshold"
    /// Zähler der «Aus»-Schaltungen (`MarketAlertReset.gasOff`): Eine laufende Prüfung schreibt danach ihren Stand nicht
    /// mehr — sonst überschriebe sie das «Aus» mit der alten Grenze (Android: gemeinsame Sperre `GasAlertChecker.lock`).
    static let keyResetGeneration = "gas_reset_generation"
    /// Höchstens alle 10 Minuten.
    private static let minInterval: Int64 = 10 * 60_000
    /// Nach einem fehlgeschlagenen Abruf: so lange bis zum nächsten Versuch (wie Android).
    private static let retryAfterFailure: Int64 = 2 * 60_000

    /// - Returns: (melden?, scharf danach?) — «scharf danach» gilt für eine angenommene Mitteilung.
    static func evaluate(value: Double, threshold: Double, armed: Bool, deliverable: Bool = true) -> (fire: Bool, armed: Bool) {
        if threshold <= 0 { return (false, true) }
        // Fällig, aber nicht zustellbar: nicht verbrauchen
        if armed && value <= threshold { return deliverable ? (true, false) : (false, true) }
        if !armed && value > threshold * rearmFactor { return (false, true) }
        return (false, armed)
    }

    /// Zu speichernder Zustand: Nahm das System die fällige Mitteilung nicht an, bleibt der alte Zustand.
    static func armedAfter(fire: Bool, posted: Bool, armedBefore: Bool, next: Bool) -> Bool {
        (fire && !posted) ? armedBefore : next
    }

    /// Gespeicherter Zustand für die aktuelle Grenze: Wurde er bei einer anderen Grenze gemerkt (geändert
    /// oder zwischendurch aus = 0), ist der Alarm wieder scharf. `storedThreshold` nil = Stand vor dieser
    /// Regel: Zustand übernehmen. Wie `GasAlertLogic.armedFor`.
    static func armedFor(storedArmed: Bool, storedThreshold: Double?, threshold: Double) -> Bool {
        if let stored = storedThreshold, stored != threshold { return true }
        return storedArmed
    }

    /// Seit Beginn der Prüfung (`generation`) kein «Aus»? Sonst den Stand nicht schreiben; der nächste Lauf bewertet neu.
    static func mayStore(generation: Int, defaults: UserDefaults) -> Bool {
        defaults.integer(forKey: keyResetGeneration) == generation
    }

    /// Wie `GasAlertChecker.isDue` (Android): auch fällig, wenn die letzte Prüfung in der Zukunft liegt.
    static func isDue(now: Int64, lastCheck: Int64) -> Bool {
        TimeUtils.isIntervalDue(now: now, last: lastCheck, minInterval: minInterval)
    }

    /// Nach einer Kursaktualisierung aufrufen; tut nichts ohne eingestellte Grenze.
    static func runIfDue(settings: AppSettings) async {
        let ethThreshold = Double(settings.gasAlertEthTenths) / 10
        let btcThreshold = Double(settings.gasAlertBtc)
        let defaults = SharedStorage.defaults
        guard ethThreshold > 0 || btcThreshold > 0 else {
            // «Aus» merken, damit beim Wiedereinschalten (auch mit gleicher Grenze) der Alarm scharf ist
            if defaults.object(forKey: keyEthThreshold) as? Double != 0 || defaults.object(forKey: keyBtcThreshold) as? Double != 0 {
                defaults.set(0.0, forKey: keyEthThreshold)
                defaults.set(0.0, forKey: keyBtcThreshold)
            }
            return
        }
        let now = TimeUtils.nowMillis
        let last = Int64(defaults.double(forKey: keyLastCheck))
        guard isDue(now: now, lastCheck: last) else { return }
        defaults.set(Double(now), forKey: keyLastCheck)
        let generation = defaults.integer(forKey: keyResetGeneration)

        guard let report = try? await GasDataSource.shared.fetch() else {
            // Fehlschlag sperrt nicht die vollen 10 Minuten
            defaults.set(Double(now - minInterval + retryAfterFailure), forKey: keyLastCheck)
            return
        }
        // Käme eine Mitteilung jetzt an? Nachtruhe: lautlos wie bisher
        let deliverable = await Notifier.isAuthorized()
        if let eth = report.evm.first(where: { $0.network == .ethereum }) {
            await check(keyEthArmed, keyEthThreshold, eth.normalGwei, ethThreshold, deliverable, defaults, generation) {
                await Notifier.showGas(
                    id: "gas-eth",
                    title: L("notification_gas_eth_title", GasFees.formatGwei(eth.normalGwei)),
                    body: L("notification_gas_eth_text", GasFees.formatGwei(ethThreshold),
                            eth.transferUsd.map { GasFees.formatUsd($0) } ?? "–")
                )
            }
        }
        if let btc = report.btc {
            await check(keyBtcArmed, keyBtcThreshold, btc.normal, btcThreshold, deliverable, defaults, generation) {
                await Notifier.showGas(
                    id: "gas-btc",
                    title: L("notification_gas_btc_title", GasFees.formatGwei(btc.normal)),
                    body: L("notification_gas_btc_text", GasFees.formatGwei(btcThreshold),
                            btc.transferUsd.map { GasFees.formatUsd($0) } ?? "–")
                )
            }
        }
    }

    private static func check(_ key: String, _ thresholdKey: String, _ value: Double, _ threshold: Double,
                              _ deliverable: Bool, _ defaults: UserDefaults, _ generation: Int,
                              notify: () async -> Bool) async {
        let armed = armedFor(storedArmed: defaults.object(forKey: key) as? Bool ?? true,
                             storedThreshold: defaults.object(forKey: thresholdKey) as? Double, threshold: threshold)
        let result = evaluate(value: value, threshold: threshold, armed: armed, deliverable: deliverable)
        let posted = result.fire ? await notify() : false
        guard mayStore(generation: generation, defaults: defaults) else { return }
        defaults.set(armedAfter(fire: result.fire, posted: posted, armedBefore: armed, next: result.armed), forKey: key)
        defaults.set(threshold, forKey: thresholdKey)
    }
}
