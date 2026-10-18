import Foundation

/// Version einer Sicherung (Klartext-Feld «version») — wie `BackupVersion` in `BackupCrypto.kt`: Eine Sicherung einer
/// NEUEREN App-Version wird ganz abgelehnt (`Failure.newer`, Meldung «neuere App-Version») — nie teilweise übernommen.
/// Ältere Versionen (und fehlende Angabe = älteste Sicherungen) bleiben lesbar.
enum BackupVersion {
    /// Höchste Version, die diese App liest und schreibt (`BackupManager.version`).
    static let supported = 1

    enum Failure: Error, Equatable {
        /// Sicherung einer neueren App-Version.
        case newer(Int)
        /// «version» ist keine ganze Zahl ≥ 1 (unlesbar).
        case invalid
    }

    /// `raw` = Wert von «version» (nil/NSNull = fehlt): ganze Zahl (auch als Text) ≤ `supported` → diese Zahl, fehlt → 1;
    /// grösser → `Failure.newer`; keine ganze Zahl ≥ 1 → `Failure.invalid`. Wie Android `check`.
    static func check(_ raw: Any?) throws -> Int {
        guard let raw, !(raw is NSNull) else { return 1 }
        let number: Double
        switch raw {
        case let n as NSNumber:
            // JSON true/false ist auch eine NSNumber — keine Version
            if CFGetTypeID(n) == CFBooleanGetTypeID() { throw Failure.invalid }
            number = n.doubleValue
        case let text as String:
            guard let d = Double(text.trimmingCharacters(in: .whitespacesAndNewlines)) else { throw Failure.invalid }
            number = d
        default:
            throw Failure.invalid
        }
        if !number.isFinite || number.truncatingRemainder(dividingBy: 1) != 0 || number < 1 { throw Failure.invalid }
        if number > Double(supported) { throw Failure.newer(number > Double(Int32.max) ? Int(Int32.max) : Int(number)) }
        return Int(number)
    }
}
