import CryptoKit
import Foundation

/// Signiertes Manifest des Daten-Spiegels (`DataMirror`). Die Action «Market data» legt neben die Spiegel-Dateien
/// `manifest.json` (`{"created":<ms>,"files":{"<Datei>":"<SHA-256 hex>"…},"v":1}`) und `manifest.json.sig` (Base64
/// der DER-Signatur ECDSA P-256 mit SHA-256 über die genauen Bytes des Manifests, erzeugt mit dem Secret
/// MIRROR_SIGNING_KEY in `mirror_data.py --manifest`). Die App nimmt eine Spiegel-Datei nur, wenn die Signatur zum
/// eingebauten Schlüssel `publicKey` passt, das Manifest höchstens `maxAgeMillis` alt ist (Schutz gegen alte, wieder
/// eingespielte Daten) und der SHA-256 der Datei stimmt — sonst fragt sie die Anbieter direkt, wie bei einem Ausfall
/// des Spiegels. Gleich wie Android `MirrorManifest.kt`, gemeinsame Testfälle `mirror_signature.json` (mit eigenem
/// Test-Schlüsselpaar).
enum MirrorManifest {
    static let file = "manifest.json"
    static let signatureFile = "manifest.json.sig"
    static let url = DataMirror.base + file
    static let signatureURL = DataMirror.base + signatureFile

    /// Öffentlicher Schlüssel zu MIRROR_SIGNING_KEY (SubjectPublicKeyInfo, DER, Base64); Wechsel: DEVELOPMENT.md.
    static let publicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEnyWpdnpqDJUM1yHZQxQKPAcaXyGpbk8HvfNVmnR1"
        + "MAuShndvEGub711SBOXLJ0aottnrndttNuskZV2BdiG/Pw=="
    /// Reserve-Schlüssel (F-29): privater Teil nur offline beim Inhaber. Geht MIRROR_SIGNING_KEY verloren, signiert
    /// die Action mit dem Reserve-Schlüssel weiter, ohne dass die Apps ein Update brauchen. Wird MIRROR_SIGNING_KEY
    /// bekannt (Leck), genügt das nicht: `publicKey` bleibt in `publicKeys` gültig, bis das nächste App-Update ihn
    /// entfernt — bis dahin werden auch damit signierte Manifeste angenommen.
    static let reservePublicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE2QmggFHrIqdCNnz8a03ZyaQoet4YwwLdBwy9MP4k"
        + "lEv3ChD2aFAhLUyH2gs3vk7c4TUuihar2eMy/wx6He+Ltw=="
    /// Alle Schlüssel, mit denen ein Manifest gültig ist (aktiver und Reserve-Schlüssel).
    static let publicKeys = [publicKey, reservePublicKey]

    /// Älter → verworfen (die Action läuft stündlich).
    static let maxAgeMillis: Int64 = 7 * 24 * 60 * 60 * 1000
    /// Höchstgrösse von Manifest und Signatur beim Laden (wie Android `MAX_BYTES`/`SIGNATURE_MAX_BYTES`).
    static let maxBytes = 64 * 1024
    static let signatureMaxBytes = 1024
    /// Zeitpunkt in der Zukunft bis zu so viel gilt noch (Uhren gehen nicht genau gleich).
    private static let clockSkewMillis: Int64 = 5 * 60 * 1000
    /// Grösste ganze Zahl, die ein JSON-Double genau hält (2^53).
    private static let maxSafeInteger = 9_007_199_254_740_992.0
    private static let fileNameChars = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-".unicodeScalars)
    private static let hexChars = Set("0123456789abcdef".unicodeScalars)

    enum Status: String, Sendable {
        case ok, missing, invalid
        case tooOld = "too_old"
    }

    enum FileStatus: String, Sendable {
        case ok
        case hashMismatch = "hash_mismatch"
        case notListed = "not_listed"
    }

    /// Geprüftes Manifest: Erstellzeit (ms) und SHA-256 (hex, klein) je Datei.
    struct Manifest: Equatable, Sendable {
        let created: Int64
        let files: [String: String]
    }

