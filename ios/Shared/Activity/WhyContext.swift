import Foundation

// «Warum?»-Zusammenhang — wie `domain/activity/WhyContext.kt`: Markt-Referenzen, Herkunft der
// Daten, Lage in drei Tagen, relative Schwellen und Stablecoins.

/// Markt-Referenz im «Warum?»-Blatt: Bitcoin, Ether oder der breite Markt (Top-Coins).
/// Rohwerte = Kotlin-Enum-Namen.
enum MarketRef: String, Sendable, Equatable {
    case BTC, ETH, BROAD
}

/// Wie die Futures-Quelle zum Paar steht (für die Herkunftsangabe).
enum FuturesSourceNote: String, Sendable, Equatable {
    /// Die erste Quelle des Paars war nicht erreichbar, eine andere Börse sprang ein.
    case FALLBACK
    /// Spot-Paar oder Kontrakt einer anderen Börse: Kennzahlen des USDT-Perpetuals als Richtwert.
    case REFERENCE
}

/// Herkunft der Daten im «Warum?»-Blatt — für «Datenstand» unter «Beobachtung» und die Quellenzeile
/// (wie `WhySources` in Android).
struct WhySources: Equatable, Sendable {
    /// Anbieter der Stundenkerzen des Paars.
    var candles: String? = nil
    /// Kerzen kommen nicht von der ersten Quelle der Kette (Binance), sondern von einer Ausweichquelle.
    var candlesFallback: Bool = false
    /// Anbieter der Vergleichskerzen (BTC/ETH); nur genannt, wenn anders als `candles`.
    var reference: String? = nil
    /// Futures-Quelle («Binance Futures», «Bybit», «OKX»).
    var futures: String? = nil
    var futuresNote: FuturesSourceNote? = nil
    /// Zeitpunkt des Fear-&-Greed-Werts (Epoch-ms, Tagesbeginn laut alternative.me).
    var fearGreedTime: Int64? = nil
    /// Stand des breiten Markts (`BroadMarketSnapshot.time`); nil = nicht genutzt.
    var broadTime: Int64? = nil
}

/// Lage in den letzten drei Tagen (`Position3d`). Rohwerte = Kotlin-Enum-Namen.
enum Position3dKind: String, Sendable, Equatable {
    case NEAR_HIGH, NEAR_LOW, BELOW_HIGH, ABOVE_LOW
}

/// Lage des Kurses in den letzten 72 Stunden, nur wenn auffällig.
/// `percent`: NEAR_HIGH/BELOW_HIGH Abstand zum 3-Tage-Hoch in % (≤ 0); NEAR_LOW/ABOVE_LOW Abstand
/// zum 3-Tage-Tief in % (≥ 0). `threshold`: Schwelle, ab der ein Abstand auffällt (in %).
struct Position3d: Equatable, Sendable {
    let kind: Position3dKind
    let percent: Double
    let threshold: Double
}

/// Bewegungen relativ zur eigenen Schwankung — wie `RelativeMove` in Android.
///
/// Eine 24-h-Bewegung gilt als deutlich, wenn sie mindestens k-mal so gross ist wie die übliche
/// 24-h-Schwankung des Coins (bzw. der Referenz) UND eine feste Untergrenze überschreitet:
///
///   Schwelle = max(Untergrenze, k · σ₁ₕ · √24)
///
/// σ₁ₕ ist die Standardabweichung (Stichprobe) der Stunden-Renditen VOR dem bewerteten 24-h-Fenster
/// (höchstens 72, mindestens 24 Stunden) — so bläht die Bewegung selbst ihre Schwelle nicht auf.
/// √24 rechnet die Stunden- auf die Tagesschwankung um (Zufallsweg-Näherung). Gibt es zu wenig
/// Verlauf, gelten die festen Schwellen.
enum RelativeMove {
    /// Mindestens so viele Stunden-Renditen für σ, sonst feste Schwelle.
    static let minReturns = 24
    /// Höchstens so viele (die jüngsten vor dem Fenster).
    static let maxReturns = 72
    /// Vielfaches der üblichen Schwankung für «dieser Coin bewegt sich deutlich» (streng: Coin allein ist eine starke Aussage).
    static let kCoin = 2.0
    /// Vielfaches der üblichen Schwankung für «der Markt (BTC/ETH) bewegt sich deutlich».
    static let kMarket = 1.5
    /// Untergrenze für den Coin über 24 h in % — darunter ist es Rauschen, auch bei sehr ruhigen Coins.
    static let coinFloorPercent = 2.0
    /// Untergrenze für den Markt über 24 h in %.
    static let marketFloorPercent = 1.0
    /// Stark ab diesem Vielfachen der Schwelle (vorher 3 % bei 1,5 %).
    static let strongFactor = 2.0
    /// Tagesfenster der Bewegung in Stunden (√24 für die Umrechnung).
    static let moveHours = 24

