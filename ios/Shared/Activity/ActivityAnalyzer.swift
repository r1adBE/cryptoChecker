import Foundation

/// Reine Auswertung ohne Netz: aus Stundenkerzen, Funding Rate und Open Interest
/// werden Signale («hier passiert gerade etwas») und Gründe («warum bewegt sich
/// das?») — als Daten mit Art und Zahlen. Texte macht die Oberfläche, damit alles
/// übersetzbar und prüfbar bleibt. Wie `ActivityAnalyzer.kt` (Werte 1:1 übernommen).
enum ActivityAnalyzer {

    static let hourMillis: Int64 = 60 * 60_000

    /// Vergleichsstunden für Volatilität und Volumen.
    static let windowHours = 24

    /// 24 Vergleichsrenditen brauchen 25 Schlusskurse + letzte abgeschlossene + laufende Stunde.
    static let minCandles = windowHours + 3

    // MARK: Schwellen der Einordnung («Warum»-Blatt)
    static let priceZThreshold = 3.0
    /// Mindestbewegung, damit ruhige Coins (Stablecoins) nicht bei Kleinigkeiten melden.
    static let priceMinMovePercent = 1.5
    static let priceStrongZ = 5.0
    static let priceStrongMovePercent = 5.0
    static let volumeSpikeRatio = 3.0
    static let volumeStrongRatio = 6.0
    /// Funding in % je Periode (Binance/Bybit: meist 8 h).
    static let fundingExtremePercent = 0.05
    static let fundingStrongPercent = 0.1
    static let oiJumpPercent = 10.0
    static let oiStrongPercent = 20.0
    static let oiMinAgeMillis: Int64 = 30 * 60_000
    static let oiMaxAgeMillis: Int64 = 6 * hourMillis

    /// Untergrenze der Standardabweichung (in %), sonst teilt ein völlig flacher Verlauf durch 0.
    static let minSigmaPercent = 0.01

    static let signalTtlMillis: Int64 = 1 * hourMillis

    // ⚡-Signale: bewusst strenger als die Einordnung im «Warum»-Blatt,
    // damit nur wirklich Auffälliges markiert wird (v16.2.2 nachgeschärft).
    static let signalPriceZ = 3.5
    static let signalPriceMinMovePercent = 2.5
    static let signalPriceStrongZ = 5.0
    static let signalPriceStrongMovePercent = 6.0
    static let signalVolumeRatio = 5.0
    static let signalVolumeStrongRatio = 10.0
    static let signalFundingPercent = 0.1
    static let signalFundingStrongPercent = 0.2
    static let signalOiPercent = 15.0
    static let signalOiStrongPercent = 25.0
    static let analysisIntervalMillis: Int64 = 10 * 60_000
    static let notifyIntervalMillis: Int64 = 60 * 60_000

    // MARK: Gründe
    static let marketMovePercent = 1.5
    static let coinMovePercent = 3.0
    static let marketStrongPercent = 3.0
    static let volumeHighRatio = 2.0
    static let volumeLowRatio = 0.6
    static let volatilityHighZ = 2.0
    static let maxReasons = 5

    /// Stärkstes zuerst, bei gleicher Stärke nach Art — stabil wie `sortedWith` in Kotlin.
    static func sortedSignals(_ signals: [ActivitySignal]) -> [ActivitySignal] {
        signals.enumerated().sorted { a, b in
            let sa = a.element.severity.ordinal, sb = b.element.severity.ordinal
            if sa != sb { return sa > sb }
            let ka = a.element.kind.ordinal, kb = b.element.kind.ordinal
            if ka != kb { return ka < kb }
            return a.offset < b.offset
        }.map(\.element)
    }

    // MARK: Kennzahlen

