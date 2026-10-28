import CryptoKit
import Foundation
import os

/// Passwortschutz der Sicherungsdatei — wie `BackupCrypto.kt` (gleiches Format, gleiche Prüfwerte),
/// damit verschlüsselte Sicherungen zwischen Android und iOS austauschbar bleiben. Version 3:
///
/// `{"format":"cryptochecker-backup-enc","v":3,"enc":"AES-256-GCM","kdf":"Argon2id","m":65536,"t":3,"p":1,
///   "salt":"<Base64, 16 Bytes>","iv":"<Base64, 12 Bytes>","data":"<Base64: Chiffrat || Tag>"}`
///
/// Klartext = die bisherige Sicherung (JSON, UTF-8). Schlüssel = Argon2id (RFC 9106, Version 0x13, `Argon2id`) aus dem
/// Passwort (Unicode NFC, UTF-8) und dem Salt, 32 Bytes; m = Speicher in KiB (64 MiB), t = Durchgänge, p = Bahnen.
/// AES-256-GCM (CryptoKit) mit 16-Byte-Tag und dem kanonischen Kopf als AAD (`header`): UTF-8 dieses kompakten JSON,
/// Felder in fester Reihenfolge, ohne Leerzeichen, Zahlen dezimal, salt/iv als Base64 mit Auffüllung (aus den
/// dekodierten Bytes neu kodiert):
/// `{"format":"cryptochecker-backup-enc","v":3,"enc":"AES-256-GCM","kdf":"Argon2id","m":65536,"t":3,"p":1,"salt":"…","iv":"…"}`
/// Ein veränderter Kopf scheitert damit am GCM-Tag — gemeldet wie ein falsches Passwort (`Failure.wrongPassword`),
/// denn beides ist nicht zu unterscheiden. Byte-Folge wie unter Android (`Cipher.doFinal`): `iv` steht eigens, `data`
/// ist `ciphertext + tag` — nicht `SealedBox.combined` (das hätte die Nonce vorne). Andere Versionen werden nicht gelesen
/// (`Failure.unsupported`, neuere: `BackupVersion.Failure.newer`).
///
/// Gemeinsame Prüfwerte: `Tests/Parity/backup_crypto.json` (gleiche Datei wie Android). Argon2id mit 64 MiB dauert
/// einige 100 ms bis wenige Sekunden — nie auf dem Main-Thread aufrufen.
enum BackupCrypto {
    static let format = "cryptochecker-backup-enc"
    /// Version der Hülle (Argon2id + AAD).
    static let version = 3
    static let enc = "AES-256-GCM"
    static let kdf = "Argon2id"
    /// Argon2id neuer Sicherungen: 64 MiB, 3 Durchgänge, 1 Bahn (RFC 9106, zweite empfohlene Wahl).
    static let argon2MemoryKiB = 65_536
    static let argon2Time = 3
    static let argon2Parallelism = 1
    static let saltBytes = 16
    static let ivBytes = 12
    static let tagBytes = 16
    static let keyBytes = 32
    /// Mindestlänge des Passworts für neue Sicherungen (Unicode-Zeichen, wie `codePointCount` unter Android). Gilt nur
    /// beim Sichern.
    static let minPasswordLength = 12
    /// Obergrenzen für m, t, p beim Lesen: Eine manipulierte Datei soll das Gerät nicht mit Gigabytes oder Minuten
    /// Rechenzeit lähmen. Untergrenze für m ist das Minimum von Argon2 (8 KiB je Bahn).
    static let maxArgon2MemoryKiB = 262_144
    static let maxArgon2Time = 10
    static let maxArgon2Parallelism = 4
    /// Reserve über m hinaus, bevor Argon2id startet (Klartext, Chiffrat, Oberfläche).
    private static let memoryReserveBytes = 16 * 1024 * 1024

    /// Schlüsselableitung samt Parametern, wie in der Datei.
    enum Kdf: Equatable {
        /// Argon2id mit «m» (KiB), «t», «p».
        case argon2(memoryKiB: Int, time: Int, parallelism: Int)
    }

    /// Argon2id neuer Sicherungen.
    static let defaultKdf = Kdf.argon2(memoryKiB: argon2MemoryKiB, time: argon2Time, parallelism: argon2Parallelism)

