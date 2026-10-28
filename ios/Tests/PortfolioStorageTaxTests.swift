import CryptoKit
import XCTest
import Security
@testable import CryptoChecker

/// Portfolio-Speicher und Steuer-Export — wie `PortfolioStorageTaxTest.kt` und `DatabaseKeyRecoveryTest.kt`:
/// Devisenkurs je Tag, Zeitzone je Vorlage, AT-Freigrenze Altbestand, Gebühr-Zeile und Zins als eigene Merker, exakter
/// Zufluss, Vorschlag «Umbuchung», Erkennung der Kopfzeile, Prüfung der Sicherung, Sperre mit monotoner Uhr, Portfolio
/// nie im Klartext, Journal des Wiederherstellens und zurückgeholte beiseitegelegte Dateien.
final class PortfolioStorageTaxTests: XCTestCase {

    private let day: Int64 = 86_400_000

    private func dec(_ s: String) -> Decimal { Decimal(string: s, locale: Locale(identifier: "en_US_POSIX"))! }

    // MARK: Devisenkurs

    func testFxOnOrBeforeFindsLastPositiveRateWithinGap() {
        let d = LocalDay(2025, 3, 10).epochDay
        let rates: [Int: Decimal] = [d - 3: dec("0.9"), d - 1: 0, d - 10: dec("0.8")]
        XCTAssertEqual(TaxExport.fxOnOrBefore(rates, day: d), dec("0.9"))
        XCTAssertNil(TaxExport.fxOnOrBefore(rates, day: d + 6))
        XCTAssertEqual(TaxExport.fxOnOrBefore(rates, day: d + 4), dec("0.9"))
        XCTAssertNil(TaxExport.fxOnOrBefore(nil, day: d))
    }

    // MARK: Zeitzone je Vorlage

    func testTemplateZonesAreTheCountryNeutralIsDevice() throws {
        let expected: [TaxTemplate: String] = [
            .DE: "Europe/Berlin", .AT: "Europe/Vienna", .CH: "Europe/Zurich", .UK: "Europe/London", .US: "America/New_York",
            .CA: "America/Toronto", .AU: "Australia/Sydney", .FR: "Europe/Paris", .JP: "Asia/Tokyo",
        ]
        let device = try XCTUnwrap(TimeZone(identifier: "Pacific/Auckland"))
        for t in TaxTemplate.allCases {
            XCTAssertEqual(t.zone(device: device).identifier, expected[t] ?? device.identifier, t.rawValue)
        }
        XCTAssertNil(TaxTemplate.NEUTRAL.zoneId)
    }

    // MARK: AT Freigrenze Altbestand ≤ 1 Jahr

    private func atStatus(sellPrice: String, currency: String = "USD") throws -> TaxSummaryLine {
        let trades = [
            PortfolioTx(id: 1, coin: "BTC", type: .BUY, amountExact: 1, priceExact: 10000, time: 1_604_318_400_000),
            PortfolioTx(id: 2, coin: "BTC", type: .SELL, amountExact: dec("0.01"), priceExact: dec(sellPrice), time: 1_622_548_800_000),
        ]
        let utc = try XCTUnwrap(TimeZone(identifier: "UTC"))
        let s = TaxExport.yearSummary(trades, template: .AT, method: .AT_MOVING_AVERAGE, startYear: 2021, timeZone: utc,
                                      currency: currency, rates: nil)
        return try XCTUnwrap(s.lines.first { $0.line == .AT_OLD_SPECULATION_LIMIT })
    }

    func testAtOldSpeculationLimitStatus() throws {
        XCTAssertEqual(try atStatus(sellPrice: "54000").status, .below) // genau 440: höchstens → unter der Freigrenze
        XCTAssertEqual(try atStatus(sellPrice: "54001").status, .reached)
        XCTAssertEqual(try atStatus(sellPrice: "5000").status, .loss)
        XCTAssertEqual(try atStatus(sellPrice: "5000").target, 440)
        XCTAssertEqual(try atStatus(sellPrice: "40000", currency: "EUR").status, .unknown)
        XCTAssertTrue(TaxLine.AT_OLD_SPECULATION_LIMIT.orientation)
    }

    // MARK: Gebühr-Zeile und Zins als eigene Merker

