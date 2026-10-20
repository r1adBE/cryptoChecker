import Foundation

/// «≈ 61’234 CHF» in der Merkliste: welche Quote-Währungen umgerechnet werden und der Text je
/// Zeile — wie `WatchlistConversion.kt` (`convertedPrice`) und die Quote-Auswahl in `WatchlistViewModel`.
enum WatchlistConversion {
    /// Faktoren alle 60 s auffrischen (die Quellen speichern selbst zwischen).
    static let refreshNanos: UInt64 = 60_000_000_000

    /// Quote für Faktor und Schlüssel der Umrechnung (`CurrencyConversion.conversionQuote`: Aster «U» = USD).
    static func quote(_ watch: Watch) -> String {
        CurrencyConversion.conversionQuote(marketKey: watch.marketKey, quote: watch.quoteAsset)
    }

    /// Quote-Währungen der Merkliste, die nicht schon die Zielwährung sind (vereinheitlicht, sortiert).
    static func quotes(_ watches: [Watch], target: String) -> [String] {
        let all = Set(watches.map { quote($0) }.filter { !$0.isEmpty })
        return all.filter { !CurrencyConversion.sameCurrency($0, target) }.sorted()
    }

    /// Text unter der Änderung: nur mit Zielwährung, bekanntem Faktor, gültigem Kurs und wenn die
    /// Quote nicht schon die Zielwährung ist; sonst nil. Devisenkurs älter als 3 h (`fxTimes`, siehe
    /// `fxTime`): dessen Uhrzeit dahinter («≈ 66’120 CHF · Kurs 08:15»), wie Android.
    /// `usdtNotes` (`CurrencyConverter.usdtNotes`): USDT/USD nicht gemessen → «· USDT = 1 angenommen», nur unsicher
    /// über USDC → «· Umrechnung unsicher» dahinter, wie Android.
    static func text(_ watch: Watch, target: String?, rates: [String: Double],
                     fxTimes: [String: Int64] = [:], usdtNotes: [String: FiatAlarmGuard.UsdtState] = [:],
                     now: Int64 = TimeUtils.nowMillis) -> String? {
        guard let target, !CurrencyConversion.sameCurrency(quote(watch), target),
              let price = watch.lastPrice, price > 0,
              let rate = rates[quote(watch)],
              let value = CurrencyConversion.convert(price, rate: rate)
        else { return nil }
        let text = "≈ " + PriceFormat.priceWithCurrency(value, target)
        let dated = fxTime(watch, fxTimes: fxTimes, now: now).map { L("converted_fx_stale", text, PriceFormat.shortTime($0)) }
            ?? text
        switch usdtNote(watch, target: target, notes: usdtNotes) {
        case .ASSUMED: return dated + " · " + L("portfolio_usdt_assumed")
        case .UNSURE: return dated + " · " + L("converted_unsure")
        default: return dated
        }
    }

    /// Vermerk zur USDT-Umrechnung von `watch` (`notes` aus `CurrencyConverter.usdtNotes`); nil = nichts — wie Android
    /// `convertedUsdtNote`.
    static func usdtNote(_ watch: Watch, target: String?,
                         notes: [String: FiatAlarmGuard.UsdtState]) -> FiatAlarmGuard.UsdtState? {
        guard let target, !CurrencyConversion.sameCurrency(quote(watch), target) else { return nil }
        return notes[quote(watch)]
    }

    /// Abrufzeit des Devisenkurses hinter der ≈ Umrechnung, wenn älter als 3 h
    /// (`CurrencyConversion.fxStaleMillis`; `fxTimes` aus `CurrencyConverter.staleFxTimes`); sonst nil.
    static func fxTime(_ watch: Watch, fxTimes: [String: Int64], now: Int64) -> Int64? {
        guard let at = fxTimes[quote(watch)],
              now - at > CurrencyConversion.fxStaleMillis else { return nil }
        return at
    }
}
