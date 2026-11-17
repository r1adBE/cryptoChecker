import Foundation

/// Fehlerprotokoll für den Diagnosebericht: die letzten `maxEntries` Fehler mit Zeit, Stufe, Bereich und Fehlertyp —
/// nie Meldungstexte. Bereich und Typ werden gefiltert und gekürzt (`field`), damit kein freier Text hineingerät. Ein
/// gleicher Eintrag innert `repeatWindowMillis` wird nicht nochmals geschrieben. Liegt nur auf dem Gerät (App Group,
/// aus dem Geräte-Backup ausgenommen) und geht nur mit dem Bericht hinaus, den man selbst teilt. Wie Android `ErrorLog`,
/// gemeinsame Testfälle `error_log.json`.
enum ErrorLog {
    static let maxEntries = 100
    static let fieldMaxLength = 80
    static let repeatWindowMillis: Int64 = 60_000
    static let fileName = "error_log.txt"
    private static let allowed = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.$:-<>")
    private static let lock = NSLock()
    /// Schreibt der Faden gerade? Dann nichts aufzeichnen (ein Fehler beim Schreiben käme sonst wieder hierher).
    private static let writingKey = "ErrorLog.writing"

    struct Entry: Equatable {
        let millis: Int64
        let level: String
        let area: String
        let type: String
    }

    /// Einen Fehler festhalten (`level` "E" oder "W"); wirft nie. Ohne App Group: nichts.
    static func record(_ level: Character = "E", area: String, type: String?, now: Int64 = EpochTime.nowMillis) {
        guard Thread.current.threadDictionary[writingKey] == nil,
              let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: SharedStorage.appGroup)
        else { return }
        Thread.current.threadDictionary[writingKey] = true
        defer { Thread.current.threadDictionary[writingKey] = nil }
        let url = dir.appendingPathComponent(fileName)
        lock.lock()
        defer { lock.unlock() }
        let next = appended(readLines(url), line(now, level, area, type))
        guard (try? Data((next.joined(separator: "\n") + "\n").utf8).write(to: url, options: .atomic)) != nil else { return }
        // Nach jedem atomaren Schreiben neu setzen (neue Datei ohne Merkmal)
        BackupExclusion.set(excluded: true, for: url)
    }

    /// Gespeicherte Zeilen (älteste zuerst); ohne App Group oder unlesbar leer.
    static func storedLines() -> [String] {
        guard let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: SharedStorage.appGroup)
        else { return [] }
        lock.lock()
        defer { lock.unlock() }
        return readLines(dir.appendingPathComponent(fileName))
    }

    private static func readLines(_ url: URL) -> [String] {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return text.split(separator: "\n").map(String.init).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    /// Erlaubte Zeichen eines Felds: Buchstaben, Ziffern und `_ . $ : - < >`; Rest fällt weg; leer → «-».
    static func field(_ text: String?) -> String {
        let kept = String((text ?? "").filter { allowed.contains($0) }.prefix(fieldMaxLength))
        return kept.isEmpty ? "-" : kept
    }

    /// Gespeicherte Zeile «Zeit|Stufe|Bereich|Typ»; Stufe E oder W (sonst E).
    static func line(_ millis: Int64, _ level: Character, _ area: String, _ type: String?) -> String {
        [String(millis), level == "W" ? "W" : "E", field(area), field(type)].joined(separator: "|")
    }

    /// Eintrag aus einer gespeicherten Zeile; nil = unlesbar.
    static func parse(_ line: String) -> Entry? {
        let parts = line.trimmingCharacters(in: .whitespaces).split(separator: "|", omittingEmptySubsequences: false)
            .map(String.init)
        guard parts.count == 4, let millis = Int64(parts[0]), millis >= 0, parts[1] == "E" || parts[1] == "W" else {
            return nil
        }
        return Entry(millis: millis, level: parts[1], area: field(parts[2]), type: field(parts[3]))
    }

    /// Zeilen nach dem Anfügen von `line`: gleicher Eintrag wie der letzte innert `repeatWindowMillis` → unverändert;
    /// sonst angefügt und auf die letzten `maxEntries` gekürzt. Unlesbare Zeilen fallen weg.
    static func appended(_ lines: [String], _ line: String) -> [String] {
        let kept = lines.filter { parse($0) != nil }
        guard let new = parse(line) else { return Array(kept.suffix(maxEntries)) }
        if let last = kept.last.flatMap(parse), last.area == new.area, last.type == new.type, last.level == new.level,
           (0..<repeatWindowMillis).contains(new.millis - last.millis) {
            return Array(kept.suffix(maxEntries))
        }
        return Array((kept + [line]).suffix(maxEntries))
    }

    /// Zeilen für den Bericht, neueste zuletzt: «2026-11-17 18:40 E refresh SocketTimeoutException».
    static func shown(_ lines: [String], timeZone: TimeZone) -> [String] {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return lines.compactMap(parse).map { entry in
            let time = formatter.string(from: Date(timeIntervalSince1970: Double(entry.millis) / 1000))
            return "\(time) \(entry.level) \(entry.area) \(entry.type)"
        }
    }
}
