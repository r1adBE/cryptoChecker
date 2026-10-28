import XCTest

/// Rückfrage «Merkliste wird nicht gespeichert» je AUFRUF statt je Datei — wie
/// Android `WatchRepositoryCallGuardTest`. Die Schreibwege kommen aus `AppData` (Funktionen in `App/Services/AppData*.swift`,
/// deren Rumpf `watchlistWriteRefused(` enthält, plus Weiterleitungen darauf; Überladungen nach Argument-Bezeichnern). Jeder
/// Aufruf `data.<Schreibweg>(` in `App` ausserhalb von `Services` liegt im Block von `withWatchlistConfirm { … }` (nicht im
/// `cancelled:`-Block) oder in der Aktion eines Hilfswegs, der seine Aktion nur darin ausführt (z. B. `moveButton`). Ein
/// Hilfsweg ohne eigene Frage gilt, wenn jeder Aufrufer fragt (z. B. `addStarterConfirmed` oder `saveDraftNow`, dessen
/// einziger Aufrufer `saveFromEditor` fragt). Der eigene Rumpf einer Funktion zählt dabei nie als fragender Hilfsweg.
/// `confirmWatchlistWrite` ist privat (die Aktion läuft nur über `withWatchlistConfirm` unter
/// `runConsented`), ein direkter Aufruf zählt nie; ein Schreibweg in einer losgelösten Ausführung im Block
/// (`Task.detached`, `DispatchQueue….async`/`asyncAfter`) ist ein Befund — ein erbender `Task { … }` nicht.
/// Abgrenzung: Das Verhalten der Zustimmung selbst (ohne abgelehnt, mit geschrieben, erbender Task ja, `Task.detached`
/// nein) prüft `WatchlistConsentBehaviorTests` an den Schreibwegen von AppData. Dieser Wächter sichert zusätzlich, was
/// kein Verhaltenstest abdeckt: dass JEDER Aufruf in den Bildschirmen vorher fragt.
@MainActor
final class WatchlistConfirmCallGuardTests: XCTestCase {

    /// Erlaubte Aufrufe ohne Rückfrage (Datei relativ zu `App`, umschliessende Funktion). Leer: Systemwege wie «1 h
    /// später» laufen unter iOS in `AppData` selbst (Services), nicht in Bildschirmen.
    private static let allowed: [(file: String, function: String)] = []

