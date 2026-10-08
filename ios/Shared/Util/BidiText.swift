import Foundation

/// Text in Rechts-nach-links-Sprachen (Arabisch, Hebräisch, Persisch …) richtig ordnen.
/// Spiegel von `BidiText.kt` (gleiche Regeln, gleiche Tests).
///
/// - `ltr`: Zahl mit Vorzeichen («+1.20%», «−12.00 USD») als links-nach-rechts-Insel
///   (Unicode LRI … PDI). Ohne Insel stellt der Bidi-Algorithmus in einem RTL-Absatz das
///   Vorzeichen hinter die Zahl («1.20%+») — allein (Pille) wie mitten im Satz.
/// - `isolate`: Name aus fremder Schrift («Binance», «BTC/USDT») als Insel mit eigener
///   Richtung (FSI … PDI), damit «Binance · vor 5 Min.» in RTL in Lesereihenfolge steht.
///
/// Nur bei einer RTL-Sprache; sonst bleibt der Text unverändert.
enum BidiText {

    static let lri = "\u{2066}"
    static let fsi = "\u{2068}"
    static let pdi = "\u{2069}"

    /// Sprachen, die von rechts nach links geschrieben werden (auch alte Codes iw, ji).
    private static let rtlLanguages: Set<String> = ["ar", "fa", "he", "iw", "ur", "ps", "yi", "ji", "ckb", "sd", "ug", "dv"]

    /// Sprache der App-Texte (gewählte App-Sprache, sonst Systemsprache).
    static var currentLanguage: String {
        let id = Bundle.main.preferredLocalizations.first ?? Locale.current.identifier
        return language(of: id)
    }

    /// «ar-EG» / «he_IL» → «ar» / «he».
    static func language(of identifier: String) -> String {
        String(identifier.split(whereSeparator: { $0 == "-" || $0 == "_" }).first ?? "").lowercased()
    }

    static func isRtl(_ language: String = currentLanguage) -> Bool { rtlLanguages.contains(language) }

    /// Zahl/Betrag immer links nach rechts; nur bei RTL-Sprache.
    static func ltr(_ text: String, language: String = currentLanguage) -> String {
        text.isEmpty || !isRtl(language) ? text : lri + text + pdi
    }

    /// Eingebetteter Name mit eigener Richtung; nur bei RTL-Sprache.
    static func isolate(_ text: String, language: String = currentLanguage) -> String {
        text.isEmpty || !isRtl(language) ? text : fsi + text + pdi
    }

    /// Unsichtbare Richtungszeichen (LRM, RLM, ALM, Einbettungen, Inseln).
    static let marks: Set<Character> = [
        "\u{200E}", "\u{200F}", "\u{061C}",
        "\u{202A}", "\u{202B}", "\u{202C}", "\u{202D}", "\u{202E}",
        "\u{2066}", "\u{2067}", "\u{2068}", "\u{2069}",
    ]

    /// Richtungszeichen entfernen — z. B. aus eingefügtem Text.
    static func strip(_ text: String) -> String { String(text.filter { !marks.contains($0) }) }
}
