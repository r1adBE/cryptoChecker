import Foundation

// «Warum bewegt sich das?» (Teil von `ActivityAnalyzer`): Gründe je Paar (Markt vs. Coin, Volumen, Hebel, Volatilität,
// Stimmung), Lage (30-Tage-Hoch/-Tief, drei Tage) und «nicht live» — wie `ActivityWhy.kt` (Android).

extension ActivityAnalyzer {
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
