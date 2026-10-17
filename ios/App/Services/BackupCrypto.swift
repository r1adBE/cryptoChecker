import CommonCrypto
import CryptoKit
import Foundation

/// Passwortschutz der Sicherungsdatei — wie `BackupCrypto.kt` (gleiches Format, gleicher Prüfwert),
/// damit verschlüsselte Sicherungen zwischen Android und iOS austauschbar bleiben:
///
/// `{"format":"cryptochecker-backup-enc","v":2,"enc":"AES-256-GCM","kdf":"PBKDF2-HMAC-SHA256",
///   "iter":600000,"salt":"<Base64, 16 Bytes>","iv":"<Base64, 12 Bytes>","data":"<Base64: Chiffrat || Tag>"}`
///
/// Klartext = die bisherige Sicherung (JSON, UTF-8). Schlüssel = PBKDF2-HMAC-SHA256 (CommonCrypto)
/// aus dem Passwort (Unicode NFC, UTF-8) und dem Salt, 32 Bytes; AES-256-GCM (CryptoKit) mit
/// 16-Byte-Tag. Byte-Folge wie unter Android (`Cipher.doFinal`): `iv` steht eigens, `data` ist
/// `ciphertext + tag` — nicht `SealedBox.combined` (das hätte die Nonce vorne).
///
/// «iter»: Neue Sicherungen mit `defaultIterations` = 600 000 (OWASP-Empfehlung für PBKDF2-HMAC-SHA256);
/// ältere Fassungen schrieben 210 000. Gelesen wird immer der Wert aus der Datei, ältere Sicherungen
/// bleiben also lesbar. 600 000 dauern auf älteren Geräten etwa 1 s — nie auf dem Main-Thread aufrufen.
enum BackupCrypto {
    static let format = "cryptochecker-backup-enc"
    static let version = 2
    static let enc = "AES-256-GCM"
    static let kdf = "PBKDF2-HMAC-SHA256"
    static let defaultIterations = 600_000
    /// «iter» älterer Sicherungen (weiterhin lesbar).
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

    /// Passwort für eine neue Sicherung erlaubt: lang genug und nicht leicht zu erraten (`BackupPasswordStrength`).
    static func isPasswordAllowed(_ password: String) -> Bool {
        !BackupPasswordStrength.isWeak(password)
    }

    /// Formular «Mit Passwort schützen»: aus, oder erlaubtes Passwort und beide Felder gleich.
    static func canExport(protect: Bool, password: String, repeated: String) -> Bool {
        !protect || (isPasswordAllowed(password) && password == repeated)
    }

    // MARK: Hülle

    /// Kopf einer verschlüsselten Sicherung? (Klartext-Sicherungen haben kein «enc».)
    static func isEnvelope(_ root: [String: Any]) -> Bool {
        guard root["format"] as? String == format, let e = root["enc"] as? String else { return false }
        return !e.isEmpty
    }

    /// Felder aus dem JSON prüfen und dekodieren.
    static func envelope(from root: [String: Any]) throws -> Envelope {
        guard let v = root["v"] as? Int else { throw Failure.unsupported("version") }
        // Neuere Hülle: klare Meldung «neuere App-Version» statt «fehlgeschlagen» (wie Android)
        if v > version { throw BackupVersion.Failure.newer(v) }
        guard v == version else { throw Failure.unsupported("version") }
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
    /// Passwort `Kryptö-Test 2026!`, salt 00..0f, iv a0..ab; `data` mit iter 210000 (alte Datei),
    /// `data600k` mit iter 600000 (neue Sicherungen).
    enum TestVector {
        static let password = "Kryptö-Test 2026!"
        static let salt = "AAECAwQFBgcICQoLDA0ODw=="
        static let iv = "oKGio6Slpqeoqaqr"
        static let plain = #"{"format":"cryptochecker-backup","version":1,"portfolio":[]}"#
        static let data =
            "F8ZO22w17BWWRjDa0IBQavtrw3kOeap/pSDHBNMr2yCCLsZy82l3RqH6OxEDzba6sota8lpn/SClooHDYy0tm81k5gCByAiia3plAA=="
        static let data600k =
            "0Ku8OwoZGZNMLB4nSmFxc+ftNLme+bH0q7YUsGipFoc6Edly6EQAyI3y2P5g9FVosu0L/CWRglvDAWzcW6/sO5/liBMAHn0IC/qYSg=="
    }

    /// Verschlüsselt den Prüfwert (alte Datei, iter 210000) und entschlüsselt ihn wieder; true, wenn
    /// beides mit Android übereinstimmt. Dauert spürbar — nur im Hintergrund aufrufen.
    static func verifyTestVector() -> Bool {
        guard let salt = Data(base64Encoded: TestVector.salt),
              let iv = Data(base64Encoded: TestVector.iv),
              let env = try? encrypt(Data(TestVector.plain.utf8), password: TestVector.password, salt: salt, iv: iv,
                                     iterations: legacyIterations),
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
    ///  - ein wiederholtes Stück («abcabcabcabc», «Test1Test1»).
    /// Die frühere Regel «höchstens 2 Zeichenklassen und kürzer als 12 Zeichen» deckt seit Build 69 die
    /// Mindestlänge 12 ab (Zeichenklassen spielen ab 12 Zeichen keine Rolle).
    static func isWeak(_ password: String) -> Bool {
        let normalized = password.precomposedStringWithCanonicalMapping
        let scalars = Array(normalized.unicodeScalars)
        if scalars.count < BackupCrypto.minPasswordLength { return true }
        let digits: ClosedRange<Unicode.Scalar> = "0"..."9"
        if scalars.allSatisfy({ digits.contains($0) }) { return true }
        if Set(scalars).count == 1 { return true }
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