    /// Verschlüsselte Sicherung (Felder wie im JSON, Bytes schon dekodiert).
    struct Envelope {
        let kdf: Kdf
        let salt: Data
        let iv: Data
        let data: Data

        /// «v».
        var version: Int { BackupCrypto.version }
    }

    enum Failure: Error {
        /// Passwort falsch oder Datei verändert (GCM-Tag passt nicht, auch bei verändertem Kopf).
        case wrongPassword
        /// Unbekannte Version, Verfahren oder ungültige Felder (z. B. aus einer neueren App).
        case unsupported(String)
        /// Zu wenig Arbeitsspeicher für Argon2id: klare Meldung statt Absturz, kein Rückfall auf ein schwächeres Verfahren.
        case notEnoughMemory
    }

    // MARK: Passwort

    static func passwordLength(_ password: String) -> Int {
        normalize(password).unicodeScalars.count
    }

    static func isPasswordLongEnough(_ password: String) -> Bool {
        passwordLength(password) >= minPasswordLength
    }

    /// Passwort für eine neue Sicherung erlaubt: lang genug und nicht leicht zu erraten (`BackupPasswordStrength`).
    static func isPasswordAllowed(_ password: String) -> Bool {
        !BackupPasswordStrength.isWeak(password)
    }

    /// Formular «Mit Passwort schützen»: aus, oder erlaubtes Passwort und beide Felder gleich.
    static func canExport(protect: Bool, password: String, repeated: String) -> Bool {
        !protect || (isPasswordAllowed(password) && password == repeated)
    }

    /// Passwort als Bytes für `encrypt`/`decrypt` (Unicode NFC, UTF-8). Der Aufrufer überschreibt sie danach
    /// (`Argon2id.wipe`); der String selbst lässt sich in Swift nicht löschen.
    static func passwordBytes(_ password: String) -> [UInt8] {
        Array(normalize(password).utf8)
    }

    // MARK: Hülle

    /// Kopf einer verschlüsselten Sicherung? (Klartext-Sicherungen haben kein «enc».)
    static func isEnvelope(_ root: [String: Any]) -> Bool {
        guard root["format"] as? String == format, let e = root["enc"] as? String else { return false }
        return !e.isEmpty
    }

    /// Felder aus dem JSON prüfen und dekodieren (Argon2id mit «m», «t», «p»).
    static func envelope(from root: [String: Any]) throws -> Envelope {
        guard let v = root["v"] as? Int else { throw Failure.unsupported("version") }
        // Neuere Hülle: klare Meldung «neuere App-Version» statt «fehlgeschlagen» (wie Android)
        if v > version { throw BackupVersion.Failure.newer(v) }
        guard v == version else { throw Failure.unsupported("version") }
        guard root["kdf"] as? String == kdf, root["enc"] as? String == enc else { throw Failure.unsupported("enc/kdf") }
        guard let m = root["m"] as? Int, let t = root["t"] as? Int, let p = root["p"] as? Int else {
            throw Failure.unsupported("argon2")
        }
        let parsed = Kdf.argon2(memoryKiB: m, time: t, parallelism: p)
        guard let salt = (root["salt"] as? String).flatMap({ Data(base64Encoded: $0) }),
              let iv = (root["iv"] as? String).flatMap({ Data(base64Encoded: $0) }),
              let data = (root["data"] as? String).flatMap({ Data(base64Encoded: $0) })
        else { throw Failure.unsupported("base64") }
        let envelope = Envelope(kdf: parsed, salt: salt, iv: iv, data: data)
        try validate(envelope)
        return envelope
    }

    /// JSON der verschlüsselten Sicherung — gleiche Felder und Reihenfolge wie unter Android.
    static func json(_ envelope: Envelope) -> Data {
        let kdfLines: String
        switch envelope.kdf {
        case let .argon2(m, t, p):
            kdfLines = """
              "kdf": "\(kdf)",
              "m": \(m),
              "t": \(t),
              "p": \(p),
            """
        }
        let text = """
        {
          "format": "\(format)",
          "v": \(envelope.version),
          "enc": "\(enc)",
        \(kdfLines)
          "salt": "\(envelope.salt.base64EncodedString())",
          "iv": "\(envelope.iv.base64EncodedString())",
          "data": "\(envelope.data.base64EncodedString())"
        }
        """
        return Data(text.utf8)
    }

