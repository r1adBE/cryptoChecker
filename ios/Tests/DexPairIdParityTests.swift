import XCTest
@testable import CryptoChecker

/// DexScreener-Kennung «chain/adresse» (`Parity/dex_pair_id.json`, seit Build 104): ungültige Kennungen gehen nie
/// unkodiert in den Pfad — Einzelkurs «nicht gefunden» ohne Anfrage, Suchtreffer fallen weg, Sicherung lehnt ab.
/// Gegenstück: `DexPairIdParityTest.kt`.
final class DexPairIdParityTests: XCTestCase {
    private typealias S = TestSupport
    private let market = DexScreener()

    private func cases() throws -> [[String: Any]] {
        S.list(try S.fixture("dex_pair_id")["cases"])
    }

    func testKennungWieGemeinsameFaelle() throws {
        for c in try cases() {
            let id = try XCTUnwrap(c["pairId"] as? String)
            XCTAssertEqual(DexScreener.isSafePairId(id), try XCTUnwrap(c["valid"] as? Bool), id)
        }
    }

    func testEinzelkursNurMitGueltigerKennung() throws {
        let base = try XCTUnwrap(try S.fixture("dex_pair_id")["tickerBase"] as? String)
        for c in try cases() {
            let id = try XCTUnwrap(c["pairId"] as? String)
            let info = PairQuery("X", "USD (ethereum)", id, .none)
            if try XCTUnwrap(c["valid"] as? Bool) {
                XCTAssertNil(market.tickerQueryError(call: 0, info: info), id)
                XCTAssertEqual(market.tickerAddress(call: 0, info: info), base + id, id)
            } else {
                let error = market.tickerQueryError(call: 0, info: info) as? JSONError
                XCTAssertEqual(error?.message, DexScreener.poolNotFound, id)
            }
        }
        let missing = market.tickerQueryError(call: 0, info: PairQuery("X", "USD (ethereum)", nil, .none)) as? JSONError
        XCTAssertEqual(missing?.message, DexScreener.poolNotFound)
    }

    func testSuchtrefferMitUngueltigerKennungFallenWeg() throws {
        let search = try XCTUnwrap(try S.fixture("dex_pair_id")["search"] as? [String: Any])
        let pools = try DexScreener.parseSearch(try XCTUnwrap(search["body"] as? String))
        XCTAssertEqual(pools.map(\.pairId), search["expectedPairIds"] as? [String])
    }

    func testSicherungUebernimmtNurGueltigeKennung() throws {
        for c in try cases() {
            let id = try XCTUnwrap(c["pairId"] as? String)
            let kept = BackupRestoreLimits.acceptsPair(marketKey: DexScreener.key, marketName: "DexScreener",
                                                       base: "X", quote: "USD (ethereum)", pairId: id)
            XCTAssertEqual(kept, try XCTUnwrap(c["backupKept"] as? Bool), id)
        }
        XCTAssertFalse(BackupRestoreLimits.acceptsPair(marketKey: DexScreener.key, marketName: "DexScreener",
                                                       base: "X", quote: "USD (ethereum)", pairId: nil))
    }
}
