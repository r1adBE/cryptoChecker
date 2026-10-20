import Foundation

// Zeit- und Kennungs-Helfer der eigenen Kerzen, zweite Runde (`ExchangeCandleSourcesGlobal.swift`,
// `ExchangeCandleSourcesSpecial.swift`) — wie `CandleTime` in `ExchangeCandleSourcesGlobal.kt` (Android).

/// Zeit- und Kennungs-Helfer der zweiten Runde.
enum CandleTime {
    static let hour: Int64 = 3_600_000

    private static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        var q = a / b
        if a % b != 0 && (a < 0) != (b < 0) { q -= 1 }
        return q
    }

    /// Beginn des laufenden Intervalls (UTC-Raster).
    static func aligned(_ now: Int64, step: Int64) -> Int64 { floorDiv(now, step) * step }

    /// Beginn eines Fensters, das `count` Kerzen bis zur laufenden umfasst.
    static func windowStart(_ now: Int64, step: Int64, count: Int) -> Int64 {
        aligned(now, step: step) - Int64(max(count - 1, 0)) * step
    }

    /// Kennung des Paars mit zusätzlich erlaubten Zeichen `extra` (z. B. «:» bei Bitfinex); sonst nil.
    static func pairId(_ pair: CandlePair, extra: String = "") -> String? {
        let t = (pair.pairId ?? "").trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty, t.allSatisfy({ ($0.isASCII && ($0.isLetter || $0.isNumber)) || $0 == "-" || $0 == "_" || extra.contains($0) })
        else { return nil }
        return t
    }

    /// Tage seit 1970 für ein Datum (proleptisch gregorianisch, ohne Zeitzone).
    private static func daysFromCivil(_ y0: Int64, _ m: Int64, _ d: Int64) -> Int64 {
        let y = m <= 2 ? y0 - 1 : y0
        let era = floorDiv(y, 400)
        let yoe = y - era * 400
        let mp = (m + 9) % 12
        let doy = (153 * mp + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /// «2025-10-14T00:00:00», «…T13:30:00.000Z» (UTC) → Epoch-ms; nil, wenn nicht lesbar.
    static func parseIso(_ s: String?) -> Int64? {
        guard var t = s?.trimmingCharacters(in: .whitespaces) else { return nil }
        if t.hasSuffix("Z") { t.removeLast() }
        let chars = Array(t)
        guard chars.count >= 19, chars[4] == "-", chars[7] == "-", chars[10] == "T" || chars[10] == " ",
              chars[13] == ":", chars[16] == ":" else { return nil }
        func int(_ from: Int, _ to: Int) -> Int64? {
            let part = chars[from..<to]
            guard part.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
            return Int64(String(part))
        }
        guard let y = int(0, 4), let mo = int(5, 7), let d = int(8, 10), let h = int(11, 13),
              let mi = int(14, 16), let se = int(17, 19) else { return nil }
        var ms: Int64 = 0
        if chars.count > 19 {
            guard chars[19] == ".", chars.count > 20 else { return nil }
            let frac = chars[20...]
            guard frac.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
            let three = String(frac.prefix(3)).padding(toLength: 3, withPad: "0", startingAt: 0)
            ms = Int64(three) ?? 0
        }
        let days = daysFromCivil(y, mo, d)
        return ((days * 24 + h) * 60 + mi) * 60_000 + se * 1000 + ms
    }

    /// Epoch-ms → «2025-10-14T00:00:00.000Z» (UTC).
    static func formatIso(_ millis: Int64) -> String {
        let days = floorDiv(millis, 86_400_000)
        let rest = millis - days * 86_400_000
        let z = days + 719468
        let era = floorDiv(z, 146097)
        let doe = z - era * 146097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        let y = yoe + era * 400 + (m <= 2 ? 1 : 0)
        func pad(_ v: Int64, _ n: Int) -> String {
            let s = String(v)
            return String(repeating: "0", count: max(n - s.count, 0)) + s
        }
        return "\(pad(y, 4))-\(pad(m, 2))-\(pad(d, 2))T\(pad(rest / 3_600_000, 2)):\(pad(rest / 60_000 % 60, 2)):"
            + "\(pad(rest / 1000 % 60, 2)).\(pad(rest % 1000, 3))Z"
    }

    /// 4 h aus Stundenkerzen (UTC-Raster).
    static func fourHours(_ hourly: [MarketCandle]) -> [MarketCandle] {
        CandleDataSource.aggregate(hourly.sorted { $0.openTime < $1.openTime }, bucketMillis: 4 * hour, offsetMillis: 0, fullCount: 4)
    }

    /// Rohkerzen für `limit` Kerzen (4 h aus Stundenkerzen: viermal so viele und ein Block Reserve).
    static func rawCount(_ interval: CandleInterval, _ limit: Int) -> Int { interval == .h4 ? limit * 4 + 4 : limit }

    /// Menge in der Basis aus dem Quote-Umsatz (nur genähert) — wenn die Quelle nur Kontrakte zählt.
    static func baseFromQuote(_ quote: Double, close: Double) -> Double {
        quote.isFinite && quote >= 0 && close.isFinite && close > 0 ? quote / close : .nan
    }

    /// Spalte `name` einer Antwort mit parallelen Listen (MEXC Futures, Deribit).
    static func column(_ o: JObject, _ name: String, _ i: Int) -> Double {
        guard let a = o.optArray(name) else { return .nan }
        return CandleParse.num(a, i)
    }

    static func candle(_ t: Int64, o: Double, h: Double, l: Double, c: Double, v: Double, qv: Double? = nil) -> MarketCandle {
        MarketCandle(openTime: t, open: o, high: h, low: l, close: c, volume: v, quoteVolume: qv)
    }
}