    func testFileAndBackupCarryFlags() throws {
        var fee = PortfolioTx(id: 3, coin: "BTC", type: .SELL, amountExact: dec("0.0001"), priceExact: 0, time: 10, note: "Fee")
        fee.importKey = "KRAKEN|F1"
        fee.feeRow = true
        // Datei (Codable)
        let encoded = try JSONEncoder().encode(fee)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        XCTAssertEqual(object["feeRow"] as? Bool, true)
        XCTAssertEqual(object["interest"] as? Bool, false)
        XCTAssertTrue(try JSONDecoder().decode(PortfolioTx.self, from: encoded).feeRow)
        var missing = object
        missing.removeValue(forKey: "feeRow")
        missing.removeValue(forKey: "interest")
        let missingData = try JSONSerialization.data(withJSONObject: missing)
        XCTAssertFalse(try JSONDecoder().decode(PortfolioTx.self, from: missingData).feeRow, "fehlendes Feld = nein")
        var explicit = object
        explicit["feeRow"] = false
        XCTAssertFalse(try JSONDecoder().decode(PortfolioTx.self, from: JSONSerialization.data(withJSONObject: explicit)).feeRow)
        // Sicherung
        let json = BackupManager.txToJson(fee)
        XCTAssertEqual(json["feeRow"] as? Bool, true)
        XCTAssertEqual(try BackupManager.jsonToTx(json)?.feeRow, true)
        var reward = BackupManager.txToJson(PortfolioTx(id: 4, coin: "ETH", type: .REWARD, amountExact: Decimal(string: "0.1")!,
                                                        time: 5, note: "Lending interest"))
        XCTAssertEqual(try BackupManager.jsonToTx(reward)?.interest, false)
        reward["interest"] = true
        XCTAssertEqual(try BackupManager.jsonToTx(reward)?.interest, true)
        var buy = json
        buy["type"] = "BUY"
        XCTAssertEqual(try BackupManager.jsonToTx(buy)?.feeRow, false)
    }

    func testFeeRowOnlyByFlagAndInterestOnlyByFlag() {
        var sell = PortfolioTx(id: 1, coin: "BTC", type: .SELL, amountExact: 1, priceExact: nil, time: 1)
        sell.feeRow = true
        XCTAssertTrue(PortfolioLedger.isFeeDisposal(sell))
        var reward = PortfolioTx(id: 2, coin: "ETH", type: .REWARD, amountExact: 1, priceExact: 100, time: 1, note: "interest")
        XCTAssertFalse(reward.interest)
        reward.interest = true
        XCTAssertTrue(reward.interest)
    }

    func testCsvImportSetsFeeRowAndInterest() {
        func priced(_ kind: CsvImportKind, _ raw: String) -> CsvImportPriced {
            CsvImportPriced(row: CsvImportRow(line: 1, kind: kind, rawKind: raw, coin: "BTC", amount: dec("0.01"), price: nil,
                                              quote: "USD", fee: nil, feeUnit: nil, timeMillis: 1_700_000_000_000,
                                              externalId: nil, key: "X|\(raw)"),
                            priceUsdt: 30000, feeUsdt: nil, needsFx: false, missingPrice: false)
        }
        let fee = CsvImportMapping.toTx(priced(.FEE, "Fee"))
        XCTAssertEqual(fee.type, .SELL)
        XCTAssertTrue(fee.feeRow)
        XCTAssertFalse(fee.interest)
        XCTAssertTrue(CsvImportMapping.toTx(priced(.REWARD, "Lending interest")).interest)
        XCTAssertFalse(CsvImportMapping.toTx(priced(.REWARD, "Staking")).interest)
        XCTAssertTrue(PortfolioCsvImporter.tx(fee).feeRow)
    }

    // MARK: Exakter Zufluss

    func testFlowIsExactUntilTheResult() {
        let buy = PortfolioTx(id: 1, coin: "X", type: .BUY, amountExact: 3, priceExact: dec("0.1"), time: 1, feeExact: dec("0.2"),
                              feeUnit: .QUOTE)
        XCTAssertEqual(PortfolioLedger.flowExact(PortfolioLedgerEvent(trade: buy, feeLeg: false), holdingsBefore: 0), dec("0.5"))
        XCTAssertEqual(PortfolioLedger.flow(PortfolioLedgerEvent(trade: buy, feeLeg: false), holdingsBefore: 0), 0.5)
        let reward = PortfolioTx(id: 2, coin: "X", type: .REWARD, amountExact: 3, priceExact: nil, time: 1)
        XCTAssertEqual(PortfolioLedger.flow(PortfolioLedgerEvent(trade: reward, feeLeg: false), holdingsBefore: 0, marketPrice: 0.1), 0.3)
    }

    // MARK: Vorschlag «Umbuchung»

