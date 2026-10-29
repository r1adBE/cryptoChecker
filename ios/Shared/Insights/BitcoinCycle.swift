import Foundation

/// Letztes und — geschätzt — nächstes Bitcoin-Halving. Reines Datum, ohne Phase oder Fortschritt.
struct HalvingInfo: Equatable, Sendable {
    let lastHalving: LocalDay
    /// Schätzung: letztes Halving plus `BitcoinHalving.intervalDays`; die App kennt die Blockhöhe nicht.
    let nextHalvingEstimate: LocalDay
}

/// Bitcoin-Halving-Termine. Rein kalenderbasiert, ohne Internet. Die App liest keine
/// Blockhöhe ein; das nächste Halving ist darum nur grob geschätzt und wird als Monat
/// («voraussichtlich ~April 2028 (geschätzt)») gezeigt. Wie `BitcoinHalving` (Android).
enum BitcoinHalving {

    /// Tatsächliche Halving-Termine.
    static let halvings: [LocalDay] = [
        LocalDay(2012, 11, 28),
        LocalDay(2016, 7, 9),
        LocalDay(2020, 5, 11),
        LocalDay(2024, 4, 20),
    ]

    /// 210'000 Blöcke à rund 10 Minuten ≈ 1458 Tage — nur für die Schätzung des nächsten Termins.
    static let intervalDays = 1_458

    static func info(today: LocalDay = .today()) -> HalvingInfo {
        let last = halvings.last(where: { !$0.isAfter(today) }) ?? halvings[0]

        // Liegt heute schon nach einem geschätzten künftigen Halving, weiterzählen.
        var lastHalving = last
        var next = last.plusDays(intervalDays)
        while !next.isAfter(today) {
            lastHalving = next
            next = next.plusDays(intervalDays)
        }
        return HalvingInfo(lastHalving: lastHalving, nextHalvingEstimate: next)
    }
}

/// Nächstes Halving rechnerisch aus der Blockhöhe (mempool.space) — wie `HalvingEstimate` (Android).
struct HalvingEstimate: Equatable, Sendable {
    let date: LocalDay
    let height: Int
    let remainingBlocks: Int
    let avgBlockSeconds: Double
}

enum HalvingMath {
    static let blocksPerHalving = 210_000
    /// Blockhöhe ab hier unplausibel (Server-Wert; heute unter 1 Mio., die Rechnung liefe nahe Int.max über).
    static let maxPlausibleHeight = 10_000_000

    /// nil bei ungültigen Werten (Höhe ≤ 0 oder ab `maxPlausibleHeight`, Blockzeit ausserhalb 1–60 Minuten). Tag in UTC
    /// wie Android; Testfälle `server_values.json`.
    static func estimate(tipHeight: Int, avgBlockSeconds: Double, nowMillis: Int64) -> HalvingEstimate? {
        guard tipHeight > 0, tipHeight < maxPlausibleHeight else { return nil }
        guard avgBlockSeconds.isFinite, avgBlockSeconds >= 60, avgBlockSeconds <= 3_600 else { return nil }
        let next = (tipHeight / blocksPerHalving + 1) * blocksPerHalving
        let remaining = next - tipHeight
        let millis = nowMillis + Int64(Double(remaining) * avgBlockSeconds * 1000)
        let date = LocalDay(epochMillisUTC: millis)
        return HalvingEstimate(date: date, height: next, remainingBlocks: remaining, avgBlockSeconds: avgBlockSeconds)
    }

    /// Blockhöhe und mittlere Blockzeit der laufenden Schwierigkeitsperiode von mempool.space:
    /// `GET /api/blocks/tip/height` und `GET /api/v1/difficulty-adjustment` («timeAvg» in ms). nil bei Fehler.
    static func fetchBlocks() async -> (height: Int, avgBlockSeconds: Double)? {
        guard let heightText = try? await ExchangeHTTP.call("https://mempool.space/api/blocks/tip/height"),
              let height = Int(heightText.trimmingCharacters(in: .whitespacesAndNewlines)),
              let adjText = try? await ExchangeHTTP.call("https://mempool.space/api/v1/difficulty-adjustment"),
              let json = try? JObject(string: adjText) else { return nil }
        let avg = json.optDouble("timeAvg")
        guard avg.isFinite, avg > 0 else { return nil }
        return (height, avg / 1000)
    }
}

