import XCTest
@testable import CryptoChecker

/// Build 46: Herkunftsschlüssel (CSV-Import) und beobachtete Adressen in der Sicherung —
/// wie `PortfolioBackupWatchedTest.kt`.
final class PortfolioBackupWatchedTests: XCTestCase {

    private let btc = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
    private let eth = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"

    private func imported() -> PortfolioTx {
        var t = PortfolioTx(id: 3, coin: "BTC", type: .BUY, amountExact: Decimal(string: "0.001")!,
                            priceExact: 60_000, time: 1_000)
        t.importKey = "relai:2024-01-05T10:00:00:0.001"
        return t
    }

    func testImportKeyRoundTripsInFileAndBackup() throws {
        let t = imported()
        XCTAssertEqual(try JSONDecoder().decode(PortfolioTx.self, from: JSONEncoder().encode(t)), t)
        let json = try JSONSerialization.data(withJSONObject: BackupManager.txToJson(t))
        let o = try XCTUnwrap(JSONSerialization.jsonObject(with: json) as? [String: Any])
        XCTAssertEqual(BackupManager.jsonToTx(o), t)
        // Ältere Sicherung ohne Schlüssel
        var old = o
        old.removeValue(forKey: "importKey")
        XCTAssertNil(BackupManager.jsonToTx(old)?.importKey)
        XCTAssertNil(PortfolioTx.cleanImportKey("  "))
        XCTAssertNil(PortfolioTx.cleanImportKey(String(repeating: "x", count: PortfolioTx.maxImportKey + 1)))
        XCTAssertEqual(PortfolioTx.cleanImportKey(" a "), "a")
    }

    func testWatchedAddressesFormatAndOldBackups() throws {
        let entries = [WatchedAddressBackupEntry(chain: .BTC, address: btc, label: "L", addedAt: 9)]
        let array = BackupManager.watchedToJson(entries)
        XCTAssertEqual(Set(array[0].keys), ["chain", "address", "label", "addedAt"])
        let data = try JSONSerialization.data(withJSONObject: [WatchedAddressRules.backupKey: array])
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(BackupManager.watchedEntries(root), entries)
        // Ältere Sicherung: kein Schlüssel = bestehende Adressen bleiben (nil, nicht leer)
        XCTAssertNil(BackupManager.watchedEntries(["portfolio": [] as [Any]]))
        // Leere Liste = ersetzen durch keine
        XCTAssertEqual(BackupManager.watchedEntries([WatchedAddressRules.backupKey: [] as [Any]]), [])
    }

    func testRestoreMergeValidatesAndDropsDuplicates() {
        let entries = [
            WatchedAddressBackupEntry(chain: .BTC, address: btc),
            WatchedAddressBackupEntry(chain: .BTC, address: btc.uppercased()),
            WatchedAddressBackupEntry(chain: .ETH, address: eth.lowercased(), addedAt: 6),
            WatchedAddressBackupEntry(chain: .ETH, address: btc), // falsches Netz
        ]
        let merged = WatchedAddressRules.merge([], entries)
        XCTAssertEqual(merged.map(\.address), [btc, eth])  // Ethereum mit Prüfsumme
        XCTAssertNil(merged[0].lastBalance)
    }

    func testStoreInsertRespectsLimitAndDuplicates() {
        var file = WatchedAddressFile()
        XCTAssertNotNil(WatchedAddressStore.insert(WatchedAddress(chain: .BTC, address: btc), into: &file))
        XCTAssertNil(WatchedAddressStore.insert(WatchedAddress(chain: .BTC, address: btc), into: &file))
        XCTAssertEqual(file.addresses.count, 1)
    }

    func testWatchedFileFollowsSystemBackupSwitch() {
        XCTAssertTrue(PortfolioStore.backupPolicyURLs.contains(WatchedAddressStore.url))
    }
}
