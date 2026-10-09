import CommonCrypto
import CryptoKit
import Foundation

/// Passwortschutz der Sicherungsdatei — wie `BackupCrypto.kt` (gleiches Format, gleicher Prüfwert),
/// damit verschlüsselte Sicherungen zwischen Android und iOS austauschbar bleiben:
///
/// `{"format":"cryptochecker-backup-enc","v":2,"enc":"AES-256-GCM","kdf":"PBKDF2-HMAC-SHA256",
///   "iter":210000,"salt":"<Base64, 16 Bytes>","iv":"<Base64, 12 Bytes>","data":"<Base64: Chiffrat || Tag>"}`
///
/// Klartext = die bisherige Sicherung (JSON, UTF-8). Schlüssel = PBKDF2-HMAC-SHA256 (CommonCrypto)
/// aus dem Passwort (Unicode NFC, UTF-8) und dem Salt, 32 Bytes; AES-256-GCM (CryptoKit) mit
/// 16-Byte-Tag. Byte-Folge wie unter Android (`Cipher.doFinal`): `iv` steht eigens, `data` ist
/// `ciphertext + tag` — nicht `SealedBox.combined` (das hätte die Nonce vorne).
enum BackupCrypto {
    static let format = "cryptochecker-backup-enc"
    static let version = 2
    static let enc = "AES-256-GCM"
    static let kdf = "PBKDF2-HMAC-SHA256"
    static let defaultIterations = 210_000
    static let saltBytes = 16
    static let ivBytes = 12
    static let tagBytes = 16
    static let keyBytes = 32
    /// Mindestlänge des Passworts (Unicode-Zeichen, wie `codePointCount` unter Android).
    static let minPasswordLength = 8
    /// Grenzen für «iter» beim Lesen: Schutz vor unsinnigen oder absichtlich lähmenden Werten.
    private static let iterationRange = 10_000...10_000_000

    /// Verschlüsselte Sicherung (Felder wie im JSON, Bytes schon dekodiert).
    struct Envelope {
        let iterations: Int
        let salt: Data
        let iv: Data
        let data: Data
    }

    enum Failure: Error {
        /// Passwort falsch oder Datei verändert (GCM-Tag passt nicht).
        case wrongPassword
        /// Unbekannte Version, Verfahren oder ungültige Felder (z. B. aus einer neueren App).
        case unsupported(String)
    }

    // MARK: Passwort

    static func passwordLength(_ password: String) -> Int {
        normalize(password).unicodeScalars.count
    }

    static func isPasswordLongEnough(_ password: String) -> Bool {
        passwordLength(password) >= minPasswordLength
    }

    /// Formular «Mit Passwort schützen»: aus, oder lang genug und beide Felder gleich.
    static func canExport(protect: Bool, password: String, repeated: String) -> Bool {
        !protect || (isPasswordLongEnough(password) && password == repeated)
    }

    // MARK: Hülle

    /// Kopf einer verschlüsselten Sicherung? (Klartext-Sicherungen haben kein «enc».)
    static func isEnvelope(_ root: [String: Any]) -> Bool {
        guard root["format"] as? String == format, let e = root["enc"] as? String else { return false }
        return !e.isEmpty
    }

    /// Felder aus dem JSON prüfen und dekodieren.
    static func envelope(from root: [String: Any]) throws -> Envelope {
        guard let v = root["v"] as? Int, v == version else { throw Failure.unsupported("version") }
        guard root["enc"] as? String == enc, root["kdf"] as? String == kdf else { throw Failure.unsupported("enc/kdf") }
        guard let iterations = root["iter"] as? Int else { throw Failure.unsupported("iter") }
        guard let salt = (root["salt"] as? String).flatMap({ Data(base64Encoded: $0) }),
              let iv = (root["iv"] as? String).flatMap({ Data(base64Encoded: $0) }),
              let data = (root["data"] as? String).flatMap({ Data(base64Encoded: $0) })
        else { throw Failure.unsupported("base64") }
        let envelope = Envelope(iterations: iterations, salt: salt, iv: iv, data: data)
        try validate(envelope)
        return envelope
    }

    /// JSON der verschlüsselten Sicherung — gleiche Felder und Reihenfolge wie unter Android.
    static func json(_ envelope: Envelope) -> Data {
        let text = """
        {
          "format": "\(format)",
          "v": \(version),
          "enc": "\(enc)",
          "kdf": "\(kdf)",
          "iter": \(envelope.iterations),
          "salt": "\(envelope.salt.base64EncodedString())",
          "iv": "\(envelope.iv.base64EncodedString())",
          "data": "\(envelope.data.base64EncodedString())"
        }
        """
        return Data(text.utf8)
    }