    /// Ergebnis von `verify`; `manifest` nur bei `.ok`.
    struct Verification: Equatable, Sendable {
        let status: Status
        let manifest: Manifest?
    }

    /// Spiegel-Datei wird geprüft und nicht genutzt (wie Android `ParseError`); kein Netzfehler.
    struct UnverifiedError: LocalizedError {
        var errorDescription: String? { "Mirror file without valid signature" }
    }

    /// Spiegel-Datei, die gegen das Manifest geprüft wird (alles unter `DataMirror.base` ausser Manifest und Signatur).
    static func isSignedFile(_ url: String) -> Bool {
        url.hasPrefix(DataMirror.base) && url != Self.url && url != signatureURL
    }

    /// Manifest und Signatur prüfen — gleiche Regeln wie Android: fehlt eines (nil/leer) → `.missing`; Signatur
    /// ungültig, Inhalt unbekannt oder Erstellzeit mehr als 5 Minuten in der Zukunft → `.invalid`; `maxAgeMillis`
    /// oder älter → `.tooOld`. Gültig, wenn die Signatur zu einem der `publicKeys` passt; andere Schlüssel nur
    /// für Tests.
    static func verify(_ manifestText: String?, signature signatureText: String?, now: Int64,
                       publicKey: String) -> Verification {
        verify(manifestText, signature: signatureText, now: now, publicKeys: [publicKey])
    }

    static func verify(_ manifestText: String?, signature signatureText: String?, now: Int64,
                       publicKeys: [String] = MirrorManifest.publicKeys) -> Verification {
        let signature = (signatureText ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard let manifestText, !manifestText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !signature.isEmpty
        else { return Verification(status: .missing, manifest: nil) }
        let bytes = Data(manifestText.utf8)
        guard publicKeys.contains(where: { signatureValid(bytes, signature: signature, publicKey: $0) }),
              let manifest = parse(manifestText), manifest.created <= now + clockSkewMillis
        else { return Verification(status: .invalid, manifest: nil) }
        if now - manifest.created >= maxAgeMillis { return Verification(status: .tooOld, manifest: nil) }
        return Verification(status: .ok, manifest: manifest)
    }

    /// Passt `text` (als UTF-8-Bytes) zum Eintrag von `file` im geprüften Manifest?
    static func fileStatus(_ manifest: Manifest, file: String, text: String) -> FileStatus {
        guard let expected = manifest.files[file] else { return .notListed }
        return sha256Hex(text) == expected ? .ok : .hashMismatch
    }

    static func sha256Hex(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// Streng wie Android: Länge ein Vielfaches von 4, nur das Base64-Alphabet.
    private static func signatureValid(_ data: Data, signature: String, publicKey: String) -> Bool {
        guard signature.utf8.count % 4 == 0, let der = Data(base64Encoded: signature),
              let keyData = Data(base64Encoded: publicKey),
              let key = try? P256.Signing.PublicKey(derRepresentation: keyData),
              let ecdsa = try? P256.Signing.ECDSASignature(derRepresentation: der)
        else { return false }
        return key.isValidSignature(ecdsa, for: data)
    }

    private static func parse(_ text: String) -> Manifest? {
        guard let o = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              number(o["v"]) == 1,
              let created = number(o["created"]), created > 0, created == created.rounded(), created <= maxSafeInteger,
              let files = o["files"] as? [String: Any]
        else { return nil }
        var out: [String: String] = [:]
        for (name, value) in files {
            guard let hash = value as? String, validName(name), validHash(hash) else { return nil }
            out[name] = hash
        }
        return Manifest(created: Int64(created), files: out)
    }

    /// JSON-Zahl (nie true/false, die als NSNumber ankommen).
    private static func number(_ value: Any?) -> Double? {
        guard let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        return n.doubleValue
    }

    private static func validName(_ name: String) -> Bool {
        (1...64).contains(name.unicodeScalars.count) && name.unicodeScalars.allSatisfy { fileNameChars.contains($0) }
    }

    private static func validHash(_ hash: String) -> Bool {
        hash.unicodeScalars.count == 64 && hash.unicodeScalars.allSatisfy { hexChars.contains($0) }
    }
}