    /// Standardabweichung (in %) der Stunden-Renditen abgeschlossener Kerzen, deren Stunde bis
    /// `endAt` (Epoch-ms) zu Ende ist — die jüngsten bis `maxReturns`. Nur Renditen über genau
    /// eine Stunde (Lücken zählen nicht). nil bei weniger als `minReturns` Renditen.
    static func hourlySigma(_ candles: [MarketCandle]?, endAt: Int64) -> Double? {
        guard let candles, candles.count >= 3 else { return nil }
        // Die letzte Kerze läuft noch
        let closed = Array(candles.dropLast())
        let hour = ActivityAnalyzer.hourMillis
        var returns: [Double] = []
        var i = closed.count - 1
        while i >= 1 && returns.count < maxReturns {
            let cur = closed[i]
            let prev = closed[i - 1]
            i -= 1
            if cur.openTime + hour > endAt { continue }
            if cur.openTime - prev.openTime != hour { continue }
            if !(prev.close > 0) || !(cur.close > 0) { continue }
            let r = (cur.close / prev.close - 1) * 100
            if r.isFinite { returns.append(r) }
        }
        guard returns.count >= minReturns else { return nil }
        let mean = returns.reduce(0, +) / Double(returns.count)
        let variance = returns.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Double(returns.count - 1)
        let sigma = variance.squareRoot()
        return sigma.isFinite ? max(sigma, ActivityAnalyzer.minSigmaPercent) : nil
    }

    /// σ vor dem 24-h-Fenster, das bei `now` endet.
    static func sigmaBefore24h(_ candles: [MarketCandle]?, now: Int64) -> Double? {
        hourlySigma(candles, endAt: now - Int64(moveHours) * ActivityAnalyzer.hourMillis)
    }

    /// Schwelle für eine 24-h-Bewegung: max(`floor`, `k` · σ · √24); ohne σ `fallback`.
    static func threshold24h(sigma: Double?, k: Double, floor: Double, fallback: Double) -> Double {
        guard let sigma else { return fallback }
        return max(floor, k * sigma * Double(moveHours).squareRoot())
    }

    /// Schwelle für den Coin (k = 2, Untergrenze 2 %, sonst 3 %).
    static func coinThreshold(_ sigma: Double?) -> Double {
        threshold24h(sigma: sigma, k: kCoin, floor: coinFloorPercent, fallback: ActivityAnalyzer.coinMovePercent)
    }

    /// Schwelle für eine Markt-Referenz mit Kerzen (k = 1,5, Untergrenze 1 %, sonst 1,5 %).
    static func marketThreshold(_ sigma: Double?) -> Double {
        threshold24h(sigma: sigma, k: kMarket, floor: marketFloorPercent, fallback: ActivityAnalyzer.marketMovePercent)
    }
}

/// Stablecoins — wie `StablePeg` in Android: Ein Stablecoin gegen USD oder einen anderen
/// USD-Stablecoin (z. B. USDC/USDT) soll 1:1 stehen. Statt %-Bewegungen als Ausreisser zu werten,
/// beschreibt «Warum?» die Abweichung von 1:1; Markt-Vergleich, Schwankung und Lage entfallen.
/// Liste wie im Portfolio (`CurrencyConversion.usdStables`).
enum StablePeg {
    /// Bis zu dieser Abweichung (in %) «nahe an der 1:1-Bindung».
    static let nearPercent = 0.1
    /// Ab dieser Abweichung (in %) ist der Faktor auffällig.
    static let notablePercent = 0.5
    /// Ab dieser Abweichung (in %) stark.
    static let strongPercent = 2.0

    /// Stablecoin gegen USD bzw. einen USD-Stablecoin? Gegen andere Währungen (USDT/TRY) nicht.
    static func isPegged(base: String, quote: String?) -> Bool {
        let b = CurrencyConversion.normalize(base)
        let q = CurrencyConversion.normalize(quote)
        return b != "USD" && CurrencyConversion.usdStables.contains(b) && CurrencyConversion.usdStables.contains(q)
    }

