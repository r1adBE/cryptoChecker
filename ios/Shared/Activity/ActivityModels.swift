import Foundation

// Daten für «Ungewöhnliche Aktivität» und «Warum bewegt sich das?» — wie
// `domain/activity/ActivityModels.kt`. Kerzen sind `MarketCandle` (Stundenkerzen,
// aufsteigend; die LETZTE Kerze ist die laufende, noch nicht abgeschlossene Stunde).

/// Kennzahlen der letzten abgeschlossenen Stunde.
struct HourStats: Equatable, Sendable {
    /// Rendite der letzten abgeschlossenen Stunde in Prozent (Schluss zu Schluss).
    let movePercent: Double
    /// z-Wert dieser Rendite gegenüber den 24 Stunden davor.
    let zScore: Double
    /// Volumen dieser Stunde geteilt durch den Schnitt der 24 Stunden davor; nil ohne Volumen.
    let volumeRatio: Double?
    /// Startzeit der bewerteten Stunde (Epoch-ms).
    let candleOpenTime: Int64
}

/// Arten von «hier passiert gerade etwas». Reihenfolge = Rang bei gleicher Stärke.
/// Rohwerte = Kotlin-Enum-Namen (gespeichert).
enum ActivitySignalKind: String, CaseIterable, Sendable, Codable {
    case PRICE_MOVE, VOLUME_SPIKE, OPEN_INTEREST_JUMP, FUNDING_EXTREME

    var ordinal: Int { Self.allCases.firstIndex(of: self) ?? 0 }
}

/// Stärke eines Signals; STRONG wird zuerst genannt.
enum ActivitySignalSeverity: String, CaseIterable, Sendable, Codable {
    case NOTABLE, STRONG

    var ordinal: Int { Self.allCases.firstIndex(of: self) ?? 0 }
}

/// Ein erkanntes Signal.
///
/// Bedeutung von `value` / `factor` je Art:
///  - PRICE_MOVE: value = Bewegung der letzten Stunde in % (mit Vorzeichen), factor = |z|
///  - VOLUME_SPIKE: value = Volumen-Verhältnis (z. B. 4.2), factor = nil
///  - OPEN_INTEREST_JUMP: value = Veränderung in % (mit Vorzeichen), factor = Minuten seit der Vergleichsmessung
///  - FUNDING_EXTREME: value = Funding Rate in % je Periode (mit Vorzeichen), factor = nil
struct ActivitySignal: Equatable, Sendable {
    let kind: ActivitySignalKind
    let severity: ActivitySignalSeverity
    let value: Double
    var factor: Double? = nil
    /// Zuletzt erkannt (Epoch-ms); das Signal gilt bis `ActivityAnalyzer.signalTtlMillis` danach.
    var seenAt: Int64 = 0
}

/// Ergebnis der Prüfung eines Paars.
struct ActivityReport: Equatable, Sendable {
    let signals: [ActivitySignal]
    /// Zeitpunkt der letzten Prüfung (Epoch-ms) — Grundlage für den 10-Minuten-Cache.
    let computedAt: Int64

    /// Noch gültige Signale, stärkstes zuerst.
    func active(now: Int64) -> [ActivitySignal] {
        ActivityAnalyzer.sortedSignals(signals.filter {
            let age = now - $0.seenAt
            return age >= 0 && age <= ActivityAnalyzer.signalTtlMillis
        })
    }
}

/// Gespeicherte Open-Interest-Messung (in Coins, nicht USD — Kursbewegungen zählen so nicht mit).
struct OiSample: Equatable, Sendable {
    let units: Double
    let time: Int64
}

/// Ergebnis des Open-Interest-Vergleichs.
struct OiUpdate: Equatable, Sendable {
    /// Veränderung in %, nil wenn kein brauchbarer Vergleichswert (zu jung/zu alt/fehlt).
    let changePercent: Double?
    /// Minuten seit der Vergleichsmessung, sofern verglichen.
    let minutes: Int?
    /// Neue Messung speichern?
    let store: Bool
}

