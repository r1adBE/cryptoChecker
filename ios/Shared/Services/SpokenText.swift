import Foundation

/// Sätze für die Sprachausgabe — wie `SpokenText.kt`.
enum SpokenText {
    static func price(_ watch: Watch, _ price: Double) -> String {
        L("tts_price", marketName(watch), watch.baseAsset, watch.quoteAsset, PriceFormat.spokenPrice(price, locale: ttsLocale))
    }

    /// Sprache der Ansagen: die der App (Sätze aus dem Katalog); `Speaker` wählt dieselbe Stimme. Wie Android
    /// `SpokenText.ttsLocale`.
    static var ttsLanguage: String { Bundle.main.preferredLocalizations.first ?? Locale.current.identifier }

    static var ttsLocale: Locale { Locale(identifier: ttsLanguage) }

    static func alarm(_ watch: Watch, _ condition: AlarmCondition, _ price: Double) -> String {
        let direction: String
        switch condition {
        case .PRICE_ABOVE, .CHANGE_PERCENT_UP: direction = L("tts_direction_up")
        case .PRICE_BELOW, .CHANGE_PERCENT_DOWN: direction = L("tts_direction_down")
        case .MOVE_PERCENT_WINDOW: direction = L("tts_direction_move")
        case .VOLUME_SPIKE: direction = L("tts_direction_volume_spike")
        case .NEAR_HIGH: direction = L("tts_direction_near_high")
        case .NEAR_LOW: direction = L("tts_direction_near_low")
        case .FUNDING_ABOVE: direction = L("tts_direction_funding_above")
        case .FUNDING_BELOW: direction = L("tts_direction_funding_below")
        case .OI_UP: direction = L("tts_direction_oi_up")
        case .OI_DOWN: direction = L("tts_direction_oi_down")
        }
        return L("tts_alarm", marketName(watch), watch.baseAsset, watch.quoteAsset, direction,
                 PriceFormat.spokenPrice(price, locale: ttsLocale))
    }

    private static func marketName(_ watch: Watch) -> String {
        MarketsConfig.market(watch.marketKey)?.ttsName ?? watch.marketName
    }
}
