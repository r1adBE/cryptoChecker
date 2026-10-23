import SwiftUI

// Faktoren der Merkliste (Teil von `WatchlistScreen`): Sortieren nach Kurs in einer gemeinsamen Währung und die
// umgerechneten Kurse «≈ 61’234 CHF» — Regeln in WatchlistConversion.swift.

extension WatchlistScreen {
    /// Gespeicherte Spalten-Sortierung; nil = eigene Reihenfolge.
    var columnSort: ColumnSort? { ColumnSort.decode(columnSortRaw) }

    /// Kurs in CHF fürs Sortieren; nil ohne Kurs oder Umrechnungsfaktor (dann ans Ende).
    func sortValue(_ watch: Watch) -> Double? {
        guard let price = watch.lastPrice, price > 0 else { return nil }
        let quote = WatchlistConversion.quote(watch)
        if CurrencyConversion.sameCurrency(quote, Self.sortCurrency) { return price }
        guard let rate = sortRates[quote], rate > 0 else { return nil }
        return price * rate
    }

    /// Gemeinsame Währung fürs Sortieren nach Kurs (nur intern, nie angezeigt) — wie Android.
    static let sortCurrency = "CHF"

    /// Ändert sich nur mit der Sortierung nach Kurs oder der Menge der Quote-Währungen.
    var sortRatesKey: String {
        guard columnSort?.key == .PRICE else { return "" }
        return WatchlistConversion.quotes(data.watches, target: Self.sortCurrency).joined(separator: ",")
    }

    /// Faktoren in CHF, zuerst aus den Zwischenspeichern, dann frisch und danach alle 60 s.
    func refreshSortRates() async {
        guard columnSort?.key == .PRICE else {
            sortRates = [:]
            return
        }
        let quotes = WatchlistConversion.quotes(data.watches, target: Self.sortCurrency)
        guard !quotes.isEmpty else {
            sortRates = [:]
            return
        }
        var known = CurrencyConverter.cachedRates(quotes: quotes, target: Self.sortCurrency)
        sortRates = known
        while !Task.isCancelled {
            let fresh = await CurrencyConverter.rates(quotes: quotes, target: Self.sortCurrency)
            if Task.isCancelled { return }
            known.merge(fresh) { _, new in new }
            sortRates = known
            try? await Task.sleep(nanoseconds: WatchlistConversion.refreshNanos)
        }
    }

    /// Zielwährung der umgerechneten Kurse; nil = ausgeschaltet.
    var convertTarget: String? {
        data.settings.showConverted ? data.settings.portfolioCurrency : nil
    }

    /// Ändert sich nur mit der Einstellung oder der Menge der Quote-Währungen.
    var conversionKey: String {
        guard let target = convertTarget else { return "" }
        return target + "|" + WatchlistConversion.quotes(data.watches, target: target).joined(separator: ",")
    }

    /// Zuerst aus den Zwischenspeichern, dann frisch und danach alle 60 s; fehlt ein
    /// Faktor einmal, bleibt der letzte bekannte stehen.
    func refreshConversion() async {
        guard let target = convertTarget else {
            convertRates = [:]
            convertFxTimes = [:]
            convertUsdtNotes = [:]
            return
        }
        let quotes = WatchlistConversion.quotes(data.watches, target: target)
        guard !quotes.isEmpty else {
            convertRates = [:]
            convertFxTimes = [:]
            convertUsdtNotes = [:]
            return
        }
        var known = CurrencyConverter.cachedRates(quotes: quotes, target: target)
        convertRates = known
        convertFxTimes = CurrencyConverter.staleFxTimes(quotes: quotes, target: target)
        convertUsdtNotes = CurrencyConverter.usdtNotes(quotes: quotes, target: target)
        while !Task.isCancelled {
            let fresh = await CurrencyConverter.rates(quotes: quotes, target: target)
            if Task.isCancelled { return }
            known.merge(fresh) { _, new in new }
            convertRates = known
            // Alter der Devisenkurse nach dem Auffrischen (älter als 3 h: Uhrzeit in der Zeile)
            convertFxTimes = CurrencyConverter.staleFxTimes(quotes: quotes, target: target)
            // USDT/USD: angenommen (keine Messung) bzw. unsicher (nur über USDC) — Vermerk an der Zeile
            convertUsdtNotes = CurrencyConverter.usdtNotes(quotes: quotes, target: target)
            await WatchlistConversion.waitForNextRound(usdtAssumed: convertUsdtNotes.values.contains(.ASSUMED))
        }
    }
}