    /// Kanonischer Kopf der Hülle als AAD (UTF-8, kompaktes JSON, feste Reihenfolge; gleiche Bytes wie Android
    /// `BackupCrypto.header`). Aus den gelesenen Werten neu gebaut, nie aus dem Dateitext: Leerzeichen oder
    /// Feldreihenfolge der Datei spielen keine Rolle, jeder geänderte Wert schon.
    static func header(_ envelope: Envelope) -> Data {
        let salt = envelope.salt.base64EncodedString()
        let iv = envelope.iv.base64EncodedString()
        switch envelope.kdf {
        case let .argon2(m, t, p):
            let text = #"{"format":"\#(format)","v":\#(version),"enc":"\#(enc)","kdf":"\#(kdf)","m":\#(m),"t":\#(t),"p":\#(p),"salt":"\#(salt)","iv":"\#(iv)"}"#
            return Data(text.utf8)
        }
    }

    // MARK: Ver- und Entschlüsseln

    /// Verschlüsselt `plain`. `password` = `passwordBytes(_:)`; gehört dem Aufrufer, der es danach überschreibt.
    /// `kdf` ist für neue Sicherungen immer `defaultKdf`; andere Parameter nur in Tests (Prüfwerte).
    /// - Throws: `Failure.notEnoughMemory`, wenn der Speicher für Argon2id nicht reicht.
    static func encrypt(_ plain: Data, password: [UInt8], salt: Data? = nil, iv: Data? = nil,
                        kdf: Kdf = defaultKdf) throws -> Envelope {
        let salt = salt ?? randomBytes(saltBytes)
        let iv = iv ?? randomBytes(ivBytes)
        guard salt.count == saltBytes, iv.count == ivBytes else { throw Failure.unsupported("sizes") }
        let draft = Envelope(kdf: kdf, salt: salt, iv: iv, data: Data())
        try validate(draft, checkData: false)
        let key = try deriveKey(password, envelope: draft)
        let nonce = try AES.GCM.Nonce(data: iv)
        let box = try AES.GCM.seal(plain, using: key, nonce: nonce, authenticating: header(draft))
        // Chiffrat || Tag, wie `Cipher.doFinal` unter Android
        var data = Data(box.ciphertext)
        data.append(box.tag)
        return Envelope(kdf: kdf, salt: salt, iv: iv, data: data)
    }

    /// Wie oben, mit dem Passwort als Text (Tests, Prüfwert); die Bytes werden danach überschrieben.
    static func encrypt(_ plain: Data, password: String, salt: Data? = nil, iv: Data? = nil,
                        kdf: Kdf = defaultKdf) throws -> Envelope {
        var bytes = passwordBytes(password)
        defer { Argon2id.wipe(&bytes) }
        return try encrypt(plain, password: bytes, salt: salt, iv: iv, kdf: kdf)
    }

    /// - Throws: `Failure.wrongPassword` bei falschem Passwort oder veränderter Datei (auch verändertem Kopf),
    ///   `Failure.notEnoughMemory`, wenn der Speicher für Argon2id nicht reicht.
    static func decrypt(_ envelope: Envelope, password: [UInt8]) throws -> Data {
        try validate(envelope)
        let key = try deriveKey(password, envelope: envelope)
        let nonce = try AES.GCM.Nonce(data: envelope.iv)
        let split = envelope.data.count - tagBytes
        let box = try AES.GCM.SealedBox(
            nonce: nonce,
            ciphertext: envelope.data.prefix(split),
            tag: envelope.data.suffix(tagBytes)
        )
        do {
            return try AES.GCM.open(box, using: key, authenticating: header(envelope))
        } catch {
            throw Failure.wrongPassword
        }
    }

    /// Wie oben, mit dem Passwort als Text (Tests, Prüfwert); die Bytes werden danach überschrieben.
    static func decrypt(_ envelope: Envelope, password: String) throws -> Data {
        var bytes = passwordBytes(password)
        defer { Argon2id.wipe(&bytes) }
        return try decrypt(envelope, password: bytes)
    }

