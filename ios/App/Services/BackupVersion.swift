import Foundation

/// Version einer Sicherung (Klartext-Feld «version») — wie `BackupVersion` in `BackupCrypto.kt`: Eine Sicherung einer
/// NEUEREN App-Version wird ganz abgelehnt (`Failure.newer`, Meldung «neuere App-Version») — nie teilweise übernommen.
/// Ohne «version» ist die Sicherung unlesbar.
enum BackupVersion {
    /// Höchste Version, die diese App liest und schreibt (`BackupManager.version`).
    static let supported = 1

    enum Failure: Error, Equatable {
        /// Sicherung einer neueren App-Version.
        case newer(Int)
        /// «version» fehlt oder ist keine ganze Zahl ≥ 1 (unlesbar).
        case invalid
    }

    /// `raw` = Wert von «version» (nil/NSNull = fehlt): ganze Zahl ≤ `supported` → diese Zahl; grösser →
    /// `Failure.newer`; fehlt, keine Zahl (auch Text) oder keine ganze Zahl ≥ 1 → `Failure.invalid`. Wie Android `check`.
    static func check(_ raw: Any?) throws -> Int {
        // JSON true/false ist auch eine NSNumber — keine Version
        guard let n = raw as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { throw Failure.invalid }
        let number = n.doubleValue
        if !number.isFinite || number.truncatingRemainder(dividingBy: 1) != 0 || number < 1 { throw Failure.invalid }
        if number > Double(supported) { throw Failure.newer(number > Double(Int32.max) ? Int(Int32.max) : Int(number)) }
        return Int(number)
    }
}
