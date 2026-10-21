import SwiftUI

// Texte von «Was bewegt sich mit?»: Kurzsatz, Faktor-Titel/Werte/Hinweise, Funding, Open Interest, Angst & Gier,
// Referenzmarkt, Quellen- und Stand-Zeilen.
// Eigene Datei, weil es reine Textregeln sind; die Karten in `WatchWhyFactors.swift` und das Blatt
// `WatchWhySheet.swift` nutzen sie gemeinsam (wie `WatchWhyTexts.kt`).

/// Kurzer Titel, Wert rechts (mit gesprochener Fassung) und Erklärung eines Grundes.
enum WatchlistWhyTexts {
    struct Factor {
        let title: String
        let value: String
        var spokenValue: String? = nil
    }

    static func factor(_ r: WhyReason) -> Factor {
        func pct(_ v: Double) -> String { ActivityTexts.percent(v, 1) }
        func ref(_ title: String, _ symbol: String, _ v: Double?) -> Factor {
            guard let v, v.isFinite else { return Factor(title: title, value: "") }
            return Factor(title: title, value: symbol + " " + pct(v),
                          spokenValue: A11y.join([symbol, A11y.change(abs(v) < 0.05 ? 0 : v)]))
        }
        // Markt-Referenz des Grundes: BTC, ETH oder «Alts» (breiter Markt)
        let symbol = marketRefLabel(r.ref)

        switch r.kind {
        case .MARKET_WIDE:
            return ref(L("why_f_market_wide"), symbol, r.value)
        case .COIN_ONLY:
            return ref(L("why_f_coin_only"), symbol, r.secondary)
        case .AGAINST_MARKET:
            return ref(L("why_f_against"), symbol, r.secondary)
        case .MARKET_CALM:
            return ref(L("why_f_market_calm"), symbol, r.value)
        case .PEG:
            return Factor(title: L("why_factor_peg"), value: ActivityTexts.percent(r.value, 2),
                          spokenValue: A11y.change(r.value))
        case .MARKET_LEADER:
            // Der Coin ist Bitcoin selbst (24h steht oben): rechts ETH zum Vergleich
            return ref(L("why_f_leader"), "ETH", r.secondary)
        case .VOLUME_HIGH, .VOLUME_LOW, .VOLUME_NORMAL:
            let number = r.value.isFinite ? String(format: "%.2f", locale: Locale.current, r.value) : "—"
            return Factor(title: L("factor_volume"), value: number + "×",
                          spokenValue: L("factor_spoken_volume", number))
        case .LEVERAGE_LONGS:
            return Factor(title: L("why_f_leverage_longs"), value: ActivityTexts.fundingValue(r.value, intervalHours: r.intervalHours))
        case .LEVERAGE_SHORTS:
            return Factor(title: L("why_f_leverage_shorts"), value: ActivityTexts.fundingValue(r.value, intervalHours: r.intervalHours))
        case .LEVERAGE_BALANCED:
            return Factor(title: L("why_f_leverage_balanced"), value: ActivityTexts.fundingValue(r.value, intervalHours: r.intervalHours))
        case .VOLATILITY_HIGH, .VOLATILITY_NORMAL:
            let number = ActivityTexts.factor(r.value)
            return Factor(title: L("factor_volatility"), value: number + "×",
                          spokenValue: L("factor_spoken_volatility", number))
        case .SENTIMENT:
            let value = r.value.isFinite ? Int(r.value.rounded()) : 0
            let label = fearGreedLabel(value)
            let number = LocaleNumbers.integer(value)
            return Factor(title: L("factor_fear_greed"), value: "\(number) · \(label)",
                          spokenValue: "\(number), \(label)")
        }
    }

    /// Texte einer Faktorzeile: Titel, Wert rechts, Nebenzeile und der gesprochene Satz.
    struct FactorLine {
        let title: String
        let value: String
        let note: String
        let spoken: String
    }

