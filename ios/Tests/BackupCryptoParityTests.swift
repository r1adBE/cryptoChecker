import XCTest
@testable import CryptoChecker

/// Verschlüsselte Sicherungen v3 (Argon2id + AAD) mit festen Prüfwerten (`backup_crypto.json`, mit
/// Python `cryptography` nachgerechnet); Gegenstück: `BackupCryptoParityTest.kt`. Gleiche Bytes auf beiden Seiten =
/// Sicherungen zwischen Android und iPhone austauschbar.
final class BackupCryptoParityTests: XCTestCase {

    private func hex(_ value: String) -> [UInt8] {
        let chars = Array(value)
        return stride(from: 0, to: chars.count, by: 2).map { UInt8(String(chars[$0...$0 + 1]), radix: 16) ?? 0 }
    }

    private func data(_ base64: Any?) throws -> Data {
        try XCTUnwrap((base64 as? String).flatMap { Data(base64Encoded: $0) })
    }

    func testV3FilesDecryptAndHeaderIsCanonical() throws {
        let root = try TestSupport.fixture("backup_crypto")
        let password = try XCTUnwrap(root["password"] as? String)
        let plain = try XCTUnwrap(root["plain"] as? String)
        let files = TestSupport.list(root["v3"])
        XCTAssertEqual(files.count, 3)
        for f in files {
            let env = try BackupCrypto.envelope(from: f)
            XCTAssertEqual(env.version, 3)
            XCTAssertEqual(String(decoding: BackupCrypto.header(env), as: UTF8.self), f["header"] as? String)
            XCTAssertEqual(String(decoding: try BackupCrypto.decrypt(env, password: password), as: UTF8.self), plain)
        }
    }

    func testV3EncryptionMatchesFixture() throws {
        let root = try TestSupport.fixture("backup_crypto")
        let password = try XCTUnwrap(root["password"] as? String)
        let plain = try XCTUnwrap(root["plain"] as? String)
        // Ohne den ersten Fall (64 MiB): den prüft BackupCryptoTests.testNewBackupsUseArgon2idV3
        for f in TestSupport.list(root["v3"]).dropFirst() {
            let kdf = BackupCrypto.Kdf.argon2(memoryKiB: try XCTUnwrap(f["m"] as? Int), time: try XCTUnwrap(f["t"] as? Int),
                                              parallelism: try XCTUnwrap(f["p"] as? Int))
            let env = try BackupCrypto.encrypt(Data(plain.utf8), password: password, salt: data(f["salt"]),
                                               iv: data(f["iv"]), kdf: kdf)
            XCTAssertEqual(env.data.base64EncodedString(), f["data"] as? String)
            // Geschriebenes JSON trägt dieselben Felder wie die Prüfdatei
            let written = try XCTUnwrap(try JSONSerialization.jsonObject(with: BackupCrypto.json(env)) as? [String: Any])
            for key in ["format", "enc", "kdf", "salt", "iv", "data"] {
                XCTAssertEqual(written[key] as? String, f[key] as? String, key)
            }
            for key in ["v", "m", "t", "p"] {
                XCTAssertEqual(written[key] as? Int, f[key] as? Int, key)
            }
        }
    }

    func testManipulatedFilesAreRejected() throws {
        let root = try TestSupport.fixture("backup_crypto")
        let password = try XCTUnwrap(root["password"] as? String)
        let cases = TestSupport.list(root["reject"])
        XCTAssertGreaterThanOrEqual(cases.count, 20)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let file = try XCTUnwrap(c["file"] as? [String: Any], name)
            let result: String
            do {
                _ = try BackupCrypto.decrypt(try BackupCrypto.envelope(from: file), password: password)
                result = "accepted"
            } catch BackupCrypto.Failure.wrongPassword {
                result = "wrongPassword"
            } catch BackupCrypto.Failure.unsupported {
                result = "unsupported"
            } catch BackupVersion.Failure.newer {
                result = "newer"
            } catch {
                result = "\(error)"
            }
            XCTAssertEqual(result, c["result"] as? String, name)
        }
    }

    /// Hülle als Text wie aus der Datei (`BackupManager.needsPassword`, dann `BackupCrypto.envelope(from:)`) — gleiche
    /// Fälle wie Android `BackupFormat.parse`.
    func testEnvelopeTextIsReadStrictly() throws {
        let root = try TestSupport.fixture("backup_crypto")
        for c in TestSupport.list(root["envelopeText"]) {
            let name = c["name"] as? String ?? ""
            let data = Data(try XCTUnwrap(c["text"] as? String, name).utf8)
            let result: String
            do {
                guard try BackupManager.needsPassword(data),
                      let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    XCTFail(name)
                    continue
                }
                _ = try BackupCrypto.envelope(from: object)
                result = "envelope"
            } catch BackupCrypto.Failure.unsupported {
                result = "unsupported"
            } catch BackupVersion.Failure.newer {
                result = "newer"
            } catch {
                result = "invalid"
            }
            XCTAssertEqual(result, c["result"] as? String, name)
        }
    }

    func testArgon2idMatchesRfc9106() throws {
        let root = try TestSupport.fixture("backup_crypto")
        let v = try XCTUnwrap(root["rfc9106"] as? [String: Any])
        let tagHex = try XCTUnwrap(v["tag"] as? String)
        let tag = try Argon2id.hash(
            password: hex(try XCTUnwrap(v["password"] as? String)),
            salt: hex(try XCTUnwrap(v["salt"] as? String)),
            memoryKiB: try XCTUnwrap(v["m"] as? Int),
            iterations: try XCTUnwrap(v["t"] as? Int),
            parallelism: try XCTUnwrap(v["p"] as? Int),
            tagLength: tagHex.count / 2,
            secret: hex(try XCTUnwrap(v["secret"] as? String)),
            ad: hex(try XCTUnwrap(v["ad"] as? String))
        )
        XCTAssertEqual(tag, hex(tagHex))
    }
}
