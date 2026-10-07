import Foundation

/// Texte für VoiceOver — gleiche Schlüssel wie Android (`strings_a11y.xml`).
/// Prozent werden mit Richtungswort statt Vorzeichen gesprochen («gestiegen um 1.24%»),
/// Teile einer Zeile mit «, » verbunden.
enum A11y {
    /// Nicht-leere Teile, mit «, » verbunden.
    static func join(_ parts: [String?]) -> String {
        parts.compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ", ")
    }

    /// «gestiegen um 1.24%» / «gefallen um 0.82%» / «unverändert»; nil ohne Wert.
    static func change(_ percent: Double?) -> String? {
        guard let percent, percent.isFinite else { return nil }
        guard abs(percent) >= 0.005 else { return L("a11y_change_flat") }
        let value = String(format: "%.2f%%", locale: Locale.current, abs(percent))
        return L(percent > 0 ? "a11y_change_up" : "a11y_change_down", value)
    }

    /// Veränderung über 24 Stunden: «gestiegen um 2.30% in 24 Stunden»; ohne Wert
    /// «Veränderung über 24 Stunden nicht verfügbar» (Pille «—») — wie `A11yText.change24h`.
    static func change24h(_ percent: Double?) -> String {
        guard let text = change(percent) else { return L("a11y_change_24h_none") }
        return L("a11y_change_24h", text)
    }

    /// «Chart 24h: von 96’000 auf 97’512, gestiegen um 1.57%. Hoch 98’100, Tief 95’800.»
    /// `period`: Zeitraum (z. B. «24h», «7 Tage»); `format`: Wert als Text.
    static func chart(period: String, values: [Double], format: (Double) -> String = { PriceFormat.price($0) }) -> String {
        let clean = values.filter { $0.isFinite }
        guard clean.count >= 2, let first = clean.first, let last = clean.last,
              let high = clean.max(), let low = clean.min() else { return L("a11y_chart_empty") }
        return chart(period: period, first: first, last: last, high: high, low: low, format: format)
    }

    /// Gleicher Satz mit vorgegebenen Werten (Kerzen: Start = erstes Open, Ende = letztes Close,
    /// Hoch = höchstes High, Tief = tiefstes Low).
    static func chart(period: String, first: Double, last: Double, high: Double, low: Double,
                      format: (Double) -> String = { PriceFormat.price($0) }) -> String {
        let percent: Double? = first != 0 ? (last / first - 1) * 100 : nil
        let changeText = Self.change(percent) ?? L("a11y_change_flat")
        return L("a11y_chart", period, format(first), format(last), changeText, format(high), format(low))
    }

    /// Eine Zeile der Merkliste (App und Widget): Paar und Börse, Kurs, Veränderung über 24 Stunden,
    /// danach die optionalen Teile in der Reihenfolge der Spezifikation — wie
    /// `A11yText.row` in Android: ≈, Verlauf, Notiz, Alarme, `extra` (z. B. Mitteilung an),
    /// Fehler, veralteter Stand.
    static func watchRow(_ watch: Watch,
                         converted: String? = nil,
                         chart: String? = nil,
                         alarmCount: Int = 0,
                         extra: [String?] = [],
                         stale: String? = nil) -> String {
        let pair = watch.displayName
        let price = L("a11y_price", PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset))
        let convertedText = converted.map { text -> String in
            var t = text
            if t.hasPrefix("≈") { t = String(t.dropFirst()).trimmingCharacters(in: .whitespaces) }
            return L("a11y_converted", t)
        }
        let note = watch.note.flatMap { $0.isEmpty ? nil : L("a11y_note", $0) }
        let alarms = alarmCount > 0 ? L("a11y_alarm_count", alarmCount) : nil
        let error = watch.lastError.flatMap { $0.isEmpty ? nil : ConnectionErrors.display($0) }
        return join([L("a11y_row_pair", pair, watch.marketName), price, Self.change24h(watch.change24h),
                     convertedText, chart, note, alarms] + extra + [error, stale])
    }
}