    /// Abweichung von 1:1 in % (mit Vorzeichen); nil ohne gültigen Kurs.
    static func deviationPercent(_ price: Double?) -> Double? {
        guard let price, price.isFinite, price > 0 else { return nil }
        return (price - 1) * 100
    }

    /// Auffällige Abweichung?
    static func isNotable(_ deviation: Double) -> Bool { abs(deviation) >= notablePercent }
}

/// Rolle einer Quelle im «Warum?»-Blatt.
enum WhySourceRole: Sendable, Equatable {
    case candles, comparison, futures, breadth, fearGreed
}

/// Eine genannte Quelle: Rolle, Anbieter und ob sie als Ausweichquelle/Richtwert einsprang.
struct WhySourceItem: Equatable, Sendable {
    let role: WhySourceRole
    let provider: String
    var note: FuturesSourceNote? = nil
}

/// Herkunft und Stand im «Warum?»-Blatt — wie `WhyFreshness` in Android: welche Quellen genannt
/// werden und wie alt Fear & Greed bzw. der Open-Interest-Vergleich sind.
enum WhyFreshness {
    /// Anbieter von Fear & Greed.
    static let fearGreedProvider = "alternative.me"

    /// Tage seit dem Fear-&-Greed-Wert in UTC-Tagen (alternative.me zählt UTC-Tage); 0 = heute.
    static func fearGreedDaysAgo(time: Int64, now: Int64) -> Int64 {
        floorDiv(now, ActivityAnalyzer.dayMillis) - floorDiv(time, ActivityAnalyzer.dayMillis)
    }

    /// Kerzen als Ersatzquelle kennzeichnen? Ja, sobald sie nicht von der Börse des Paars (`exchanges` = Schlüssel
    /// und Name) kommen — auch Binance (z. B. Phemex-, Bithumb- oder HTX-Paar). Wie Android.
    static func candlesFallback(exchanges: [String], provider: String?) -> Bool {
        DayChange.foreignCandleSource(exchanges: exchanges, provider: provider) != nil
    }

    /// Open-Interest-Vergleich: unter 90 Min. nil (dann in Minuten nennen), sonst ganze Stunden (gerundet).
    static func oiAgeHours(minutes: Int) -> Int? {
        minutes < 90 ? nil : (minutes + 30) / 60
    }

    /// Floss der breite Markt in den Markt-Vergleich ein (nicht bei Bitcoin selbst und Stablecoins)?
    static func usesBreadth(_ report: WhyReport) -> Bool {
        guard report.sources?.broadTime != nil else { return false }
        return report.reasons.contains {
            $0.kind == .MARKET_WIDE || $0.kind == .COIN_ONLY || $0.kind == .AGAINST_MARKET || $0.kind == .MARKET_CALM
        }
    }

    /// Genannte Quellen in fester Reihenfolge: Kerzen, Vergleichskerzen (nur wenn anderer Anbieter),
    /// Futures, breiter Markt (nur wenn genutzt), Fear & Greed (nur mit Wert).
    static func sources(_ report: WhyReport) -> [WhySourceItem] {
        guard let s = report.sources else { return [] }
        var out: [WhySourceItem] = []
        if let candles = s.candles, report.hasMarketData {
            out.append(WhySourceItem(role: .candles, provider: candles, note: s.candlesFallback ? .FALLBACK : nil))
        }
        if let reference = s.reference {
            out.append(WhySourceItem(role: .comparison, provider: reference))
        }
        let leverage = report.reasons.contains {
            $0.kind == .LEVERAGE_LONGS || $0.kind == .LEVERAGE_SHORTS || $0.kind == .LEVERAGE_BALANCED
        }
        if let futures = s.futures, leverage {
            out.append(WhySourceItem(role: .futures, provider: futures, note: s.futuresNote))
        }
        if usesBreadth(report) {
            out.append(WhySourceItem(role: .breadth, provider: BroadMarket.provider))
        }
        if report.reasons.contains(where: { $0.kind == .SENTIMENT }) {
            out.append(WhySourceItem(role: .fearGreed, provider: fearGreedProvider))
        }
        return out
    }

    /// Ganzzahlige Division mit Abrundung (wie `Math.floorDiv`).
    private static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q
    }
}
