import Foundation

/// Fehlertext eines Zahlenfelds für eine mehrdeutige Eingabe («1.234» in DE: «Mehrdeutig – 1234 oder 1,234?»), sonst
/// nil. Der Parser liest solche Eingaben nicht (`ThresholdParser.ambiguity`); dieser Text sagt, warum — für jedes
/// Zahlenfeld der App, wie Android `ambiguousNumberText`.
func ambiguousNumberText(_ text: String, decimalSeparator: Character = ThresholdParser.localeDecimalSeparator,
                         priceHint: Double? = nil) -> String? {
    ThresholdParser.ambiguity(text, decimalSeparator: decimalSeparator, priceHint: priceHint)
        .map { L("number_ambiguous", $0.grouped, $0.decimal) }
}