    /// Letzte abgeschlossene Stunde (vorletzte Kerze) gegen die 24 Stunden davor.
    /// Renditen Schluss zu Schluss in %, z = (r − Mittel) / Standardabweichung
    /// (Stichprobe, n − 1). nil bei zu wenig oder ungültigen Kerzen.
    static func hourStats(_ candles: [MarketCandle]) -> HourStats? {
        guard candles.count >= minCandles else { return nil }
        let last = candles.count - 2
        let first = last - windowHours

        func ret(_ i: Int) -> Double? {
            let prev = candles[i - 1].close
            let cur = candles[i].close
            return prev > 0 && cur > 0 ? (cur / prev - 1) * 100 : nil
        }

        var window: [Double] = []
        window.reserveCapacity(windowHours)
        for i in first..<last {
            guard let r = ret(i) else { return nil }
            window.append(r)
        }
        guard let move = ret(last) else { return nil }
        let mean = window.reduce(0, +) / Double(window.count)
        let variance = window.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Double(window.count - 1)
        let sigma = max(variance.squareRoot(), minSigmaPercent)

        let avgVolume = (first..<last).reduce(0.0) { $0 + candles[$1].volume } / Double(windowHours)
        let ratio: Double? = avgVolume > 0 ? candles[last].volume / avgVolume : nil

        return HourStats(
            movePercent: move,
            zScore: (move - mean) / sigma,
            volumeRatio: ratio,
            candleOpenTime: candles[last].openTime
        )
    }

    /// Kurs zu einem Zeitpunkt, innerhalb einer abgeschlossenen Kerze linear
    /// zwischen Eröffnung und Schluss geschätzt. Ab der laufenden Kerze: letzter Kurs.
    static func priceAt(_ candles: [MarketCandle], _ time: Int64) -> Double? {
        guard let firstCandle = candles.first, let lastCandle = candles.last, time >= firstCandle.openTime else { return nil }
        if time >= lastCandle.openTime { return lastCandle.close }
        guard let candle = candles.last(where: { $0.openTime <= time }) else { return nil }
        let fraction = min(max(Double(time - candle.openTime) / Double(hourMillis), 0), 1)
        return candle.open + (candle.close - candle.open) * fraction
    }

    /// Veränderung des letzten Kurses gegenüber vor `hours` Stunden, in %.
    static func changeOver(_ candles: [MarketCandle]?, hours: Int, now: Int64) -> Double? {
        guard let candles, let current = candles.last?.close else { return nil }
        guard let past = priceAt(candles, now - Int64(hours) * hourMillis) else { return nil }
        return past > 0 && current > 0 ? (current / past - 1) * 100 : nil
    }

    /// Open-Interest-Vergleich mit der gespeicherten Messung. Verglichen wird nur
    /// gegen eine Messung, die 30 Min.–6 Std. alt ist. Jüngere bleibt stehen
    /// (sonst wäre der Abstand immer nur 10 Min.), ältere oder fehlende wird ersetzt.
    static func oiChange(previous: OiSample?, currentUnits: Double?, now: Int64) -> OiUpdate {
        guard let currentUnits, currentUnits > 0 else { return OiUpdate(changePercent: nil, minutes: nil, store: false) }
        guard let previous, previous.units > 0 else { return OiUpdate(changePercent: nil, minutes: nil, store: true) }
        let age = now - previous.time
        if age < 0 || age > oiMaxAgeMillis { return OiUpdate(changePercent: nil, minutes: nil, store: true) }
        if age < oiMinAgeMillis { return OiUpdate(changePercent: nil, minutes: nil, store: false) }
        return OiUpdate(
            changePercent: (currentUnits / previous.units - 1) * 100,
            minutes: Int(age / 60_000),
            store: true
        )
    }

    // MARK: Signale

