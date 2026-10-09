import Foundation

/// «≈ 61’234 CHF» in der Merkliste: welche Quote-Währungen umgerechnet werden und der Text je
/// Zeile — wie `convertedPrice` (WatchlistRow.kt) und die Quote-Auswahl in `WatchlistViewModel`.
enum WatchlistConversion {
    /// Faktoren alle 60 s auffrischen (die Quellen speichern selbst zwischen).
    static let refreshNanos: UInt64 = 60_000_000_000

    /// Quote-Währungen der Merkliste, die nicht schon die Zielwährung sind (vereinheitlicht, sortiert).
    static func quotes(_ watches: [Watch], target: String) -> [String] {
        let all = Set(watches.map { CurrencyConversion.normalize($0.quoteAsset) }.filter { !$0.isEmpty })
        return all.filter { !CurrencyConversion.sameCurrency($0, target) }.sorted()
    }

    /// Text unter der Pille: nur mit Zielwährung, bekanntem Faktor, gültigem Kurs und wenn die
    /// Quote nicht schon die Zielwährung ist; sonst nil.
    static func text(_ watch: Watch, target: String?, rates: [String: Double]) -> String? {
        guard let target, !CurrencyConversion.sameCurrency(watch.quoteAsset, target),
              let price = watch.lastPrice, price > 0,
              let rate = rates[CurrencyConversion.normalize(watch.quoteAsset)],
              let value = CurrencyConversion.convert(price, rate: rate)
        else { return nil }
        return "≈ " + PriceFormat.priceWithCurrency(value, target)
    }
}
