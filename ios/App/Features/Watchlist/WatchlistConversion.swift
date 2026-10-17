import Foundation

/// «≈ 61’234 CHF» in der Merkliste: welche Quote-Währungen umgerechnet werden und der Text je
/// Zeile — wie `WatchlistConversion.kt` (`convertedPrice`) und die Quote-Auswahl in `WatchlistViewModel`.
enum WatchlistConversion {
    /// Faktoren alle 60 s auffrischen (die Quellen speichern selbst zwischen).
    static let refreshNanos: UInt64 = 60_000_000_000

    /// Quote-Währungen der Merkliste, die nicht schon die Zielwährung sind (vereinheitlicht, sortiert).
    static func quotes(_ watches: [Watch], target: String) -> [String] {
        let all = Set(watches.map { CurrencyConversion.normalize($0.quoteAsset) }.filter { !$0.isEmpty })
        return all.filter { !CurrencyConversion.sameCurrency($0, target) }.sorted()
    }

    /// Text unter der Änderung: nur mit Zielwährung, bekanntem Faktor, gültigem Kurs und wenn die
    /// Quote nicht schon die Zielwährung ist; sonst nil. Devisenkurs älter als 3 h (`fxTimes`, siehe
    /// `fxTime`): dessen Uhrzeit dahinter («≈ 66’120 CHF · Kurs 08:15»), wie Android.
    static func text(_ watch: Watch, target: String?, rates: [String: Double],
                     fxTimes: [String: Int64] = [:], now: Int64 = TimeUtils.nowMillis) -> String? {
        guard let target, !CurrencyConversion.sameCurrency(watch.quoteAsset, target),
              let price = watch.lastPrice, price > 0,
              let rate = rates[CurrencyConversion.normalize(watch.quoteAsset)],
              let value = CurrencyConversion.convert(price, rate: rate)
        else { return nil }
        let text = "≈ " + PriceFormat.priceWithCurrency(value, target)
        guard let at = fxTime(watch, fxTimes: fxTimes, now: now) else { return text }
        return L("converted_fx_stale", text, PriceFormat.shortTime(at))
    }

    /// Abrufzeit des Devisenkurses hinter der ≈ Umrechnung, wenn älter als 3 h
    /// (`CurrencyConversion.fxStaleMillis`; `fxTimes` aus `CurrencyConverter.staleFxTimes`); sonst nil.
    static func fxTime(_ watch: Watch, fxTimes: [String: Int64], now: Int64) -> Int64? {
        guard let at = fxTimes[CurrencyConversion.normalize(watch.quoteAsset)],
              now - at > CurrencyConversion.fxStaleMillis else { return nil }
        return at
    }
}
