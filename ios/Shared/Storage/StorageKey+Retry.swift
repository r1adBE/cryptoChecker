import Foundation
import Security

extension StorageKey {
    /// Wartezeiten (µs) vor den Wiederholungen eines gescheiterten Schlüsselbund-Zugriffs: Der Schlüsselbund liefert
    /// manchmal kurz einen Fehler (direkt nach dem Start, im Simulator), und ohne Schlüssel speichert die App nichts
    /// («Dieses Gerät liefert gerade keinen Schlüssel»). Zusammen höchstens 0,2 s.
    static let retryPauses: [UInt32] = [50_000, 150_000]

    /// `operation` mit Wiederholungen nach `retryPauses`. «Gerät gesperrt» (vor dem ersten Entsperren) nicht wiederholen:
    /// Das ändert sich nicht in Sekundenbruchteilen.
    func retrying<T>(_ operation: () throws -> T) throws -> T {
        var pauses = Self.retryPauses[...]
        while true {
            do {
                return try operation()
            } catch let error as StorageKeychainError where error.status != errSecInteractionNotAllowed {
                guard let next = pauses.popFirst() else { throw error }
                pause(next)
            }
        }
    }
}
