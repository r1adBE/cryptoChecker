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
    /// Funding in % je 8 Stunden (stündliches Funding wird umgerechnet: `fundingPer8h`).
    static let fundingExtremePercent = 0.05
    static let fundingStrongPercent = 0.1
    static let oiJumpPercent = 10.0
    static let oiStrongPercent = 20.0
    static let oiMinAgeMillis: Int64 = 30 * 60_000
    static let oiMaxAgeMillis: Int64 = 6 * hourMillis

    /// Höchstens so viele der 24 Vergleichsstunden dürfen ganz ohne Handel sein (ein Viertel),
    /// sonst ist das Volumen-Verhältnis unzuverlässig und entfällt.
    static let maxEmptyVolumeHours = windowHours / 4

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

    /// Funding-Schwellen gelten pro so vielen Stunden (wie `DerivativesAlarm.fundingBasisHours`).
    static let fundingBasisHours = 8

    /// Abrechnungsintervall in Stunden, wenn es nicht `fundingBasisHours` ist; sonst nil. `reported` =
    /// Intervall des Kontrakts laut Börse, sonst das übliche der Quelle (Hyperliquid: 1) — wie Android.
    static func fundingIntervalHours(_ source: String?, reported: Int? = nil) -> Int? {
        // Unbekanntes Intervall: wie 8 Std. — nur für die Einschätzung im Blatt, nie für Alarme (wie Android)
        let hours = reported.flatMap { (1...24).contains($0) ? $0 : nil } ?? FuturesSources.fundingIntervalHours(source) ?? fundingBasisHours
        return hours == fundingBasisHours ? nil : hours
    }

    /// Funding für den Vergleich mit den Schwellen auf `fundingBasisHours` Stunden umgerechnet, damit
    /// Gleiches mit Gleichem verglichen wird (stündlicher Satz zählt achtfach). `intervalHours` nil = 8.
    static func fundingPer8h(_ percent: Double, intervalHours: Int?) -> Double {
        let hours = intervalHours.flatMap { $0 > 0 ? $0 : nil } ?? fundingBasisHours
        return percent * Double(fundingBasisHours) / Double(hours)
    }
    static let analysisIntervalMillis: Int64 = 10 * 60_000
    static let notifyIntervalMillis: Int64 = 60 * 60_000

    // MARK: Gründe
    static let marketMovePercent = 1.5
    static let coinMovePercent = 3.0
    static let marketStrongPercent = 3.0
    static let volumeHighRatio = 2.0
    static let volumeLowRatio = 0.6
    static let volatilityHighZ = 2.0
    /// «Schwankung höher als üblich» braucht neben |z| ≥ `volatilityHighZ` auch eine Stunden-
    /// Bewegung von mindestens 0,5 % — sonst meldet ein sehr ruhiger Coin schon Staub.
    static let volatilityMinMovePercent = 0.5
    static let maxReasons = 5

    /// «Nähe zum Tief»: gleiche Tage wie das Hoch.
    static let lowDays = 30

    /// Kerzen für «Warum?»: 72 Stunden Lage + 24-h-Fenster + Vergleichsstunden davor, mit Reserve.
    static let whyHourlyLimit = 100

    // MARK: Lage in den letzten drei Tagen
    /// Fenster in Stunden.
    static let rangeHours = 72
    /// Mindestens so viele Stunden Verlauf, sonst keine Aussage.
    static let rangeMinHours = 48
    /// Bis zu diesem Abstand (in %) «nahe am 3-Tage-Hoch/-Tief».
    static let rangeNearPercent = 1.0
    /// «Nahe am Hoch/Tief» nur, wenn die Spanne der drei Tage mindestens so gross ist (in %).
    static let rangeMinSpanPercent = 3.0
    /// Untergrenze für «−8 % seit dem 3-Tage-Hoch» (in %).
    static let rangeFloorPercent = 5.0
    /// Abstand auffällig ab k · σ₁ₕ · √72 (mindestens `rangeFloorPercent`).
    static let rangeSigmaK = 2.0
    /// Ähnlich genug, dass «nur dieser Coin» nicht stimmt: Eine Referenz bewegt sich in dieselbe
    /// Richtung und mindestens halb so stark wie der Coin.
    static let similarRatio = 0.5

    /// «Nähe zum Hoch»: so viele Tage zurück.
    static let highDays = 30
    static let dayMillis: Int64 = 24 * hourMillis

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

        // Volumen: Umsatz in der Quote, wenn jede Kerze ihn hat (Binance), sonst die gehandelte
        // Menge in Coins (Coinbase u. a.) — dann nur genähert. Zu viele Stunden ganz ohne Handel:
        // kein Verhältnis (ein Schnitt über leere Stunden wäre bedeutungslos). Wie Android.
        let quote = (first...last).allSatisfy { i in
            guard let q = candles[i].quoteVolume else { return false }
            return q.isFinite && q >= 0
        }
        func vol(_ i: Int) -> Double { quote ? (candles[i].quoteVolume ?? 0) : candles[i].volume }
        let emptyHours = (first..<last).filter { !(vol($0) > 0) }.count
        let avgVolume = (first..<last).reduce(0.0) { $0 + vol($1) } / Double(windowHours)
        var ratio: Double? = avgVolume > 0 && emptyHours <= maxEmptyVolumeHours ? vol(last) / avgVolume : nil
        if let r = ratio, !r.isFinite { ratio = nil }

        return HourStats(
            movePercent: move,
            zScore: (move - mean) / sigma,
            volumeRatio: ratio,
            candleOpenTime: candles[last].openTime,
            volumeApproximate: !quote
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

    /// Alle Signale eines Paars, stärkstes zuerst. Die Schwellen kommen aus der
    /// `sensitivity` (Standard = bisherige Werte) — für Karte und Meldungen gleich.
    /// `fundingSource` = Quelle der Funding Rate (stündliches Funding zählt achtfach, `fundingPer8h`).
    static func signals(stats: HourStats?, fundingPercent: Double?, oiChangePercent: Double?,
                        oiMinutes: Int?, now: Int64,
                        sensitivity: ActivitySensitivity = .NORMAL,
                        fundingSource: String? = nil,
                        fundingIntervalHours reportedInterval: Int? = nil) -> [ActivitySignal] {
        // Kandidaten mit allen Werten; ob und wie stark sie zählen, entscheiden die Schwellen
        var candidates: [ActivitySignal] = []
        if let stats {
            candidates.append(ActivitySignal(
                kind: .PRICE_MOVE,
                severity: .NOTABLE,
                value: stats.movePercent,
                factor: abs(stats.zScore),
                seenAt: now
            ))
            if let ratio = stats.volumeRatio {
                candidates.append(ActivitySignal(kind: .VOLUME_SPIKE, severity: .NOTABLE, value: ratio, seenAt: now))
            }
        }
        if let oi = oiChangePercent {
            candidates.append(ActivitySignal(
                kind: .OPEN_INTEREST_JUMP,
                severity: .NOTABLE,
                value: oi,
                factor: oiMinutes.map { Double($0) },
                seenAt: now
            ))
        }
        if let funding = fundingPercent {
            // Wert der Börse (Anzeige), Intervall für den Vergleich auf 8 Stunden
            let interval = fundingIntervalHours(fundingSource, reported: reportedInterval).map { Double($0) }
            candidates.append(ActivitySignal(kind: .FUNDING_EXTREME, severity: .NOTABLE, value: funding,
                                             factor: interval, seenAt: now))
        }
        return applySensitivity(candidates, sensitivity)
    }

    /// Beurteilt Signale neu nach der `sensitivity`: was unter den Schwellen liegt, fällt
    /// weg, die Stärke wird neu bestimmt. So wirkt eine geänderte Einstellung sofort auch
    /// auf gespeicherte Signale (Karte, ⚡ an den Zeilen); stärkstes zuerst.
    static func applySensitivity(_ signals: [ActivitySignal], _ sensitivity: ActivitySensitivity) -> [ActivitySignal] {
        let thresholds = SignalThresholds.of(sensitivity)
        return sortedSignals(signals.compactMap { signal in
            thresholds.severity(of: signal).map { severity in
                ActivitySignal(kind: signal.kind, severity: severity, value: signal.value,
                               factor: signal.factor, seenAt: signal.seenAt)
            }
        })
    }

    /// Neuer Bericht plus die Arten, die vorher nicht aktiv waren (für die Meldung).
    struct Merge: Sendable {
        let report: ActivityReport
        let newKinds: Set<ActivitySignalKind>
    }

    /// Frische Signale ersetzen gleichartige alte; alte, die nicht mehr erkannt
    /// werden, bleiben bis 1 Std. nach dem letzten Erkennen stehen.
    static func merge(previous: ActivityReport?, fresh: [ActivitySignal], now: Int64,
                      sensitivity: ActivitySensitivity = .NORMAL) -> Merge {
        // Alte Signale nach der aktuellen Empfindlichkeit: nach «Weniger» zählt nur noch, was dort auffällt
        let before = applySensitivity(previous?.active(now: now) ?? [], sensitivity)
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

    /// Arten für die Meldung: neue plus die zurückgehaltenen `pending` (Mitteilung kam zuletzt nicht an),
    /// soweit sie im Bericht noch aktiv sind — wie Android.
    static func noticeKinds(newKinds: Set<ActivitySignalKind>, pending: Set<ActivitySignalKind>,
                            report: ActivityReport, now: Int64) -> Set<ActivitySignalKind> {
        guard !pending.isEmpty else { return newKinds }
        let active = Set(report.active(now: now).map(\.kind))
        return newKinds.union(pending.intersection(active))
    }

    /// Zurückzuhaltende Arten nach der Meldung — gleiche Regel wie bei den Kursalarmen
    /// (`QuietHours.mayFire`): Fällige Meldung (`notify`) nicht zustellbar bzw. nicht angenommen
    /// (`posted` = false) → `kinds` bleiben offen und nichts gilt als gemeldet; sonst nichts offen.
    static func pendingAfter(kinds: Set<ActivitySignalKind>, notify: Bool, posted: Bool) -> Set<ActivitySignalKind> {
        (notify && !posted) ? kinds : []
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

    /// Ordnet ein Paar ein: Markt vs. Coin, Volumen, Hebel, Volatilität, Stimmung; dazu Lage
    /// (30-Tage-Hoch/-Tief, drei Tage), Funding-Verlauf und Herkunft. Bewegungen werden an der
    /// eigenen Schwankung gemessen (`RelativeMove`); Markt = BTC, ETH und der breite Markt
    /// (`BroadMarket`). Stablecoins (`StablePeg`): Abweichung von 1:1 statt Markt und Schwankung.
    /// Wie Android (gemeinsame Testfälle `why_analysis.json`).
    static func explain(_ input: WhyInput) -> WhyReport {
        let now = input.now
        // Nicht mehr gehandelt: nichts ausgeben (keine Kennzahlen, keine Gründe)
        guard input.marketLive else { return notLive(now: now) }
        // Veraltete, leere oder unplausibel flache Reihen zählen als «keine Daten» (siehe CandleSeries)
        let candles = CandleSeries.usable(input.candles, now: now, tickerChange24h: input.tickerChange24h)
        let change1h = changeOver(candles, hours: 1, now: now)
        // 24 h wie Änderung und Merkliste (Ticker des Paars), sonst aus den Kerzen
        let candleChange24h = changeOver(candles, hours: 24, now: now)
        let change24h = CandleSeries.change24h(ticker: input.tickerChange24h, candles: candleChange24h)
        let stats = candles.flatMap { hourStats($0) }
        let reference = CandleSeries.usable(input.referenceCandles, now: now, tickerChange24h: nil)
        let stable = StablePeg.isPegged(base: input.baseAsset, quote: input.quoteAsset)
        let isBtc = input.baseAsset.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == "BTC"
        // Referenzen (BTCUSDT, ETHUSDT, breiter Markt) sind in USD: nur bei USD-Quote vergleichbar
        let usdQuote = isUsdQuote(input.quoteAsset)

        // Deutliche Bewegung des Coins: relativ zur eigenen Schwankung vor dem 24-h-Fenster
        let coinThreshold = RelativeMove.coinThreshold(RelativeMove.sigmaBefore24h(candles, now: now))
        let coinMoves = change24h.map { abs($0) >= coinThreshold } ?? false
        // Stunde schwankt stärker als üblich: |z| ≥ 2 und mindestens 0,5 % (kein Staub)
        let volatile = stats.map { abs($0.zScore) >= volatilityHighZ && abs($0.movePercent) >= volatilityMinMovePercent } ?? false

        var reasons: [WhyReason] = []

        // 1) Markt vs. Coin — bei Stablecoins stattdessen die Abweichung von 1:1
        if stable {
            if let deviation = StablePeg.deviationPercent(candles?.last?.close) {
                let notable = StablePeg.isNotable(deviation)
                reasons.append(WhyReason(kind: .PEG, tone: notable ? .warning : .neutral, value: deviation, strong: notable))
            }
        } else if !usdQuote {
            // Quote nicht USD (z. B. ETH/BTC, SOL/EUR): Kerzen und Referenzen nicht vergleichbar — kein Markt-Grund
        } else if isBtc {
            if let change24h {
                // Bitcoin ist selbst der Markt: Schwelle aus seiner eigenen Schwankung
                let threshold = RelativeMove.marketThreshold(RelativeMove.sigmaBefore24h(candles, now: now))
                reasons.append(WhyReason(
                    kind: .MARKET_LEADER,
                    tone: toneOf(change24h),
                    value: change24h,
                    secondary: changeOver(reference, hours: 24, now: now),
                    strong: abs(change24h) >= RelativeMove.strongFactor * threshold,
                    threshold: threshold
                ))
            }
        } else if let change24h {
            let refs = marketRefs(input, reference: reference, now: now)
            if !refs.isEmpty { reasons.append(marketReason(coin: change24h, coinThreshold: coinThreshold, refs: refs)) }
        }

        // 2) Volumen
        if let stats, let ratio = stats.volumeRatio {
            let moving = volatile || coinMoves
            let approximate = stats.volumeApproximate
            if ratio >= volumeHighRatio {
                reasons.append(WhyReason(kind: .VOLUME_HIGH, tone: .warning, value: ratio,
                                         strong: ratio >= volumeSpikeRatio, approximate: approximate))
            } else if ratio <= volumeLowRatio && moving {
                reasons.append(WhyReason(kind: .VOLUME_LOW, tone: .warning, value: ratio, approximate: approximate))
            } else {
                reasons.append(WhyReason(kind: .VOLUME_NORMAL, tone: .neutral, value: ratio, approximate: approximate))
            }
        }

        // 3) Hebel (Futures)
        if let funding = input.fundingPercent {
            let oi = input.openInterestChangePercent
            // Sprung nur bei einheitlicher Richtung gegenüber den älteren Messungen (OiSeries)
            let oiJump = input.openInterestConsistent && (oi.map { abs($0) >= oiJumpPercent } ?? false)
            // Angezeigt wird der Satz der Börse, verglichen auf 8 Stunden umgerechnet
            let interval = fundingIntervalHours(input.fundingSource, reported: input.fundingIntervalHours)
            let per8h = fundingPer8h(funding, intervalHours: interval)
            if per8h >= fundingExtremePercent {
                reasons.append(WhyReason(kind: .LEVERAGE_LONGS, tone: .warning, value: funding, secondary: oi,
                                         strong: per8h >= fundingStrongPercent || oiJump, intervalHours: interval))
            } else if per8h <= -fundingExtremePercent {
                reasons.append(WhyReason(kind: .LEVERAGE_SHORTS, tone: .warning, value: funding, secondary: oi,
                                         strong: per8h <= -fundingStrongPercent || oiJump, intervalHours: interval))
            } else {
                reasons.append(WhyReason(kind: .LEVERAGE_BALANCED, tone: .neutral, value: funding, secondary: oi,
                                         strong: oiJump, intervalHours: interval))
            }
        }

        // 4) Volatilität (nicht bei Stablecoins: dort zählt die Abweichung von 1:1)
        if let stats, !stable {
            let factor = abs(stats.zScore)
            if volatile {
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
        // Lage (Hoch/Tief) nur mit Kerzen und nicht bei Stablecoins
        let rangeCandles: [MarketCandle]? = stable ? nil : candles
        let withRange = rangeCandles != nil
        let high: Double? = withRange ? high30d(input.dailyCandles, now: now) : nil
        let low: Double? = withRange ? low30d(input.dailyCandles, now: now) : nil
        let position: Position3d? = rangeCandles.flatMap { position3d($0, now: now) }
        // Referenz ist BTCUSDT (bei Bitcoin selbst ETH — dann ohnehin kein Satz); nur bei USD-Quote
        let link: BtcLink? = stable || !usdQuote ? nil : BtcCorrelation.link(base: input.baseAsset, candles: candles, btc: reference)
        var fundingTrend: FundingTrend? = nil
        if input.fundingPercent != nil {
            fundingTrend = FundingTrendRules.trend(input.fundingHistory, current: input.fundingPercent,
                                                   currentSource: input.fundingSource, now: now,
                                                   currentIntervalHours: input.fundingIntervalHours)
        }
        let oiMinutes: Int? = input.openInterestChangePercent != nil ? input.openInterestMinutes : nil
        return WhyReport(
            price: candles?.last?.close,
            change1h: change1h,
            change24h: change24h,
            reasons: Array(ordered.prefix(maxReasons)),
            hasMarketData: candles != nil,
            dataTime: now,
            high30d: high,
            btcLink: link,
            low30d: low,
            position3d: position,
            fundingTrend: fundingTrend,
            oiMinutes: oiMinutes,
            oiConsistent: input.openInterestConsistent,
            candleTime: candles?.last?.openTime,
            stable: stable,
            sources: input.sources
        )
    }

    /// Quote USD oder ein USD-Stablecoin (`CurrencyConversion.classify` → `.usd`)? Nur dann sind die Kerzen
    /// des Paars mit den USD-Referenzen vergleichbar. Ohne Quote (unbekannt) wie bisher USD. Wie Android.
    static func isUsdQuote(_ quoteAsset: String?) -> Bool {
        let code = CurrencyConversion.normalize(quoteAsset)
        return code.isEmpty || CurrencyConversion.classify(code, fiat: []) == .usd
    }

    /// Eine Markt-Referenz: 24-h-Veränderung in % und ihre Schwelle.
    private struct RefMove {
        let ref: MarketRef
        let change: Double
        let threshold: Double
    }

    /// Markt-Referenzen für einen Coin (nicht Bitcoin): BTC (aus `reference`), ETH (nicht für ETH
    /// selbst) mit Schwellen aus ihrer eigenen Schwankung, dazu der breite Markt mit der festen
    /// Schwelle `marketMovePercent` (ohne Kerzen keine Schwankung).
    private static func marketRefs(_ input: WhyInput, reference: [MarketCandle]?, now: Int64) -> [RefMove] {
        var out: [RefMove] = []
        if let btc = changeOver(reference, hours: 24, now: now) {
            out.append(RefMove(ref: .BTC, change: btc,
                               threshold: RelativeMove.marketThreshold(RelativeMove.sigmaBefore24h(reference, now: now))))
        }
        if input.baseAsset.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() != "ETH" {
            let eth = CandleSeries.usable(input.ethCandles, now: now, tickerChange24h: nil)
            if let change = changeOver(eth, hours: 24, now: now) {
                out.append(RefMove(ref: .ETH, change: change,
                                   threshold: RelativeMove.marketThreshold(RelativeMove.sigmaBefore24h(eth, now: now))))
            }
        }
        if let broad = input.broadMarket?.change24h, broad.isFinite {
            out.append(RefMove(ref: .BROAD, change: broad, threshold: marketMovePercent))
        }
        return out
    }

    /// Erste Referenz mit dem grössten Wert von `score` (bei Gleichstand die frühere — wie Android).
    private static func firstMax(_ refs: [RefMove], _ score: (RefMove) -> Double) -> RefMove? {
        guard var best = refs.first else { return nil }
        for r in refs.dropFirst() where score(r) > score(best) { best = r }
        return best
    }

    private static func sameSign(_ a: Double, _ b: Double) -> Bool { (a > 0 && b > 0) || (a < 0 && b < 0) }

    /// Markt vs. Coin über 24 h (wie Android). Eine Referenz bewegt sich deutlich, wenn
    /// |Veränderung| ≥ ihre Schwelle.
    ///  - gegen den Markt: der Coin bewegt sich deutlich, ALLE deutlich bewegten Referenzen in die Gegenrichtung
    ///  - marktweit: mindestens eine Referenz bewegt sich deutlich (gezeigt: die deutlichste, gleiche Richtung bevorzugt)
    ///  - marktweit (schwach): der Coin bewegt sich deutlich, eine Referenz ähnlich (`similarRatio`)
    ///  - nur dieser Coin: der Coin bewegt sich deutlich, kein Markt ähnlich
    ///  - sonst ruhig
    private static func marketReason(coin: Double, coinThreshold: Double, refs: [RefMove]) -> WhyReason {
        let moving = refs.filter { abs($0.change) >= $0.threshold }
        let coinMoves = abs(coin) >= coinThreshold
        let display = refs.first { $0.ref == .BTC } ?? refs[0]
        let ratio: (RefMove) -> Double = { abs($0.change) / $0.threshold }
        if coinMoves && !moving.isEmpty && moving.allSatisfy({ sameSign($0.change, -coin) }),
           let m = firstMax(moving, ratio) {
            return WhyReason(kind: .AGAINST_MARKET, tone: toneOf(coin), value: coin, secondary: m.change,
                             strong: true, ref: m.ref, threshold: coinThreshold)
        }
        if !moving.isEmpty {
            let same = moving.filter { sameSign($0.change, coin) }
            if let d = firstMax(same.isEmpty ? moving : same, ratio) {
                return WhyReason(kind: .MARKET_WIDE, tone: toneOf(d.change), value: d.change, secondary: coin,
                                 strong: abs(d.change) >= RelativeMove.strongFactor * d.threshold,
                                 ref: d.ref, threshold: d.threshold)
            }
        }
        if coinMoves {
            let similar = refs.filter { sameSign($0.change, coin) && abs($0.change) >= similarRatio * abs(coin) }
            if let d = firstMax(similar, { abs($0.change) }) {
                return WhyReason(kind: .MARKET_WIDE, tone: toneOf(d.change), value: d.change, secondary: coin,
                                 strong: false, ref: d.ref, threshold: d.threshold)
            }
            return WhyReason(kind: .COIN_ONLY, tone: toneOf(coin), value: coin, secondary: display.change,
                             strong: true, ref: display.ref, threshold: coinThreshold)
        }
        return WhyReason(kind: .MARKET_CALM, tone: .neutral, value: display.change, secondary: coin,
                         ref: display.ref, threshold: display.threshold)
    }

    /// Höchster Kurs der letzten `highDays` Tage aus Tageskerzen (letzte = laufender Tag);
    /// nil bei weniger als 30 Tagen, einer veralteten Reihe oder ungültigen Werten (wie Android).
    static func high30d(_ daily: [MarketCandle]?, now: Int64) -> Double? {
        guard let days = recentDays(daily, now: now, count: highDays),
              let high = days.map(\.high).max(), high.isFinite, high > 0 else { return nil }
        return high
    }

    /// Tiefster Kurs der letzten `lowDays` Tage, gleiche Regeln wie `high30d`.
    static func low30d(_ daily: [MarketCandle]?, now: Int64) -> Double? {
        guard let days = recentDays(daily, now: now, count: lowDays) else { return nil }
        // Ungültige Tiefs (≤ 0) zählen nicht, sonst stünde jeder Kurs «weit über dem Tief»
        if days.contains(where: { !($0.low > 0) || !$0.low.isFinite }) { return nil }
        return days.map(\.low).min()
    }

    /// Die letzten `count` Tageskerzen; nil bei zu wenigen oder einer veralteten/künftigen Reihe.
    private static func recentDays(_ daily: [MarketCandle]?, now: Int64, count: Int) -> [MarketCandle]? {
        guard let daily, daily.count >= count, let last = daily.last else { return nil }
        // Reihe endet vor über zwei Tagen (oder liegt in der Zukunft): kein Hoch/Tief
        if now - last.openTime > 2 * dayMillis || last.openTime - now > dayMillis { return nil }
        return Array(daily.suffix(count))
    }

    /// Lage in den letzten `rangeHours` Stunden (Stundenkerzen, inkl. laufender), nur wenn auffällig
    /// (wie Android):
    ///  - «−8 % seit dem 3-Tage-Hoch» / «+6 % seit dem 3-Tage-Tief»: Abstand mindestens
    ///    max(`rangeFloorPercent`, `rangeSigmaK` · σ₁ₕ · √72); beides → der grössere
    ///  - sonst nahe am 3-Tage-Hoch/-Tief: höchstens `rangeNearPercent` entfernt, bei einer Spanne
    ///    von mindestens `rangeMinSpanPercent`
    /// nil bei weniger als `rangeMinHours` Stunden Verlauf oder ungültigen Werten.
    static func position3d(_ candles: [MarketCandle], now: Int64) -> Position3d? {
        let start = now - Int64(rangeHours) * hourMillis
        let window = candles.filter { $0.openTime >= start }
        guard window.count >= rangeMinHours, let firstCandle = window.first, let lastCandle = window.last,
              firstCandle.openTime <= now - Int64(rangeMinHours) * hourMillis,
              let high = window.map(\.high).max(), let low = window.map(\.low).min() else { return nil }
        let price = lastCandle.close
        guard high > 0, low > 0, price > 0, high.isFinite, low.isFinite, price.isFinite else { return nil }
        let fromHigh = min((price / high - 1) * 100, 0)
        let fromLow = max((price / low - 1) * 100, 0)
        let span = (high / low - 1) * 100
        let threshold: Double
        if let sigma = RelativeMove.hourlySigma(candles, endAt: now) {
            threshold = max(rangeFloorPercent, rangeSigmaK * sigma * Double(rangeHours).squareRoot())
        } else {
            threshold = rangeFloorPercent
        }
        // Zuerst der deutliche Abstand (sagt mehr als «nahe am Tief» nach einem Einbruch), dann die Nähe
        let down = -fromHigh >= threshold
        let up = fromLow >= threshold
        if down && (!up || -fromHigh >= fromLow) { return Position3d(kind: .BELOW_HIGH, percent: fromHigh, threshold: threshold) }
        if up { return Position3d(kind: .ABOVE_LOW, percent: fromLow, threshold: threshold) }
        if span < rangeMinSpanPercent { return nil }
        if -fromHigh <= rangeNearPercent { return Position3d(kind: .NEAR_HIGH, percent: fromHigh, threshold: threshold) }
        if fromLow <= rangeNearPercent { return Position3d(kind: .NEAR_LOW, percent: fromLow, threshold: threshold) }
        return nil
    }

    /// Leerer Bericht für Paare, die nicht mehr gehandelt werden.
    static func notLive(now: Int64) -> WhyReport {
        WhyReport(price: nil, change1h: nil, change24h: nil, reasons: [], hasMarketData: false, dataTime: now)
    }

    private static func toneOf(_ change: Double) -> WhyReasonTone {
        if change >= 0.005 { return .up }
        if change <= -0.005 { return .down }
        return .neutral
    }
}