    private func withdrawal(_ key: String, _ amount: String, _ time: Int64) -> CsvImportTx {
        CsvImportTx(line: 1, coin: "BTC", type: .WITHDRAWAL, amount: dec(amount), priceUsdt: nil, time: time, fee: nil,
                    feeUnit: nil, feeCoin: nil, feeCoinAmount: nil, note: nil, importKey: key)
    }

    private func address(_ observations: [(Int64, String)]) -> WatchedAddress {
        WatchedAddress(id: 1, chain: .BTC, address: "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", label: "Ledger",
                       observations: observations.map { AddressObservation(time: $0.0, balance: dec($0.1)) },
                       countInPortfolio: true)
    }

    func testProposalNeedsObservationAfterWithdrawalWithinSevenDays() {
        let t: Int64 = 1_718_870_400_000
        let w = [withdrawal("A", "0.005", t)]
        XCTAssertNil(CsvImportMapping.proposeWithdrawals(w, addresses: [address([(t - 1, "0.005")])]).first?.choice)
        XCTAssertEqual(CsvImportMapping.proposeWithdrawals(w, addresses: [address([(t + CsvImportMapping.matchWindowMillis, "0.005")])])
            .first?.choice, .TRANSFER)
        XCTAssertNil(CsvImportMapping.proposeWithdrawals(w, addresses: [address([(t + CsvImportMapping.matchWindowMillis + 1, "0.005")])])
            .first?.choice)
    }

    func testEachObservedChangeMatchesOnlyOneWithdrawal() {
        let t: Int64 = 1_718_870_400_000
        let two = [withdrawal("A", "0.005", t), withdrawal("B", "0.005", t + 1000)]
        XCTAssertEqual(CsvImportMapping.proposeWithdrawals(two, addresses: [address([(t + day, "0.005")])]).map(\.choice),
                       [.TRANSFER, nil])
        XCTAssertEqual(CsvImportMapping.proposeWithdrawals(two, addresses: [address([(t + day, "0.005"), (t + 2 * day, "0.010")])])
            .map(\.choice), [.TRANSFER, .TRANSFER])
    }

    func testDustToleranceForTinyAmounts() {
        let t: Int64 = 1_718_870_400_000
        let w = [withdrawal("A", "0.00000100", t)]
        XCTAssertEqual(CsvImportMapping.proposeWithdrawals(w, addresses: [address([(t + 1, "0.00000101")])]).first?.choice, .TRANSFER)
        XCTAssertNil(CsvImportMapping.proposeWithdrawals(w, addresses: [address([(t + 1, "0.00000103")])]).first?.choice)
    }

    // MARK: Erkennung der Kopfzeile

    func testTokenizeStopsAfterMaxRecords() {
        let text = Array((1...50).map { "a\($0);b\($0)" }.joined(separator: "\n").unicodeScalars)
        XCTAssertEqual(CsvImportParser.tokenize(text, delimiter: ";", maxRecords: 10).count, 10)
        XCTAssertEqual(CsvImportParser.tokenize(text, delimiter: ";").count, 50)
        XCTAssertEqual(CsvImportParser.tokenize(text, delimiter: ";", maxRecords: 10).last?.cells.first, "a10")
    }

    // MARK: Sicherung prüfen, Sperre

