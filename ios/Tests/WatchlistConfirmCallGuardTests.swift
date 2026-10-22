import XCTest

/// Seit Build 106 (Review TST4-01, F-08): Rückfrage «Merkliste wird nicht gespeichert» je AUFRUF statt je Datei — wie
/// Android `WatchRepositoryCallGuardTest`. Die Schreibwege kommen aus `AppData` (Funktionen in `App/Services/AppData*.swift`,
/// deren Rumpf `watchlistWriteRefused(` enthält, plus Weiterleitungen darauf; Überladungen nach Argument-Bezeichnern). Jeder
/// Aufruf `data.<Schreibweg>(` in `App` ausserhalb von `Services` liegt im Block von `withWatchlistConfirm { … }` (nicht im
/// `cancelled:`-Block), in der Aktion eines Hilfswegs, der seine Aktion nur darin ausführt (z. B. `moveButton`), oder hinter
/// `await confirmWatchlistWrite()` (Block noch offen). Ein Hilfsweg ohne eigene Frage gilt, wenn jeder Aufrufer fragt (z. B.
/// `addStarterConfirmed`; seit Build 107 `saveDraftNow`, einziger Aufrufer `saveFromEditor` fragt). Der eigene Rumpf einer
/// Funktion zählt dabei nie als fragender Hilfsweg (seit Build 107).
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
        XCTAssertTrue(result.problems.isEmpty, "Schreibweg ohne Rückfrage F-08 davor:\n" + result.problems.joined(separator: "\n"))
    }

    /// Gegenprobe mit eingebautem Beispiel: ohne Rückfrage, im `cancelled:`-Block, nach dem Block, Zeichenkette mit
    /// Kommentarzeichen → Befund; Hilfsweg, `confirmWatchlistWrite` und andere Überladung → kein Befund. Seit Build 107:
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
            func okAsync() {
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
                       ["bad", "cancelledPath", "afterBlock", "stringOpensComment", "regexHidesNothing", "ownClosureParam"])
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
