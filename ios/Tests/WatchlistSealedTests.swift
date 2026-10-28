import CryptoKit
import Security
import XCTest
@testable import CryptoChecker

/// Merkliste nur versiegelt (`SharedStorage.writeFiles`, `requireSealed`): ohne Schlüssel nie Klartext, eine
/// unverschlüsselte Datei gilt wie eine kaputte.
/// Dazu die Ablage der Alarmtöne in `Library/Sounds` der App Group (`AlarmSounds.installSounds`) und das Abwarten der
/// Schreib-Warteschlange ohne Blockieren (`SharedStorage.snapshotWritesSettled`).
/// Läuft in einem temporären Ordner mit gefälschtem Schlüsselbund, nie auf der App Group.
final class WatchlistSealedTests: XCTestCase {

    private var dir: URL!
    private var watchlist: URL { dir.appendingPathComponent("watchlist.json") }
    private var portfolioAlarms: URL { dir.appendingPathComponent("portfolio_alarms.json") }
    private let fixedKey = SymmetricKey(data: Data(repeating: 9, count: 32))
    private let json = Data("""
    {"watches":[{"id":1,"exchangeKey":"binance","baseAsset":"BTC","quoteAsset":"USDT","note":"Ø 77 500","exchangeName":"binance","contractType":0,"sortOrder":0,"notificationEnabled":true,"ttsEnabled":false,"lastUpdate":0,"notifiedAt":0,"favorite":false,"disabled":false,"priceChangedAt":0}],
     "alarms":[],"nextWatchId":2,"nextAlarmId":1}
    """.utf8)

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("watchlist-sealed-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
        // Prozessweite Merker der gastgebenden App nicht verfälscht zurücklassen
        SharedStorage.watchlistPending = false
        SharedStorage.portfolioAlarmsPending = false
    }

    private func keys(_ key: SymmetricKey?) -> StorageKey {
        let fake = ProtectedFileTests.FakeKeychain()
        fake.stored = key?.withUnsafeBytes { Data($0) }
        return StorageKey(keychain: fake)
    }

    /// Schlüsselbund gerade nicht lesbar (z. B. Fehler beim Zugriff).
    private func lockedKeys() -> StorageKey {
        let fake = ProtectedFileTests.FakeKeychain()
        fake.readStatus = errSecInteractionNotAllowed
        return StorageKey(keychain: fake)
    }

    private func read(_ keys: StorageKey) throws -> SharedStorage.Snapshot {
        try XCTUnwrap(SharedStorage.readFiles(watchlist: watchlist, portfolioAlarms: portfolioAlarms, keys: keys).snapshot)
    }

    // MARK: Merkliste ohne Schlüssel

    /// Unverschlüsselte Datei (schreibt die App nie): mit und ohne Schlüssel «unlesbar» wie eine kaputte; ohne Schlüssel
    /// bleibt sie Byte für Byte (kein Klartext), mit Schlüssel ersetzt sie das nächste Schreiben versiegelt.
    func testPlainFileIsUnreadableAndReplacedSealed() throws {
        try json.write(to: watchlist, options: [.atomic])
        for k in [keys(nil), keys(fixedKey)] {
            guard case .unreadable = SharedStorage.readFiles(watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                                             keys: k) else {
                return XCTFail("erwartet .unreadable")
            }
        }
        var snap = SharedStorage.Snapshot()
        snap.watches = [Watch(id: 1, exchangeKey: "binance", exchangeName: "Binance", baseAsset: "ETH", quoteAsset: "USDT")]
        XCTAssertFalse(SharedStorage.writeFiles(snap, watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                                includePortfolioInBackup: true, keys: keys(nil)))
        XCTAssertTrue(SharedStorage.watchlistPending)
        XCTAssertEqual(try Data(contentsOf: watchlist), json)

        XCTAssertTrue(SharedStorage.writeFiles(snap, watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                               includePortfolioInBackup: true, keys: keys(fixedKey)))
        XCTAssertFalse(SharedStorage.watchlistPending)
        XCTAssertTrue(ProtectedStorage.isSealedFile(watchlist))
        XCTAssertEqual(try read(keys(fixedKey)).watches.map(\.baseAsset), ["ETH"])
    }