    static func factorLine(_ f: WhyFactor) -> FactorLine {
        func pct(_ v: Double) -> String { ActivityTexts.percent(v, 1) }
        func spokenChange(_ v: Double) -> String { A11y.change(abs(v) < 0.05 ? 0 : v) ?? "" }
        let title: String
        var value = ""
        var spokenValue = ""
        var note = f.note.key.map { L($0) } ?? ""
        switch f.kind {
        case .volume:
            let number = ActivityTexts.factor(f.value)
            title = L("factor_volume")
            value = number + "×"
            spokenValue = L("factor_spoken_volume", number)
            // Ohne Quote-Umsatz der Quelle nur genähert
            if f.approximate { note += " · " + L("why_fn_estimated") }
        case .market:
            title = L("why_factor_market")
            // Bei Bitcoin selbst steht BTC oben: rechts ETH zum Vergleich
            let leader = f.note == .marketLeaderMoves || f.note == .marketLeaderCalm
            let symbol = leader ? "ETH" : marketRefLabel(f.ref)
            let change: Double? = leader ? f.secondary : f.value
            if let change, change.isFinite {
                value = symbol + " " + pct(change)
                spokenValue = A11y.join([symbol, spokenChange(change)])
            }
        case .volatility:
            let number = ActivityTexts.factor(f.value)
            title = L("factor_volatility")
            value = number + "×"
            spokenValue = L("factor_spoken_volatility", number)
            // «absolut gering (unter 0.5 %)»: Mindestbewegung in der Schreibweise der App-Sprache
            if f.note == .volatilityTiny { note = L("why_fn_volatility_tiny", unsignedPercent(ActivityAnalyzer.volatilityMinMovePercent, 1)) }
        case .nearLow:
            title = L("why_factor_near_low")
            if f.note == .lowAbove {
                value = pct(f.value)
                spokenValue = spokenChange(f.value)
            }
        case .position3d:
            title = L("why_factor_range3d")
            value = pct(f.value)
            spokenValue = spokenChange(f.value)
        case .peg:
            // Abweichung steht in der Nebenzeile («0.12 % unter der 1:1-Bindung»), rechts kein Wert
            title = L("why_factor_peg")
            let amount = unsignedPercent(abs(f.value), 2)
            if f.note == .pegBelow { note = L("why_fn_peg_below", amount) }
            if f.note == .pegAbove { note = L("why_fn_peg_above", amount) }
        case .openInterest:
            title = L("why_factor_futures")
            value = pct(f.value)
            spokenValue = spokenChange(f.value)
        case .nearHigh:
            title = L("why_factor_near_high")
            if f.note == .highBelow {
                value = pct(-f.value)
                spokenValue = spokenChange(-f.value)
            }
        case .funding:
            title = L("why_factor_funding")
            value = ActivityTexts.fundingValue(f.value, intervalHours: f.intervalHours)
            spokenValue = value
            // Verlauf der abgerechneten Sätze («seit 24 Std. steigend»)
            if let trend = f.trend { note += " · " + fundingTrendText(trend) }
        case .sentiment:
            let points = f.value.isFinite ? Int(f.value.rounded()) : 0
            title = L("factor_fear_greed")
            value = LocaleNumbers.integer(points)
            spokenValue = value
            note = fearGreedLabel(points)
        }
        let spoken = L("label_value", title, note) + (spokenValue.isEmpty ? "" : ", \(spokenValue)") + "."
        return FactorLine(title: title, value: value, note: note, spoken: spoken)
    }