// MARK: «Warum bewegt sich das?»

/// Art eines Grundes. Bedeutung von `WhyReason.value` / `secondary`:
///  - MARKET_WIDE:     value = BTC 24h %, secondary = Coin 24h %
///  - COIN_ONLY:       value = Coin 24h %, secondary = BTC 24h %
///  - AGAINST_MARKET:  value = Coin 24h %, secondary = BTC 24h %
///  - MARKET_CALM:     value = BTC 24h %, secondary = Coin 24h %
///  - MARKET_LEADER:   value = BTC 24h %, secondary = ETH 24h % (oder nil)
///  - VOLUME_HIGH / VOLUME_LOW / VOLUME_NORMAL: value = Volumen-Verhältnis letzte Stunde
///  - LEVERAGE_LONGS / LEVERAGE_SHORTS / LEVERAGE_BALANCED: value = Funding %, secondary = Open-Interest-Veränderung % (oder nil)
///  - VOLATILITY_HIGH / VOLATILITY_NORMAL: value = |z| (Faktor gegenüber üblich), secondary = Bewegung letzte Stunde %
///  - SENTIMENT:       value = Fear & Greed (0–100), secondary = Veränderung gegenüber gestern (oder nil)
enum WhyReasonKind: String, Sendable {
    case MARKET_WIDE, COIN_ONLY, AGAINST_MARKET, MARKET_CALM, MARKET_LEADER
    case VOLUME_HIGH, VOLUME_LOW, VOLUME_NORMAL
    case LEVERAGE_LONGS, LEVERAGE_SHORTS, LEVERAGE_BALANCED
    case VOLATILITY_HIGH, VOLATILITY_NORMAL
    case SENTIMENT
}

/// Grundton für Symbolfarbe: steigend, fallend, neutral oder Vorsicht.
enum WhyReasonTone: Sendable {
    case up, down, neutral, warning
}

/// Ein Grund als Daten; Text und Zahlenformat macht die Oberfläche.
struct WhyReason: Equatable, Sendable {
    let kind: WhyReasonKind
    let tone: WhyReasonTone
    let value: Double
    var secondary: Double? = nil
    /// Auffällig — wird vor den ruhigen Gründen gezeigt.
    var strong: Bool = false
}

/// Fear-&-Greed-Stufe (wie im Markt-Tab).
enum FearGreedLevel: Sendable {
    case extremeFear, fear, neutral, greed, extremeGreed
}

/// Eingaben für die Einordnung eines Paars. Alles optional — was fehlt, entfällt.
struct WhyInput: Sendable {
    let baseAsset: String
    /// Stundenkerzen des Paars (letzte = laufende), nil wenn keine Quelle es führt.
    let candles: [MarketCandle]?
    /// Stundenkerzen der Referenz: BTCUSDT, bei Bitcoin selbst ETHUSDT.
    let referenceCandles: [MarketCandle]?
    /// Funding Rate in % je Periode, nil ohne Futures.
    let fundingPercent: Double?
    /// Open-Interest-Veränderung in %, nil ohne Vergleichswert.
    let openInterestChangePercent: Double?
    let fearGreed: Int?
    let fearGreedYesterday: Int?
    let now: Int64
}

/// Ergebnis für das «Warum»-Blatt.
struct WhyReport: Equatable, Sendable {
    /// Letzter Kurs (laufende Kerze), nil ohne Kerzen.
    let price: Double?
    let change1h: Double?
    let change24h: Double?
    /// 2–5 Gründe, auffällige zuerst (höchstens `ActivityAnalyzer.maxReasons`).
    let reasons: [WhyReason]
    /// false = keine Quelle führt das Paar (Leerzustand zeigen).
    let hasMarketData: Bool
    /// Zeitpunkt der Daten (Epoch-ms).
    let dataTime: Int64
}
