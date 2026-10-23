import XCTest
@testable import CryptoChecker

/// Wie `AddressAlarmStateParityTest.kt` (`Parity/address_balance.json`): nie geladene Adressen sperren die
/// Portfolio-Alarme nur noch 24 Stunden (`AddressAlarmState`), nicht bestätigte Token-Guthaben (`WatchedAddress.staleTokens`)
/// aus einer vollen TON-Jetton-Seite (`ChainBalance.parseTonJettonPage`).
final class AddressAlarmStateTests: XCTestCase {

    private typealias S = TestSupport

    private func data() throws -> [String: Any] { try S.fixture("address_balance") }

    private func int64(_ value: Any?) -> Int64 { (value as? NSNumber)?.int64Value ?? 0 }

    private func watched(_ o: [String: Any]) -> WatchedAddress {
        WatchedAddress(chain: AddressChain(rawValue: o["chain"] as? String ?? "") ?? .BTC,
                       address: o["address"] as? String ?? "", label: o["label"] as? String,
                       lastBalance: o["lastBalance"] as? String, lastError: o["lastError"] as? String,
                       addedAt: int64(o["addedAt"]), tokenBalances: o["tokenBalances"] as? [String: String] ?? [:],
                       countInPortfolio: o["countInPortfolio"] as? Bool ?? true,
                       staleTokens: o["staleTokens"] as? [String] ?? [], pendingSince: int64(o["pendingSince"]))
    }

    func testAlarmState() throws {
        let block = try XCTUnwrap(try data()["addressAlarmState"] as? [String: Any])
        let state = AddressAlarmState.of(S.list(block["addresses"]).map(watched), now: int64(block["now"]))
        let e = try XCTUnwrap(block["expected"] as? [String: Any])
        XCTAssertEqual(state.pending, Int(int64(e["pending"])))
        XCTAssertEqual(state.failing, e["failing"] as? [String])
        XCTAssertEqual(state.staleTokenAddresses, e["staleTokenAddresses"] as? [String])
        XCTAssertEqual(state.staleTokenAmounts.mapValues { PortfolioDecimal.text($0) }, e["staleTokenAmounts"] as? [String: String])
        let prices = (block["prices"] as? [String: Any] ?? [:]).compactMapValues { S.number($0) }
        XCTAssertEqual(state.staleTokenValue(prices: prices), S.number(e["staleTokenValue"]) ?? .nan, accuracy: 1e-9)
        XCTAssertEqual(AddressAlarmState().staleTokenValue(prices: prices), 0)
    }

    /// Eingespielt (Sicherung) mit altem `addedAt`: `pendingSince` = Zeitpunkt des Einspielens, damit die Adresse wieder
    /// 24 h zum Laden hat; gerätelokal — nicht in der Sicherung, aber in der Datei. Wie `pendingSinceLocal` (Android).
    func testPendingSinceLokal() throws {
        let now: Int64 = 1_700_000_000_000
        let entry = WatchedAddressBackupEntry(chain: .BTC, address: "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh", addedAt: 0)
        let restored = try XCTUnwrap(WatchedAddressRules.merge([], [entry], now: now).first)
        XCTAssertEqual(restored.pendingSince, now)
        XCTAssertEqual(restored.addedAt, 0)
        XCTAssertEqual(WatchedAddressRules.backupEntries([restored]), [entry])
        let backup = String(decoding: try JSONEncoder().encode(WatchedAddressRules.backupEntries([restored])), as: UTF8.self)
        XCTAssertFalse(backup.contains("pendingSince"))
        // Erster Fehler kurz nach dem Einspielen: lädt noch, nach über 24 h fehlgeschlagen
        var failed = restored
        failed.lastError = "timeout"
        XCTAssertTrue(AddressAlarmState.isPending(failed, now: now + 3_600_000))
        XCTAssertFalse(AddressAlarmState.isPending(failed, now: now + AddressAlarmState.pendingMillis))
        var old = failed
        old.pendingSince = 0
        XCTAssertFalse(AddressAlarmState.isPending(old, now: now))
        // Datei hin und zurück; ohne Zeitpunkt schreibt die Datei das Feld nicht
        let decoded = try JSONDecoder().decode(WatchedAddress.self, from: try JSONEncoder().encode(restored))
        XCTAssertEqual(decoded.pendingSince, now)
        let plain = String(decoding: try JSONEncoder().encode(old), as: UTF8.self)
        XCTAssertFalse(plain.contains("pendingSince"))
    }

    func testKeptTokens() throws {
        let block = try XCTUnwrap(try data()["keptTokens"] as? [String: Any])
        let start = watched(try XCTUnwrap(block["start"] as? [String: Any]))
        var a = start
        for (i, s) in S.list(block["steps"]).enumerated() {
            a = WatchedAddressRules.applyFetched(a, time: int64(s["time"]), balance: s["balance"] as? String ?? "",
                                                 provider: nil, tokens: s["tokens"] as? [String: String],
                                                 keptTokens: s["kept"] as? [String])
            XCTAssertEqual(a.tokenBalances, s["tokenBalances"] as? [String: String], "tokenBalances \(i)")
            XCTAssertEqual(a.staleTokens, s["staleTokens"] as? [String], "staleTokens \(i)")
            // Datei hin und zurück
            let decoded = try JSONDecoder().decode(WatchedAddress.self, from: try JSONEncoder().encode(a))
            XCTAssertEqual(decoded.staleTokens, a.staleTokens, "Datei \(i)")
        }
        // «Tokens mitzählen» aus: nicht bestätigte Jettons fallen mit ihrem Guthaben weg
        var stale = start
        stale.staleTokens = ["USDT"]
        XCTAssertTrue(WatchedAddressRules.withCountTokens(stale, false).staleTokens.isEmpty)
    }

    func testTonJettonPageKept() throws {
        let chain = try XCTUnwrap(try data()["chainBalance"] as? [String: Any])
        let ton = try XCTUnwrap(chain["ton"] as? [String: Any])
        for c in S.list(ton["jettons"]) where c["error"] as? Bool != true {
            let page = try ChainBalance.parseTonJettonPage(c["body"] as? String ?? "")
            let name = c["name"] as? String ?? ""
            XCTAssertEqual(page.tokens, c["tokens"] as? [String: String], name)
            XCTAssertEqual(page.kept, c["kept"] as? [String], name)
        }
    }
}
