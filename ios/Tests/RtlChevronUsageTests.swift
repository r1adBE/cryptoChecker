import XCTest

/// Richtungs-Pfeile folgen der Leserichtung: `chevron.forward`/`chevron.backward` spiegeln sich in Arabisch,
/// Persisch und Hebräisch, `chevron.right`/`chevron.left` zeigten dort in die falsche Richtung. Quelltext-Scan über
/// `App`, `Shared` und `Widgets` (Symbolnamen in Anführungszeichen, auch in `systemImage:`); zusammengesetzte Symbole wie
/// `chevron.left.forwardslash.chevron.right` (Code-Zeichen `</>`) zählen nicht.
final class RtlChevronUsageTests: XCTestCase {

    private static let areas = ["App", "Shared", "Widgets"]

    private var iosDir: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
    }

    /// Fundstellen «Datei:Zeile: Symbol» in `text`.
    static func offenders(_ text: String, file: String) -> [String] {
        var out: [String] = []
        for (index, line) in text.components(separatedBy: "\n").enumerated() {
            for symbol in ["\"chevron.right\"", "\"chevron.left\""] where line.contains(symbol) {
                out.append("\(file):\(index + 1): \(symbol) → chevron.forward/chevron.backward")
            }
        }
        return out
    }

    func testNoFixedDirectionChevrons() throws {
        var problems: [String] = []
        var files = 0
        for area in Self.areas {
            let dir = iosDir.appendingPathComponent(area)
            guard let walker = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: nil) else { continue }
            for case let url as URL in walker where url.pathExtension == "swift" {
                files += 1
                let text = try String(contentsOf: url, encoding: .utf8)
                problems += Self.offenders(text, file: "\(area)/\(url.lastPathComponent)")
            }
        }
        XCTAssertGreaterThan(files, 50, "zu wenige Swift-Dateien gefunden — Pfad defekt?")
        XCTAssertTrue(problems.isEmpty, "Feste Pfeilrichtung (RTL):\n" + problems.joined(separator: "\n"))
    }

    /// Gegenprobe: feste Richtungen gefunden, gespiegelte und zusammengesetzte Symbole nicht.
    func testCounterSample() {
        let sample = """
        Image(systemName: "chevron.right")
        Label("x", systemImage: "chevron.left")
        Image(systemName: "chevron.forward")
        Image(systemName: "chevron.backward")
        Image(systemName: "chevron.left.forwardslash.chevron.right")
        Image(systemName: "chevron.up.chevron.down")
        """
        let found = Self.offenders(sample, file: "Sample.swift")
        XCTAssertEqual(found.count, 2, found.joined(separator: "\n"))
        XCTAssertTrue(found[0].hasPrefix("Sample.swift:1:"))
        XCTAssertTrue(found[1].hasPrefix("Sample.swift:2:"))
    }
}
