import Foundation

/// Quelltext-Scan für `WatchlistConfirmCallGuardTests` (Review TST4-01): Schreibwege aus `AppData` ableiten
/// und je Aufruf prüfen, ob die Rückfrage F-08 davor liegt. Arbeitet auf UTF-16 (Positionen passen zu NSRegularExpression).
@MainActor
enum CallGuard {

    struct Param: Equatable {
        let label: String
        let name: String
        let hasDefault: Bool
        let isClosure: Bool
    }

    struct WritePath {
        let name: String
        let params: [Param]
    }

    struct Region {
        let name: String
        let start: Int
        /// Nach «(» der Funktion bzw. nach «{» der berechneten Eigenschaft.
        let headerEnd: Int
        let end: Int
        let isFunc: Bool
    }

    struct Result {
        var calls = 0
        var problems: [String] = []
        var exempted: [String] = []
    }

    /// Quelltext ohne Kommentare (durch Leerraum ersetzt); Zeichenketten bleiben, Kommentarzeichen darin zählen nicht.
    @MainActor
    final class Source {
        let u: [UInt16]
        let text: NSString
        private(set) lazy var regions: [Region] = CallGuard.regions(self)

        init(_ raw: String) {
            var stripper = Stripper(Array(raw.utf16))
            _ = stripper.code(0, untilParen: false)
            u = stripper.out
            text = String(decoding: stripper.out, as: UTF16.self) as NSString
        }

        func string(_ from: Int, _ to: Int) -> String { text.substring(with: NSRange(location: from, length: max(0, to - from))) }

        func matches(_ regex: NSRegularExpression, _ from: Int = 0, _ to: Int? = nil) -> [NSTextCheckingResult] {
            regex.matches(in: text as String, range: NSRange(location: from, length: (to ?? u.count) - from))
        }

        func group(_ m: NSTextCheckingResult, _ i: Int) -> String? {
            let r = m.range(at: i)
            return r.location == NSNotFound ? nil : text.substring(with: r)
        }

        /// Index der passenden schliessenden Klammer zu `open` (dort «(» bzw. «{»).
        func closing(_ open: Int) -> Int {
            let a = u[open], b: UInt16 = a == 40 ? 41 : 125
            var depth = 0
            for i in open..<u.count {
                if u[i] == a { depth += 1 } else if u[i] == b { depth -= 1; if depth == 0 { return i } }
            }
            return u.count - 1
        }

        /// Bereiche der Argumente zwischen `from` und `to` (Kommas auf oberster Ebene).
        func arguments(_ from: Int, _ to: Int) -> [String] {
            guard from < to else { return [] }
            var parts: [String] = [], depth = 0, start = from
            for i in from..<to {
                switch u[i] {
                case 40, 91, 123: depth += 1
                case 41, 93, 125: depth -= 1
                case 44 where depth == 0: parts.append(string(start, i)); start = i + 1
                default: break
                }
            }
            let last = string(start, to)
            if !last.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { parts.append(last) }
            return parts
        }

        func line(_ pos: Int) -> Int { u[0..<pos].filter { $0 == 10 }.count + 1 }
    }

    // MARK: Ableitung

    static func writePaths(_ repo: [Source]) -> [WritePath] {
        var writes: [WritePath] = []
        var all: [(Source, Region)] = []
        for src in repo {
            for region in src.regions where region.isFunc && region.name != "watchlistWriteRefused" {
                all.append((src, region))
                let refused = src.matches(refusedCall, region.headerEnd, region.end).compactMap { src.group($0, 1) }
                if !refused.isEmpty && !refused.allSatisfy({ $0.contains("notify: false") }) {
                    writes.append(WritePath(name: region.name, params: params(src, region)))
                }
            }
        }
        // Weiterleitungen (addWatch → tryAddWatch, deleteNotTradedForUndo → deleteForUndo(ids:)) zählen mit
        var changed = true
        while changed {
            changed = false
            for (src, region) in all {
                let own = params(src, region)
                if writes.contains(where: { $0.name == region.name && $0.params == own }) { continue }
                if calls(src, bareCall, region.headerEnd, region.end).contains(where: { isWrite($0, writes) }) {
                    writes.append(WritePath(name: region.name, params: own))
                    changed = true
                }
            }
        }
        return writes
    }

