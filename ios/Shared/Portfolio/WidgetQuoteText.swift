import Foundation

// Währung hinter Kurs und Betrag in den Widgets — Spiegel von `WidgetQuoteText.kt` (Android).

/// Währung hinter einem Kurs oder Betrag in den Widgets: kleiner und grau wie der Börsenname der
/// Merkliste — wie `WidgetQuoteText` (Android, getestet in WidgetQuoteTextTest).
enum WidgetQuoteText {
    /// Kleinste Schriftgrösse der Währung (pt) — wie `WidgetPriceText`.
    static let minQuoteSize: Double = 11
    /// Währung ≈ 42 % der Kursgrösse.
    static let ratio: Double = 0.42

    /// Lage der Währung `quote` in `text`: das letzte Vorkommen mit Leerzeichen davor, ohne Buchstabe oder
    /// Ziffer dahinter und mit einer Zahl davor («1’968.40 CHF · 24h» → «CHF»); sonst nil («•••», «—»).
    static func range(in text: String, quote: String) -> Range<String.Index>? {
        guard !quote.trimmingCharacters(in: .whitespaces).isEmpty, !text.isEmpty else { return nil }
        var upper = text.endIndex
        while let found = text.range(of: quote, options: .backwards, range: text.startIndex..<upper) {
            let spaceBefore = found.lowerBound > text.startIndex
                && text[text.index(before: found.lowerBound)].isWhitespace
            let freeAfter = found.upperBound == text.endIndex
                || !(text[found.upperBound].isLetter || text[found.upperBound].isNumber)
            let numberBefore = text[text.startIndex..<found.lowerBound].contains { $0.isNumber }
            if spaceBefore && freeAfter && numberBefore { return found }
            upper = found.lowerBound
        }
        return nil
    }

    /// Text vor der Währung, die Währung und der Rest (ohne Währung: alles in `before`).
    static func split(_ text: String, quote: String) -> (before: String, quote: String?, after: String) {
        guard let r = range(in: text, quote: quote) else { return (text, nil, "") }
        return (String(text[..<r.lowerBound]), String(text[r]), String(text[r.upperBound...]))
    }

    /// Schriftgrösse der Währung zur Kursgrösse `size`: 42 %, mindestens `minQuoteSize`, höchstens `size`.
    static func quoteSize(for priceSize: Double) -> Double {
        guard priceSize > 0 else { return priceSize }
        return Swift.min(priceSize, Swift.max(minQuoteSize, priceSize * ratio))
    }
}