    private var appDir: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("App")
    }

    func testEveryWatchlistWriteCallConfirmsFirst() throws {
        let all = try swiftSources(in: appDir)
        let repo = all.filter { $0.key.hasPrefix("Services/AppData") }.map(\.value)
        let writes = CallGuard.writePaths(repo)
        let names = Set(writes.map(\.name))
        for expected in ["toggleFavorite", "tryAddWatch", "addWatch", "saveAlarm", "deleteNotTradedForUndo", "setAlarmEnabled"] {
            XCTAssertTrue(names.contains(expected), "Schreibweg \(expected) nicht abgeleitet: \(names.sorted())")
        }
        // Überladung ohne Merkliste (Favoriten der Auswahllisten) zählt nicht
        XCTAssertEqual(writes.filter { $0.name == "toggleFavorite" }.map(\.params.count), [1])
        let screens = all.filter { !$0.key.hasPrefix("Services/") }
        let result = CallGuard.check(screens, writes: writes, allowed: Self.allowed)
        XCTAssertGreaterThanOrEqual(result.calls, 30, "zu wenige Aufrufe gefunden — Scan defekt?")
        XCTAssertEqual(result.exempted.count, Self.allowed.count, "Ausnahmeliste veraltet")
        XCTAssertTrue(result.problems.isEmpty, "Schreibweg ohne Rückfrage «nicht gespeichert» davor:\n" + result.problems.joined(separator: "\n"))
    }

    /// Gegenprobe mit eingebautem Beispiel: ohne Rückfrage, direkt hinter `confirmWatchlistWrite`, im
    /// `cancelled:`-Block, nach dem Block, Zeichenkette mit Kommentarzeichen → Befund; Hilfsweg und andere Überladung → kein Befund.
    /// Regex-Literal mit `//` verdeckt nichts; Schreibweg vor der Frage in einer Funktion mit Closure-Parameter → Befund.
    func testCounterSampleIsFlagged() {
        let repo = CallGuard.Source(#"""
        extension AppData {
            func toggleFavorite(_ watch: Watch) { guard !watchlistWriteRefused() else { return } }
            func toggleFavorite(_ kind: FavoriteKind, _ item: String) { favorites.toggle() }
            func reorder(_ ids: [Int64]) { guard !watchlistWriteRefused() else { return } }
            func setNote(_ watch: Watch, _ note: String?) { guard !watchlistWriteRefused() else { return } }
            func follow(_ id: Int64) { guard !watchlistWriteRefused(notify: false) else { return } }
            func wrapper(_ ids: [Int64]) { reorder(ids) }
        }
        """#)
        let writes = CallGuard.writePaths([repo])
        XCTAssertEqual(Set(writes.map(\.name)), ["toggleFavorite", "reorder", "setNote", "wrapper"])
        let sample = CallGuard.Source(#"""
        extension X {
            func ok() {
                data.withWatchlistConfirm { data.toggleFavorite(w) }
            }
            func okHelper() {
                tile { data.reorder([]) }
            }
            private func tile(action: @escaping () -> Void) -> some View {
                Button { data.withWatchlistConfirm { action() } } label: { Text("x") }
            }
            func directConfirm() {
                Task { guard await data.confirmWatchlistWrite() else { return }; data.setNote(w, nil) }
            }
            func bad() {
                data.toggleFavorite(w)
            }
            func cancelledPath() {
                data.withWatchlistConfirm { } cancelled: { data.reorder([]) }
            }
            func afterBlock() {
                data.withWatchlistConfirm { }
                data.setNote(w, nil)
            }
            func stringOpensComment() {
                let s = "/* kein Kommentar \(s.count)"; data.reorder([]); let e = "*/"
            }
            func regexHidesNothing() {
                let r = #/a//b/#; data.reorder([])
            }
            func ownClosureParam(_ note: String?, close: @escaping () -> Void) {
                data.setNote(w, note)
                data.withWatchlistConfirm({ close() }, cancelled: nil)
            }
            func otherOverload() {
                data.toggleFavorite(.coin, "BTC")
            }
            // data.reorder([]) — Kommentar zählt nicht
        }
        """#)
        let result = CallGuard.check(["Features/Sample.swift": sample], writes: writes, allowed: [])
        XCTAssertEqual(result.calls, 9)
        XCTAssertEqual(result.problems.map { $0.components(separatedBy: " ").last ?? "" },
                       ["directConfirm", "bad", "cancelledPath", "afterBlock", "stringOpensComment", "regexHidesNothing",
                        "ownClosureParam"])
    }

    /// `confirmWatchlistWrite` ist privat — keine Form eines direkten Aufrufs zählt, auch nicht
    /// `guard await … else { … }` mit Schreibweg danach (die Aktion liefe nicht unter `runConsented`); ebenso im else-Block,
    /// im `else` eines `if`, nach `if !(…)`, hinter unbenutztem oder gespeichertem Ergebnis, im positiven `if` und
    /// ausserhalb des Blocks → Befund.
    func testDirectConfirmCallNeverCounts() {
        let repo = CallGuard.Source(#"""
        extension AppData {
            func reorder(_ ids: [Int64]) { guard !watchlistWriteRefused() else { return } }
            func setNote(_ watch: Watch, _ note: String?) { guard !watchlistWriteRefused() else { return } }
        }
        """#)
        let writes = CallGuard.writePaths([repo])
        let sample = CallGuard.Source(#"""
        extension X {
            func guardLine() {
                Task { guard await data.confirmWatchlistWrite() else { return }; data.setNote(w, nil) }
            }
            func guardBlock() {
                Task {
                    guard await data.confirmWatchlistWrite() else {
                        cancelled()
                        return
                    }
                    data.reorder([])
                }
            }
            func inElse() {
                Task { guard await data.confirmWatchlistWrite() else { data.reorder([]); return } }
            }
            func ifElse() {
                Task { if await data.confirmWatchlistWrite() { } else { data.setNote(w, nil) } }
            }
            func negatedIf() {
                Task { if !(await data.confirmWatchlistWrite()) { data.reorder([]) } }
            }
            func ignored() {
                Task { _ = await data.confirmWatchlistWrite(); data.reorder([]) }
            }
            func stored() {
                Task { let ok = await data.confirmWatchlistWrite(); if ok { data.reorder([]) } }
            }
            func positiveIf() {
                Task { if await data.confirmWatchlistWrite() { data.reorder([]) } }
            }
            func outsideTask() {
                Task { guard await data.confirmWatchlistWrite() else { return } }
                data.reorder([])
            }
        }
        """#)
        let result = CallGuard.check(["Features/Sample.swift": sample], writes: writes, allowed: [])
        XCTAssertEqual(result.calls, 9)
        XCTAssertEqual(result.problems.map { $0.components(separatedBy: " ").last ?? "" },
                       ["guardLine", "guardBlock", "inElse", "ifElse", "negatedIf", "ignored", "stored", "positiveIf", "outsideTask"])
    }

    /// Gegenprobe zum Ausweichen aus der Aktion. Ein erbender `Task` im Block und ein geschlossener
    /// losgelöster Block davor bestehen; ein Schreibweg in `Task.detached`, `DispatchQueue….async`/`asyncAfter` (auch mit
    /// `execute:`) im Block von `withWatchlistConfirm` oder eines fragenden Hilfswegs → Befund.
    func testDetachedExecutionIsFlagged() {
        let repo = CallGuard.Source(#"""
        extension AppData {
            func reorder(_ ids: [Int64]) { guard !watchlistWriteRefused() else { return } }
            func setNote(_ watch: Watch, _ note: String?) { guard !watchlistWriteRefused() else { return } }
        }
        """#)
        let writes = CallGuard.writePaths([repo])
        let sample = CallGuard.Source(#"""
        extension X {
            func okTask() {
                data.withWatchlistConfirm { Task { await load(); data.reorder([]) } }
            }
            func okClosedDetached() {
                data.withWatchlistConfirm {
                    Task.detached { log() }
                    data.setNote(w, nil)
                }
            }
            func inDetached() {
                data.withWatchlistConfirm { Task.detached(priority: .low) { await data.reorder([]) } }
            }
            func inMainAsync() {
                data.withWatchlistConfirm { DispatchQueue.main.async { data.reorder([]) } }
            }
            func inAsyncAfter() {
                data.withWatchlistConfirm {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { data.setNote(w, nil) }
                }
            }
            func withExecute() {
                data.withWatchlistConfirm({ DispatchQueue.global().async(execute: { data.reorder([]) }) }, cancelled: nil)
            }
            func viaHelper() {
                tile { DispatchQueue.main.async { data.reorder([]) } }
            }
            private func tile(action: @escaping () -> Void) -> some View {
                Button { data.withWatchlistConfirm { action() } } label: { Text("x") }
            }
        }
        """#)
        let result = CallGuard.check(["Features/Sample.swift": sample], writes: writes, allowed: [])
        XCTAssertEqual(result.calls, 7)
        XCTAssertEqual(result.problems.map { $0.components(separatedBy: " ").last ?? "" },
                       ["inDetached", "inMainAsync", "inAsyncAfter", "withExecute", "viaHelper"])
    }

    /// Mehrzeiliges Regex-Literal auch mit Leerzeichen/Tab bzw. CRLF nach `#/` — ein `/*` darin öffnet keinen
    /// Kommentar, der Schreibweg danach bleibt sichtbar (wie `ios_release_audit.py --self-test`).
    func testMultilineRegexWithSpaceOrCRLF() {
        let repo = CallGuard.Source(#"""
        extension AppData {
            func reorder(_ ids: [Int64]) { guard !watchlistWriteRefused() else { return } }
        }
        """#)
        let writes = CallGuard.writePaths([repo])
        XCTAssertEqual(writes.map(\.name), ["reorder"])
        for gap in [" \t\n", "\r\n", " \r\n"] {
            let text = "extension X {\n    func spaced() {\n        let r = #/" + gap + "  a /* c" + gap
                + "/#\n        data.reorder([])\n    }\n}\n"
            let result = CallGuard.check(["Features/Sample.swift": CallGuard.Source(text)], writes: writes, allowed: [])
            XCTAssertEqual(result.problems.map { $0.components(separatedBy: " ").last ?? "" }, ["spaced"], gap.debugDescription)
        }
    }

    /// Alle Swift-Dateien unter `dir` (Schlüssel relativ zu `dir`), ohne Kommentare.
    private func swiftSources(in dir: URL) throws -> [String: CallGuard.Source] {
        var out: [String: CallGuard.Source] = [:]
        let base = dir.resolvingSymlinksInPath().path + "/"
        guard let walker = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: nil) else { return out }
        for case let url as URL in walker where url.pathExtension == "swift" {
            let path = url.resolvingSymlinksInPath().path
            guard path.hasPrefix(base) else { continue }
            let rel = String(path.dropFirst(base.count))
            out[rel] = CallGuard.Source(try String(contentsOf: url, encoding: .utf8))
        }
        XCTAssertFalse(out.isEmpty, "\(dir.path) leer")
        return out
    }
}
