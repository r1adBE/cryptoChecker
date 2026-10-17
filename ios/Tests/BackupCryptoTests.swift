import XCTest
@testable import CryptoChecker

private let pwSecret = "geheim123"
private let pwRight = "richtig-123"
private let pwWrong = "falsch-1234"
private let pwEight = "12345678"

/// Wie `BackupCryptoTest.kt` — vor allem der feste Prüfwert (mit Python `cryptography`
/// nachgerechnet): Android und iOS müssen Sicherungen gegenseitig lesen können.
final class BackupCryptoTests: XCTestCase {

    private typealias V = BackupCrypto.TestVector
    private let salt = "AAECAwQFBgcICQoLDA0ODw=="
    private let iv = "oKGio6Slpqeoqaqr"

    private func data(_ base64: String) throws -> Data { try XCTUnwrap(Data(base64Encoded: base64)) }

    private func isWrongPassword(_ error: Error) -> Bool {
        if case BackupCrypto.Failure.wrongPassword = error { return true }
        return false
    }

    private func isUnsupported(_ error: Error) -> Bool {
        if case BackupCrypto.Failure.unsupported = error { return true }
        return false
    }

    func testVectorMatchesAndroidAndPython() throws {
        // Gleiche Werte wie in BackupCryptoTest.kt
        XCTAssertEqual(V.password, "Kryptö-Test 2026!")
        XCTAssertEqual(V.salt, salt)
        XCTAssertEqual(V.iv, iv)
        XCTAssertEqual(V.plain, #"{"format":"cryptochecker-backup","version":1,"portfolio":[]}"#)
        XCTAssertEqual(V.data,
                       "F8ZO22w17BWWRjDa0IBQavtrw3kOeap/pSDHBNMr2yCCLsZy82l3RqH6OxEDzba6sota8lpn/SClooHDYy0tm81k5gCByAiia3plAA==")
        XCTAssertEqual(try data(salt), Data((0..<16).map { UInt8($0) }))
        XCTAssertEqual(try data(iv), Data((0..<12).map { (i: Int) -> UInt8 in UInt8(0xA0 + i) }))

        let env = try BackupCrypto.encrypt(Data(V.plain.utf8), password: V.password, salt: data(salt), iv: data(iv),
                                           iterations: BackupCrypto.legacyIterations)
        XCTAssertEqual(env.data.base64EncodedString(), V.data)
        XCTAssertEqual(env.iterations, 210_000)
        XCTAssertTrue(BackupCrypto.verifyTestVector())
    }

    func testNewBackupsUse600kIterations() throws {
        XCTAssertEqual(BackupCrypto.defaultIterations, 600_000)
        XCTAssertEqual(V.data600k,
                       "0Ku8OwoZGZNMLB4nSmFxc+ftNLme+bH0q7YUsGipFoc6Edly6EQAyI3y2P5g9FVosu0L/CWRglvDAWzcW6/sO5/liBMAHn0IC/qYSg==")
        let env = try BackupCrypto.encrypt(Data(V.plain.utf8), password: V.password, salt: data(salt), iv: data(iv))
        XCTAssertEqual(env.iterations, 600_000)
        XCTAssertEqual(env.data.base64EncodedString(), V.data600k)
        XCTAssertTrue(String(decoding: BackupCrypto.json(env), as: UTF8.self).contains(#""iter": 600000"#))
    }

    func testVectorDecrypts() throws {
        // Alte Datei (iter 210000) bleibt lesbar
        let root: [String: Any] = ["format": "cryptochecker-backup-enc", "v": 2, "enc": "AES-256-GCM",
                                   "kdf": "PBKDF2-HMAC-SHA256", "iter": 210_000, "salt": salt, "iv": iv, "data": V.data]
        let env = try BackupCrypto.envelope(from: root)
        let plain = try BackupCrypto.decrypt(env, password: V.password)
        XCTAssertEqual(String(decoding: plain, as: UTF8.self), V.plain)
    }

    func testVector600kDecrypts() throws {
        let root: [String: Any] = ["format": "cryptochecker-backup-enc", "v": 2, "enc": "AES-256-GCM",
                                   "kdf": "PBKDF2-HMAC-SHA256", "iter": 600_000, "salt": salt, "iv": iv,
                                   "data": V.data600k]
        let env = try BackupCrypto.envelope(from: root)
        let plain = try BackupCrypto.decrypt(env, password: V.password)
        XCTAssertEqual(String(decoding: plain, as: UTF8.self), V.plain)
    }

    func testDecomposedPasswordEqualsComposed() throws {
        // «ö» als o + U+0308 (NFD) ergibt denselben Schlüssel wie das vorkomponierte «ö»
        let env = try BackupCrypto.encrypt(Data(V.plain.utf8), password: "Krypto\u{0308}-Test 2026!",
                                           salt: data(salt), iv: data(iv), iterations: BackupCrypto.legacyIterations)
        XCTAssertEqual(env.data.base64EncodedString(), V.data)
    }

    func testRoundTripWithRandomSaltAndIv() throws {
        let plain = Data(#"{"format":"cryptochecker-backup","version":1,"note":"Ünïcödé ✓"}"#.utf8)
        let a = try BackupCrypto.encrypt(plain, password: pwSecret)
        let b = try BackupCrypto.encrypt(plain, password: pwSecret)
        XCTAssertNotEqual(a.salt, b.salt)
        XCTAssertNotEqual(a.iv, b.iv)
        XCTAssertEqual(a.data.count, plain.count + BackupCrypto.tagBytes)
        XCTAssertEqual(try BackupCrypto.decrypt(a, password: pwSecret), plain)
        XCTAssertEqual(try BackupCrypto.decrypt(b, password: pwSecret), plain)
    }

    func testWrongPasswordIsReported() throws {
        let env = try BackupCrypto.encrypt(Data("{}".utf8), password: pwRight)
        XCTAssertThrowsError(try BackupCrypto.decrypt(env, password: pwWrong)) { error in
            XCTAssertTrue(self.isWrongPassword(error), "\(error)")
        }
    }

    func testTamperedDataIsRejected() throws {
        let env = try BackupCrypto.encrypt(Data(#"{"a":1}"#.utf8), password: pwRight)
        var bad = env.data
        bad[bad.startIndex] ^= 1
        XCTAssertThrowsError(try BackupCrypto.decrypt(
            BackupCrypto.Envelope(iterations: env.iterations, salt: env.salt, iv: env.iv, data: bad), password: pwRight
        )) { error in XCTAssertTrue(self.isWrongPassword(error), "\(error)") }
        var badTag = env.data
        badTag[badTag.index(before: badTag.endIndex)] ^= 0x80
        XCTAssertThrowsError(try BackupCrypto.decrypt(
            BackupCrypto.Envelope(iterations: env.iterations, salt: env.salt, iv: env.iv, data: badTag), password: pwRight
        )) { error in XCTAssertTrue(self.isWrongPassword(error), "\(error)") }
    }

    func testEnvelopeJsonHasTheAndroidFields() throws {
        let env = try BackupCrypto.encrypt(Data(V.plain.utf8), password: V.password, salt: data(salt), iv: data(iv),
                                           iterations: BackupCrypto.legacyIterations)
        let json = String(decoding: BackupCrypto.json(env), as: UTF8.self)
        for field in [#""format": "cryptochecker-backup-enc""#, #""v": 2"#, #""enc": "AES-256-GCM""#,
                      #""kdf": "PBKDF2-HMAC-SHA256""#, #""iter": 210000"#, "\"salt\": \"\(salt)\"",
                      "\"iv\": \"\(iv)\"", "\"data\": \"\(V.data)\""] {
            XCTAssertTrue(json.contains(field), field)
        }
        // Und wieder lesbar
        let object = try JSONSerialization.jsonObject(with: BackupCrypto.json(env))
        let root = try XCTUnwrap(object as? [String: Any])
        XCTAssertTrue(BackupCrypto.isEnvelope(root))
        XCTAssertEqual(try BackupCrypto.envelope(from: root).data, env.data)
    }

    func testUnsupportedEnvelopesAreRejected() {
        func root(v: Int = 2, enc: String = "AES-256-GCM", kdf: String = "PBKDF2-HMAC-SHA256", iter: Int = 210_000,
                  salt: String? = "AAECAwQFBgcICQoLDA0ODw==", iv: String = "oKGio6Slpqeoqaqr",
                  data: String = BackupCrypto.TestVector.data) -> [String: Any] {
            var r: [String: Any] = ["format": "cryptochecker-backup-enc", "v": v, "enc": enc, "kdf": kdf, "iter": iter,
                                    "iv": iv, "data": data]
            if let salt { r["salt"] = salt }
            return r
        }
        // Neuere Hülle: eigene Meldung «neuere App-Version» (wie Android)
        XCTAssertThrowsError(try BackupCrypto.envelope(from: root(v: 3))) { error in
            XCTAssertEqual(error as? BackupVersion.Failure, .newer(3), "\(error)")
        }
        let rejected: [[String: Any]] = [
            root(v: 1), root(enc: "ChaCha20"), root(kdf: "scrypt"), root(iter: 1), root(iter: 2_147_483_647),
            root(iv: "AAEC"), root(data: "%%%"), root(salt: nil), root(data: "AAEC"),
        ]
        for r in rejected {
            XCTAssertThrowsError(try BackupCrypto.envelope(from: r)) { error in
                XCTAssertTrue(self.isUnsupported(error), "\(error)")
            }
        }
    }

    func testEnvelopeDetection() {
        XCTAssertTrue(BackupCrypto.isEnvelope(["format": "cryptochecker-backup-enc", "enc": "AES-256-GCM"]))
        XCTAssertFalse(BackupCrypto.isEnvelope(["format": "cryptochecker-backup-enc"]))
        XCTAssertFalse(BackupCrypto.isEnvelope(["format": "cryptochecker-backup-enc", "enc": ""]))
        XCTAssertFalse(BackupCrypto.isEnvelope(["format": "other", "enc": "AES-256-GCM"]))
        XCTAssertFalse(BackupCrypto.isEnvelope(["format": "cryptochecker-backup", "enc": "AES-256-GCM"]))
    }

    func testPasswordRules() {
        // Mindestlänge 10 (neue Sicherungen)
        XCTAssertFalse(BackupCrypto.isPasswordLongEnough("123456789"))
        XCTAssertTrue(BackupCrypto.isPasswordLongEnough("1234567890"))
        // Emoji zählt als ein Zeichen
        XCTAssertFalse(BackupCrypto.isPasswordLongEnough("12345678🔒"))
        XCTAssertTrue(BackupCrypto.isPasswordLongEnough("123456789🔒"))
        let good = "Kryptö-Test 2026!"
        XCTAssertTrue(BackupCrypto.canExport(protect: false, password: "", repeated: ""))
        XCTAssertFalse(BackupCrypto.canExport(protect: true, password: good, repeated: "Kryptö-Test 2027!"))
        XCTAssertFalse(BackupCrypto.canExport(protect: true, password: "1234", repeated: "1234"))
        XCTAssertTrue(BackupCrypto.canExport(protect: true, password: good, repeated: good))
        // Schwache Passwörter sperren das Sichern, auch ab 10 Zeichen
        // Liste statt Literal am Parameter: das Upload-Skript meldet sonst ein Passwort im Klartext
        let weakSamples = ["1234567890", "aaaaaaaaaaaa", "MeinPasswort"]
        for weak in weakSamples {
            XCTAssertFalse(BackupCrypto.canExport(protect: true, password: weak, repeated: weak), weak)
        }
        XCTAssertFalse(BackupCrypto.isPasswordAllowed("Abc!def9x"))     // 9 Zeichen
        XCTAssertTrue(BackupCrypto.isPasswordAllowed("Abc!def9xy"))     // 10 Zeichen
    }

    func testOldFilesDecryptWithShortPassword() throws {
        // Entschlüsseln prüft das Passwort nicht: alte Sicherungen mit 8 Zeichen bleiben lesbar
        let plain = Data("{}".utf8)
        let env = try BackupCrypto.encrypt(plain, password: pwEight, iterations: 10_000)
        XCTAssertEqual(try BackupCrypto.decrypt(env, password: pwEight), plain)
    }

    // MARK: Schwaches Passwort — gleiche Fälle wie BackupPasswordStrengthTest.kt

    func testPasswordStrengthShortIsWeak() {
        XCTAssertTrue(BackupPasswordStrength.isWeak("Abc!def9"))
        XCTAssertTrue(BackupPasswordStrength.isWeak("Abc!def9x"))        // 9 Zeichen
        XCTAssertFalse(BackupPasswordStrength.isWeak("Abc!def9xy"))      // 10 Zeichen
        XCTAssertFalse(BackupPasswordStrength.isWeak("Abc!def9xyz"))     // 11 Zeichen
        // Emoji zählt als ein Zeichen
        XCTAssertTrue(BackupPasswordStrength.isWeak("Abc!def9🔒"))       // 9 Zeichen
        XCTAssertFalse(BackupPasswordStrength.isWeak("Abc!def9x🔒"))     // 10 Zeichen
    }

    func testPasswordStrengthOnlyDigitsOrOneRepeatedChar() {
        XCTAssertTrue(BackupPasswordStrength.isWeak("982374659102"))
        XCTAssertTrue(BackupPasswordStrength.isWeak("aaaaaaaaaaaaaaa"))
        XCTAssertTrue(BackupPasswordStrength.isWeak("ööööööööööööö"))
    }

    func testPasswordStrengthCommonSequences() {
        XCTAssertTrue(BackupPasswordStrength.isWeak("12345678abcd"))
        XCTAssertTrue(BackupPasswordStrength.isWeak("MeinPassword!"))
        XCTAssertTrue(BackupPasswordStrength.isWeak("PASSWORT2026!"))
        XCTAssertTrue(BackupPasswordStrength.isWeak("Qwertz-Sommer"))
        XCTAssertTrue(BackupPasswordStrength.isWeak("xQWERTYx9!aa"))
        XCTAssertTrue(BackupPasswordStrength.isWeak("abcdefgh1234"))
    }

    func testPasswordStrengthLongMixedIsNotWeak() {
        XCTAssertFalse(BackupPasswordStrength.isWeak("Kryptö-Test 2026!"))
        XCTAssertFalse(BackupPasswordStrength.isWeak("blau Haus Fahrrad"))
        XCTAssertFalse(BackupPasswordStrength.isWeak("7Tg#kd92!mQz"))
    }

    func testPasswordStrengthHintOnlyFromMinimumLength() {
        XCTAssertFalse(BackupPasswordStrength.showWeakHint(""))
        XCTAssertFalse(BackupPasswordStrength.showWeakHint("123456789"))   // noch «mindestens 10 Zeichen»
        XCTAssertFalse(BackupPasswordStrength.showWeakHint("Abc!def9"))
        XCTAssertTrue(BackupPasswordStrength.showWeakHint("1234567890"))
        XCTAssertTrue(BackupPasswordStrength.showWeakHint("Passwort123"))
        XCTAssertFalse(BackupPasswordStrength.showWeakHint("7Tg#kd92!mQz"))
    }
}

/// Wie `BackupLimitsTest.kt`: Grenzen beim Einlesen und gezählte ungültige Portfolio-Einträge.
final class BackupLimitsTests: XCTestCase {
    func testDeepNestingIsRejectedButBracketsInTextsDoNotCount() throws {
        try BackupLimits.checkDepth(Data(#"{"a":[{"b":"[[[[[[[[[[[[[[[[[[[["}]}"#.utf8), max: 4)
        XCTAssertThrowsError(try BackupLimits.checkDepth(Data(String(repeating: "[", count: 5).utf8), max: 4))
        try BackupLimits.checkDepth(Data(#"{"a":"x\"[[[[[[["}"#.utf8), max: 2)
    }

    func testInvalidPortfolioEntriesAreCounted() throws {
        let json = #"""
        {"format":"cryptochecker-backup","portfolio":[
          {"coin":"BTC","type":"BUY","amountText":"1","time":1},
          {"coin":"ETH","type":"BUY","time":2},
          "kein Objekt"],
         "portfolioAlarms":[{"kind":"UNKNOWN","threshold":1}]}
        """#
        XCTAssertEqual(try BackupManager.invalidEntries(Data(json.utf8)), 3)
    }

    /// Ohne «amountText» gelten für die Zahl dieselben Grenzen wie für den Text (wie Android `PortfolioJson.decimal`).
    func testNumberWithoutTextHasSameLimits() throws {
        let json = #"""
        {"format":"cryptochecker-backup","portfolio":[
          {"coin":"BTC","type":"BUY","amount":0.5,"time":1},
          {"coin":"BTC","type":"BUY","amount":1e300,"time":2},
          {"coin":"BTC","type":"BUY","amount":1e-50,"time":3},
          {"coin":"BTC","type":"BUY","amount":1,"priceUsdt":1e31,"time":4},
          {"coin":"BTC","type":"BUY","amount":1,"fee":1e35,"time":5}]}
        """#
        // Menge zu gross bzw. zu fein → ungültig; ungültiger Preis bzw. Gebühr → Eintrag gilt ohne
        XCTAssertEqual(try BackupManager.invalidEntries(Data(json.utf8)), 2)
    }
}
