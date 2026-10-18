import XCTest
@testable import CryptoChecker

/// Referenz-Fuzz der xpub-Ableitung aus `bip32_fuzz.json` (erzeugt von `android/tools/gen_bip32_vectors.py`,
/// Python-Referenz mit OpenSSL gegengeprüft; Android: `Bip32FuzzParityTest.kt`). Abweichungen werden gesammelt
/// gemeldet, damit ein Fehler in der eigenen 256-Bit-Arithmetik nicht hinter dem ersten Fall verschwindet.
final class Bip32FuzzTests: XCTestCase {

    private typealias S = TestSupport

    private func data() throws -> [String: Any] { try S.fixture("bip32_fuzz") }

    private func hex(_ bytes: [UInt8]) -> String { bytes.map { String(format: "%02x", $0) }.joined() }

    private func unhex(_ s: String) -> [UInt8] {
        let chars = Array(s)
        return stride(from: 0, to: chars.count, by: 2).map { (i: Int) -> UInt8 in UInt8(String(chars[i...i + 1]), radix: 16) ?? 0 }
    }

    private func int(_ value: Any?) -> Int64 { (value as? NSNumber)?.int64Value ?? -99 }

    private let scripts: [Bip32.Script] = [.p2pkh, .p2shP2wpkh, .p2wpkh, .p2tr]

    private func assertNoMismatch(_ what: String, _ mismatches: [String], file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(mismatches.isEmpty, "\(what): \(mismatches.count) Abweichungen, z. B.\n"
                      + mismatches.prefix(5).joined(separator: "\n"), file: file, line: line)
    }

    func testZufaelligeSchluesselLeitenWieDieReferenzAb() throws {
        let cases = S.list(try data()["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 1000)
        var mismatches: [String] = []
        for (n, c) in cases.enumerated() {
            let xpub = c["xpub"] as? String ?? ""
            let chain = int(c["chain"])
            let index = int(c["index"])
            guard let root = Bip32.parse(xpub) else {
                mismatches.append("#\(n) parse nil")
                continue
            }
            if root.script.code != c["script"] as? String { mismatches.append("#\(n) script \(root.script.code)") }
            if Bip32.serialize(root) != xpub { mismatches.append("#\(n) serialize") }
            guard let chainKey = Bip32.child(root, index: chain), let leaf = Bip32.child(chainKey, index: index) else {
                mismatches.append("#\(n) child nil")
                continue
            }
            if hex(leaf.publicKey) != c["pubkey"] as? String { mismatches.append("#\(n) pubkey \(chain)/\(index)") }
            if Bip32.serialize(leaf) != c["leaf"] as? String { mismatches.append("#\(n) leaf \(chain)/\(index)") }
            let expected = (c["addresses"] as? [String]) ?? []
            for (i, script) in scripts.enumerated() {
                let got = Bip32.address(script, publicKey: leaf.publicKey)
                if i >= expected.count || got != expected[i] {
                    mismatches.append("#\(n) \(script.code) \(chain)/\(index): \(got ?? "nil")")
                }
            }
            // x-only: der Tweak hängt nicht von der Parität des Schlüssels ab
            let x = Array(leaf.publicKey[1..<33])
            if Bip32.taprootOutputKey([2] + x) != Bip32.taprootOutputKey([3] + x) { mismatches.append("#\(n) tweak parity") }
            // Ableiter (Kettenschlüssel zwischengespeichert) stichprobenweise, sonst doppelte Kurvenrechnung
            if n % 10 == 0, let at = scripts.firstIndex(of: root.script), at < expected.count {
                let got = XpubDeriver(xpub)?.address(chain: Int(chain), index: Int(index))
                if got != expected[at] { mismatches.append("#\(n) deriver \(got ?? "nil")") }
            }
        }
        assertNoMismatch("Ableitung", mismatches)
    }

    func testPunktrechnungAnRandfaellen() throws {
        var mismatches: [String] = []
        for op in S.list(try data()["pointOps"]) {
            let got = Bip32.multiplyAddG(unhex(op["k"] as? String ?? ""), unhex(op["q"] as? String ?? "")).map(hex)
            let want = op["result"] as? String
            if got != want { mismatches.append("\(op["note"] as? String ?? ""): \(got ?? "nil") ≠ \(want ?? "nil")") }
        }
        assertNoMismatch("k·G + Q", mismatches)
    }

    func testGebastelteSchluesselWerdenGeprueft() throws {
        for c in S.list(try data()["keyChecks"]) {
            let note = c["note"] as? String ?? ""
            let xpub = c["xpub"] as? String ?? ""
            let problem = c["problem"] as? String
            XCTAssertEqual(Bip32.check(xpub)?.rawValue, problem, note)
            let key = Bip32.parse(xpub)
            if problem == nil {
                let parsed = try XCTUnwrap(key, note)
                XCTAssertEqual(Bip32.serialize(parsed), xpub, note)
                XCTAssertEqual(Bip32.child(parsed, index: 0).map { hex($0.publicKey) }, c["child0"] as? String, note)
            } else {
                XCTAssertNil(key, note)
            }
        }
    }

    func testIndexGrenzen() throws {
        let d = try data()
        let key = try XCTUnwrap(Bip32.parse(S.list(d["cases"]).first?["xpub"] as? String ?? ""))
        for c in S.list(d["indexLimits"]) {
            let index = int(c["index"])
            XCTAssertEqual(Bip32.child(key, index: index) != nil, c["valid"] as? Bool, "Index \(index)")
        }
    }
}
