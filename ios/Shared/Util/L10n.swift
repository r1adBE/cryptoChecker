import Foundation

/// Übersetzter Text zum Android-Schlüssel (`R.string.<key>`).
/// Die Texte stehen in `Localizable.xcstrings`, erzeugt aus den `strings.xml`
/// der Android-Fassung (30 Sprachen). Platzhalter: `%@` für Text, `%ld` für
/// ganze Zahlen, `%.1f` usw. für Kommazahlen — wie bei `String(format:)`.
///
/// Ganze Zahlen immer als `Int` übergeben, Text als `String`.
func L(_ key: String) -> String {
    let value = Bundle.main.localizedString(forKey: key, value: nil, table: nil)
    return value.replacingOccurrences(of: "\\n", with: "\n")
}

func L(_ key: String, _ args: CVarArg...) -> String {
    String(format: L(key), locale: Locale.current, arguments: args)
}

/// Wie `L(_:_:)`, aber mit einem Array (für Weitergabe aus anderen Funktionen).
func Lv(_ key: String, _ args: [CVarArg]) -> String {
    String(format: L(key), locale: Locale.current, arguments: args)
}

/// Text mit Pluralform zum Android-Schlüssel `R.plurals.<key>` (im Katalog als
/// Plural-Variante: one/few/many/other … je Sprache). Die Form wählt das System nach
/// der Zahl im Format (`String(format:locale:arguments:)` mit `Locale.current`,
/// wie `String.localizedStringWithFormat`), deshalb wird der Katalog-Text
/// hier unverändert weitergegeben — `L(_:_:)` bearbeitet den Text vorher
/// (`replacingOccurrences`) und kann dabei die Pluralangaben verlieren.
///
/// `args` sind die Werte in der Reihenfolge der Android-Platzhalter (die Zahl an ihrer
/// Stelle, als `Int`); ohne `args` wird nur `count` eingesetzt: `L("alarm_near_window_days", count: 3)`.
func L(_ key: String, count: Int, _ args: CVarArg...) -> String {
    let format = Bundle.main.localizedString(forKey: key, value: nil, table: nil)
    // = String.localizedStringWithFormat, aber mit Array (die variadische Form nimmt kein Array)
    let values: [CVarArg] = args.isEmpty ? [count] : args
    return String(format: format, locale: Locale.current, arguments: values)
}
