import CryptoKit
import XCTest
@testable import CryptoChecker

/// Build 79 — wie die Android-Tests (`PortfolioLockPolicyTest`, `AlarmCardStatusTest`, `PriceKindLabelTest`,
/// `FirstAlarmCardTest`, `StatusPillToneTest`): Sicherung mit beobachteten Adressen gilt als Portfolio-Daten,
/// beobachtete Adressen nie im Klartext, kein Alarm auf einen Mark-Ersatzkurs, Karte «Ersten Alarm setzen» nach der
/// gespeicherten Reihenfolge, «veraltet» in Bernstein.
final class ReviewFixesB79Tests: XCTestCase {

    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("b79-\(UUID().uuidString)")
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

    // MARK: Sicherung

    func testBackupCountsWatchedAddressesAsPortfolioData() {
        // Nur Adressen (auch xpub) ohne Trades: Bestände stehen trotzdem in der Datei
        XCTAssertTrue(PortfolioLockPolicy.backupHasPortfolioData(transactions: 0, watchedAddresses: 1))
        XCTAssertTrue(PortfolioLockPolicy.backupHasPortfolioData(transactions: 3, watchedAddresses: 0))
        XCTAssertTrue(PortfolioLockPolicy.backupHasPortfolioData(transactions: 3, watchedAddresses: 2))
        XCTAssertFalse(PortfolioLockPolicy.backupHasPortfolioData(transactions: 0, watchedAddresses: 0))
    }

    // MARK: Beobachtete Adressen nie im Klartext

    func testWatchedAddressesAreNeverWrittenInPlaintext() throws {
        let url = dir.appendingPathComponent("watched_addresses.json")
        let address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        let file = WatchedAddressFile(addresses: [WatchedAddress(id: 1, chain: .BTC, address: address)], nextId: 2)

        // Ohne Schlüssel: nichts geschrieben, der Aufrufer behält den Stand
        XCTAssertEqual(WatchedAddressStore.save(file, includeInBackup: false, to: url, keys: keychain(nil)), .needsKey)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))

        // Mit Schlüssel versiegelt: die Adresse steht nicht lesbar in der Datei
        let key = SymmetricKey(data: Data(repeating: 7, count: 32))
        XCTAssertEqual(WatchedAddressStore.save(file, includeInBackup: false, to: url, keys: keychain(key)), .saved)
        let raw = try Data(contentsOf: url)
        XCTAssertTrue(ProtectedContainer.isSealed(raw))
        XCTAssertNil(raw.range(of: Data(address.utf8)))
        let back = try JSONDecoder().decode(WatchedAddressFile.self, from: ProtectedFile.read(url, keys: keychain(key)))
        XCTAssertEqual(back.addresses.map(\.address), [address])

        // Schlüssel später weg: die versiegelte Datei bleibt unverändert, kein Klartext daneben
        XCTAssertEqual(WatchedAddressStore.save(file, includeInBackup: false, to: url, keys: keychain(nil)), .needsKey)
        XCTAssertEqual(try Data(contentsOf: url), raw)
    }

    // MARK: Mark-Ersatzkurs

    func testMarkPriceFallbackHoldsAlarmsAndWaits() {
        let now: Int64 = 1_700_000_000_000
        let minute: Int64 = 60_000
        XCTAssertTrue(AlarmFreshness.blocksAlarms(nil, markFallback: true, now: now))
        XCTAssertFalse(AlarmFreshness.blocksAlarms(now - minute, markFallback: false, now: now))
        XCTAssertTrue(AlarmFreshness.blocksAlarms(now - 3 * 60 * minute, markFallback: false, now: now))

        func status(dex: Bool = false, mark: Bool) -> AlarmCardStatus.Kind? {
            AlarmCardStatus.of(alarmEnabled: true, notificationsAllowed: true, dexBlocksAlarms: dex, exchangeTime: nil,
                               lastUpdate: now - minute, now: now, staleAfterMillis: 45 * minute, unchecked: true,
                               markFallback: mark)
        }
        XCTAssertEqual(status(mark: true), .waitingForPrice)
        XCTAssertEqual(status(dex: true, mark: true), .lowLiquidity)
        XCTAssertEqual(status(mark: false), .unchecked)
    }

    func testPriceKindLabelPrefersMarkFallback() {
        XCTAssertEqual(PriceKindLabel.of(priceIsMid: true, markFallback: true, hourlyClose: false), .mark)
        XCTAssertEqual(PriceKindLabel.of(priceIsMid: true, markFallback: false, hourlyClose: false), .mid)
        XCTAssertEqual(PriceKindLabel.of(priceIsMid: false, markFallback: false, hourlyClose: true), .hourly)
        XCTAssertNil(PriceKindLabel.of(priceIsMid: false, markFallback: false, hourlyClose: false))
        XCTAssertFalse(Woo().priceIsHourlyClose) // seit Build 91 letzter Abschluss statt Stundenkurs
        XCTAssertFalse(WooFutures().priceIsHourlyClose)
    }

    // MARK: Karte «Ersten Alarm setzen»

    func testFirstAlarmTargetIsFirstEnabledPairInStoredOrder() {
        typealias Pair = (name: String, disabled: Bool)
        let stored: [Pair] = [("ETH/USDT", true), ("SOL/USDT", false), ("BTC/USDT", false)]
        XCTAssertEqual(FirstAlarmCard.target(stored: stored, disabled: { $0.disabled })?.name, "SOL/USDT")
        let allOff: [Pair] = [("ETH/USDT", true), ("BTC/USDT", true)]
        XCTAssertEqual(FirstAlarmCard.target(stored: allOff, disabled: { $0.disabled })?.name, "ETH/USDT")
        XCTAssertNil(FirstAlarmCard.target(stored: [Pair](), disabled: { $0.disabled }))
    }

    // MARK: Farbe der Statuszeile

    func testStalePillUsesWarningNotError() {
        XCTAssertEqual(StatusPillTone.of(none: false, deviceOffline: false, offline: false, stale: 2, failed: 1), .warning)
        XCTAssertEqual(StatusPillTone.of(none: false, deviceOffline: false, offline: false, stale: 0, failed: 1), .error)
        XCTAssertEqual(StatusPillTone.of(none: false, deviceOffline: false, offline: true, stale: 3, failed: 3), .error)
        XCTAssertEqual(StatusPillTone.of(none: false, deviceOffline: true, offline: true, stale: 3, failed: 3), .neutral)
        XCTAssertEqual(StatusPillTone.of(none: true, deviceOffline: false, offline: false, stale: 0, failed: 0), .neutral)
        XCTAssertEqual(StatusPillTone.of(none: false, deviceOffline: false, offline: false, stale: 0, failed: 0), .normal)
    }
}