    // MARK: Ver- und Entschlüsseln

    static func encrypt(_ plain: Data, password: String, salt: Data? = nil, iv: Data? = nil,
                        iterations: Int = defaultIterations) throws -> Envelope {
        let salt = salt ?? randomBytes(saltBytes)
        let iv = iv ?? randomBytes(ivBytes)
        guard salt.count == saltBytes, iv.count == ivBytes else { throw Failure.unsupported("sizes") }
        let key = try deriveKey(password, salt: salt, iterations: iterations)
        let nonce = try AES.GCM.Nonce(data: iv)
        let box = try AES.GCM.seal(plain, using: key, nonce: nonce)
        // Chiffrat || Tag, wie `Cipher.doFinal` unter Android
        var data = Data(box.ciphertext)
        data.append(box.tag)
        return Envelope(iterations: iterations, salt: salt, iv: iv, data: data)
    }

    /// - Throws: `Failure.wrongPassword` bei falschem Passwort oder veränderter Datei.
    static func decrypt(_ envelope: Envelope, password: String) throws -> Data {
        try validate(envelope)
        let key = try deriveKey(password, salt: envelope.salt, iterations: envelope.iterations)
        let nonce = try AES.GCM.Nonce(data: envelope.iv)
        let split = envelope.data.count - tagBytes
        let box = try AES.GCM.SealedBox(
            nonce: nonce,
            ciphertext: envelope.data.prefix(split),
            tag: envelope.data.suffix(tagBytes)
        )
        do {
            return try AES.GCM.open(box, using: key)
        } catch {
            throw Failure.wrongPassword
        }
    }

    // MARK: Prüfwert

    /// Prüfwert wie in `BackupCryptoTest.kt` (mit Python `cryptography` nachgerechnet):
    /// Passwort `Kryptö-Test 2026!`, iter 210000, salt 00..0f, iv a0..ab.
    enum TestVector {
        static let password = "Kryptö-Test 2026!"
        static let salt = "AAECAwQFBgcICQoLDA0ODw=="
        static let iv = "oKGio6Slpqeoqaqr"
        static let plain = #"{"format":"cryptochecker-backup","version":1,"portfolio":[]}"#
        static let data =
            "F8ZO22w17BWWRjDa0IBQavtrw3kOeap/pSDHBNMr2yCCLsZy82l3RqH6OxEDzba6sota8lpn/SClooHDYy0tm81k5gCByAiia3plAA=="
    }

    /// Verschlüsselt den Prüfwert und entschlüsselt ihn wieder; true, wenn beides mit Android übereinstimmt.
    static func verifyTestVector() -> Bool {
        guard let salt = Data(base64Encoded: TestVector.salt),
              let iv = Data(base64Encoded: TestVector.iv),
              let env = try? encrypt(Data(TestVector.plain.utf8), password: TestVector.password, salt: salt, iv: iv),
              env.data.base64EncodedString() == TestVector.data,
              let back = try? decrypt(env, password: TestVector.password)
        else { return false }
        return String(decoding: back, as: UTF8.self) == TestVector.plain
    }

    // MARK: Intern

    private static func validate(_ envelope: Envelope) throws {
        guard iterationRange.contains(envelope.iterations) else { throw Failure.unsupported("iter") }
        guard envelope.salt.count >= 8, envelope.iv.count == ivBytes, envelope.data.count >= tagBytes else {
            throw Failure.unsupported("sizes")
        }
    }

    private static func deriveKey(_ password: String, salt: Data, iterations: Int) throws -> SymmetricKey {
        let passwordBytes = Array(normalize(password).utf8).map { CChar(bitPattern: $0) }
        let saltArray = [UInt8](salt)
        let length = keyBytes
        var derived = [UInt8](repeating: 0, count: length)
        let status = CCKeyDerivationPBKDF(
            CCPBKDFAlgorithm(kCCPBKDF2),
            passwordBytes, passwordBytes.count,
            saltArray, saltArray.count,
            CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
            UInt32(iterations),
            &derived, length
        )
        guard status == Int32(kCCSuccess) else { throw Failure.unsupported("pbkdf2") }
        return SymmetricKey(data: derived)
    }

    /// NFC: «ö» als ein Zeichen — gleich wie unter Android (`Normalizer.Form.NFC`).
    private static func normalize(_ password: String) -> String {
        password.precomposedStringWithCanonicalMapping
    }

    private static func randomBytes(_ count: Int) -> Data {
        var generator = SystemRandomNumberGenerator()
        return Data((0..<count).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
    }
}