    /// Noch keine Datei und kein Schlüssel: keine Klartext-Datei anlegen.
    func testNoKeyCreatesNoPlainFile() {
        var snap = SharedStorage.Snapshot()
        snap.watches = [Watch(id: 1, exchangeKey: "binance", exchangeName: "Binance", baseAsset: "ETH", quoteAsset: "USDT")]
        XCTAssertFalse(SharedStorage.writeFiles(snap, watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                                includePortfolioInBackup: true, keys: keys(nil)))
        XCTAssertTrue(SharedStorage.watchlistPending)
        XCTAssertFalse(FileManager.default.fileExists(atPath: watchlist.path))
    }

    /// Versiegelte Datei, Schlüsselbund gerade nicht lesbar: «nicht zugreifbar» beim Lesen, Schreiben lässt sie
    /// unverändert; danach mit Schlüssel geschrieben.
    func testLockedKeychainLeavesSealedFileUntouched() throws {
        let snap = try JSONDecoder().decode(SharedStorage.Snapshot.self, from: json)
        XCTAssertTrue(SharedStorage.writeFiles(snap, watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                               includePortfolioInBackup: true, keys: keys(fixedKey)))
        let before = try Data(contentsOf: watchlist)
        guard case .inaccessible = SharedStorage.readFiles(watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                                           keys: lockedKeys()) else {
            return XCTFail("erwartet .inaccessible")
        }
        var changed = snap
        changed.watches = []
        XCTAssertFalse(SharedStorage.writeFiles(changed, watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                                includePortfolioInBackup: true, keys: lockedKeys()))
        XCTAssertTrue(SharedStorage.watchlistPending)
        XCTAssertEqual(try Data(contentsOf: watchlist), before)
        XCTAssertEqual(try read(keys(fixedKey)).watches.map(\.id), [1])
    }

    // MARK: Schreib-Warteschlange

    /// Abwarten ohne Blockieren kehrt zurück (die Warteschlange ist seriell; danach ist alles davor Eingereihte fertig).
    func testSnapshotWritesSettledReturns() async {
        await SharedStorage.snapshotWritesSettled()
    }

    // MARK: Alarmtöne in der App Group

    func testSoundsDirectoryIsLibrarySounds() {
        let container = URL(fileURLWithPath: "/tmp/group", isDirectory: true)
        XCTAssertEqual(AlarmSounds.soundsDirectory(in: container).path, "/tmp/group/Library/Sounds")
        XCTAssertEqual(AlarmSounds.fileName(AlarmSounds.tones[0]), "cc_chime.wav")
    }

    /// Kopiert, ein zweiter Lauf ändert nichts, ein geänderter Ton wird ersetzt.
    func testInstallSoundsIsIdempotent() throws {
        let source = dir.appendingPathComponent("bundle", isDirectory: true)
        try FileManager.default.createDirectory(at: source, withIntermediateDirectories: true)
        let chime = source.appendingPathComponent("cc_chime.wav")
        let pulse = source.appendingPathComponent("cc_pulse.wav")
        try Data([1, 2, 3]).write(to: chime)
        try Data([4, 5]).write(to: pulse)
        let target = AlarmSounds.soundsDirectory(in: dir.appendingPathComponent("group", isDirectory: true))

        XCTAssertEqual(AlarmSounds.installSounds([chime, pulse], into: target), 2)
        XCTAssertEqual(try Data(contentsOf: target.appendingPathComponent("cc_chime.wav")), Data([1, 2, 3]))
        XCTAssertEqual(AlarmSounds.installSounds([chime, pulse], into: target), 0)

        try Data([9]).write(to: chime)
        XCTAssertEqual(AlarmSounds.installSounds([chime, pulse], into: target), 1)
        XCTAssertEqual(try Data(contentsOf: target.appendingPathComponent("cc_chime.wav")), Data([9]))
        // Fehlende Quelle: übersprungen, kein Abbruch
        XCTAssertEqual(AlarmSounds.installSounds([source.appendingPathComponent("fehlt.wav")], into: target), 0)
    }
}
