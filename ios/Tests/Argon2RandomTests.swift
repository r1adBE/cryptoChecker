import XCTest
@testable import CryptoChecker

/// Argon2id mit zufälligen Parametern (Passwort-, Salt-, Taglänge, m ≤ 1 MiB, t, p, teils Secret/AD) aus
/// `argon2_random.json`, gerechnet mit Python `cryptography` (`android/tools/gen_bip32_vectors.py`; Android:
/// `Argon2RandomParityTest.kt`). Ergänzt den einen RFC-Prüfwert in `Argon2idTests` um Spuren, Taglängen über 64 Bytes
/// und ungerade Speichergrössen.
final class Argon2RandomTests: XCTestCase {

    private typealias S = TestSupport

    private func hex(_ bytes: [UInt8]) -> String { bytes.map { String(format: "%02x", $0) }.joined() }

    private func unhex(_ s: String) -> [UInt8] {
        let chars = Array(s)
        return stride(from: 0, to: chars.count, by: 2).map { (i: Int) -> UInt8 in UInt8(String(chars[i...i + 1]), radix: 16) ?? 0 }
    }

    private func int(_ value: Any?) -> Int { (value as? NSNumber)?.intValue ?? 0 }

    func testZufaelligeParameterWieDieReferenz() throws {
        let cases = S.list(try S.fixture("argon2_random")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 20)
        for (n, c) in cases.enumerated() {
            let tag = try Argon2id.hash(
                password: unhex(c["password"] as? String ?? ""),
                salt: unhex(c["salt"] as? String ?? ""),
                memoryKiB: int(c["m"]),
                iterations: int(c["t"]),
                parallelism: int(c["p"]),
                tagLength: int(c["tagLength"]),
                secret: unhex(c["secret"] as? String ?? ""),
                ad: unhex(c["ad"] as? String ?? "")
            )
            XCTAssertEqual(hex(tag), c["tag"] as? String, "Fall \(n) (m=\(int(c["m"])), t=\(int(c["t"])), p=\(int(c["p"])))")
        }
    }
}
