import Foundation

/// Die Sätze von «Kurz gesagt» als Text — geteilt von der Karte im Markt-Tab (`MarketPlainSummaryCard`) und
/// dem Widget «Was gerade auffällt» (`PulseWidget`), damit beide dieselben Schlüssel und Formate nutzen.
/// Wie Android `MarketPlainSummaryCard` / `PulseWidgetTexts.plainSentences`.
enum MarketPlainSummaryText {

    /// Die vorhandenen Sätze in der Reihenfolge der Karte: Richtung, Stimmung, was heraussticht.
    static func sentences(_ summary: PlainSummary) -> [String] {
        [summary.direction.map(Self.direction), summary.mood.map(Self.mood), summary.standout.map(Self.standout)].compactMap { $0 }
    }

    static func direction(_ direction: PlainDirection) -> String {
        let key = switch direction.kind {
        case .flat: "market_plain_direction_flat"
        case .upSlight: "market_plain_direction_up_slight"
        case .upClear: "market_plain_direction_up_clear"
        case .downSlight: "market_plain_direction_down_slight"
        case .downClear: "market_plain_direction_down_clear"
        }
        return L(key, PriceFormat.signedPercent(direction.changePercent, decimals: 1))
    }

    static func mood(_ mood: PlainMood) -> String {
        let key = switch mood.kind {
        case .extremeFear: "market_plain_mood_extreme_fear"
        case .fear: "market_plain_mood_fear"
        case .neutral: "market_plain_mood_neutral"
        case .greed: "market_plain_mood_greed"
        case .extremeGreed: "market_plain_mood_extreme_greed"
        }
        return L(key, mood.value)
    }

    static func standout(_ standout: PlainStandout) -> String {
        let coin = standout.coin ?? ""
        func percent(_ value: Double?) -> String { PriceFormat.signedPercent(value ?? 0, decimals: 1) }
        switch standout.kind {
        case .aboveBtc:
            return L("market_plain_standout_above_btc", coin, percent(standout.change24h), percent(standout.btc24h))
        case .belowBtc:
            return L("market_plain_standout_below_btc", coin, percent(standout.change24h), percent(standout.btc24h))
        case .againstBtc:
            return L("market_plain_standout_against_btc", coin, percent(standout.change24h), percent(standout.btc24h))
        case .volume:
            return L("market_plain_standout_volume", coin, LocaleNumbers.decimal(standout.volumeRatio ?? 0, maxDecimals: 1))
        case .futuresUp:
            return L("market_plain_standout_futures_up", coin)
        case .futuresDown:
            return L("market_plain_standout_futures_down", coin)
        case .quiet:
            return L("market_plain_standout_quiet")
        }
    }
}
