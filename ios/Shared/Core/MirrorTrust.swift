import Foundation

/// Prüft Spiegel-Dateien gegen das signierte Manifest (`MirrorManifest`). Manifest und Signatur kommen nur in den
/// Speicher: gültig höchstens `okTTLMillis`, nach einem Fehlschlag (fehlt, ungültig, zu alt, nicht erreichbar) erst
/// nach `failTTLMillis` wieder versucht — so kostet ein fehlendes Manifest nicht jede Spiegel-Anfrage zwei Abrufe.
/// Passt eine Datei nicht zum gemerkten Manifest (die Action lief seither), wird es einmal neu geholt, höchstens
/// einmal je `reloadAfterMillis`. Manifest höchstens `MirrorManifest.maxBytes`, Signatur höchstens
/// `MirrorManifest.signatureMaxBytes` (grösser → wie fehlend). Gleich wie Android `MirrorTrust.kt`.
actor MirrorTrust {
    /// Einzige Instanz der App: eingebaute Schlüssel (`MirrorManifest.publicKeys`), Systemuhr.
    static let shared = MirrorTrust(publicKeys: MirrorManifest.publicKeys, now: { TimeUtils.nowMillis })

    /// Lädt eine Adresse mit Höchstgrösse (Bytes); nil bei jedem Fehler (auch zu gross).
    typealias Loader = @Sendable (String, Int) async -> String?

    private static let okTTLMillis: Int64 = 15 * 60 * 1000
    private static let failTTLMillis: Int64 = 5 * 60 * 1000
    private static let reloadAfterMillis: Int64 = 60 * 1000

    private var loadedAt: Int64 = 0
    private var cached: MirrorManifest.Verification?
    private var loading: Task<MirrorManifest.Verification, Never>?
    private let publicKeys: [String]
    private let clock: @Sendable () -> Int64

    /// Schlüssel und Uhr nur in Tests anders (Test-Schlüsselpaar aus `mirror_signature.json`); die App nutzt `shared`.
    init(publicKeys: [String], now: @escaping @Sendable () -> Int64) {
        self.publicKeys = publicKeys
        self.clock = now
    }

    /// Nur Tests: ein einzelner Schlüssel.
    init(publicKey: String, now: @escaping @Sendable () -> Int64) {
        self.init(publicKeys: [publicKey], now: now)
    }

    /// Stand der letzten Prüfung; nil = noch keine.
    var lastStatus: MirrorManifest.Status? { cached?.status }

    /// Darf `text` als Spiegel-Datei `file` genutzt werden (gültige Signatur, Manifest frisch, SHA-256 stimmt)?
    func verified(file: String, text: String, load: @escaping Loader) async -> Bool {
        let first = await manifest(load: load, okTTL: Self.okTTLMillis)
        if Self.matches(first, file: file, text: text) { return true }
        guard first.status == .ok else { return false }
        // Datei neuer als das gemerkte Manifest: einmal neu holen
        return Self.matches(await manifest(load: load, okTTL: Self.reloadAfterMillis), file: file, text: text)
    }

    private static func matches(_ v: MirrorManifest.Verification, file: String, text: String) -> Bool {
        guard let manifest = v.manifest else { return false }
        return MirrorManifest.fileStatus(manifest, file: file, text: text) == .ok
    }

    private func manifest(load: @escaping Loader, okTTL: Int64) async -> MirrorManifest.Verification {
        let now = clock()
        if let cached {
            let ttl = cached.status == .ok ? okTTL : Self.failTTLMillis
            let age = now - loadedAt
            if age >= 0, age < ttl { return cached }
        }
        // Gleichzeitige Prüfungen teilen sich einen Abruf
        if let loading { return await loading.value }
        let keys = publicKeys
        let clockNow = self.clock
        let task = Task { await Self.fetch(load, publicKeys: keys, now: clockNow) }
        loading = task
        let verification = await task.value
        loading = nil
        cached = verification
        loadedAt = now
        return verification
    }

    private static func fetch(_ load: Loader, publicKeys: [String],
                              now: @Sendable () -> Int64) async -> MirrorManifest.Verification {
        guard let manifest = await load(MirrorManifest.url, MirrorManifest.maxBytes) else {
            return MirrorManifest.verify(nil, signature: nil, now: now(), publicKeys: publicKeys)
        }
        let signature = await load(MirrorManifest.signatureURL, MirrorManifest.signatureMaxBytes)
        return MirrorManifest.verify(manifest, signature: signature, now: now(), publicKeys: publicKeys)
    }
}
