import XCTest
@testable import CryptoChecker

/// Nie geladene Adressen sperren Portfolio-Alarme, Abruf-Reihenfolge im Hintergrund, tolerante Datei der
/// beobachteten Adressen und der Hinweis «Portfolio-Alarme pausiert» — Fälle aus `address_balance.json` wie Android
/// (`AddressBalanceParityTest.pendingCount` / `dueOrder`, `PortfolioAlarmLogicTest`, `PortfolioPauseTextTest`).
final class WatchedAddressPendingTests: XCTestCase {

    private typealias S = TestSupport

    private func data() throws -> [String: Any] { try S.fixture("address_balance") }

    private func int64(_ value: Any?) -> Int64 { (value as? NSNumber)?.int64Value ?? 0 }

    func testPendingCount() throws {
        let p = try XCTUnwrap(try data()["pendingCount"] as? [String: Any])
        let list = S.list(p["addresses"]).map {
            WatchedAddress(chain: AddressChain(rawValue: $0["chain"] as? String ?? "") ?? .BTC,
                           address: $0["address"] as? String ?? "", lastBalance: $0["lastBalance"] as? String,
                           countInPortfolio: $0["countInPortfolio"] as? Bool ?? true)
        }
        let now = int64(p["now"])
        XCTAssertEqual(AddressBalance.pendingCount(list, now: now), Int(int64(p["expected"])))
        XCTAssertEqual(AddressBalance.pendingCount([], now: now), 0)
    }

    func testDueOrder() throws {
        let d = try XCTUnwrap(try data()["dueOrder"] as? [String: Any])
        let now = int64(d["now"])
        let list = S.list(d["addresses"]).map {
            WatchedAddress(id: int64($0["id"]), chain: .BTC, address: "x\(int64($0["id"]))", lastAttempt: int64($0["lastAttempt"]))
        }
        XCTAssertEqual(AddressBalance.dueOrder(list, now: now, force: false, background: false).map(\.id),
                       (d["foreground"] as? [Any] ?? []).map { int64($0) })
        XCTAssertEqual(AddressBalance.dueOrder(list, now: now, force: false, background: true).map(\.id),
                       (d["background"] as? [Any] ?? []).map { int64($0) })
    }

    /// Zählende Adresse ohne geladenes Guthaben: kein Portfolio-Alarm meldet oder stellt wieder scharf.
    func testPendingAddressBlocksEveryKind() {
        let addresses = [
            WatchedAddress(chain: .BTC, address: "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh", lastBalance: nil),
            WatchedAddress(chain: .ETH, address: "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", lastBalance: nil,
                           countInPortfolio: false),
        ]
        let pending = AddressBalance.pendingCount(addresses, now: 1_700_000_000_000)
        XCTAssertEqual(pending, 1)
        var reading = PortfolioReading(total: 50, currency: "CHF", totalUsdt: 55, changePercent: 12, empty: false)
        reading.addressesPending = pending
        for kind in PortfolioAlarmKind.allCases {
            let threshold: Double = kind.isValue ? 100 : 10
            let armed = PortfolioAlarm(id: 1, kind: kind, threshold: threshold, currency: "CHF")
            XCTAssertEqual(PortfolioAlarmLogic.decide(armed, reading, now: 1_700_000_000_000, cooldownMinutes: 0), .nothing)
            let fired = PortfolioAlarm(id: 1, kind: kind, threshold: threshold, currency: "CHF", referenceAt: 1)
            XCTAssertEqual(PortfolioAlarmLogic.decide(fired, reading, now: 1_700_000_000_000, cooldownMinutes: 0), .nothing)
        }
        reading.addressesPending = 0
        let below = PortfolioAlarm(id: 1, kind: .VALUE_BELOW, threshold: 100, currency: "CHF")
        XCTAssertEqual(PortfolioAlarmLogic.decide(below, reading, now: 1_700_000_000_000, cooldownMinutes: 0), .fire(50))
    }

    /// Alter Kurs unter 1 % des Werts: Alarme prüfen trotzdem; darüber nicht.
    func testImmaterialStalePrice() {
        var small = PortfolioReading(total: 50, currency: "CHF", totalUsdt: 55, changePercent: 12, empty: false)
        small.stalePrices = 1
        small.staleValueUsdt = 0.5
        XCTAssertFalse(PortfolioAlarmLogic.staleMaterial(small))
        let below = PortfolioAlarm(id: 1, kind: .VALUE_BELOW, threshold: 100, currency: "CHF")
        XCTAssertEqual(PortfolioAlarmLogic.decide(below, small, now: 1_700_000_000_000, cooldownMinutes: 0), .fire(50))
        var material = small
        material.staleValueUsdt = 0.6
        XCTAssertTrue(PortfolioAlarmLogic.staleMaterial(material))
        XCTAssertEqual(PortfolioAlarmLogic.decide(below, material, now: 1_700_000_000_000, cooldownMinutes: 0), .nothing)
        var noTotal = small
        noTotal.totalUsdt = nil
        XCTAssertTrue(PortfolioAlarmLogic.staleMaterial(noTotal))
    }

    /// Ein unlesbarer Eintrag macht die Datei nicht unlesbar (wie Android `mapNotNull`); die Id läuft nicht zurück.
    func testStoreFileSkipsUnreadableEntry() throws {
        let json = """
        {"addresses":[{"id":1,"chain":"BTC","address":"1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"},\
        {"id":2,"chain":"DOGE","address":"D7Y55"},\
        {"id":3,"chain":"ETH","address":"0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"}],"nextId":2}
        """
        let file = try JSONDecoder().decode(WatchedAddressFile.self, from: Data(json.utf8))
        XCTAssertEqual(file.addresses.map(\.id), [1, 3])
        XCTAssertEqual(file.nextId, 4)
        // Keine Liste: unlesbar (nicht überschreiben)
        XCTAssertThrowsError(try JSONDecoder().decode(WatchedAddressFile.self, from: Data(#"{"addresses":5}"#.utf8)))
    }

    func testPauseTextCoins() {
        XCTAssertEqual(PortfolioPauseText.coins(["PEPE"], hideCoins: false), "PEPE")
        XCTAssertEqual(PortfolioPauseText.coins(["A", "B", "C"], hideCoins: false), "A, B, C")
        XCTAssertEqual(PortfolioPauseText.coins(["A", "B", "C", "D"], hideCoins: false), "A, B, C …")
        // Gesperrtes Portfolio: die Zusammensetzung des Bestands bleibt verborgen
        XCTAssertNil(PortfolioPauseText.coins(["PEPE"], hideCoins: true))
        XCTAssertNil(PortfolioPauseText.coins([], hideCoins: false))
    }
}