    /// Alle Signale eines Paars, stärkstes zuerst.
    static func signals(stats: HourStats?, fundingPercent: Double?, oiChangePercent: Double?,
                        oiMinutes: Int?, now: Int64) -> [ActivitySignal] {
        var out: [ActivitySignal] = []
        if let stats {
            let z = abs(stats.zScore)
            let move = abs(stats.movePercent)
            if z >= signalPriceZ && move >= signalPriceMinMovePercent {
                out.append(ActivitySignal(
                    kind: .PRICE_MOVE,
                    severity: z >= signalPriceStrongZ || move >= signalPriceStrongMovePercent ? .STRONG : .NOTABLE,
                    value: stats.movePercent,
                    factor: z,
                    seenAt: now
                ))
            }
            if let ratio = stats.volumeRatio, ratio >= signalVolumeRatio {
                out.append(ActivitySignal(
                    kind: .VOLUME_SPIKE,
                    severity: ratio >= signalVolumeStrongRatio ? .STRONG : .NOTABLE,
                    value: ratio,
                    seenAt: now
                ))
            }
        }
        if let oi = oiChangePercent, abs(oi) >= signalOiPercent {
            out.append(ActivitySignal(
                kind: .OPEN_INTEREST_JUMP,
                severity: abs(oi) >= signalOiStrongPercent ? .STRONG : .NOTABLE,
                value: oi,
                factor: oiMinutes.map { Double($0) },
                seenAt: now
            ))
        }
        if let funding = fundingPercent, abs(funding) >= signalFundingPercent {
            out.append(ActivitySignal(
                kind: .FUNDING_EXTREME,
                severity: abs(funding) >= signalFundingStrongPercent ? .STRONG : .NOTABLE,
                value: funding,
                seenAt: now
            ))
        }
        return sortedSignals(out)
    }

    /// Neuer Bericht plus die Arten, die vorher nicht aktiv waren (für die Meldung).
    struct Merge: Sendable {
        let report: ActivityReport
        let newKinds: Set<ActivitySignalKind>
    }

    /// Frische Signale ersetzen gleichartige alte; alte, die nicht mehr erkannt
    /// werden, bleiben bis 1 Std. nach dem letzten Erkennen stehen.
    static func merge(previous: ActivityReport?, fresh: [ActivitySignal], now: Int64) -> Merge {
        let before = previous?.active(now: now) ?? []
        let freshKinds = Set(fresh.map(\.kind))
        let kept = before.filter { !freshKinds.contains($0.kind) }
        let renewed = fresh.map { s -> ActivitySignal in
            var copy = s
            copy.seenAt = now
            return copy
        }
        return Merge(
            report: ActivityReport(signals: sortedSignals(renewed + kept), computedAt: now),
            newKinds: freshKinds.subtracting(before.map(\.kind))
        )
    }

    /// Melden, wenn etwas Neues dazukam und die letzte Meldung über 60 Min. her ist.
    static func shouldNotify(enabled: Bool, newKinds: Set<ActivitySignalKind>, lastNotifiedAt: Int64, now: Int64) -> Bool {
        enabled && !newKinds.isEmpty && now - lastNotifiedAt >= notifyIntervalMillis
    }

    /// Ist die letzte Prüfung älter als 10 Min. (oder gibt es keine)?
    static func isDue(_ report: ActivityReport?, now: Int64) -> Bool {
        guard let report else { return true }
        let age = now - report.computedAt
        return !(age >= 0 && age < analysisIntervalMillis)
    }

    // MARK: Gründe

    static func fearGreedLevel(_ value: Int) -> FearGreedLevel {
        if value < 25 { return .extremeFear }
        if value < 45 { return .fear }
        if value <= 55 { return .neutral }
        if value <= 75 { return .greed }
        return .extremeGreed
    }