    // MARK: Prüfung

    static func check(_ sources: [String: Source], writes: [WritePath], allowed: [(file: String, function: String)]) -> Result {
        var result = Result()
        for (file, src) in sources.sorted(by: { $0.key < $1.key }) {
            for call in calls(src, dataCall, 0, src.u.count) where isWrite(call, writes) {
                result.calls += 1
                let function = src.regions.last(where: { $0.start <= call.pos })?.name ?? "?"
                if allowed.contains(where: { $0.file == file && $0.function == function }) {
                    result.exempted.append("\(file) \(function)")
                } else if !confirmed(sources, file, call.pos, seen: []) {
                    result.problems.append("\(file):\(src.line(call.pos)) \(function)")
                }
            }
        }
        return result
    }

    private struct Call {
        let pos: Int
        let name: String
        let labels: [String]
        let trailingClosure: Bool
    }

    private static func calls(_ src: Source, _ regex: NSRegularExpression, _ from: Int, _ to: Int) -> [Call] {
        src.matches(regex, from, to).compactMap { m -> Call? in
            guard let name = src.group(m, 1) else { return nil }
            let open = m.range.location + m.range.length - 1
            let close = src.closing(open)
            let labels = src.arguments(open + 1, close).map { arg -> String in
                guard let l = argLabel.firstMatch(in: arg, range: NSRange(location: 0, length: (arg as NSString).length)) else { return "_" }
                return (arg as NSString).substring(with: l.range(at: 1))
            }
            var k = close + 1
            while k < src.u.count && (src.u[k] == 32 || src.u[k] == 10 || src.u[k] == 9) { k += 1 }
            return Call(pos: m.range.location, name: name, labels: labels, trailingClosure: k < src.u.count && src.u[k] == 123)
        }
    }

    private static func isWrite(_ call: Call, _ writes: [WritePath]) -> Bool {
        writes.contains { $0.name == call.name && fits($0.params, call.labels, call.trailingClosure) }
    }

    /// Passen die Argument-Bezeichner zur Deklaration (Standardwerte dürfen fehlen, eine nachgestellte Closure füllt den
    /// ersten offenen Closure-Parameter)?
    private static func fits(_ params: [Param], _ labels: [String], _ trailing: Bool) -> Bool {
        var i = 0, trailingLeft = trailing
        for p in params {
            if i < labels.count && labels[i] == p.label { i += 1; continue }
            if p.hasDefault { continue }
            if trailingLeft && p.isClosure { trailingLeft = false; continue }
            return false
        }
        return i == labels.count
    }

    private static func params(_ src: Source, _ region: Region) -> [Param] {
        guard region.isFunc else { return [] }
        let close = src.closing(region.headerEnd - 1)
        return src.arguments(region.headerEnd, close).compactMap { raw -> Param? in
            let arg = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            let ns = arg as NSString
            guard let m = paramHead.firstMatch(in: arg, range: NSRange(location: 0, length: ns.length)) else { return nil }
            let label = ns.substring(with: m.range(at: 1))
            let inner = m.range(at: 2).location == NSNotFound ? label : ns.substring(with: m.range(at: 2))
            let type = ns.substring(from: m.range.location + m.range.length)
            return Param(label: label, name: inner, hasDefault: type.contains("="), isClosure: type.contains("->"))
        }
    }

