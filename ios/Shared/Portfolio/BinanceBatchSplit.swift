import Foundation

/// Binance-Sammelabfrage der Portfolio-Kurse (`ticker/price?symbols=[…]`) robust gegen unbekannte Symbole: Binance lehnt
/// die ganze Anfrage mit HTTP 400 ab, sobald ein Symbol unbekannt ist. Dann wird der Teil halbiert und jede Hälfte neu
/// abgefragt, bis hinunter zu einzelnen Symbolen. Ein einzelnes Symbol mit 400 = Binance führt es nicht: kein Kurs von
/// Binance, aber KEIN Fehler (`Outcome.unlisted`). Andere Fehler (Netz, Zeitgrenze, 403/451, 429) = `Outcome.failed`,
/// ohne Halbieren. Zusätzliche Abfragen durch das Halbieren höchstens `extraRequests`; was danach offen bleibt, geht
/// ohne Fehler an die Kerzen-Kette. So bekommt ein Binance-Coin (z. B. IOTA) seinen Kurs auch bei der ersten
/// Aktualisierung ohne Coin-Liste, wenn andere gehaltene Coins bei Binance fehlen.
///
/// Reine Regel mit eingesetzter Abfrage; wie `BinanceBatchSplit.kt` (Android), Fixture `binance_batch_split.json`.
enum BinanceBatchSplit {
    /// Höchstens so viele Symbole je Abfrage.
    static let maxSymbols = 100
    /// Zusätzliche Abfragen je Sammelabfrage durch das Halbieren (reicht für einige unbekannte Coins).
    static let extraRequests = 40

    /// Antwort auf eine Abfrage eines Teils.
    enum Reply: Sendable, Equatable {
        /// Gelesene Kurse (Coin → Kurs); fehlende Coins haben keinen.
        case prices([String: Double])
        /// HTTP 400: mindestens ein Symbol des Teils ist Binance unbekannt.
        case rejected
        /// Netz, Zeitgrenze, Sperre oder Bremse: kein Beleg, dass es keinen Kurs gibt.
        case failed
    }

    /// `prices`: Coin → Kurs; `failed`: mindestens eine Abfrage scheiterte (nicht 400); `unlisted`: einzeln mit 400
    /// abgelehnt (Binance führt sie nicht); `requests`: Anzahl Abfragen.
    struct Outcome: Sendable, Equatable {
        var prices: [String: Double] = [:]
        var failed = false
        var unlisted: Set<String> = []
        var requests = 0
    }

    /// Kurse für `coins` in Teilen zu höchstens `maxSymbols`; abgelehnte Teile werden halbiert (zuerst die erste Hälfte,
    /// Tiefe zuerst — fester Ablauf wie Android), höchstens `budget` zusätzliche Abfragen.
    static func fetch(_ coins: [String],
                      maxSymbols: Int = BinanceBatchSplit.maxSymbols,
                      budget: Int = BinanceBatchSplit.extraRequests,
                      request: ([String]) async -> Reply) async -> Outcome {
        var seen = Set<String>()
        let unique = coins.filter { seen.insert($0).inserted }
        let size = max(1, maxSymbols)
        // Teil und ob er aus dem Halbieren stammt (zählt zum Budget)
        var pending: [(part: [String], fromSplit: Bool)] = stride(from: 0, to: unique.count, by: size).map {
            (Array(unique[$0..<min($0 + size, unique.count)]), false)
        }
        var out = Outcome()
        var extra = 0
        while !pending.isEmpty {
            let (part, fromSplit) = pending.removeFirst()
            if fromSplit {
                // Budget aufgebraucht: offen lassen (Kerzen-Kette), nicht als Fehler werten
                if extra >= budget { continue }
                extra += 1
            }
            out.requests += 1
            switch await request(part) {
            case .prices(let prices):
                for (coin, price) in prices where part.contains(coin) { out.prices[coin] = price }
            case .failed:
                out.failed = true
            case .rejected:
                if part.count == 1 {
                    out.unlisted.insert(part[0])
                } else {
                    let half = (part.count + 1) / 2
                    pending.insert((Array(part[half...]), true), at: 0)
                    pending.insert((Array(part[..<half]), true), at: 0)
                }
            }
        }
        return out
    }
}