    // MARK: Prüfwert

    /// Prüfwerte wie in `backup_crypto.json` (mit Python `cryptography` nachgerechnet): Passwort `Kryptö-Test 2026!`,
    /// salt 00..0f, iv a0..ab; `data3` mit Argon2id m 65536, t 3, p 1 (neue Sicherungen), `data3Fast` mit m 64, t 2, p 2.
    enum TestVector {
        static let password = "Kryptö-Test 2026!"
        static let salt = "AAECAwQFBgcICQoLDA0ODw=="
        static let iv = "oKGio6Slpqeoqaqr"
        static let plain = #"{"format":"cryptochecker-backup","version":1,"portfolio":[]}"#
        static let data3 =
            "+SPClkaGX/1HR97Xx2B2RkuJ5sOCke/BDIWZy+pBS/5Q9ahYvVYgL1NvS5CbKq1YF51Ekq4lXZYjbOMggfmkUn3RmvloYKVoB1ajeA=="
        static let data3Fast =
            "D41TWXCaoW8JsAP4rFwOgrAbvFpP6nRa4fMNxboVbJXqsLJ5J7BsLsshL6Rz62pnPQz2zwcf0Qyw6IKcZ0WXnF3ik41Zihyqliu9ng=="
        static let kdf3Fast = Kdf.argon2(memoryKiB: 64, time: 2, parallelism: 2)
    }

    /// Verschlüsselt den Prüfwert (Argon2id mit kleinen Parametern, mit AAD) und entschlüsselt ihn wieder; true, wenn
    /// alles mit Android übereinstimmt. Dauert spürbar — nur im Hintergrund.
    static func verifyTestVector() -> Bool {
        guard let salt = Data(base64Encoded: TestVector.salt),
              let iv = Data(base64Encoded: TestVector.iv),
              let env = try? encrypt(Data(TestVector.plain.utf8), password: TestVector.password, salt: salt, iv: iv,
                                     kdf: TestVector.kdf3Fast),
              env.data.base64EncodedString() == TestVector.data3Fast,
              let back = try? decrypt(env, password: TestVector.password)
        else { return false }
        return String(decoding: back, as: UTF8.self) == TestVector.plain
    }

    // MARK: Intern

    private static func validate(_ envelope: Envelope, checkData: Bool = true) throws {
        switch envelope.kdf {
        case let .argon2(m, t, p):
            guard (1...maxArgon2Parallelism).contains(p), (1...maxArgon2Time).contains(t),
                  m >= 8 * p, m <= maxArgon2MemoryKiB
            else { throw Failure.unsupported("argon2") }
        }
        guard envelope.salt.count >= 8, envelope.iv.count == ivBytes, !checkData || envelope.data.count >= tagBytes else {
            throw Failure.unsupported("sizes")
        }
    }

    private static func deriveKey(_ password: [UInt8], envelope: Envelope) throws -> SymmetricKey {
        switch envelope.kdf {
        case let .argon2(m, t, p):
            return try argon2(password, salt: envelope.salt, memoryKiB: m, time: t, parallelism: p)
        }
    }

    private static func argon2(_ password: [UInt8], salt: Data, memoryKiB: Int, time: Int,
                               parallelism: Int) throws -> SymmetricKey {
        // iOS beendet eine App, die zu viel Speicher belegt, ohne dass `calloc` scheitert: vorher prüfen.
        // 0 = unbekannt (z. B. Simulator) → versuchen
        let available = os_proc_available_memory()
        if available > 0 && available < memoryKiB * 1024 + memoryReserveBytes { throw Failure.notEnoughMemory }
        var key: [UInt8]
        do {
            key = try Argon2id.hash(password: password, salt: [UInt8](salt), memoryKiB: memoryKiB, iterations: time,
                                    parallelism: parallelism, tagLength: keyBytes)
        } catch Argon2id.Failure.outOfMemory {
            throw Failure.notEnoughMemory
        } catch {
            throw Failure.unsupported("argon2")
        }
        defer { Argon2id.wipe(&key) }
        return SymmetricKey(data: key)
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
