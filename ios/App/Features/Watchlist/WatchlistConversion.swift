import Foundation

/// «≈ 61’234 CHF» in der Merkliste: welche Quote-Währungen umgerechnet werden und der Text je
/// Zeile — wie `WatchlistConversion.kt` (`convertedPrice`) und die Quote-Auswahl in `WatchlistViewModel`.
enum WatchlistConversion {
    /// Faktoren alle 60 s auffrischen (die Quellen speichern selbst zwischen).
    static let refreshNanos: UInt64 = 60_000_000_000
    /// Solange USDT = 1 angenommen ist: so oft nachsehen, ob die erste USDT/USD-Messung da ist.
    static let measurePollNanos: UInt64 = 2_000_000_000

    /// Bis zur nächsten Runde warten (60 s). Ist USDT = 1 angenommen, endet das Warten, sobald eine frische
    /// USDT/USD-Messung da ist — Faktor und Vermerk stimmen dann sofort (wie Android `usdtMeasured`).
    static func waitForNextRound(usdtAssumed: Bool,
                                 measured: () -> Bool = { DepegAlarmCheck.liveUsdtUsd() != nil }) async {
        guard usdtAssumed else {
            try? await Task.sleep(nanoseconds: refreshNanos)
            return
        }
        var waited: UInt64 = 0
        while waited < refreshNanos, !Task.isCancelled {
            try? await Task.sleep(nanoseconds: measurePollNanos)
            waited += measurePollNanos
            if measured() { return }
        }
    }

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
    /// `usdtNotes` (`CurrencyConverter.usdtNotes`): USDT/USD nicht gemessen oder nur unsicher über USDC → kurz
    /// «· Umrechnung unsicher» dahinter (passt neben das Paar; der ganze Satz in `accessibilityText`), wie Android.
    static func text(_ watch: Watch, target: String?, rates: [String: Double],
                     fxTimes: [String: Int64] = [:], usdtNotes: [String: FiatAlarmGuard.UsdtState] = [:],
                     now: Int64 = TimeUtils.nowMillis) -> String? {
        guard let dated = dated(watch, target: target, rates: rates, fxTimes: fxTimes, now: now) else { return nil }
        return usdtNote(watch, target: target, notes: usdtNotes) == nil ? dated : dated + " · " + L("converted_unsure")
    }

    /// VoiceOver-Fassung von `text`: beim nicht gemessenen USDT der ganze Satz («USDT = 1 angenommen»); sonst nil
    /// (dann gilt `text`). Wie Android `convertedA11y`.
    static func accessibilityText(_ watch: Watch, target: String?, rates: [String: Double],
                                  fxTimes: [String: Int64] = [:], usdtNotes: [String: FiatAlarmGuard.UsdtState] = [:],
                                  now: Int64 = TimeUtils.nowMillis) -> String? {
        guard usdtNote(watch, target: target, notes: usdtNotes) == .ASSUMED,
              let dated = dated(watch, target: target, rates: rates, fxTimes: fxTimes, now: now) else { return nil }
        return dated + " · " + L("portfolio_usdt_assumed")
    }

    private static func dated(_ watch: Watch, target: String?, rates: [String: Double],
                              fxTimes: [String: Int64], now: Int64) -> String? {
        guard let target, !CurrencyConversion.sameCurrency(quote(watch), target),
              let price = watch.lastPrice, price > 0,
              let rate = rates[quote(watch)],
              let value = CurrencyConversion.convert(price, rate: rate)
        else { return nil }
        let text = "≈ " + PriceFormat.priceWithCurrency(value, target)
        return fxTime(watch, fxTimes: fxTimes, now: now).map { L("converted_fx_stale", text, PriceFormat.shortTime($0)) }
            ?? text
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
