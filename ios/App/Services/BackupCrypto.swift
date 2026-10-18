import CommonCrypto
import CryptoKit
import Foundation
import os

/// Passwortschutz der Sicherungsdatei — wie `BackupCrypto.kt` (gleiches Format, gleiche Prüfwerte),
/// damit verschlüsselte Sicherungen zwischen Android und iOS austauschbar bleiben. Geschrieben wird immer v3:
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
/// ist `ciphertext + tag` — nicht `SealedBox.combined` (das hätte die Nonce vorne).
///
/// v2 (bis Build 76, weiterhin lesbar, unverändert): «kdf» PBKDF2-HMAC-SHA256 (CommonCrypto) mit «iter» (600 000,
/// ältere Dateien 210 000), ohne AAD.
///
/// Gemeinsame Prüfwerte: `Tests/Parity/backup_crypto.json` (gleiche Datei wie Android). Argon2id mit 64 MiB dauert
/// einige 100 ms bis wenige Sekunden — nie auf dem Main-Thread aufrufen.
enum BackupCrypto {
    static let format = "cryptochecker-backup-enc"
    /// Version neuer Sicherungen (Argon2id + AAD).
    static let version = 3
    /// Ältere Hülle mit PBKDF2 (weiterhin lesbar).
    static let versionPBKDF2 = 2
    static let enc = "AES-256-GCM"
    static let kdf = "Argon2id"
    static let kdfPBKDF2 = "PBKDF2-HMAC-SHA256"
    /// Argon2id neuer Sicherungen: 64 MiB, 3 Durchgänge, 1 Bahn (RFC 9106, zweite empfohlene Wahl).
    static let argon2MemoryKiB = 65_536
    static let argon2Time = 3
    static let argon2Parallelism = 1
    /// «iter» der v2-Sicherungen mit PBKDF2 (bis Build 76).
    static let defaultIterations = 600_000
    /// «iter» noch älterer v2-Sicherungen (weiterhin lesbar).
    static let legacyIterations = 210_000
    static let saltBytes = 16
    static let ivBytes = 12
    static let tagBytes = 16
    static let keyBytes = 32
    /// Mindestlänge des Passworts für neue Sicherungen (Unicode-Zeichen, wie `codePointCount` unter Android; seit
    /// Build 69 12, vorher 10 bzw. 8). Ältere Dateien lassen sich mit jedem Passwort entschlüsseln.
    static let minPasswordLength = 12
    /// Grenzen für «iter» beim Lesen: Schutz vor unsinnigen oder absichtlich lähmenden Werten (umfasst 210 000 und 600 000).
    private static let iterationRange = 10_000...10_000_000
    /// Obergrenzen für m, t, p beim Lesen: Eine manipulierte Datei soll das Gerät nicht mit Gigabytes oder Minuten
    /// Rechenzeit lähmen. Untergrenze für m ist das Minimum von Argon2 (8 KiB je Bahn).
    static let maxArgon2MemoryKiB = 262_144
    static let maxArgon2Time = 10
    static let maxArgon2Parallelism = 4
    /// Reserve über m hinaus, bevor Argon2id startet (Klartext, Chiffrat, Oberfläche).
    private static let memoryReserveBytes = 16 * 1024 * 1024

    /// Schlüsselableitung samt Parametern, wie in der Datei.
    enum Kdf: Equatable {
        /// v2: PBKDF2-HMAC-SHA256 mit «iter».
        case pbkdf2(iterations: Int)
        /// v3: Argon2id mit «m» (KiB), «t», «p».
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

        /// «v»: 2 für PBKDF2, 3 für Argon2id.
        var version: Int {
            if case .pbkdf2 = kdf { return BackupCrypto.versionPBKDF2 }
            return BackupCrypto.version
        }
    }

    enum Failure: Error {
        /// Passwort falsch oder Datei verändert (GCM-Tag passt nicht; bei v3 auch ein veränderter Kopf).
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

