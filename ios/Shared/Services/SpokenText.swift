import Foundation

/// Sätze für die Sprachausgabe — wie `SpokenText.kt`.
enum SpokenText {
    static func price(_ watch: Watch, _ price: Double) -> String {
        L("tts_price", exchangeName(watch), watch.baseAsset, watch.quoteAsset, PriceFormat.spokenPrice(price, locale: ttsLocale))
    }

    /// Sprache der Ansagen: die der App (Sätze aus dem Katalog); `Speaker` wählt dieselbe Stimme. Wie Android
    /// `SpokenText.ttsLocale`.
    static var ttsLanguage: String { Bundle.main.preferredLocalizations.first ?? Locale.current.identifier }

    static var ttsLocale: Locale { Locale(identifier: ttsLanguage) }

    /// `currency`: Währung von `price`, wenn nicht die Quote des Paars (Kursmarke mit eigener Alarmwährung, `price`
    /// schon umgerechnet) — wird dann hinter dem Kurs genannt, wie in der Mitteilung; nil = Quote des Paars (steht
    /// schon im Paarnamen). Wie Android.
    static func alarm(_ watch: Watch, _ condition: AlarmCondition, _ price: Double, currency: String? = nil) -> String {
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
        return L("tts_alarm", exchangeName(watch), watch.baseAsset, watch.quoteAsset, direction,
                 spokenAlarmPrice(PriceFormat.spokenPrice(price, locale: ttsLocale), quote: watch.quoteAsset,
                                  currency: currency))
    }

    /// Gesprochener Kurs, mit Währung nur, wenn sie von der Quote abweicht («60000 CHF») — wie Android
    /// `SpokenText.spokenAlarmPrice`.
    static func spokenAlarmPrice(_ spoken: String, quote: String, currency: String?) -> String {
        guard let currency, !currency.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              currency.caseInsensitiveCompare(quote) != .orderedSame else { return spoken }
        return "\(spoken) \(currency.uppercased())"
    }

    private static func exchangeName(_ watch: Watch) -> String {
        ExchangesConfig.exchange(watch.exchangeKey)?.ttsName ?? watch.exchangeName
    }
}