    /// Liegt `pos` hinter der Rückfrage — oder fragen alle Aufrufer des umschliessenden Hilfswegs?
    private static func confirmed(_ sources: [String: Source], _ file: String, _ pos: Int, seen: Set<String>) -> Bool {
        guard let src = sources[file], let region = src.regions.last(where: { $0.start <= pos }) else { return false }
        // Der eigene Rumpf zählt nicht als fragender Hilfsweg (sonst gälte eine Funktion, die ihre
        // Closure-Parameter nur nach der Frage aufruft, z. B. `saveNote(_:close:cancelled:)`, auch für Schreibwege davor)
        if insideConfirm(sources, src, region.headerEnd, pos, seen: seen.union([region.name])) { return true }
        if seen.contains(region.name) || seen.count > 4 { return false }
        guard let callRegex = try? NSRegularExpression(pattern: "(?<![\\w])\(region.name)\\(") else { return false }
        var sites: [(String, Int)] = []
        for (other, s) in sources {
            for m in s.matches(callRegex) where !s.regions.contains(where: { $0.start <= m.range.location && m.range.location < $0.headerEnd }) {
                sites.append((other, m.range.location))
            }
        }
        return !sites.isEmpty && sites.allSatisfy { confirmed(sources, $0.0, $0.1, seen: seen.union([region.name])) }
    }

    private static func insideConfirm(_ sources: [String: Source], _ src: Source, _ from: Int, _ pos: Int, seen: Set<String>) -> Bool {
        guard from <= pos else { return false }
        // Offene Blöcke um pos, innen zuerst: Closure von withWatchlistConfirm oder eines fragenden Hilfswegs
        var open: [Int] = []
        for i in from..<pos {
            if src.u[i] == 123 { open.append(i) } else if src.u[i] == 125, !open.isEmpty { open.removeLast() }
        }
        for brace in open.reversed() {
            guard let name = callee(src, before: brace) else { continue }
            if name == "withWatchlistConfirm" { return true }
            if !seen.contains(name) && helperConfirms(sources, name, seen: seen.union([name])) { return true }
        }
        // await confirmWatchlistWrite(): nur als `guard await …confirmWatchlistWrite() else { … }` und nur danach im selben
        // Block (Review TST5-R2: die Richtung zählt — nicht im else-Block, keine anderen Formen wie `if`, `_ =`, `let ok =`)
        return src.matches(confirmCall, from, pos).contains { m -> Bool in
            guard let brace = guardElseBrace(src, m) else { return false }
            let elseEnd = src.closing(brace)
            guard elseEnd < pos else { return false }
            var depth = 0
            for i in (elseEnd + 1)..<pos {
                if src.u[i] == 123 { depth += 1 } else if src.u[i] == 125 { depth -= 1; if depth < 0 { return false } }
            }
            return true
        }
    }

    /// «{» des else-Blocks, wenn der Aufruf `m` als `guard await [data.]confirmWatchlistWrite() else {` steht; sonst nil.
    private static func guardElseBrace(_ src: Source, _ m: NSTextCheckingResult) -> Int? {
        let start = max(0, m.range.location - 120)
        let head = NSRange(location: start, length: m.range.location - start)
        guard guardHead.firstMatch(in: src.text as String, range: head) != nil else { return nil }
        let end = m.range.location + m.range.length
        let tail = NSRange(location: end, length: src.u.count - end)
        guard let e = elseOpen.firstMatch(in: src.text as String, options: .anchored, range: tail) else { return nil }
        return e.range.location + e.range.length - 1
    }

    /// Funktion `name`, die jede ihrer Closure-Aktionen nur im Block von withWatchlistConfirm ausführt (z. B. `moveButton`).
    private static func helperConfirms(_ sources: [String: Source], _ name: String, seen: Set<String>) -> Bool {
        for src in sources.values {
            for region in src.regions where region.isFunc && region.name == name {
                let actions = params(src, region).filter(\.isClosure).map(\.name)
                let uses = actions.flatMap { action -> [Int] in
                    guard let regex = try? NSRegularExpression(pattern: "(?<![\\w.])\(action)\\(") else { return [] }
                    return src.matches(regex, region.headerEnd, region.end).map(\.range.location)
                }
                if !uses.isEmpty && uses.allSatisfy({ insideConfirm(sources, src, region.headerEnd, $0, seen: seen) }) { return true }
            }
        }
        return false
    }