    /// Felder aus dem JSON prüfen und dekodieren: v2 mit «iter», v3 mit «m», «t», «p».
    static func envelope(from root: [String: Any]) throws -> Envelope {
        guard let v = root["v"] as? Int else { throw Failure.unsupported("version") }
        // Neuere Hülle: klare Meldung «neuere App-Version» statt «fehlgeschlagen» (wie Android)
        if v > version { throw BackupVersion.Failure.newer(v) }
        let parsed: Kdf
        switch v {
        case versionPBKDF2:
            guard root["kdf"] as? String == kdfPBKDF2 else { throw Failure.unsupported("enc/kdf") }
            guard let iterations = root["iter"] as? Int else { throw Failure.unsupported("iter") }
            parsed = .pbkdf2(iterations: iterations)
        case version:
            guard root["kdf"] as? String == kdf else { throw Failure.unsupported("enc/kdf") }
            guard let m = root["m"] as? Int, let t = root["t"] as? Int, let p = root["p"] as? Int else {
                throw Failure.unsupported("argon2")
            }
            parsed = .argon2(memoryKiB: m, time: t, parallelism: p)
        default:
            throw Failure.unsupported("version")
        }
        guard root["enc"] as? String == enc else { throw Failure.unsupported("enc/kdf") }
        guard let salt = (root["salt"] as? String).flatMap({ Data(base64Encoded: $0) }),
              let iv = (root["iv"] as? String).flatMap({ Data(base64Encoded: $0) }),
              let data = (root["data"] as? String).flatMap({ Data(base64Encoded: $0) })
        else { throw Failure.unsupported("base64") }
        let envelope = Envelope(kdf: parsed, salt: salt, iv: iv, data: data)
        try validate(envelope)
        return envelope
    }

