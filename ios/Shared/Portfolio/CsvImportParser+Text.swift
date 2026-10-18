import Foundation

// Text der Datei (Teil von `CsvImportParser`): Zeichensatz (UTF-8, Windows-1252), Zellen zerlegen und Wörter
// vereinheitlichen — wie `CsvImportParser.kt` (Android).

extension CsvImportParser {
    // MARK: - Zeichensatz

    /// Windows-1252 0x80–0x9F (nicht belegte Bytes bleiben ihr Codepunkt, wie WHATWG); Rest = Latin-1. Wie Android.
    private static let cp1252High: [UInt32] = [
        0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
        0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178,
    ]

    /// Bytes der Datei → Text: gültiges UTF-8, sonst Windows-1252 (Excel unter Windows: «Gebühr» als 0xFC). BOM am Anfang
    /// weg. Wie Android `decode`.
    static func decode(_ data: Data) -> String {
        var text = String(data: data, encoding: .utf8) ?? decodeCp1252(data)
        if text.unicodeScalars.first == "\u{FEFF}" { text.unicodeScalars.removeFirst() }
        return text
    }

    static func decodeCp1252(_ data: Data) -> String {
        var scalars = String.UnicodeScalarView()
        for b in data {
            let v = UInt32(b)
            let code = (0x80...0x9F).contains(v) ? cp1252High[Int(v - 0x80)] : v
            if let u = Unicode.Scalar(code) { scalars.append(u) }
        }
        return String(scalars)
    }

    // MARK: - Zerlegen

    private static func isBlank(_ s: String.UnicodeScalarView) -> Bool {
        s.allSatisfy { $0.properties.isWhitespace }
    }

    static func trim(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// RFC-4180-Zerlegung («""» = «"», Umbruch in Anführungszeichen erlaubt); Zellen getrimmt, leere Zeilen weg.
    static func tokenize(_ text: [Unicode.Scalar], delimiter: Unicode.Scalar, maxRecords: Int = .max) -> [Record] {
        var out: [Record] = []
        var cells: [String] = []
        var cell = String.UnicodeScalarView()
        var inQuotes = false
        var line = 1
        var startLine = 1
        func endCell() {
            cells.append(trim(String(cell)))
            cell = String.UnicodeScalarView()
        }
        func endRecord() {
            endCell()
            if cells.contains(where: { !$0.isEmpty }) { out.append(Record(line: startLine, cells: cells)) }
            cells = []
        }
        var i = 0
        let n = text.count
        // Höchstens `maxRecords` Zeilen (Erkennung der Kopfzeile): danach nicht weiterlesen
        while i < n && out.count < maxRecords {
            let c = text[i]
            if inQuotes && c == "\"" && i + 1 < n && text[i + 1] == "\"" {
                cell.append("\"")
                i += 1
            } else if inQuotes && c == "\"" {
                inQuotes = false
            } else if inQuotes {
                if c == "\n" { line += 1 }
                cell.append(c)
            } else if c == "\"" && isBlank(cell) {
                cell = String.UnicodeScalarView()
                inQuotes = true
            } else if c == delimiter {
                endCell()
            } else if c == "\n" {
                endRecord()
                line += 1
                startLine = line
            } else {
                cell.append(c)
            }
            i += 1
        }
        if out.count < maxRecords && (!cell.isEmpty || !cells.isEmpty) { endRecord() }
        return out
    }

    /// Spaltenname/Art vergleichbar machen: klein, Umlaute ausgeschrieben, nur a–z und 0–9.
    static func normalize(_ text: String) -> String {
        var out = ""
        for ch in text.lowercased() {
            switch ch {
            case "ä": out += "ae"
            case "ö": out += "oe"
            case "ü": out += "ue"
            case "ß": out += "ss"
            case "é", "è", "ê", "ë": out += "e"
            case "à", "â": out += "a"
            case "ô": out += "o"
            case "î", "ï": out += "i"
            case "ç": out += "c"
            default:
                if ch.isASCII, let a = ch.asciiValue, (a >= 97 && a <= 122) || (a >= 48 && a <= 57) {
                    out.append(ch)
                }
            }
        }
        return out
    }
}
