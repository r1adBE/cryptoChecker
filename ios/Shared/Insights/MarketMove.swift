import Foundation

// Richtung und Stärke des Markts über 24 h — die eine Stelle, an der «kaum», «leicht»/«etwas» und
// «deutlich» festgelegt sind. Schlagzeile und Leitsatz des Pulses (`CryptoPulse`) und der Satz zum
// Gesamtwert in «Kurz gesagt» (`MarketPlainSummary`) nutzen dieselben Schwellen, damit nie «leicht»
// neben «deutlich» für dieselbe Richtung steht. Reine Regeln; wie `domain/market/MarketMove.kt` (Android).

/// Stärke einer 24-h-Veränderung (Betrag in %); Rohwerte wie die Android-Enum (Testfälle).
enum MarketMoveLevel: String, Equatable, Sendable {
    case flat = "FLAT", slight = "SLIGHT", clear = "CLEAR"
}

/// Richtung für die Schlagzeile des Pulses.
enum MarketMoveDirection: String, Equatable, Sendable {
    case up = "UP", down = "DOWN", mixed = "MIXED", calm = "CALM"
}

/// Ergebnis von `MarketMoveScale.decide`; `level` nur bei up/down (slight oder clear), sonst nil.
struct MarketMove: Equatable, Sendable {
    let direction: MarketMoveDirection
    let level: MarketMoveLevel?
}

enum MarketMoveScale {
    /// Grosswerte (BTC, ETH, SOL) gelten als gleichgerichtet, wenn alle drei betragsmässig über dieser
    /// Rauschschwelle in dieselbe Richtung gehen (genau 0.25 zählt nicht).
    static let noisePercent = 0.25

    /// Darunter «kaum verändert» bzw. «ruhig». Gleicher Wert wie die bisherige Ruhe-Schwelle der
    /// Grosswerte und die «kaum»-Schwelle des Gesamtwerts.
    static let flatBelowPercent = 1.0

    /// Ab hier «deutlich» statt «leicht»/«etwas». Gemessen an der Veränderung des Gesamtmarkts
    /// (Marktkapitalisierung aller Coins); fehlt sie, am Schnitt von BTC, ETH und SOL. Gewählt ist der
    /// Wert des Gesamtwert-Satzes (3 %), nicht die frühere Einzelcoin-Schwelle 1.5 %: Der Gesamtmarkt
    /// schwankt weniger als einzelne Coins, mit 1.5 % hiesse etwa jeder zweite Tag «deutlich».
    static let clearFromPercent = 3.0

    /// Ab diesem Anteil steigender bzw. fallender Coins der Top-Liste neigt die Breite in eine Richtung.
    static let breadthLeanShare = 0.6

    static func level(_ change: Double) -> MarketMoveLevel {
        let size = abs(change)
        if size < flatBelowPercent { return .flat }
        return size >= clearFromPercent ? .clear : .slight
    }

    /// Schlagzeile aus Grosswerten, Gesamtmarkt (`marketChange`, nil = unbekannt) und Breite
    /// (`breadth`, nil = unbekannt) — gleiche Reihenfolge wie Android `MarketMoveScale.decide`:
    /// 1. Ruhig: alle drei Grosswerte und der Gesamtmarkt (falls bekannt) unter `flatBelowPercent`.
    /// 2. Grosswerte gleichgerichtet (über `noisePercent`) → deren Richtung, nie «gemischt».
    /// 3. Sonst entscheidet die Breite (ab `breadthLeanShare`); widerspricht ihr der Gesamtmarkt
    ///    (mindestens `flatBelowPercent` in die Gegenrichtung) oder liegt sie um 50 % → gemischt.
    ///    Ohne Breite: Gesamtmarkt ab `flatBelowPercent`, sonst gemischt.
    /// Stärke: `level` des Gesamtmarkts (sonst Schnitt der Grosswerte); «deutlich» nur, wenn dieser
    /// Wert in dieselbe Richtung zeigt.
    static func decide(btc: Double, eth: Double, sol: Double, marketChange: Double?,
                       breadth: PulseBreadth?) -> MarketMove {
        let big = [btc, eth, sol]
        let market = marketChange.flatMap { $0.isFinite ? $0 : nil }
        let calmMarket = market.map { abs($0) < flatBelowPercent } ?? true
        if calmMarket && big.allSatisfy({ abs($0) < flatBelowPercent }) {
            return MarketMove(direction: .calm, level: nil)
        }
        guard let sign = direction(big: big, market: market, breadth: breadth) else {
            return MarketMove(direction: .mixed, level: nil)
        }
        let value = market ?? (btc + eth + sol) / 3
        let sameWay = sign > 0 ? value > 0 : value < 0
        let strength: MarketMoveLevel = sameWay && level(value) == .clear ? .clear : .slight
        return MarketMove(direction: sign > 0 ? .up : .down, level: strength)
    }

    /// +1 steigend, −1 fallend, nil = gemischt.
    private static func direction(big: [Double], market: Double?, breadth: PulseBreadth?) -> Int? {
        if big.allSatisfy({ $0 > noisePercent }) { return 1 }
        if big.allSatisfy({ $0 < -noisePercent }) { return -1 }
        var marketVote = 0
        if let market {
            if market >= flatBelowPercent { marketVote = 1 } else if market <= -flatBelowPercent { marketVote = -1 }
        }
        guard let breadth, breadth.total > 0 else { return marketVote == 0 ? nil : marketVote }
        let lean = breadthVote(breadth)
        if lean == 0 || marketVote == -lean { return nil }
        return lean
    }

    /// +1 / −1 ab `breadthLeanShare` steigend bzw. fallend, sonst 0; mit Toleranz für Anteile (18 / 30 gegen 0.6).
    private static func breadthVote(_ breadth: PulseBreadth) -> Int {
        if Double(breadth.up) / Double(breadth.total) >= breadthLeanShare - 1e-9 { return 1 }
        if Double(breadth.down) / Double(breadth.total) >= breadthLeanShare - 1e-9 { return -1 }
        return 0
    }
}