    /// Erklärung mit formatierten Zahlen (unter «Details» bzw. bei «!»).
    static func explanation(_ r: WhyReason) -> String {
        func pct(_ v: Double?) -> String { v.map { ActivityTexts.percent($0, 1) } ?? "—" }
        func withOpenInterest(_ text: String, _ change: Double?) -> String {
            guard let change else { return text }
            return text + " " + L("why_open_interest_change", ActivityTexts.percent(change, 1))
        }
        let estimated = r.approximate ? " " + L("why_volume_estimated_text") : ""

        switch r.kind {
        case .MARKET_WIDE:
            // Markt nur ähnlich (unter seiner Schwelle): nicht «deutlich» nennen
            if WhySummary.isSimilarOnly(r) { return L("why_market_similar_text", pct(r.secondary)) }
            return L("why_market_wide_text", pct(r.secondary))
        case .COIN_ONLY:
            return L("why_coin_only_text", pct(r.value))
        case .AGAINST_MARKET:
            if r.ref == nil || r.ref == .BTC {
                return L("why_against_market_text", pct(r.value), pct(r.secondary))
            }
            return L("why_against_ref_text", pct(r.value), marketRefLabel(r.ref), pct(r.secondary))
        case .MARKET_CALM:
            return L("why_market_calm_text", pct(r.secondary))
        case .MARKET_LEADER:
            return r.secondary.map { L("why_market_leader_text", pct($0)) } ?? L("why_market_leader_text_no_eth")
        case .VOLUME_HIGH:
            return L("why_volume_high_text") + estimated
        case .VOLUME_LOW:
            return L("why_volume_low_text") + estimated
        case .VOLUME_NORMAL:
            return L("why_volume_normal_text") + estimated
        case .LEVERAGE_LONGS:
            return withOpenInterest(L("why_leverage_longs_text"), r.secondary)
        case .LEVERAGE_SHORTS:
            return withOpenInterest(L("why_leverage_shorts_text"), r.secondary)
        case .LEVERAGE_BALANCED:
            return withOpenInterest(L("why_leverage_balanced_text"), r.secondary)
        case .VOLATILITY_HIGH:
            return L("why_volatility_high_text", pct(r.secondary))
        case .VOLATILITY_NORMAL:
            // |z| über der Schwelle, aber unter 0,5 %: ungewöhnlich für den Coin, absolut gering
            if r.value >= ActivityAnalyzer.volatilityHighZ { return L("why_volatility_tiny_text", pct(r.secondary)) }
            return L("why_volatility_normal_text", pct(r.secondary))
        case .SENTIMENT:
            if let change = r.secondary, change.isFinite {
                return L("why_sentiment_text", signedInt(Int(change.rounded())))
            }
            return L("why_sentiment_text_no_change")
        case .PEG:
            return L("why_peg_text", ActivityTexts.percent(r.value, 2))
        }
    }

    /// Kürzel der Markt-Referenz vor dem Wert: «BTC», «ETH» oder «Alts» (breiter Markt).
    static func marketRefLabel(_ ref: MarketRef?) -> String {
        switch ref {
        case .ETH: return "ETH"
        case .BROAD: return L("why_ref_alts")
        default: return "BTC"
        }
    }

    /// «seit 24 Std. steigend» / «fallend» / «stabil».
    static func fundingTrendText(_ trend: FundingTrend) -> String {
        switch trend.direction {
        case .RISING: return L("why_trend_rising", count: trend.hours, trend.hours)
        case .FALLING: return L("why_trend_falling", count: trend.hours, trend.hours)
        case .STABLE: return L("why_trend_stable", count: trend.hours, trend.hours)
        }
    }

    /// Anbieter, bei Bedarf mit «(Ausweichquelle)» bzw. «(Richtwert)».
    static func providerText(_ name: String, _ note: FuturesSourceNote?) -> String {
        switch note {
        case .FALLBACK: return L("why_source_fallback", name)
        case .REFERENCE: return L("why_source_reference", name)
        case nil: return name
        }
    }

