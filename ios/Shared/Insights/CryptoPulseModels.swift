import Foundation

// Eingaben und Ergebnis von «Crypto Pulse» (`CryptoPulse`): Bereiche, Stufen mit Text-Schlüsseln, Leitsatz und
// Bericht — wie die Datentypen in `domain/market/CryptoPulse.kt` (Android).

/// Eingaben. Alles optional — fehlende Bereiche entfallen.
struct PulseInput: Equatable, Sendable {
    /// 24-h-Veränderung in % (Binance-Ticker, sonst Coinbase).
    let btc: Double?
    let eth: Double?
    let sol: Double?
    /// BTC-Volumen der letzten abgeschlossenen Stunde ÷ Schnitt der 24 Stunden davor.
    let volumeRatio: Double?
    /// Fear & Greed (0–100).
    let fearGreed: Int?
    /// BTC-Funding in % je Periode (8 h), wie im «Warum»-Blatt.
    let fundingPercent: Double?
    /// Ethereum-Gebühr (normale Stufe) in gwei.
    let ethGasGwei: Double?
    /// 24-h-Veränderung der grössten Coins ohne Stablecoins und Doppelgänger (Rang-Reihenfolge);
    /// leer = unbekannt.
    var topChanges: [Double] = []
    /// Marktkapitalisierung aller Coins in USD (CoinGecko `/global`).
    var marketCapUsd: Double? = nil
    /// Veränderung der Marktkapitalisierung über 24 h in %.
    var marketCap24h: Double? = nil
}

/// Marktbreite: wie viele der grössten Coins über 24 h steigen bzw. fallen.
struct PulseBreadth: Equatable, Sendable {
    let up: Int
    let down: Int
    let total: Int
}

/// Satz zur Marktbreite, nur in deutlichen Fällen.
enum PulseBreadthNote: Equatable, Sendable {
    case broadUp, broadDown, narrowUp, narrowDown

    var key: String {
        switch self {
        case .broadUp: "pulse_breadth_note_broad_up"
        case .broadDown: "pulse_breadth_note_broad_down"
        case .narrowUp: "pulse_breadth_note_narrow_up"
        case .narrowDown: "pulse_breadth_note_narrow_down"
        }
    }
}

/// Eine Zeile «Markt heute», z. B. Bitcoin +3,2 %.
struct PulseCoinLine: Equatable, Sendable {
    let name: String
    let changePercent: Double
}

enum PulseAlts: Equatable, Sendable {
    case stronger, weaker, even

    var key: String {
        switch self {
        case .stronger: "pulse_alts_stronger"
        case .weaker: "pulse_alts_weaker"
        case .even: "pulse_alts_even"
        }
    }
}

enum PulseVolume: Equatable, Sendable {
    case high, low, normal
}

enum PulseFunding: Equatable, Sendable {
    case high, slight, neutral, negative
}

enum PulseGas: Equatable, Sendable {
    case low, normal, high
}

/// Schlagzeile: Richtung aus `MarketMoveScale.decide` (broadUp/broadDown = steigend/fallend, egal ob
/// leicht oder deutlich), mit hohem BTC-Volumen als eigene Variante.
enum PulseSummary: Equatable, Sendable {
    case broadUp, broadUpVolume, broadDown, broadDownVolume, mixed, calm
}

/// Erster Satz unter der Schlagzeile («Was gerade auffällt»), wie `PulseLeadKind` in Android.
enum PulseLeadKind: Equatable, Sendable {
    case btcLeads, altsStronger, btcStronger, broadUp, broadDown, driftUp, driftDown, mixed, calm

    var key: String {
        switch self {
        case .btcLeads: "pulse_lead_btc_leads"
        case .altsStronger: "pulse_lead_alts_stronger"
        case .btcStronger: "pulse_lead_btc_stronger"
        case .broadUp: "pulse_lead_broad_up"
        case .broadDown: "pulse_lead_broad_down"
        case .driftUp: "pulse_lead_drift_up"
        case .driftDown: "pulse_lead_drift_down"
        case .mixed: "pulse_lead_mixed"
        case .calm: "pulse_lead_calm"
        }
    }
}

