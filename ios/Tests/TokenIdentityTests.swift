import XCTest
@testable import CryptoChecker

/// Gleicher Token? (K-8): gemeinsame Fälle `Parity/token_identity.json` (Gegenstück `TokenIdentityTest.kt`).
final class TokenIdentityTests: XCTestCase {
    private typealias S = TestSupport

    private func text(_ value: Any?) -> String? {
        value as? String
    }

    private func identity(_ raw: Any?) -> TokenIdentity.Identity? {
        guard let o = raw as? [String: Any] else { return nil }
        let contracts = (o["contracts"] as? [Any])?.compactMap { $0 as? String } ?? []
        return TokenIdentity.Identity(name: text(o["name"]), contracts: Set(contracts))
    }

    func testNormalizeParity() throws {
        let data = try S.fixture("token_identity")
        for c in S.list(data["normalize"]) {
            XCTAssertEqual(TokenIdentity.normalizeName(text(c["input"]), symbol: c["symbol"] as? String ?? ""),
                           text(c["expected"]), c["name"] as? String ?? "")
        }
    }

    func testNamesParity() throws {
        let data = try S.fixture("token_identity")
        for c in S.list(data["names"]) {
            let symbol = c["symbol"] as? String ?? ""
            let expected = TokenIdentity.Match(rawValue: c["expected"] as? String ?? "")
            let name = c["name"] as? String ?? ""
            XCTAssertEqual(TokenIdentity.matchNames(symbol: symbol, text(c["a"]), text(c["b"])), expected, name)
            // Symmetrisch
            XCTAssertEqual(TokenIdentity.matchNames(symbol: symbol, text(c["b"]), text(c["a"])), expected, name + " (b, a)")
        }
    }

    func testIdentitiesParity() throws {
        let data = try S.fixture("token_identity")
        for c in S.list(data["identities"]) {
            let symbol = c["symbol"] as? String ?? ""
            let expected = TokenIdentity.Match(rawValue: c["expected"] as? String ?? "")
            let name = c["name"] as? String ?? ""
            XCTAssertEqual(TokenIdentity.match(symbol: symbol, identity(c["a"]), identity(c["b"])), expected, name)
            XCTAssertEqual(TokenIdentity.match(symbol: symbol, identity(c["b"]), identity(c["a"])), expected, name + " (b, a)")
        }
    }

    func testAcceptParity() throws {
        let data = try S.fixture("token_identity")
        for c in S.list(data["accept"]) {
            let match = try XCTUnwrap(TokenIdentity.Match(rawValue: c["match"] as? String ?? ""))
            XCTAssertEqual(TokenIdentity.accept(match, bandOk: c["band"] as? Bool ?? false), c["expected"] as? Bool, "\(c)")
        }
    }

    func testFallbackSeriesParity() throws {
        let data = try S.fixture("token_identity")
        for c in S.list(data["fallbackSeries"]) {
            let allowed: Bool = TokenIdentity.fallbackSeriesAllowed(symbol: c["symbol"] as? String ?? "", ownName: text(c["own"]),
                                                                  provider: text(c["provider"]), binanceName: text(c["binance"]),
                                                                  providerName: text(c["providerName"]))
            XCTAssertEqual(allowed, c["expected"] as? Bool, c["name"] as? String ?? "")
        }
    }

    func testChainSourcesParity() throws {
        let data = try S.fixture("token_identity")
        let cases = S.list(data["chainSources"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let allowed: Bool = TokenIdentity.chainSourceAllowed(symbol: c["symbol"] as? String ?? "", ownName: text(c["own"]),
                                                               host: c["host"] as? String ?? "", binanceName: text(c["binance"]),
                                                               providerName: text(c["providerName"]))
            XCTAssertEqual(allowed, c["expected"] as? Bool, c["name"] as? String ?? "")
        }
    }

    func testConsensusParity() throws {
        let data = try S.fixture("token_identity")
        for c in S.list(data["consensus"]) {
            let names = (c["names"] as? [Any] ?? []).map { $0 as? String }
            let symbol = c["symbol"] as? String ?? ""
            let name = c["name"] as? String ?? ""
            XCTAssertEqual(TokenIdentity.consensus(symbol: symbol, names), text(c["expected"]), name)
            XCTAssertEqual(TokenIdentity.conflicting(symbol: symbol, names), c["conflict"] as? Bool, name)
        }
    }

    func testCompatibleOnlyRejectsClearMismatch() {
        XCTAssertTrue(TokenIdentity.compatible(symbol: "CAT", "Simon's Cat", nil))
        XCTAssertTrue(TokenIdentity.compatible(symbol: "CAT", "Simon's Cat", "Simons Cat"))
        XCTAssertFalse(TokenIdentity.compatible(symbol: "CAT", "Simon's Cat", "Catcoin"))
    }

    func testContractKeyNormalizesEvmOnly() {
        XCTAssertEqual(TokenIdentity.contractKey(chain: " Ethereum ", address: "0xABC"), "ethereum:0xabc")
        XCTAssertEqual(TokenIdentity.contractKey(chain: "SOLANA", address: "AbC"), "solana:AbC")
        XCTAssertNil(TokenIdentity.contractKey(chain: "", address: "0x1"))
        XCTAssertNil(TokenIdentity.contractKey(chain: "bsc", address: " "))
    }

    func testNameIndexRoundTripAndCleaning() {
        let cleaned = TokenIdentity.cleaned(["cat": " Simon's Cat ", "X": "", "Y": String(repeating: "y", count: 81)])
        XCTAssertEqual(cleaned, ["CAT": "Simon's Cat"])
        let index = TokenIdentity.NameIndex(markets: ["GateIo": cleaned], binance: ["CAT": "Simon's Cat"])
        let back = TokenIdentity.decode(TokenIdentity.encode(index))
        XCTAssertEqual(back, index)
        XCTAssertEqual(back.pairName(marketKey: "GateIo", base: " cat"), "Simon's Cat")
        XCTAssertEqual(back.binanceName("Cat"), "Simon's Cat")
        XCTAssertNil(back.pairName(marketKey: "Kraken", base: "CAT"))
        XCTAssertEqual(TokenIdentity.decode(Data("{not json".utf8)), TokenIdentity.NameIndex())
        XCTAssertEqual(TokenIdentity.decode(nil), TokenIdentity.NameIndex())
    }
}