    /// Name des Aufrufs, dessen (erste bzw. nachgestellte) Closure bei `brace` beginnt; nil bei `label: {` (z. B. `cancelled:`).
    private static func callee(_ src: Source, before brace: Int) -> String? {
        var k = brace - 1
        while k >= 0 && (src.u[k] == 32 || src.u[k] == 10 || src.u[k] == 9) { k -= 1 }
        guard k >= 0 else { return nil }
        if src.u[k] == 41 {
            var depth = 0
            while k >= 0 {
                if src.u[k] == 41 { depth += 1 } else if src.u[k] == 40 { depth -= 1; if depth == 0 { break } }
                k -= 1
            }
            k -= 1
        } else if src.u[k] == 40 {
            k -= 1
        }
        while k >= 0 && src.u[k] == 32 { k -= 1 }
        var start = k
        while start >= 0, isIdentifier(src.u[start]) { start -= 1 }
        return start < k ? src.string(start + 1, k + 1) : nil
    }

    private static func isIdentifier(_ c: UInt16) -> Bool {
        (c >= 48 && c <= 57) || (c >= 65 && c <= 90) || (c >= 97 && c <= 122) || c == 95
    }

    static func regions(_ src: Source) -> [Region] {
        let found = src.matches(declaration)
        return found.enumerated().map { (i, m) -> Region in
            let isFunc = m.range(at: 1).location != NSNotFound
            return Region(name: src.group(m, isFunc ? 1 : 2) ?? "?", start: m.range.location,
                          headerEnd: m.range.location + m.range.length,
                          end: i + 1 < found.count ? found[i + 1].range.location : src.u.count, isFunc: isFunc)
        }
    }

