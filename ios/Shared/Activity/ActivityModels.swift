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
    /// Volumen-Verhältnis nur genähert: Die Quelle liefert keinen Quote-Umsatz (z. B. Coinbase),
    /// verglichen wird dann die gehandelte Menge in Coins statt des Umsatzes (wie Android).
    var volumeApproximate: Bool = false
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
///  - FUNDING_EXTREME: value = Funding Rate in % je Periode der Börse (mit Vorzeichen, so angezeigt),
///    factor = Abrechnungsintervall in Stunden, wenn nicht 8 (Hyperliquid: 1), sonst nil — die Schwellen
///    vergleichen auf 8 Stunden umgerechnet (`ActivityAnalyzer.fundingPer8h`)
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
/// `source` = Futures-Quelle («Binance Futures», «Bybit», «OKX»).
struct OiSample: Equatable, Sendable {
    let units: Double
    let time: Int64
    let source: String
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
///  - PEG:             value = Abweichung des Stablecoins von 1:1 in % (mit Vorzeichen, −0.12 = 0,12 % darunter)
///
/// Bei den Markt-Gründen sagt `WhyReason.ref`, welche Referenz «BTC» oben meint (BTC, ETH oder der
/// breite Markt); `WhyReason.threshold` ist die Schwelle, gegen die verglichen wurde.
enum WhyReasonKind: String, Sendable {
    case MARKET_WIDE, COIN_ONLY, AGAINST_MARKET, MARKET_CALM, MARKET_LEADER
    case VOLUME_HIGH, VOLUME_LOW, VOLUME_NORMAL
    case LEVERAGE_LONGS, LEVERAGE_SHORTS, LEVERAGE_BALANCED
    case VOLATILITY_HIGH, VOLATILITY_NORMAL
    case SENTIMENT
    case PEG
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
    /// Markt-Gründe: welche Referenz der Marktwert meint; nil = BTC (wie bisher).
    var ref: MarketRef? = nil
    /// Schwelle, gegen die `value` (Markt: die Referenz, Coin allein/gegen den Markt: der Coin)
    /// verglichen wurde — relativ zur eigenen Schwankung (`RelativeMove`); nil = feste Schwelle.
    var threshold: Double? = nil
    /// Volumen: nur genähert (`HourStats.volumeApproximate`).
    var approximate: Bool = false
    /// LEVERAGE_*: Abrechnungsintervall des Fundings in Stunden, wenn nicht 8 (Hyperliquid: 1); nil = 8.
    /// `value` bleibt der Satz der Börse, die Schwellen vergleichen auf 8 Stunden umgerechnet.
    var intervalHours: Int? = nil
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
    /// 24-h-Veränderung in % aus dem Ticker des Paars (dieselbe wie Änderung und Merkliste);
    /// nil = unbekannt, dann aus den Kerzen.
    var tickerChange24h: Double? = nil
    /// false = das Paar wird an seiner Börse nicht mehr gehandelt (`NotTraded`):
    /// dann gar keine Einordnung — nie ein Urteil auf alten Daten.
    var marketLive: Bool = true
    /// Tageskerzen des Paars (letzte = laufender Tag) für das 30-Tage-Hoch; nil = keine.
    var dailyCandles: [MarketCandle]? = nil
    /// Quote des Paars (für die Stablecoin-Erkennung `StablePeg`); nil = unbekannt.
    var quoteAsset: String? = nil
    /// Stundenkerzen von ETHUSDT als zweite Markt-Referenz (nicht für BTC und ETH selbst); nil = keine.
    var ethCandles: [MarketCandle]? = nil
    /// Breiter Markt (Median der CoinGecko-Top-Coins, `BroadMarket`); nil = keiner.
    var broadMarket: BroadMarketSnapshot? = nil
    /// Minuten seit der ältesten verglichenen Open-Interest-Messung (`OiSeries`); nil = kein Vergleich.
    var openInterestMinutes: Int? = nil
    /// false = Open Interest bewegte sich gegenüber den älteren Messungen nicht einheitlich (`OiSeries`).
    var openInterestConsistent: Bool = true
    /// Abgerechnete Funding Rates der letzten Perioden (`FundingTrendRules`); nil = keine.
    var fundingHistory: FundingHistory? = nil
    /// Quelle der aktuellen Funding Rate («Binance Futures», «Bybit», «OKX»); nil = unbekannt.
    var fundingSource: String? = nil
    /// Abrechnungsintervall der aktuellen Rate laut Börse in Stunden; nil = nicht gemeldet.
    var fundingIntervalHours: Int? = nil
    /// Herkunft der Daten für die Zeile «Datenstand» und die Quellen (`WhySources`).
    var sources: WhySources? = nil
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
    /// Höchster Kurs der letzten 30 Tage (`ActivityAnalyzer.high30d`); nil ohne Tageskerzen.
    var high30d: Double? = nil
    /// Läuft der Coin gerade eng mit Bitcoin bzw. unabhängig (`BtcCorrelation`); nil = kein Satz.
    var btcLink: BtcLink? = nil
    /// Tiefster Kurs der letzten 30 Tage (`ActivityAnalyzer.low30d`); nil ohne Tageskerzen.
    var low30d: Double? = nil
    /// Lage in den letzten drei Tagen, nur wenn auffällig (`Position3d`); nil = nichts zu sagen.
    var position3d: Position3d? = nil
    /// Verlauf der Funding Rate (`FundingTrendRules`); nil = keiner.
    var fundingTrend: FundingTrend? = nil
    /// Minuten seit der ältesten verglichenen Open-Interest-Messung; nil = kein Vergleich.
    var oiMinutes: Int? = nil
    /// false = Open Interest bewegte sich gegenüber den älteren Messungen nicht einheitlich.
    var oiConsistent: Bool = true
    /// Start der laufenden Stundenkerze = Ende der letzten abgeschlossenen (Epoch-ms); nil ohne Kerzen.
    var candleTime: Int64? = nil
    /// Stablecoin gegen USD/Stablecoin (`StablePeg`): Abweichung von 1:1 statt Markt und Schwankung.
    var stable: Bool = false
    /// Herkunft der Daten (`WhySources`); nil = unbekannt (dann der feste Quellen-Satz).
    var sources: WhySources? = nil
}
