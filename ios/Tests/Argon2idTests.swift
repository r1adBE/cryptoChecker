import XCTest
@testable import CryptoChecker

/// Argon2id gegen die Prüfwerte von RFC 9106 (gleiche Fälle wie `Argon2idTest.kt`).
final class Argon2idTests: XCTestCase {

    private func hex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    func testRfc9106Argon2idVector() throws {
        // RFC 9106 Abschnitt 5.3: Passwort 32 × 01, Salt 16 × 02, Secret 8 × 03, AD 12 × 04, t = 3, m = 32, p = 4
        let tag = try Argon2id.hash(
            password: [UInt8](repeating: 1, count: 32),
            salt: [UInt8](repeating: 2, count: 16),
            memoryKiB: 32,
            iterations: 3,
            parallelism: 4,
            tagLength: 32,
            secret: [UInt8](repeating: 3, count: 8),
            ad: [UInt8](repeating: 4, count: 12)
        )
        XCTAssertEqual(hex(tag), "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659")
    }

    func testVariableLengthHash() {
        // H' (BLAKE2b über LE32(Länge) || Eingabe; über 64 Bytes in Stücken zu 32), mit Python hashlib nachgerechnet —
        // prüft zugleich BLAKE2b (kurze Ausgabe, genau 64, mehrere Stücke)
        let abc = Array("abc".utf8)
        XCTAssertEqual(hex(Argon2id.hashLong(abc, length: 4)), "3e6c02ac")
        XCTAssertEqual(hex(Argon2id.hashLong(abc, length: 64)),
                       "f32577a3172f56657d531faaa43077bb8c9726ada7bb04dd337ec5a65454abff" +
                       "241ad6b87a72440e5127c6f9caa70327f2a699096e52d163eb52d9cd99620593")
        XCTAssertEqual(hex(Argon2id.hashLong(abc, length: 100)),
                       "4c9ba23bcafae5e571a5d41673bb8084a4a1de2688416ed390f669d33d364f3d" +
                       "4d9bfa7fe762680c6b2362711c4ce5b2c60ddcd14c1277ec1369c79f44c28966" +
                       "98a2b0773a3ce2e410532fa7c72f0bb61ccca0c24c362f337555cbf2998f2d36" +
                       "01be70d1")
    }

    func testLongTagsUseVariableLengthHash() throws {
        let pw = [UInt8](repeating: 7, count: 8)
        let salt = [UInt8](repeating: 9, count: 16)
        let short = try Argon2id.hash(password: pw, salt: salt, memoryKiB: 64, iterations: 1, parallelism: 1, tagLength: 64)
        let long = try Argon2id.hash(password: pw, salt: salt, memoryKiB: 64, iterations: 1, parallelism: 1, tagLength: 100)
        XCTAssertEqual(long.count, 100)
        XCTAssertNotEqual(short, Array(long.prefix(64)))
    }

    func testInvalidParametersAreRejected() {
        let pw = [UInt8](repeating: 0, count: 4)
        let cases: [(Int, Int, Int, Int)] = [(15, 1, 2, 16), (64, 0, 1, 16), (64, 1, 0, 16), (64, 1, 1, 7)]
        for (m, t, p, saltCount) in cases {
            XCTAssertThrowsError(try Argon2id.hash(password: pw, salt: [UInt8](repeating: 0, count: saltCount),
                                                   memoryKiB: m, iterations: t, parallelism: p, tagLength: 32)) { error in
                XCTAssertEqual(error as? Argon2id.Failure, .invalidParameters)
            }
        }
    }
}