    /// JSON der verschlüsselten Sicherung — gleiche Felder und Reihenfolge wie unter Android (v2 wie bisher).
    static func json(_ envelope: Envelope) -> Data {
        let kdfLines: String
        switch envelope.kdf {
        case .pbkdf2(let iterations):
            kdfLines = """
              "kdf": "\(kdfPBKDF2)",
              "iter": \(iterations),
            """
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

    /// Kanonischer Kopf einer v3-Hülle als AAD (UTF-8, kompaktes JSON, feste Reihenfolge; gleiche Bytes wie Android
    /// `BackupCrypto.header`). Aus den gelesenen Werten neu gebaut, nie aus dem Dateitext: Leerzeichen oder
    /// Feldreihenfolge der Datei spielen keine Rolle, jeder geänderte Wert schon.
    static func header(_ envelope: Envelope) throws -> Data {
        guard case let .argon2(m, t, p) = envelope.kdf else { throw Failure.unsupported("header v2") }
        let salt = envelope.salt.base64EncodedString()
        let iv = envelope.iv.base64EncodedString()
        let text = #"{"format":"\#(format)","v":\#(version),"enc":"\#(enc)","kdf":"\#(kdf)","m":\#(m),"t":\#(t),"p":\#(p),"salt":"\#(salt)","iv":"\#(iv)"}"#
        return Data(text.utf8)
    }

    // MARK: Ver- und Entschlüsseln

    /// Verschlüsselt `plain`. `password` = `passwordBytes(_:)`; gehört dem Aufrufer, der es danach überschreibt.
    /// `kdf` ist für neue Sicherungen immer `defaultKdf` — v2 schreiben nur noch Tests (Prüfwerte).
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
        let box: AES.GCM.SealedBox
        if case .argon2 = kdf {
            box = try AES.GCM.seal(plain, using: key, nonce: nonce, authenticating: try header(draft))
        } else {
            box = try AES.GCM.seal(plain, using: key, nonce: nonce)
        }
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

    /// - Throws: `Failure.wrongPassword` bei falschem Passwort oder veränderter Datei (bei v3 auch verändertem Kopf),
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
            // v2 bleibt ohne AAD (so wurden die Dateien geschrieben)
            if case .argon2 = envelope.kdf {
                return try AES.GCM.open(box, using: key, authenticating: try header(envelope))
            }
            return try AES.GCM.open(box, using: key)
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

    /// Prüfwerte wie in `BackupCryptoTest.kt` und `backup_crypto.json` (mit Python `cryptography` nachgerechnet):
    /// Passwort `Kryptö-Test 2026!`, salt 00..0f, iv a0..ab; v2 `data` mit iter 210000 (alte Datei), `data600k` mit
    /// iter 600000; v3 `data3` mit Argon2id m 65536, t 3, p 1 (neue Sicherungen), `data3Fast` mit m 64, t 2, p 2.
    enum TestVector {
        static let password = "Kryptö-Test 2026!"
        static let salt = "AAECAwQFBgcICQoLDA0ODw=="
        static let iv = "oKGio6Slpqeoqaqr"
        static let plain = #"{"format":"cryptochecker-backup","version":1,"portfolio":[]}"#
        static let data =
            "F8ZO22w17BWWRjDa0IBQavtrw3kOeap/pSDHBNMr2yCCLsZy82l3RqH6OxEDzba6sota8lpn/SClooHDYy0tm81k5gCByAiia3plAA=="
        static let data600k =
            "0Ku8OwoZGZNMLB4nSmFxc+ftNLme+bH0q7YUsGipFoc6Edly6EQAyI3y2P5g9FVosu0L/CWRglvDAWzcW6/sO5/liBMAHn0IC/qYSg=="
        static let data3 =
            "+SPClkaGX/1HR97Xx2B2RkuJ5sOCke/BDIWZy+pBS/5Q9ahYvVYgL1NvS5CbKq1YF51Ekq4lXZYjbOMggfmkUn3RmvloYKVoB1ajeA=="
        static let data3Fast =
            "D41TWXCaoW8JsAP4rFwOgrAbvFpP6nRa4fMNxboVbJXqsLJ5J7BsLsshL6Rz62pnPQz2zwcf0Qyw6IKcZ0WXnF3ik41Zihyqliu9ng=="
        static let kdf3Fast = Kdf.argon2(memoryKiB: 64, time: 2, parallelism: 2)
    }

    /// Verschlüsselt die Prüfwerte v2 (alte Datei, iter 210000) und v3 (Argon2id mit kleinen Parametern, mit AAD)
    /// und entschlüsselt sie wieder; true, wenn alles mit Android übereinstimmt. Dauert spürbar — nur im Hintergrund.
    static func verifyTestVector() -> Bool {
        guard let salt = Data(base64Encoded: TestVector.salt),
              let iv = Data(base64Encoded: TestVector.iv)
        else { return false }
        let cases: [(Kdf, String)] = [
            (.pbkdf2(iterations: legacyIterations), TestVector.data),
            (TestVector.kdf3Fast, TestVector.data3Fast),
        ]
        for (kdf, expected) in cases {
            guard let env = try? encrypt(Data(TestVector.plain.utf8), password: TestVector.password, salt: salt, iv: iv,
                                         kdf: kdf),
                  env.data.base64EncodedString() == expected,
                  let back = try? decrypt(env, password: TestVector.password),
                  String(decoding: back, as: UTF8.self) == TestVector.plain
            else { return false }
        }
        return true
    }

    // MARK: Intern

    private static func validate(_ envelope: Envelope, checkData: Bool = true) throws {
        switch envelope.kdf {
        case .pbkdf2(let iterations):
            guard iterationRange.contains(iterations) else { throw Failure.unsupported("iter") }
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
        case .pbkdf2(let iterations):
            return try pbkdf2(password, salt: envelope.salt, iterations: iterations)
        case let .argon2(m, t, p):
            return try argon2(password, salt: envelope.salt, memoryKiB: m, time: t, parallelism: p)
        }
    }

    /// v2, unverändert (Passwort-Bytes = NFC, UTF-8 wie bisher).
    private static func pbkdf2(_ password: [UInt8], salt: Data, iterations: Int) throws -> SymmetricKey {
        var passwordChars = password.map { CChar(bitPattern: $0) }
        defer { for i in passwordChars.indices { passwordChars[i] = 0 } }
        let saltArray = [UInt8](salt)
        let length = keyBytes
        var derived = [UInt8](repeating: 0, count: length)
        defer { Argon2id.wipe(&derived) }
        let status = CCKeyDerivationPBKDF(
            CCPBKDFAlgorithm(kCCPBKDF2),
            passwordChars, passwordChars.count,
            saltArray, saltArray.count,
            CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
            UInt32(iterations),
            &derived, length
        )
        guard status == Int32(kCCSuccess) else { throw Failure.unsupported("pbkdf2") }
        return SymmetricKey(data: derived)
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

/// Schwache Passwörter im Blatt «Sichern» — wie `BackupPasswordStrength` in `BackupCrypto.kt` (gemeinsame Beispiele in
/// `testdata/parity/backup_passwords.json`): sperren das Sichern mit Passwort (`BackupCrypto.canExport`). Entschlüsseln
/// prüft das Passwort nicht.
enum BackupPasswordStrength {
    /// Häufige Folgen (klein geschrieben); kommt eine davon vor, ist das Passwort schwach.
    private static let common = ["password", "passwort", "12345678", "87654321", "abcdefgh"]

    /// Tastaturreihen (QWERTZ, QWERTY, AZERTY); 6 Zeichen in Folge (auch rückwärts) gelten als Tastatur-Spaziergang.
    private static let keyboardRows = [
        "qwertzuiop", "qwertyuiop", "azertyuiop", "asdfghjkl", "qsdfghjklm", "yxcvbnm", "zxcvbnm", "wxcvbn",
    ]
    private static let walk = 6

    /// Wörter, die mit Jahr/Zahl und Zeichen geschmückt ein Passwort nicht stark machen («Summer2026!», «Winter2025»).
    private static let words = [
        // Jahreszeiten
        "spring", "summer", "autumn", "fall", "winter", "fruehling", "frühling", "sommer", "herbst",
        "printemps", "ete", "été", "automne", "hiver",
        // Monate
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
        "november", "december", "januar", "februar", "maerz", "märz", "mai", "juni", "juli", "oktober", "dezember",
        // Häufige Wörter
        "password", "passwort", "welcome", "willkommen", "hello", "hallo", "letmein", "admin", "secret", "geheim",
        "iloveyou", "monkey", "dragon", "sunshine", "football", "fussball", "master", "qwerty", "qwertz",
        "bitcoin", "crypto", "ethereum", "test",
        // Name der App
        "cryptochecker", "crypto-checker", "crypto checker", "bitcoinchecker",
    ]

    /// Höchstens so viele Ziffern/Zeichen um ein Wort herum gelten als Schmuck.
    private static let decoration = 6

    /// Schwach (für neue Sicherungen gesperrt) — Regeln wie Android:
    ///  - kürzer als `BackupCrypto.minPasswordLength`, nur Ziffern oder ein einziges Zeichen wiederholt;
    ///  - eine häufige Folge oder ein Tastatur-Spaziergang (6 Zeichen einer Reihe, auch rückwärts);
    ///  - ein Wort (Jahreszeit, Monat, häufiges Wort, Name der App) mit höchstens 6 Ziffern/Zeichen davor bzw. danach;
    ///  - ein wiederholtes Stück («abcabcabcabc», «Test1Test1»);
    ///  - fast nur ein Zeichen: höchstens 3 verschiedene Zeichen, mindestens 75 % dasselbe Zeichen oder ein einziges
    ///    wiederholtes Zeichen, wenn man Ziffern/Zeichen vorne und hinten weglässt («aaaaaaaaaaa1», «!!bbbbbbbbbb»).
    /// Fälle in `Tests/Parity/backup_passwords.json` (gleiche Datei wie Android).
    /// Die frühere Regel «höchstens 2 Zeichenklassen und kürzer als 12 Zeichen» deckt seit Build 69 die
    /// Mindestlänge 12 ab (Zeichenklassen spielen ab 12 Zeichen keine Rolle).
    static func isWeak(_ password: String) -> Bool {
        let normalized = password.precomposedStringWithCanonicalMapping
        let scalars = Array(normalized.unicodeScalars)
        if scalars.count < BackupCrypto.minPasswordLength { return true }
        let digits: ClosedRange<Unicode.Scalar> = "0"..."9"
        if scalars.allSatisfy({ digits.contains($0) }) { return true }
        if Set(scalars).count == 1 { return true }
        if mostlyOneChar(scalars) { return true }
        let lower = normalized.lowercased()
        if common.contains(where: { lower.contains($0) }) { return true }
        if keyboardWalk(lower) { return true }
        if decoratedWord(lower) { return true }
        return repeatedChunk(scalars)
    }

    /// 6 aufeinanderfolgende Zeichen einer Tastaturreihe (vorwärts oder rückwärts).
    private static func keyboardWalk(_ lower: String) -> Bool {
        keyboardRows.contains { row -> Bool in
            [row, String(row.reversed())].contains { r -> Bool in
                let chars = Array(r)
                guard chars.count >= walk else { return false }
                return (0...(chars.count - walk)).contains { i -> Bool in lower.contains(String(chars[i..<(i + walk)])) }
            }
        }
    }

    /// Wort mit Schmuck: vorne und hinten nur Ziffern/Zeichen (keine Buchstaben), zusammen höchstens `decoration`.
    private static func decoratedWord(_ lower: String) -> Bool {
        words.contains { w -> Bool in
            var searchStart = lower.startIndex
            while searchStart < lower.endIndex, let r = lower.range(of: w, range: searchStart..<lower.endIndex) {
                let rest: String = String(lower[lower.startIndex..<r.lowerBound]) + String(lower[r.upperBound...])
                if rest.unicodeScalars.count <= decoration && !rest.contains(where: { $0.isLetter }) { return true }
                searchStart = lower.index(after: r.lowerBound)
            }
            return false
        }
    }

    /// Fast nur ein Zeichen: höchstens 3 verschiedene Zeichen, mindestens 75 % dasselbe Zeichen, oder ohne
    /// Ziffern/Zeichen (alles ausser Buchstaben) vorne und hinten bleibt ein einziges wiederholtes Zeichen.
    private static func mostlyOneChar(_ s: [Unicode.Scalar]) -> Bool {
        if Set(s).count <= 3 { return true }
        var counts: [Unicode.Scalar: Int] = [:]
        for c in s { counts[c, default: 0] += 1 }
        if let most = counts.values.max(), most * 4 >= s.count * 3 { return true }
        guard let first = s.firstIndex(where: isLetterScalar), let last = s.lastIndex(where: isLetterScalar) else { return false }
        return s[first...last].allSatisfy { $0 == s[first] }
    }

    /// Buchstabe = Unicode-Kategorie L* (wie `Character.isLetter(codePoint)` unter Android).
    private static func isLetterScalar(_ c: Unicode.Scalar) -> Bool {
        switch c.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
        default: return false
        }
    }

    /// Ganzes Passwort = ein Stück, mindestens zweimal wiederholt.
    private static func repeatedChunk(_ s: [Unicode.Scalar]) -> Bool {
        let n = s.count
        guard n >= 2 else { return false }
        for p in 1...(n / 2) where n % p == 0 {
            if (p..<n).allSatisfy({ s[$0] == s[$0 - p] }) { return true }
        }
        return false
    }

    /// Hinweis «zu leicht zu erraten» zeigen? Erst ab der Mindestlänge (vorher gilt «mindestens 12 Zeichen»).
    static func showWeakHint(_ password: String) -> Bool {
        BackupCrypto.isPasswordLongEnough(password) && isWeak(password)
    }
}
