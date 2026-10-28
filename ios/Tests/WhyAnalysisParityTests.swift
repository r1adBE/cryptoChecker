import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle der «Warum?»-Auswertung (`Parity/why_analysis.json`, Quelle
/// `android/testdata/parity`, gelesen auch von `WhyAnalysisParityTest.kt`): gleiche Eingaben →
/// gleiche Gründe, Faktoren, Lage, Funding-Verlauf, Open-Interest-Vergleich und breiter Markt.
/// Kerzen werden aus kurzen Angaben gebaut — Schritt für Schritt wie `WhyParityBuilder` (Android),
/// damit dieselben Doubles entstehen.
final class WhyAnalysisParityTests: XCTestCase {

    private typealias S = TestSupport
    private static let hour: Int64 = 3_600_000
    private static let day: Int64 = 24 * hour

    // MARK: Fälle

    func testExplain() throws {
        let data = try S.fixture("why_analysis")
        let now = Int64(S.number(data["now"]) ?? 0)
        let cases = S.list(data["explain"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let input = Self.input(c["input"] as? [String: Any] ?? [:], now: now)
            let report = ActivityAnalyzer.explain(input)
            XCTAssertEqual(Self.canonical(Self.describe(report)), Self.canonical(c["expected"]), name)
        }
    }

    func testOiSeries() throws {
        let data = try S.fixture("why_analysis")
        let now = Int64(S.number(data["now"]) ?? 0)
        for c in S.list(data["oiChange"]) {
            let r = OiSeries.change(Self.samples(c["series"], now: now), currentUnits: S.number(c["current"]),
                                    source: c["source"] as? String, now: now)
            let actual: [String: Any] = [
                "change": r.changePercent as Any, "minutes": r.minutes.map { Double($0) } as Any,
                "consistent": r.consistent, "samples": Double(r.samples),
            ]
            XCTAssertEqual(Self.canonical(actual), Self.canonical(c["expected"]), c["name"] as? String ?? "?")
        }
        for c in S.list(data["oiAppend"]) {
            let sample = Self.samples([c["sample"] as Any], now: now)[0]
            let out = OiSeries.append(Self.samples(c["series"], now: now), sample, now: now)
            let actual: [Any] = out.map { s -> [Any] in
                [Double((s.time - now) / 60_000), s.units, s.source]
            }
            XCTAssertEqual(Self.canonical(actual), Self.canonical(c["expected"]), c["name"] as? String ?? "?")
        }
    }

    func testFundingTrend() throws {
        let data = try S.fixture("why_analysis")
        let now = Int64(S.number(data["now"]) ?? 0)
        for c in S.list(data["fundingTrend"]) {
            let trend = FundingTrendRules.trend(Self.history(c["history"], now: now), current: S.number(c["current"]),
                                                currentSource: c["currentSource"] as? String, now: now)
            let actual: Any = trend.map { ["direction": $0.direction.rawValue, "hours": Double($0.hours)] as [String: Any] } as Any
            XCTAssertEqual(Self.canonical(actual), Self.canonical(c["expected"]), c["name"] as? String ?? "?")
        }
    }

    func testBroadMarket() throws {
        let data = try S.fixture("why_analysis")
        let now = Int64(S.number(data["now"]) ?? 0)
        for c in S.list(data["broadMarket"]) {
            let coins: [BroadCoin] = (c["coins"] as? [Any] ?? []).compactMap { raw in
                guard let e = raw as? [Any], e.count >= 3 else { return nil }
                return BroadCoin(symbol: e[0] as? String ?? "", change24h: S.number(e[1]),
                                 lastUpdated: S.number(e[2]).map { now + Int64($0) * 60_000 })
            }
            let snap = BroadMarket.snapshot(coins, excludeBase: c["exclude"] as? String, now: now)
            let actual: Any = snap.map {
                ["change24h": $0.change24h, "coins": Double($0.coins), "timeMin": Double(($0.time - now) / 60_000)] as [String: Any]
            } as Any
            XCTAssertEqual(Self.canonical(actual), Self.canonical(c["expected"]), c["name"] as? String ?? "?")
        }
    }

    /// Herkunft: Alter von Fear & Greed (UTC-Tage) und des Open-Interest-Vergleichs — wie
    /// `freshness_sourcesAndAges` (Android).
    func testFreshnessAges() {
        let day = Self.day
        let midnight: Int64 = 1_760_000_000_000 - 1_760_000_000_000 % day
        XCTAssertEqual(WhyFreshness.fearGreedDaysAgo(time: midnight, now: midnight + 5 * Self.hour), 0)
        XCTAssertEqual(WhyFreshness.fearGreedDaysAgo(time: midnight - day, now: midnight + 5 * Self.hour), 1)
        XCTAssertEqual(WhyFreshness.fearGreedDaysAgo(time: midnight - 1, now: midnight), 1)
        XCTAssertNil(WhyFreshness.oiAgeHours(minutes: 89))
        XCTAssertEqual(WhyFreshness.oiAgeHours(minutes: 90), 2)
        XCTAssertEqual(WhyFreshness.oiAgeHours(minutes: 149), 2)
        XCTAssertEqual(WhyFreshness.oiAgeHours(minutes: 150), 3)
    }

    // MARK: Bau der Eingaben (wie `WhyParityBuilder` in Android)

    /// [[Wert, Anzahl], …] → Liste.
    private static func expand(_ spec: Any?) -> [Double] {
        var out: [Double] = []
        for pair in spec as? [Any] ?? [] {
            guard let p = pair as? [Any], !p.isEmpty else { continue }
            let value = S.number(p[0]) ?? 0
            let times = p.count > 1 ? Int(S.number(p[1]) ?? 1) : 1
            out.append(contentsOf: Array(repeating: value, count: max(times, 0)))
        }
        return out
    }

    /// Stundenkerzen: {"count", "start", "pattern", "tail", "volume", "volumeTail", "quote"}; letzte = laufende Stunde.
    static func candles(_ spec: Any?, now: Int64) -> [MarketCandle]? {
        guard let s = spec as? [String: Any] else { return nil }
        let count = Int(S.number(s["count"]) ?? 100)
        let start = S.number(s["start"]) ?? 100
        var pattern = (s["pattern"] as? [Any] ?? []).map { S.number($0) ?? 0 }
        if pattern.isEmpty { pattern = [0] }
        let tail = expand(s["tail"])
        let volume = S.number(s["volume"]) ?? 100
        let volumeTail = expand(s["volumeTail"])
        let quote = s["quote"] as? Bool ?? false
        let returns = count - 1
        var closes: [Double] = [start]
        for i in 0..<max(returns, 0) {
            let fromEnd = returns - i
            let r = fromEnd <= tail.count ? tail[tail.count - fromEnd] : pattern[i % pattern.count]
            closes.append(closes[closes.count - 1] * (1.0 + r / 100.0))
        }
        let open0 = now - now % hour
        return (0..<count).map { i in
            let open = i == 0 ? closes[0] : closes[i - 1]
            let fromEnd = count - i
            let v = fromEnd <= volumeTail.count ? volumeTail[volumeTail.count - fromEnd] : volume
            return MarketCandle(
                openTime: open0 - Int64(count - 1 - i) * hour,
                open: open,
                high: max(open, closes[i]),
                low: min(open, closes[i]),
                close: closes[i],
                volume: v,
                quoteVolume: quote ? v * closes[i] : nil
            )
        }
    }

    /// Tageskerzen: {"count", "close", "high", "low"} — alle Tage gleich, letzter = laufender Tag.
    static func daily(_ spec: Any?, now: Int64) -> [MarketCandle]? {
        guard let s = spec as? [String: Any] else { return nil }
        let count = Int(S.number(s["count"]) ?? 31)
        let close = S.number(s["close"]) ?? 100
        let high = S.number(s["high"]) ?? close
        let low = S.number(s["low"]) ?? close
        let lastOpen = now - now % day
        return (0..<count).map { i in
            MarketCandle(openTime: lastOpen - Int64(count - 1 - i) * day, open: close, high: high, low: low,
                         close: close, volume: 1)
        }
    }

    /// [[Minuten, Coins, Quelle], …]
    static func samples(_ spec: Any?, now: Int64) -> [OiSample] {
        (spec as? [Any] ?? []).compactMap { raw in
            guard let e = raw as? [Any], e.count > 2, let source = e[2] as? String else { return nil }
            return OiSample(units: S.number(e[1]) ?? 0, time: now + Int64(S.number(e[0]) ?? 0) * 60_000, source: source)
        }
    }

    /// {"source", "points": [[Minuten, Satz %], …]}
    static func history(_ spec: Any?, now: Int64) -> FundingHistory? {
        guard let s = spec as? [String: Any] else { return nil }
        let points: [FundingPoint] = (s["points"] as? [Any] ?? []).compactMap { raw in
            guard let e = raw as? [Any], e.count >= 2 else { return nil }
            return FundingPoint(time: now + Int64(S.number(e[0]) ?? 0) * 60_000, ratePercent: S.number(e[1]) ?? 0)
        }
        return FundingHistory(points: points, source: s["source"] as? String ?? "")
    }

    static func input(_ s: [String: Any], now: Int64) -> WhyInput {
        var input = WhyInput(
            baseAsset: s["base"] as? String ?? "",
            candles: candles(s["candles"], now: now),
            referenceCandles: candles(s["reference"], now: now),
            fundingPercent: S.number(s["funding"]),
            openInterestChangePercent: S.number(s["oi"]),
            fearGreed: S.number(s["fng"]).map { Int($0) },
            fearGreedYesterday: S.number(s["fngYesterday"]).map { Int($0) },
            now: now,
            tickerChange24h: S.number(s["ticker24h"]),
            marketLive: s["live"] as? Bool ?? true,
            dailyCandles: daily(s["daily"], now: now)
        )
        input.quoteAsset = s["quote"] as? String
        input.ethCandles = candles(s["eth"], now: now)
        if let b = s["broad"] as? [String: Any] {
            input.broadMarket = BroadMarketSnapshot(change24h: S.number(b["change24h"]) ?? 0,
                                                    coins: Int(S.number(b["coins"]) ?? 0),
                                                    time: now + Int64(S.number(b["timeMin"]) ?? 0) * 60_000)
        }
        input.openInterestMinutes = S.number(s["oiMinutes"]).map { Int($0) }
        input.openInterestConsistent = s["oiConsistent"] as? Bool ?? true
        input.fundingHistory = history(s["fundingHistory"], now: now)
        input.fundingSource = s["fundingSource"] as? String
        return input
    }

    // MARK: Beschreibung der Ergebnisse (Namen wie die Kotlin-Enums)

    /// «marketCoinLags» → «MARKET_COIN_LAGS», «position3d» → «POSITION_3D».
    static func kotlinName(_ value: Any) -> String {
        var out = ""
        var previous: Character? = nil
        for ch in String(describing: value) {
            if let p = previous, (ch.isUppercase && p.isLetter) || (ch.isNumber && p.isLetter) { out.append("_") }
            out.append(ch)
            previous = ch
        }
        return out.uppercased()
    }

    static func describe(_ r: WhyReport) -> [String: Any] {
        let reasons: [[String: Any]] = r.reasons.map { x in
            [
                "kind": x.kind.rawValue, "tone": kotlinName(x.tone), "value": x.value, "secondary": x.secondary as Any,
                "strong": x.strong, "ref": x.ref?.rawValue as Any, "threshold": x.threshold as Any,
                "approximate": x.approximate,
                "similarOnly": WhySummary.isSimilarOnly(x), "mark": kotlinName(WhySummary.mark(x)),
            ]
        }
        let factors: [[String: Any]] = WhyFactors.rank(r).map { f in
            [
                "kind": kotlinName(f.kind), "note": kotlinName(f.note), "direction": kotlinName(f.direction),
                "value": f.value, "secondary": f.secondary as Any, "strength": f.strength, "neutral": f.neutral,
                "ref": f.ref?.rawValue as Any, "approximate": f.approximate,
                "trend": f.trend.map { "\($0.direction.rawValue)/\($0.hours)" } as Any,
            ]
        }
        var out: [String: Any] = [
            "hasMarketData": r.hasMarketData,
            "stable": r.stable,
            "price": r.price as Any,
            "change1h": r.change1h as Any,
            "change24h": r.change24h as Any,
            "high30d": r.high30d as Any,
            "low30d": r.low30d as Any,
            "btcLink": r.btcLink.map { kotlinName($0) } as Any,
            "oiMinutes": r.oiMinutes.map { Double($0) } as Any,
            "candleTimeMin": r.candleTime.map { Double(($0 - r.dataTime) / 60_000) } as Any,
            "reasons": reasons,
            "factors": factors,
            "brief": WhySummary.keys(r.reasons),
        ]
        out["position3d"] = r.position3d.map {
            ["kind": $0.kind.rawValue, "percent": $0.percent, "threshold": $0.threshold] as [String: Any]
        } as Any
        out["fundingTrend"] = r.fundingTrend.map {
            ["direction": $0.direction.rawValue, "hours": Double($0.hours)] as [String: Any]
        } as Any
        return out
    }

    /// Kanonische Textform: Schlüssel sortiert, null-Einträge weggelassen, Zahlen als ganze Millionstel
    /// (halbe vom Nullpunkt weg gerundet — wie `WhyParityBuilder.normalize` in Android).
    static func canonical(_ value: Any?) -> String {
        guard let value else { return "null" }
        if let optional = value as? OptionalProtocol { return optional.isNil ? "null" : canonical(optional.unwrapped) }
        if value is NSNull { return "null" }
        if let s = value as? String { return "\"" + s + "\"" }
        // Zahlen und Wahrheitswerte (aus JSON wie aus Swift) kommen als NSNumber an
        if let n = value as? NSNumber {
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue ? "true" : "false" }
            return micro(n.doubleValue)
        }
        if let dict = value as? [String: Any] {
            let parts = dict.keys.sorted().compactMap { key -> String? in
                let v = canonical(dict[key])
                return v == "null" ? nil : "\"\(key)\":\(v)"
            }
            return "{" + parts.joined(separator: ",") + "}"
        }
        if let array = value as? [Any] { return "[" + array.map { canonical($0) }.joined(separator: ",") + "]" }
        return "\"\(value)\""
    }

    private static func micro(_ d: Double) -> String {
        let m = Int64((abs(d) * 1e6 + 0.5).rounded(.down))
        if m == 0 { return "0" }
        return (d < 0 ? "-" : "") + String(m)
    }
}

/// Erkennt `Optional` hinter `Any` (z. B. `nil as Double? as Any`).
private protocol OptionalProtocol {
    var isNil: Bool { get }
    var unwrapped: Any { get }
}

extension Optional: OptionalProtocol {
    var isNil: Bool { self == nil }
    var unwrapped: Any {
        switch self {
        case .some(let v): return v
        case .none: return NSNull()
        }
    }
}