/// Zweiter Satz: Volumen und/oder Funding, je Fall ein ganzer Satz.
/// Die `volAbove…`- und `volBelow…`-Fälle tragen `PulseLead.volumePercent` als Platzhalter.
enum PulseDetail: Equatable, Sendable {
    case volAbove, volAboveFundNeutral, volAboveFundHigh, volAboveFundNegative
    case volBelow, volBelowFundNeutral, volBelowFundHigh, volBelowFundNegative
    case volNormal, volNormalFundNeutral, volNormalFundHigh, volNormalFundNegative
    case fundNeutral, fundHigh, fundNegative

    var key: String {
        switch self {
        case .volAbove: "pulse_detail_vol_above"
        case .volAboveFundNeutral: "pulse_detail_vol_above_fund_neutral"
        case .volAboveFundHigh: "pulse_detail_vol_above_fund_high"
        case .volAboveFundNegative: "pulse_detail_vol_above_fund_negative"
        case .volBelow: "pulse_detail_vol_below"
        case .volBelowFundNeutral: "pulse_detail_vol_below_fund_neutral"
        case .volBelowFundHigh: "pulse_detail_vol_below_fund_high"
        case .volBelowFundNegative: "pulse_detail_vol_below_fund_negative"
        case .volNormal: "pulse_detail_vol_normal"
        case .volNormalFundNeutral: "pulse_detail_vol_normal_fund_neutral"
        case .volNormalFundHigh: "pulse_detail_vol_normal_fund_high"
        case .volNormalFundNegative: "pulse_detail_vol_normal_fund_negative"
        case .fundNeutral: "pulse_detail_fund_neutral"
        case .fundHigh: "pulse_detail_fund_high"
        case .fundNegative: "pulse_detail_fund_negative"
        }
    }

    /// Satz mit Prozent-Platzhalter?
    var hasPercent: Bool {
        switch self {
        case .volAbove, .volAboveFundNeutral, .volAboveFundHigh, .volAboveFundNegative,
             .volBelow, .volBelowFundNeutral, .volBelowFundHigh, .volBelowFundNegative: true
        default: false
        }
    }
}

/// Schlüssel + Argumente des Leitsatzes; Text macht die Oberfläche.
struct PulseLead: Equatable, Sendable {
    let kind: PulseLeadKind
    /// nil = weder Volumen noch Funding bekannt → nur ein Satz.
    let detail: PulseDetail?
    /// Abweichung vom üblichen Volumen in ganzen Prozent, ohne Vorzeichen (1.34 → 34).
    let volumePercent: Int
}

/// Zeile der Faktor-Checkliste unter «Warum?». Nur Werte ohne eigene Karte im
/// Markt-Tab: Volumen (Krypto-Markt), Fear & Greed und Gas haben eigene Karten.
enum PulseFactorKind: Equatable, Sendable {
    case funding
}

struct PulseFactor: Equatable, Sendable {
    let kind: PulseFactorKind
    let mark: WhySummary.Mark
}

/// Ergebnis; `nil` von `CryptoPulse.evaluate` = Marktdaten fehlen (`pulse_unavailable`).
struct PulseReport: Equatable, Sendable {
    let coins: [PulseCoinLine]
    let alts: PulseAlts
    let volume: PulseVolume?
    let volumeRatio: Double?
    let fearGreed: Int?
    let funding: PulseFunding?
    /// Funding in % je Periode (für die Checkliste).
    let fundingPercent: Double?
    let gas: PulseGas?
    let gasGwei: Double?
    let summary: PulseSummary
    /// nil = zu wenige Werte, Zeile entfällt.
    var breadth: PulseBreadth? = nil
    var breadthNote: PulseBreadthNote? = nil
    /// nil = Zeile «Krypto-Markt» entfällt.
    var marketCapUsd: Double? = nil
    var marketCap24h: Double? = nil
}
