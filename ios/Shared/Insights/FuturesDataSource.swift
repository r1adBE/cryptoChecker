import Foundation

/// Funding Rate und Open Interest eines Perpetual-Kontrakts.
struct FuturesInfo: Equatable, Sendable {
    /// Aktuelle Funding Rate in Prozent (z. B. 0.01 = 0,01 %).
    let fundingRatePercent: Double?
    /// Zeitpunkt der nächsten Funding-Zahlung (Epoch-ms).
    let nextFundingTime: Int64?
    /// Offene Positionen in USD.
    let openInterestUsd: Double?
    /// Woher die Daten stammen («Binance Futures», «Bybit» oder «OKX»; bei anderen Börsen als Richtwert).
    let source: String
}

/// Futures-Kennzahlen — wie `FuturesDataSource.kt`. Bybit-Futures zuerst von
/// Bybit, alles andere zuerst von Binance USDⓈ-M — bei anderen Börsen als
/// Richtwert für denselben Coin. Ist die erste Quelle gesperrt (Binance in den
/// USA: 451/403) oder liefert nichts, folgen Bybit bzw. Binance und zuletzt OKX.
/// Gesperrte Hosts werden wie bei den Kerzen 6 h übersprungen (`BlockedSources`).
enum FuturesDataSource {

    /// Hostnamen = Schlüssel in `BlockedSources`.
    private static let binanceHost = "fapi.binance.com"
    private static let bybitHost = "api.bybit.com"
    private static let okxHost = "www.okx.com"

    private typealias Attempt = (host: String, run: () async throws -> FuturesInfo)

    /// nil, wenn das Paar kein (USDT-)Perpetual ist.
    static func fetch(watch: Watch) async throws -> FuturesInfo? {
        // Wie Android: nur PERPETUAL, nicht INVERSE_PERPETUAL.
        guard watch.contractType == .perpetual else { return nil }
        let base = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        switch watch.marketKey {
        case "BybitFutures":
            let symbol = watch.pairId ?? "\(base)USDT"
            return try await firstOf([
                (host: bybitHost, run: { try await bybit(symbol: symbol) }),
                (host: binanceHost, run: { try await binance(symbol: "\(base)USDT", source: "Binance Futures") }),
                (host: okxHost, run: { try await okx(base: base) }),
            ])
        case "BinanceFutures":
            let symbol: String
            if let pairId = watch.pairId, pairId.hasSuffix("USDT") || pairId.hasSuffix("USDC") {
                symbol = pairId
            } else {
                symbol = "\(base)USDT"
            }
            return try await firstOf([
                (host: binanceHost, run: { try await binance(symbol: symbol, source: "Binance Futures") }),
                (host: bybitHost, run: { try await bybit(symbol: "\(base)USDT") }),
                (host: okxHost, run: { try await okx(base: base) }),
            ])
        default:
            return try await firstOf([
                (host: binanceHost, run: { try await binance(symbol: "\(base)USDT", source: "Binance Futures") }),
                (host: bybitHost, run: { try await bybit(symbol: "\(base)USDT") }),
                (host: okxHost, run: { try await okx(base: base) }),
            ])
        }
    }

    /// Kennzahlen des USDT-Perpetuals zu einem Coin, unabhängig vom beobachteten Paar
    /// (z. B. Spot) — für «Warum bewegt sich das?». Wie `fetchForBase` in Android.
    static func fetchForBase(baseAsset: String) async throws -> FuturesInfo {
        let base = baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        return try await firstOf([
            (host: binanceHost, run: { try await binance(symbol: "\(base)USDT", source: "Binance Futures") }),
            (host: bybitHost, run: { try await bybit(symbol: "\(base)USDT") }),
            (host: okxHost, run: { try await okx(base: base) }),
        ])
    }