    /// Herkunft und Stand unter der Faktorkarte: «Kerzen: Binance · 14:00», «Marktbreite: CoinGecko · 13:05»,
    /// «Futures: Bybit · Open Interest vor 2 Std. verglichen», «Fear & Greed: heute» (wie Android).
    static func freshnessLines(_ report: WhyReport) -> [String] {
        guard let sources = report.sources else { return [] }
        var out: [String] = []
        for item in WhyFreshness.sources(report) {
            switch item.role {
            case .candles:
                // Ende der letzten abgeschlossenen Stunde
                if let time = report.candleTime {
                    out.append(L("why_fresh_candles", providerText(item.provider, item.note), PriceFormat.shortTime(time)))
                }
            case .breadth:
                if let time = sources.broadTime {
                    out.append(L("why_fresh_breadth", item.provider, PriceFormat.shortTime(time)))
                }
            case .futures:
                let head = L("why_fresh_futures", providerText(item.provider, item.note))
                if let minutes = report.oiMinutes {
                    let oi: String
                    if let hours = WhyFreshness.oiAgeHours(minutes: minutes) {
                        oi = L("why_fresh_oi_hours", count: hours, hours)
                    } else {
                        oi = L("why_fresh_oi_minutes", count: minutes, minutes)
                    }
                    out.append(head + " · " + oi)
                } else {
                    out.append(head)
                }
            case .fearGreed:
                if let time = sources.fearGreedTime {
                    out.append(L("why_fresh_fng", fearGreedDay(time, now: report.dataTime)))
                }
            case .comparison:
                break
            }
        }
        return out
    }

    /// «heute» / «gestern» / Datum des UTC-Tags (Fear & Greed zählt UTC-Tage).
    private static func fearGreedDay(_ time: Int64, now: Int64) -> String {
        switch WhyFreshness.fearGreedDaysAgo(time: time, now: now) {
        case 0: return L("why_fresh_today")
        case 1: return L("why_fresh_yesterday")
        default:
            let formatter = DateFormatter()
            formatter.dateStyle = .short
            formatter.timeStyle = .none
            formatter.timeZone = TimeZone(identifier: "UTC")
            return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(time) / 1000))
        }
    }

    /// «Quellen: Binance (Kerzen), Bybit (Futures), CoinGecko (Marktbreite), alternative.me (Fear & Greed).»;
    /// nil ohne Angaben (dann der allgemeine Satz).
    static func sourcesLine(_ report: WhyReport) -> String? {
        let items = WhyFreshness.sources(report)
        guard !items.isEmpty else { return nil }
        let parts: [String] = items.map { item in
            let name = providerText(item.provider, item.note)
            switch item.role {
            case .candles: return L("why_src_candles", name)
            case .comparison: return L("why_src_comparison", name)
            case .futures: return L("why_src_futures", name)
            case .breadth: return L("why_src_breadth", name)
            case .fearGreed: return L("why_src_fng", name)
            }
        }
        return L("why_sources_list", parts.joined(separator: ", "))
    }

    /// Betrag ohne Vorzeichen, z. B. «0.12%» bzw. «0,12 %» (die Richtung sagt «unter»/«über»), als Insel für RTL;
    /// Schreibweise der App-Sprache über `PercentText` wie Android.
    static func unsignedPercent(_ value: Double, _ decimals: Int) -> String {
        BidiText.ltr(PercentText.unsigned(value, decimals: decimals))
    }

    private static func signedInt(_ value: Int) -> String {
        if value > 0 { return "+" + LocaleNumbers.integer(value) }
        if value < 0 { return "−" + LocaleNumbers.integer(-value) }
        return "±" + LocaleNumbers.integer(0)
    }

    private static func fearGreedLabel(_ value: Int) -> String {
        switch ActivityAnalyzer.fearGreedLevel(value) {
        case .extremeFear: L("fng_extreme_fear")
        case .fear: L("fng_fear")
        case .neutral: L("fng_neutral")
        case .greed: L("fng_greed")
        case .extremeGreed: L("fng_extreme_greed")
        }
    }
}