    /// Ordnet ein Paar ein: Markt vs. Coin, Volumen, Hebel, Volatilität, Stimmung.
    static func explain(_ input: WhyInput) -> WhyReport {
        let now = input.now
        let candles: [MarketCandle]? = (input.candles?.count ?? 0) >= 2 ? input.candles : nil
        let change1h = changeOver(candles, hours: 1, now: now)
        let change24h = changeOver(candles, hours: 24, now: now)
        let stats = candles.flatMap { hourStats($0) }
        let reference24h = changeOver(input.referenceCandles, hours: 24, now: now)

        var reasons: [WhyReason] = []

        // 1) Markt vs. Coin
        if input.baseAsset.uppercased() == "BTC" {
            if let change24h {
                reasons.append(WhyReason(
                    kind: .MARKET_LEADER,
                    tone: toneOf(change24h),
                    value: change24h,
                    secondary: reference24h,
                    strong: abs(change24h) >= marketStrongPercent
                ))
            }
        } else if let change24h, let reference24h {
            reasons.append(marketReason(coin: change24h, btc: reference24h))
        }

        // 2) Volumen
        if let stats, let ratio = stats.volumeRatio {
            let moving = abs(stats.zScore) >= volatilityHighZ || (change24h.map { abs($0) >= coinMovePercent } ?? false)
            if ratio >= volumeHighRatio {
                reasons.append(WhyReason(kind: .VOLUME_HIGH, tone: .warning, value: ratio, strong: ratio >= volumeSpikeRatio))
            } else if ratio <= volumeLowRatio && moving {
                reasons.append(WhyReason(kind: .VOLUME_LOW, tone: .warning, value: ratio))
            } else {
                reasons.append(WhyReason(kind: .VOLUME_NORMAL, tone: .neutral, value: ratio))
            }
        }

        // 3) Hebel (Futures)
        if let funding = input.fundingPercent {
            let oi = input.openInterestChangePercent
            let oiJump = oi.map { abs($0) >= oiJumpPercent } ?? false
            if funding >= fundingExtremePercent {
                reasons.append(WhyReason(kind: .LEVERAGE_LONGS, tone: .warning, value: funding, secondary: oi,
                                         strong: funding >= fundingStrongPercent || oiJump))
            } else if funding <= -fundingExtremePercent {
                reasons.append(WhyReason(kind: .LEVERAGE_SHORTS, tone: .warning, value: funding, secondary: oi,
                                         strong: funding <= -fundingStrongPercent || oiJump))
            } else {
                reasons.append(WhyReason(kind: .LEVERAGE_BALANCED, tone: .neutral, value: funding, secondary: oi, strong: oiJump))
            }
        }

        // 4) Volatilität
        if let stats {
            let factor = abs(stats.zScore)
            if factor >= volatilityHighZ {
                reasons.append(WhyReason(kind: .VOLATILITY_HIGH, tone: toneOf(stats.movePercent), value: factor,
                                         secondary: stats.movePercent, strong: factor >= priceZThreshold))
            } else {
                reasons.append(WhyReason(kind: .VOLATILITY_NORMAL, tone: .neutral, value: factor, secondary: stats.movePercent))
            }
        }

        // 5) Stimmung
        if let value = input.fearGreed {
            let level = fearGreedLevel(value)
            let extreme = level == .extremeFear || level == .extremeGreed
            reasons.append(WhyReason(
                kind: .SENTIMENT,
                tone: extreme ? .warning : .neutral,
                value: Double(value),
                secondary: input.fearGreedYesterday.map { Double(value - $0) }
            ))
        }

        // Stabil sortiert: auffällige zuerst, sonst in obiger Reihenfolge
        let ordered = reasons.filter(\.strong) + reasons.filter { !$0.strong }
        return WhyReport(
            price: candles?.last?.close,
            change1h: change1h,
            change24h: change24h,
            reasons: Array(ordered.prefix(maxReasons)),
            hasMarketData: candles != nil,
            dataTime: now
        )
    }

    private static func marketReason(coin: Double, btc: Double) -> WhyReason {
        let btcMoves = abs(btc) >= marketMovePercent
        let coinMoves = abs(coin) >= coinMovePercent
        if btcMoves && coinMoves && (coin > 0) != (btc > 0) {
            return WhyReason(kind: .AGAINST_MARKET, tone: toneOf(coin), value: coin, secondary: btc, strong: true)
        }
        if btcMoves {
            return WhyReason(kind: .MARKET_WIDE, tone: toneOf(btc), value: btc, secondary: coin, strong: abs(btc) >= marketStrongPercent)
        }
        if coinMoves {
            return WhyReason(kind: .COIN_ONLY, tone: toneOf(coin), value: coin, secondary: btc, strong: true)
        }
        return WhyReason(kind: .MARKET_CALM, tone: .neutral, value: btc, secondary: coin)
    }

    private static func toneOf(_ change: Double) -> WhyReasonTone {
        if change >= 0.005 { return .up }
        if change <= -0.005 { return .down }
        return .neutral
    }
}