    /// Fragt die Quellen der Reihe nach (gesperrte Hosts übersprungen); die erste
    /// Antwort gewinnt. Wirft den letzten Fehler, wenn alle scheitern.
    private static func firstOf(_ attempts: [Attempt]) async throws -> FuturesInfo {
        var lastError: Error = JSONError(message: "Alle Futures-Quellen gesperrt")
        for attempt in attempts {
            if BlockedSources.isBlocked(attempt.host) { continue }
            do {
                return try await attempt.run()
            } catch {
                if error is CancellationError || Task.isCancelled { throw error }
                BlockedSources.noteFailure(attempt.host, error)
                lastError = error
            }
        }
        throw lastError
    }

    private static func binance(symbol: String, source: String) async throws -> FuturesInfo {
        async let premiumJob = MarketHTTP.call("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=\(symbol)")
        async let oiJob = optionalCall("https://fapi.binance.com/fapi/v1/openInterest?symbol=\(symbol)")

        let p = try JObject(string: try await premiumJob)
        let oiText = await oiJob
        let oi = oiText.flatMap { try? JObject(string: $0) }

        let mark = Double(p.optString("markPrice"))
        let contracts = oi.flatMap { Double($0.optString("openInterest")) }
        let next = p.optLong("nextFundingTime")
        var openInterest: Double? = nil
        if let contracts, let mark { openInterest = contracts * mark }
        return FuturesInfo(
            fundingRatePercent: Double(p.optString("lastFundingRate")).map { $0 * 100.0 },
            nextFundingTime: next > 0 ? next : nil,
            openInterestUsd: openInterest,
            source: source
        )
    }

    private static func bybit(symbol: String) async throws -> FuturesInfo {
        let url = "https://api.bybit.com/v5/market/tickers?category=linear&symbol=\(symbol)"
        let t = try JObject(string: try await MarketHTTP.call(url))
            .object("result").array("list").object(0)
        let next = Int64(t.optString("nextFundingTime"))
        return FuturesInfo(
            fundingRatePercent: Double(t.optString("fundingRate")).map { $0 * 100.0 },
            nextFundingTime: (next ?? 0) > 0 ? next : nil,
            openInterestUsd: Double(t.optString("openInterestValue")),
            source: "Bybit"
        )
    }

    /// OKX-Perpetual BASE-USDT-SWAP. Funding: «fundingTime» ist die nächste Abrechnung.
    /// Open Interest in USD aus «oiUsd», sonst Coins × Mark-Preis.
    private static func okx(base: String) async throws -> FuturesInfo {
        let instId = "\(base)-USDT-SWAP"
        async let fundingJob = MarketHTTP.call("https://www.okx.com/api/v5/public/funding-rate?instId=\(instId)")
        async let oiJob = optionalCall("https://www.okx.com/api/v5/public/open-interest?instType=SWAP&instId=\(instId)")

        let f = try okxFirst(try await fundingJob)
        let oiText = await oiJob
        let oi = oiText.flatMap { try? okxFirst($0) }

        var openInterest: Double? = oi.flatMap { Double($0.optString("oiUsd")) }
        if openInterest == nil, let coins = oi.flatMap({ Double($0.optString("oiCcy")) }) {
            let markText = await optionalCall("https://www.okx.com/api/v5/public/mark-price?instType=SWAP&instId=\(instId)")
            if let mark = markText.flatMap({ try? okxFirst($0) }).flatMap({ Double($0.optString("markPx")) }) {
                openInterest = coins * mark
            }
        }
        let next = Int64(f.optString("fundingTime")) ?? Int64(f.optString("nextFundingTime"))
        return FuturesInfo(
            fundingRatePercent: Double(f.optString("fundingRate")).map { $0 * 100.0 },
            nextFundingTime: (next ?? 0) > 0 ? next : nil,
            openInterestUsd: openInterest,
            source: "OKX"
        )
    }

    /// OKX-Antwort {"code":"0","data":[{…}]}: erstes Datenobjekt, sonst Fehler.
    private static func okxFirst(_ text: String) throws -> JObject {
        let root = try JObject(string: text)
        guard root.optString("code") == "0" else {
            throw JSONError(message: "OKX-Fehler: \(root.optString("msg"))")
        }
        return try root.array("data").object(0)
    }

    private static func optionalCall(_ url: String) async -> String? {
        try? await MarketHTTP.call(url)
    }
}
