import Foundation

/// Dauerhaft gesperrte Schlüssel des Daten-Spiegels (Schlüssel-Ids, `MirrorManifest.keyId`): Die Liste wächst nur —
/// eine Sperre aus einem gültigen Manifest (`MirrorManifest.Verification.revoke`) gilt auch nach einem Neustart und
/// ohne erneutes Manifest. Gespeichert eine Id je Zeile in `fileURL` (nil = nur im Speicher, z. B. in Tests); eine
/// unlesbare Datei gilt als leer. App und Widgets teilen die Datei (App Group). Gleich wie Android
/// `MirrorRevocations.kt`.
final class MirrorRevocations: @unchecked Sendable {
    /// Dateiname im gemeinsamen Ordner (wie Android `FILE_NAME`).
    static let fileName = "mirror_revoked.txt"

    /// Gemeinsame Liste von App und Widgets.
    static let shared = MirrorRevocations(fileURL: SharedStorage.directory.appendingPathComponent(fileName))

    private let fileURL: URL?
    private let lock = NSLock()
    private var cache: Set<String>?

    init(fileURL: URL?) {
        self.fileURL = fileURL
    }

    /// Gesperrte Schlüssel-Ids.
    func current() -> Set<String> {
        lock.withLock { loadedLocked() }
    }

    /// `ids` dauerhaft sperren (nie wieder entfernt); nichts Neues = nichts schreiben.
    func add(_ ids: Set<String>) {
        lock.withLock {
            let now = loadedLocked()
            let next = now.union(ids.filter(Self.valid))
            guard next.count != now.count else { return }
            cache = next
            guard let fileURL else { return }
            // Scheitert das Schreiben, gilt die Sperre bis zum Neustart; das nächste Manifest sperrt erneut
            try? (next.sorted().joined(separator: "\n") + "\n").write(to: fileURL, atomically: true, encoding: .utf8)
        }
    }

    private func loadedLocked() -> Set<String> {
        if let cache { return cache }
        let text = fileURL.flatMap { try? String(contentsOf: $0, encoding: .utf8) }
        let loaded = Self.decode(text)
        cache = loaded
        return loaded
    }

    static func decode(_ text: String?) -> Set<String> {
        guard let text else { return [] }
        return Set(text.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter(valid))
    }

    private static func valid(_ id: String) -> Bool {
        id.utf8.count == 64 && id.unicodeScalars.allSatisfy { "0123456789abcdef".unicodeScalars.contains($0) }
    }
}
