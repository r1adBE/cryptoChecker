import Foundation

// Grenzen beim Einlesen einer Sicherung (Grösse, Verschachtelungstiefe).
// Eigene Datei wie `BackupLimits.kt`.

/// Grenzen beim Einlesen einer Sicherung — wie `BackupLimits.kt`: höchstens 16 MB und höchstens 32 Ebenen
/// verschachtelt (sonst Speicher- bzw. Stacküberlauf beim Parsen einer manipulierten Datei).
enum BackupLimits {
    static let maxBytes = 16 * 1024 * 1024
    static let maxDepth = 32

    enum Failure: Error { case tooLarge }

    /// Verschachtelungstiefe von Objekten/Listen ausserhalb von Texten prüfen.
    static func checkDepth(_ data: Data, max: Int = maxDepth) throws {
        var depth = 0
        var inString = false
        var escaped = false
        for byte in data {
            if inString {
                if escaped { escaped = false } else if byte == 0x5C { escaped = true } else if byte == 0x22 { inString = false }
                continue
            }
            switch byte {
            case 0x22: inString = true
            case 0x7B, 0x5B:
                depth += 1
                if depth > max { throw Failure.tooLarge }
            case 0x7D, 0x5D: depth -= 1
            default: break
            }
        }
    }
}