    private static let declaration = try! NSRegularExpression(
        pattern: #"^[ \t]*(?:@\w+(?:\([^)\n]*\))?\s+)*(?:(?:private|fileprivate|internal|public|static|class|final|override|nonisolated|mutating)\s+)*(?:func\s+(\w+)\s*(?:<[^>\n]*>)?\s*\(|var\s+(\w+)\s*:[^=\n{]*\{[ \t]*$)"#,
        options: [.anchorsMatchLines])
    private static let refusedCall = try! NSRegularExpression(pattern: #"watchlistWriteRefused\(([^)]*)\)"#)
    private static let bareCall = try! NSRegularExpression(pattern: #"(?<![\w.])(?:self\.)?(\w+)\("#)
    private static let dataCall = try! NSRegularExpression(pattern: #"\bdata\s*\.\s*(\w+)\("#)
    private static let confirmCall = try! NSRegularExpression(pattern: #"confirmWatchlistWrite\(\)"#)
    private static let guardHead = try! NSRegularExpression(pattern: #"\bguard\s+await\s+(?:\w+\s*\.\s*)?$"#)
    private static let elseOpen = try! NSRegularExpression(pattern: #"\s*else\s*\{"#)
    private static let argLabel = try! NSRegularExpression(pattern: #"^\s*(\w+)\s*:(?!:)"#)
    private static let paramHead = try! NSRegularExpression(pattern: #"^(\w+)(?:\s+(\w+))?\s*:"#)
}

/// Kleiner Zerleger für Swift: Kommentare (auch verschachtelt) durch Leerraum ersetzen; Zeichenketten `"…"`, `"""…"""` und
/// `#"…"#` samt Interpolation `\(…)` überspringen, damit z. B. `"/0/*"` keinen Kommentar öffnet. REL5-R1:
/// auch erweiterte Regex-Literale `#/…/#` (beliebig viele `#`), wie `ios_release_audit.py`; nackte `/…/` bewusst nicht
/// (nur Swift 6 bzw. mit Compiler-Schalter, ohne Parser nicht sicher von Division zu trennen).
private struct Stripper {
    let t: [UInt16]
    var out: [UInt16]

    init(_ t: [UInt16]) {
        self.t = t
        out = t
    }

    private func has(_ i: Int, _ p: [UInt16]) -> Bool { i + p.count <= t.count && t[i..<(i + p.count)].elementsEqual(p) }

    private mutating func blank(_ from: Int, _ to: Int) {
        for k in from..<to where out[k] != 10 { out[k] = 32 }
    }

    /// Code ab `start`; mit `untilParen` bis nach der «)» einer Interpolation. Rückgabe: Index danach.
    mutating func code(_ start: Int, untilParen: Bool) -> Int {
        var i = start, depth = 0
        while i < t.count {
            if has(i, [47, 47]) {
                var j = i
                while j < t.count && t[j] != 10 { j += 1 }
                blank(i, j)
                i = j
            } else if has(i, [47, 42]) {
                var nested = 0, k = i
                while k < t.count {
                    if has(k, [47, 42]) {
                        nested += 1; k += 2
                    } else if has(k, [42, 47]) {
                        nested -= 1; k += 2
                        if nested == 0 { break }
                    } else {
                        k += 1
                    }
                }
                blank(i, k)
                i = k
            } else if t[i] == 34 || (t[i] == 35 && rawStringStart(i)) {
                i = string(i)
            } else if t[i] == 35 && regexStart(i) {
                i = regex(i)
            } else {
                if untilParen && t[i] == 40 { depth += 1 }
                if untilParen && t[i] == 41 {
                    if depth == 0 { return i + 1 }
                    depth -= 1
                }
                i += 1
            }
        }
        return t.count
    }

    private func rawStringStart(_ i: Int) -> Bool {
        var k = i
        while k < t.count && t[k] == 35 { k += 1 }
        return k < t.count && t[k] == 34
    }

    private func regexStart(_ i: Int) -> Bool {
        var k = i
        while k < t.count && t[k] == 35 { k += 1 }
        return k < t.count && t[k] == 47
    }

    /// Erweitertes Regex-Literal ab `start` (dort «#»): bis «/» plus gleich viele «#»; `\` maskiert das nächste Zeichen;
    /// mehrzeilig, wenn nach «#/» bis zum Zeilenende nur Leerzeichen/Tabs (und das CR von CRLF) stehen (REL6-R1).
    /// Bleibt stehen (wie Zeichenketten), wird nicht geleert.
    private func regex(_ start: Int) -> Int {
        var i = start, hashes = 0
        while t[i] == 35 { hashes += 1; i += 1 }
        i += 1
        var j = i
        while j < t.count && (t[j] == 32 || t[j] == 9) { j += 1 }
        if j < t.count && t[j] == 13 { j += 1 }
        let multi = j < t.count && t[j] == 10
        let close = [UInt16(47)] + [UInt16](repeating: 35, count: hashes)
        while i < t.count {
            if t[i] == 92 {
                i += 2
            } else if has(i, close) {
                return i + close.count
            } else if !multi && t[i] == 10 {
                return i
            } else {
                i += 1
            }
        }
        return t.count
    }

    private mutating func string(_ start: Int) -> Int {
        var i = start, hashes = 0
        while t[i] == 35 { hashes += 1; i += 1 }
        let multi = has(i, [34, 34, 34])
        i += multi ? 3 : 1
        let close = [UInt16](repeating: 34, count: multi ? 3 : 1) + [UInt16](repeating: 35, count: hashes)
        let escape = [UInt16(92)] + [UInt16](repeating: 35, count: hashes)
        while i < t.count {
            if has(i, escape) {
                let j = i + escape.count
                if j < t.count && t[j] == 40 {
                    i = code(j + 1, untilParen: true)
                } else {
                    i = j + 1
                }
            } else if has(i, close) {
                return i + close.count
            } else if !multi && t[i] == 10 {
                return i
            } else {
                i += 1
            }
        }
        return t.count
    }
}
