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
        XCTAssertEqual(try data(iv), Data((0..<12).map { UInt8(0xA0 + $0) }))

        let env = try BackupCrypto.encrypt(Data(V.plain.utf8), password: V.password, salt: data(salt), iv: data(iv))
        XCTAssertEqual(env.data.base64EncodedString(), V.data)
        XCTAssertEqual(env.iterations, BackupCrypto.defaultIterations)
        XCTAssertTrue(BackupCrypto.verifyTestVector())
    }

    func testVectorDecrypts() throws {
        let root: [String: Any] = ["format": "cryptochecker-backup-enc", "v": 2, "enc": "AES-256-GCM",
                                   "kdf": "PBKDF2-HMAC-SHA256", "iter": 210_000, "salt": salt, "iv": iv, "data": V.data]
        let env = try BackupCrypto.envelope(from: root)
        let plain = try BackupCrypto.decrypt(env, password: V.password)
        XCTAssertEqual(String(decoding: plain, as: UTF8.self), V.plain)
    }

    func testDecomposedPasswordEqualsComposed() throws {
        // «ö» als o + U+0308 (NFD) ergibt denselben Schlüssel wie das vorkomponierte «ö»
        let env = try BackupCrypto.encrypt(Data(V.plain.utf8), password: "Krypto\u{0308}-Test 2026!",
                                           salt: data(salt), iv: data(iv))
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
        let env = try BackupCrypto.encrypt(Data(V.plain.utf8), password: V.password, salt: data(salt), iv: data(iv))
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
        let rejected: [[String: Any]] = [
            root(v: 3), root(enc: "ChaCha20"), root(kdf: "scrypt"), root(iter: 1), root(iter: 2_147_483_647),
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
        XCTAssertFalse(BackupCrypto.isPasswordLongEnough("1234567"))
        XCTAssertTrue(BackupCrypto.isPasswordLongEnough("12345678"))
        // Emoji zählt als ein Zeichen
        XCTAssertFalse(BackupCrypto.isPasswordLongEnough("123456🔒"))
        XCTAssertTrue(BackupCrypto.canExport(protect: false, password: "", repeated: ""))
        XCTAssertFalse(BackupCrypto.canExport(protect: true, password: pwEight, repeated: "12345679"))
        XCTAssertFalse(BackupCrypto.canExport(protect: true, password: "1234", repeated: "1234"))
        XCTAssertTrue(BackupCrypto.canExport(protect: true, password: pwEight, repeated: "12345678"))
    }
}
