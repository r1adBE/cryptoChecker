import XCTest
@testable import CryptoChecker

/// Hilfen für die Unit-Tests: Werte aus den gemeinsamen Testfällen (`Parity/`, vom
/// Projekt-Generator aus `android/testdata/parity` kopiert) und Vergleiche mit Toleranz.
enum TestSupport {

    /// Gemeinsamen Testfall laden (liegt als Ressource im Test-Bundle).
    static func fixture(_ name: String, file: StaticString = #filePath, line: UInt = #line) throws -> [String: Any] {
        let bundle = Bundle(for: BundleToken.self)
        let url = try XCTUnwrap(bundle.url(forResource: name, withExtension: "json"),
                                "\(name).json fehlt im Test-Bundle (tools/gen_xcodeproj.py ausführen)",
                                file: file, line: line)
        let data = try Data(contentsOf: url)
        let object = try JSONSerialization.jsonObject(with: data)
        return try XCTUnwrap(object as? [String: Any], file: file, line: line)
    }

    /// Zahl aus JSON: null → nil; «NaN», «Infinity», «-Infinity» als Text.
    static func number(_ value: Any?) -> Double? {
        guard let value, !(value is NSNull) else { return nil }
        if let text = value as? String {
            switch text {
            case "NaN": return .nan
            case "Infinity": return .infinity
            case "-Infinity": return -.infinity
            default: return Double(text)
            }
        }
        return (value as? NSNumber)?.doubleValue
    }

    /// Zeit aus JSON: ISO-8601 (UTC) oder Epoch-ms.
    static func millis(_ value: Any?) -> Int64 {
        if let text = value as? String {
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime]
            if let date = formatter.date(from: text) {
                return Int64((date.timeIntervalSince1970 * 1000).rounded())
            }
        }
        return Int64(number(value) ?? 0)
    }

    /// «2026-10-07T00:00:00Z» → Epoch-ms.
    static func t(_ iso: String) -> Int64 { millis(iso) }

    static func list(_ value: Any?) -> [[String: Any]] {
        (value as? [Any])?.compactMap { $0 as? [String: Any] } ?? []
    }

    /// Alarm aus einer Beschreibung der Testfälle (fehlende Felder: Standardwerte wie in Android).
    static func alarm(_ spec: [String: Any]) -> Alarm {
        Alarm(
            id: 1,
            watchId: 1,
            condition: AlarmCondition(rawValue: spec["condition"] as? String ?? "") ?? .PRICE_ABOVE,
            threshold: number(spec["threshold"]) ?? 0,
            enabled: spec["enabled"] as? Bool ?? true,
            repeating: spec["repeating"] as? Bool ?? false,
            referencePrice: number(spec["referencePrice"]),
            lastTriggeredAt: Int64(number(spec["lastTriggeredAt"]) ?? 0),
            windowHours: Int(number(spec["windowHours"]) ?? 1),
            referenceAt: Int64(number(spec["referenceAt"]) ?? 0)
        )
    }

    /// Erwartet nil oder einen Wert innerhalb von `accuracy`.
    static func assertNumber(_ actual: Double?, _ expected: Double?, accuracy: Double, _ message: String,
                             file: StaticString = #filePath, line: UInt = #line) {
        guard let expected else {
            XCTAssertNil(actual, message, file: file, line: line)
            return
        }
        guard let actual else {
            XCTFail("\(message): nil statt \(expected)", file: file, line: line)
            return
        }
        XCTAssertEqual(actual, expected, accuracy: accuracy, message, file: file, line: line)
    }
}

/// Anker für `Bundle(for:)` — das Test-Bundle, nicht die App.
private final class BundleToken {}
