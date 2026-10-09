import Foundation

/// Suche in den Einstellungen (Runde 31) — reine Logik, wie `SettingsSearch.kt`.
///
/// Ein Eintrag ist eine Zeile der Hauptseite oder ein Punkt einer Unterseite: `title` wie
/// angezeigt, `path` die Seite bzw. Gruppe darüber (z. B. «Alarme»), `synonyms` weitere
/// Wörter aus den Hinweistexten darunter. `id` führt die Oberfläche zum Ziel.
///
/// Verglichen wird ohne Gross-/Kleinschreibung (nach den Regeln der Sprache) und ohne
/// Akzente: «uberwachung» findet «Überwachung», «ALARM» findet «Alarme», «cafe» «Café».
/// Jedes Wort der Suche muss passen; Treffer im Titel stehen vor Treffern im Hinweis.
struct SettingsSearchEntry: Hashable {
    let id: String
    let title: String
    var path: String = ""
    var synonyms: [String] = []
}

enum SettingsSearch {

    /// Kürzeste Suchwortlänge für Treffer in Hinweistexten (sonst passt «a» überall).
    static let minSynonymToken = 2

    // Punkte je Suchwort, höchster zählt
    private static let titleStart = 100
    private static let titleWord = 80
    private static let titlePart = 60
    private static let pathWord = 30
    private static let synonymWord = 25
    private static let synonymPart = 20

    /// Vorbereiteter Eintrag: einmal normalisiert, dann für jede Eingabe wiederverwendet.
    struct Index {
        fileprivate let items: [Item]
        fileprivate let locale: Locale
        var count: Int { items.count }
    }

    fileprivate struct Item {
        let entry: SettingsSearchEntry
        let title: String
        let titleWords: [String]
        let pathWords: [String]
        let synonyms: [String]
        let synonymWords: [String]
    }

    /// Baut den Index; doppelte `id` zählen nur beim ersten Mal.
    static func index(_ entries: [SettingsSearchEntry], locale: Locale) -> Index {
        var seen = Set<String>()
        let items = entries.filter { seen.insert($0.id).inserted }.map { entry -> Item in
            let title = normalize(entry.title, locale: locale)
            let synonyms = entry.synonyms.map { normalize($0, locale: locale) }.filter { !$0.isEmpty }
            return Item(
                entry: entry,
                title: title,
                titleWords: words(title),
                pathWords: words(normalize(entry.path, locale: locale)),
                synonyms: synonyms,
                synonymWords: synonyms.flatMap { words($0) }
            )
        }
        return Index(items: items, locale: locale)
    }

    /// Treffer zu `query`, beste zuerst (bei Gleichstand in der Reihenfolge des Index). Leer bei leerer Suche.
    static func search(_ index: Index, query: String) -> [SettingsSearchEntry] {
        let queryTokens = Self.tokens(query, locale: index.locale)
        guard !queryTokens.isEmpty else { return [] }
        var scored: [(entry: SettingsSearchEntry, points: Int, position: Int)] = []
        for (position, item) in index.items.enumerated() {
            var total = 0
            var matched = true
            for token in queryTokens {
                let points = Self.score(item, token)
                if points == 0 { matched = false; break }
                total += points
            }
            if matched { scored.append((item.entry, total, position)) }
        }
        return scored
            .sorted { $0.points != $1.points ? $0.points > $1.points : $0.position < $1.position }
            .map(\.entry)
    }

    /// Bequem für einmalige Suchen (Tests): Index bauen und suchen.
    static func search(_ entries: [SettingsSearchEntry], query: String, locale: Locale) -> [SettingsSearchEntry] {
        search(Self.index(entries, locale: locale), query: query)
    }

    /// Suchwörter: normalisiert, an Leerraum getrennt.
    static func tokens(_ query: String, locale: Locale) -> [String] {
        normalize(query, locale: locale).split(separator: " ").map(String.init)
    }

    /// Vergleichsform: Kleinbuchstaben nach `locale` (Türkisch «I» → «ı»), Akzente und
    /// Vokalzeichen (Arabisch, Hebräisch) entfernt, Sonderbuchstaben ausgeschrieben
    /// («ß» → «ss», «ø» → «o», «ı» → «i»), Leerraum zu einem Leerzeichen. Japanische
    /// Dakuten (が ≠ か) und Schriftzeichen anderer Schriften bleiben unverändert.
    static func normalize(_ text: String, locale: Locale) -> String {
        let decomposed = text.lowercased(with: locale).decomposedStringWithCanonicalMapping
        var out = String.UnicodeScalarView()
        var lastSpace = true
        for scalar in decomposed.unicodeScalars {
            // Akzente und Tatweel (arabische Dehnung) tragen nichts zum Vergleich bei
            if isStrippedMark(scalar) || scalar.value == 0x0640 { continue }
            if scalar.properties.isWhitespace {
                if !lastSpace { out.append(" ") }
                lastSpace = true
                continue
            }
            lastSpace = false
            switch scalar {
            case "ß": out.append(contentsOf: "ss".unicodeScalars)
            case "ı": out.append("i")
            case "ø": out.append("o")
            case "æ": out.append(contentsOf: "ae".unicodeScalars)
            case "œ": out.append(contentsOf: "oe".unicodeScalars)
            case "đ": out.append("d")
            case "ł": out.append("l")
            default: out.append(scalar)
            }
        }
        if let last = out.last, last == " " { out.removeLast() }
        return String(out).precomposedStringWithCanonicalMapping
    }

    /// Akzente (U+0300–036F), arabische Vokalzeichen, hebräische Punkte.
    static func isStrippedMark(_ scalar: Unicode.Scalar) -> Bool {
        let c = scalar.value
        return (0x0300...0x036F).contains(c) || (0x064B...0x065F).contains(c) || c == 0x0670 ||
            ((0x0591...0x05C7).contains(c) && scalar.properties.generalCategory == .nonspacingMark)
    }

    /// Wörter: Folgen aus Buchstaben, Ziffern und Zeichen (Schriften ohne Leerzeichen bleiben am Stück).
    static func words(_ text: String) -> [String] {
        var result: [String] = []
        var current = String.UnicodeScalarView()
        for scalar in text.unicodeScalars {
            switch scalar.properties.generalCategory {
            case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
                 .decimalNumber, .nonspacingMark, .spacingMark:
                current.append(scalar)
            default:
                if !current.isEmpty {
                    result.append(String(current))
                    current = String.UnicodeScalarView()
                }
            }
        }
        if !current.isEmpty { result.append(String(current)) }
        return result
    }

    // Vergleich Zeichen für Zeichen (UTF-16, wie Kotlin) — nicht nach Graphemen, damit «स»
    // auch in «से» passt.
    private static func starts(_ text: String, with token: String) -> Bool {
        text.range(of: token, options: [.literal, .anchored]) != nil
    }

    private static func contains(_ text: String, _ token: String) -> Bool {
        text.range(of: token, options: .literal) != nil
    }

    private static func score(_ item: Item, _ token: String) -> Int {
        if Self.starts(item.title, with: token) { return titleStart }
        if item.titleWords.contains(where: { Self.starts($0, with: token) }) { return titleWord }
        if Self.contains(item.title, token) { return titlePart }
        if item.pathWords.contains(where: { Self.starts($0, with: token) }) { return pathWord }
        if token.utf16.count < minSynonymToken { return 0 }
        if item.synonymWords.contains(where: { Self.starts($0, with: token) }) { return synonymWord }
        if item.synonyms.contains(where: { Self.contains($0, token) }) { return synonymPart }
        return 0
    }
}
