import XCTest
@testable import CryptoChecker

/// Taproot: BIP86-Ableitung, Deskriptoren (BIP380/389) und Bech32m (BIP350) aus `taproot.json`
/// (Android: `TaprootParityTest.kt`).
final class TaprootTests: XCTestCase {

    private typealias S = TestSupport

    private func data() throws -> [String: Any] { try S.fixture("taproot") }

    private func hex(_ bytes: [UInt8]) -> String { bytes.map { String(format: "%02x", $0) }.joined() }

    private func unhex(_ s: String) -> [UInt8] {
        let chars = Array(s)
        return stride(from: 0, to: chars.count, by: 2).map { (i: Int) -> UInt8 in UInt8(String(chars[i...i + 1]), radix: 16) ?? 0 }
    }

    private func int(_ value: Any?) -> Int { (value as? NSNumber)?.intValue ?? 0 }

    func testBip86Addresses() throws {
        let b = try XCTUnwrap(try data()["bip86"] as? [String: Any])
        let normalized = try XCTUnwrap(b["normalized"] as? String)
        let xpub = try XCTUnwrap(b["xpub"] as? String)
        let key = try XCTUnwrap(Bip32.parse(normalized))
        XCTAssertTrue(key.taproot)
        XCTAssertEqual(key.script, .p2tr)
        XCTAssertFalse(key.detectsScript)
        // Gleicher Schlüssel wie der einfache xpub, nur der Adresstyp unterscheidet sich
        XCTAssertEqual(Bip32.serialize(key), xpub)
        XCTAssertFalse(try XCTUnwrap(Bip32.parse(xpub)).taproot)
        let deriver = try XCTUnwrap(XpubDeriver(normalized))
        XCTAssertEqual(deriver.script, .p2tr)
        for c in S.list(b["addresses"]) {
            let chain = int(c["chain"])
            let index = int(c["index"])
            let chainKey = try XCTUnwrap(Bip32.child(key, index: Int64(chain)))
            let leaf = try XCTUnwrap(Bip32.child(chainKey, index: Int64(index)))
            XCTAssertEqual(hex(leaf.publicKey), c["pubkey"] as? String, "pubkey \(chain)/\(index)")
            XCTAssertEqual(Bip32.taprootOutputKey(leaf.publicKey).map(hex), c["outputKey"] as? String, "output \(chain)/\(index)")
            XCTAssertEqual(Bip32.address(.p2tr, publicKey: leaf.publicKey), c["address"] as? String, "address \(chain)/\(index)")
            XCTAssertEqual(deriver.address(chain: chain, index: index), c["address"] as? String)
            let check = AddressValidation.check(c["address"] as? String)
            XCTAssertEqual(check.kind, .P2TR)
            XCTAssertEqual(check.chain, .BTC)
        }
        // Ein einfacher xpub bleibt bei der bisherigen Suche (kein Taproot ohne Deskriptor)
        XCTAssertEqual(XpubScan.scripts(try XCTUnwrap(Bip32.parse(xpub)), cache: nil), [.p2pkh, .p2shP2wpkh, .p2wpkh])
        XCTAssertEqual(XpubScan.scripts(key, cache: nil), [.p2tr])
        XCTAssertNil(XpubScan.pickScript(key, tried: [(script: .p2tr, used: 3)]))
        // Privater Schlüssel: nie, auch nicht im Deskriptor
        let xprv = try XCTUnwrap(b["xprv"] as? String)
        XCTAssertEqual(AddressValidation.check(xprv).problem, .SECRET)
        XCTAssertNil(Bip32.parse("tr(" + xprv + "/<0;1>/*)"))
    }

    func testTaggedHash() throws {
        let t = try XCTUnwrap(try data()["taggedHash"] as? [String: Any])
        XCTAssertEqual(hex(Bip32.taggedHash(t["tag"] as? String ?? "", unhex(t["data"] as? String ?? ""))), t["hash"] as? String)
    }

    func testDescriptors() throws {
        for c in S.list(try data()["descriptors"]) {
            let input = c["input"] as? String ?? ""
            let note = c["note"] as? String ?? ""
            let check = AddressValidation.check(input)
            if let normalized = c["normalized"] as? String {
                XCTAssertNil(check.problem, note)
                XCTAssertEqual(check.normalized, normalized, note)
                XCTAssertEqual(check.kind, .TR_XPUB, note)
                XCTAssertEqual(check.chain, .BTC, note)
                // Normalform prüft sich wieder zu sich selbst (Sicherung zurückspielen)
                XCTAssertEqual(AddressValidation.check(normalized).normalized, normalized, note)
                XCTAssertTrue(AddressValidation.isExtendedKey(normalized), note)
                XCTAssertEqual(AddressValidation.check(input, expected: .ETH).problem, .WRONG_CHAIN, note)
            } else {
                XCTAssertEqual(check.problem?.rawValue, c["problem"] as? String, note)
            }
        }
    }

    func testDescriptorChecksums() throws {
        for c in S.list(try data()["checksums"]) {
            let descriptor = c["descriptor"] as? String ?? ""
            XCTAssertEqual(AddressValidation.descriptorChecksum(descriptor), c["checksum"] as? String, descriptor)
        }
    }

    func testBech32m() throws {
        let b = try XCTUnwrap(try data()["bech32m"] as? [String: Any])
        for c in S.list(b["encode"]) {
            XCTAssertEqual(Bip32.segwitAddress(hrp: c["hrp"] as? String ?? "", version: int(c["version"]),
                                               program: unhex(c["program"] as? String ?? "")),
                           c["address"] as? String)
        }
        for c in S.list(b["checks"]) {
            let input = c["input"] as? String ?? ""
            let check = AddressValidation.check(input)
            XCTAssertEqual(check.problem?.rawValue, c["problem"] as? String, "problem \(input)")
            if c["problem"] as? String == nil {
                XCTAssertEqual(check.kind?.rawValue, c["kind"] as? String)
                XCTAssertEqual(check.normalized, c["normalized"] as? String)
            }
        }
    }

    func testWatchedAccount() throws {
        let b = try XCTUnwrap(try data()["bip86"] as? [String: Any])
        let normalized = try XCTUnwrap(b["normalized"] as? String)
        XCTAssertEqual(WatchedAddressRules.shortAddress(normalized), b["short"] as? String)
        XCTAssertEqual(Bip32.taprootAccount(normalized).map(Bip32.taprootDescriptor), normalized)
        XCTAssertNil(Bip32.taprootAccount(b["xpub"] as? String ?? ""))
        XCTAssertTrue(WatchedAddress(chain: .BTC, address: normalized).isXpub)
    }
}