    func testBackupVerificationComparesSizeAndContent() {
        let written = Data(#"{"format":"cryptochecker-backup"}"#.utf8)
        XCTAssertNil(BackupManager.verify(written: written, readBack: written))
        XCTAssertNotNil(BackupManager.verify(written: written, readBack: written.dropLast()))
        var changed = written
        changed[3] = UInt8(ascii: "X")
        XCTAssertNotNil(BackupManager.verify(written: written, readBack: changed))
    }

    func testRelockWithMonotonicClock() {
        let limit = PortfolioLockPolicy.backgroundLimitSeconds
        XCTAssertFalse(PortfolioLockPolicy.relockAfterBackground(backgroundSince: 10, now: 10 + limit))
        XCTAssertTrue(PortfolioLockPolicy.relockAfterBackground(backgroundSince: 10, now: 10 + limit + 0.001))
        XCTAssertTrue(PortfolioLockPolicy.relockAfterBackground(backgroundSince: 10, now: 9))
        XCTAssertFalse(PortfolioLockPolicy.relockAfterBackground(backgroundSince: nil, now: 1))
        XCTAssertGreaterThan(PortfolioLockPolicy.monotonicSeconds(), 0)
    }

    // MARK: Portfolio nie im Klartext, Journal, zurückgeholte Dateien

    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("portfolio-storage-tax-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func keychain(_ key: SymmetricKey?) -> StorageKey {
        let fake = ProtectedFileTests.FakeKeychain()
        fake.stored = key?.withUnsafeBytes { Data($0) }
        return StorageKey(keychain: fake)
    }

    func testRequireSealedNeverWritesPlaintext() throws {
        let url = dir.appendingPathComponent("portfolio.json")
        let data = Data(#"{"transactions":[{"coin":"BTC"}]}"#.utf8)
        XCTAssertThrowsError(try ProtectedFile.write(data, to: url, keys: keychain(nil), requireSealed: true)) { error in
            guard case ProtectedFile.ReadError.locked = error else { return XCTFail("erwartet .locked, nicht \(error)") }
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        // Ohne die Vorgabe (nur Kopien unlesbarer Dateien, `writeCopy`): Klartext
        try ProtectedFile.write(data, to: url, keys: keychain(nil))
        XCTAssertEqual(try Data(contentsOf: url), data)
        // Mit Schlüssel versiegelt
        let key = SymmetricKey(data: Data(repeating: 3, count: 32))
        try ProtectedFile.write(data, to: url, keys: keychain(key), requireSealed: true)
        XCTAssertTrue(ProtectedContainer.isSealed(try Data(contentsOf: url)))
    }

    func testRestoreJournalIsSealedAndReadsBack() throws {
        let url = dir.appendingPathComponent("journal.sealed")
        let key = SymmetricKey(data: Data(repeating: 5, count: 32))
        let state = Data(#"{"format":"cryptochecker-backup","portfolio":[]}"#.utf8)
        // Schlüsselbund gesperrt: kein Journal
        let locked = ProtectedFileTests.FakeKeychain()
        locked.readStatus = errSecInteractionNotAllowed
        XCTAssertFalse(RestoreJournal.write(state, keys: StorageKey(keychain: locked), to: url), "gesperrt: kein Journal")
        // Frische Installation (noch kein Schlüssel): Schlüssel wird angelegt, Journal geschrieben
        let fresh = ProtectedFileTests.FakeKeychain()
        let freshURL = dir.appendingPathComponent("journal-fresh.sealed")
        XCTAssertTrue(RestoreJournal.write(state, keys: StorageKey(keychain: fresh), to: freshURL), "frisch: Journal mit neuem Schlüssel")
        XCTAssertNotNil(fresh.stored)
        guard case .absent = RestoreJournal.read(keys: keychain(key), from: url) else { return XCTFail("kein Journal erwartet") }
        XCTAssertTrue(RestoreJournal.write(state, keys: keychain(key), to: url))
        XCTAssertNil(try Data(contentsOf: url).range(of: Data("portfolio".utf8)))
        guard case .found(let back) = RestoreJournal.read(keys: keychain(key), from: url) else { return XCTFail("Journal erwartet") }
        XCTAssertEqual(back, state)
        guard case .unreadable = RestoreJournal.read(keys: keychain(SymmetricKey(size: .bits256)), from: url) else {
            return XCTFail("mit anderem Schlüssel unlesbar")
        }
        RestoreJournal.delete(at: url)
        guard case .absent = RestoreJournal.read(keys: keychain(key), from: url) else { return XCTFail("gelöscht") }
    }

    func testLockedFileThatOpensWithTheKeyComesBack() throws {
        let key = SymmetricKey(data: Data(repeating: 9, count: 32))
        let url = dir.appendingPathComponent("portfolio.json")
        let old = Data(#"{"transactions":[{"coin":"ETH"}]}"#.utf8)
        try ProtectedContainer.seal(old, key: key).write(to: url.appendingPathExtension("locked"))
        let fresh = Data(#"{"transactions":[]}"#.utf8)
        try ProtectedContainer.seal(fresh, key: key).write(to: url)
        let outcome = ProtectedStorage.prepareAtLaunch(files: [(url, url, true)], keys: keychain(key))
        XCTAssertEqual(outcome.restored, ["portfolio.json"])
        XCTAssertEqual(try ProtectedFile.read(url, keys: keychain(key)), old)
        // Die inzwischen neu begonnene Datei bleibt daneben
        XCTAssertEqual(try ProtectedFile.read(url.appendingPathExtension("replaced"), keys: keychain(key)), fresh)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.appendingPathExtension("locked").path))
        // Mit fremdem Schlüssel bleibt eine beiseitegelegte Datei liegen
        try ProtectedContainer.seal(old, key: SymmetricKey(size: .bits256)).write(to: url.appendingPathExtension("locked"))
        XCTAssertEqual(ProtectedStorage.prepareAtLaunch(files: [(url, url, true)], keys: keychain(key)).restored, [])
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.appendingPathExtension("locked").path))
    }
}
