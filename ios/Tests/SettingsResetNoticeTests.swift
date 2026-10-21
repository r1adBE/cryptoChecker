import XCTest
@testable import CryptoChecker

/// Unlesbare Einstellungen (seit Build 97): Entscheid `SharedStorage.settingsCorrupt` und Reihenfolge der Hinweise
/// `StorageRecoveryNotice.next` — gleiche Fälle wie Android `SettingsResetNoticeTest`.
final class SettingsResetNoticeTests: XCTestCase {

    private func next(restoreInterrupted: String? = nil, keyRestored: Bool = false, settingsReset: Bool = false,
                      setAside: Bool = false, setAsideHidden: Bool = false) -> StorageRecoveryNotice.Kind? {
        StorageRecoveryNotice.next(restoreInterrupted: restoreInterrupted, keyRestored: keyRestored,
                                   settingsReset: settingsReset, setAside: setAside, setAsideHidden: setAsideHidden)
    }

    func testCorruptNurBeiGespeichertemAberUnlesbaremStand() {
        XCTAssertTrue(SharedStorage.settingsCorrupt(hasData: true, decodes: false))
        // Gelesen: nichts zu melden
        XCTAssertFalse(SharedStorage.settingsCorrupt(hasData: true, decodes: true))
        // Kein Stand: neue Installation oder gesperrte Ablage — kein Hinweis
        XCTAssertFalse(SharedStorage.settingsCorrupt(hasData: false, decodes: false))
    }

    /// Seit Build 102: unlesbar → Portfolio-Sperre unbekannt → an (wie Android `SettingsLockFallbackTest`).
    func testUnlesbareEinstellungenSperrenDasPortfolio() {
        XCTAssertTrue(SharedStorage.settingsAfterRead(nil, corrupt: true).appLock)
        // Neue Installation bzw. gesperrte Ablage: Standard (aus); gesperrte Ablage sperrt über `state`
        XCTAssertFalse(SharedStorage.settingsAfterRead(nil, corrupt: false).appLock)
        var stored = AppSettings()
        stored.appLock = false
        XCTAssertFalse(SharedStorage.settingsAfterRead(stored, corrupt: false).appLock)
        let loaded = SharedStorage.LoadedSettings(settings: AppSettings(), state: .missing, corrupt: true)
        XCTAssertTrue(loaded.appLockAssumed)
        XCTAssertFalse(SharedStorage.LoadedSettings(settings: AppSettings(), state: .missing).appLockAssumed)
        XCTAssertTrue(SharedStorage.LoadedSettings(settings: AppSettings(), state: .inaccessible).appLockAssumed)
    }

    func testNichtsVorgemerktKeinHinweis() {
        XCTAssertNil(next())
    }

    func testEinstellungenZurueckgesetztVorNichtEntsperrt() {
        XCTAssertEqual(next(settingsReset: true), .settingsReset)
        XCTAssertEqual(next(settingsReset: true, setAside: true), .settingsReset)
        XCTAssertEqual(next(settingsReset: true, setAside: true, setAsideHidden: true), .settingsReset)
    }

    func testAbgebrochenesWiederherstellenUndZurueckgeholteDatenZuerst() {
        XCTAssertEqual(next(restoreInterrupted: "rolled_back", settingsReset: true), .restoreRolledBack)
        XCTAssertEqual(next(restoreInterrupted: "incomplete", settingsReset: true), .restoreIncomplete)
        XCTAssertEqual(next(keyRestored: true, settingsReset: true), .keyRestored)
    }

    func testNichtEntsperrtNurOhneWegtippen() {
        XCTAssertEqual(next(setAside: true), .setAside)
        XCTAssertNil(next(setAside: true, setAsideHidden: true))
    }

    @MainActor
    func testVormerkenUndSchliessen() {
        let suite = "SettingsResetNoticeTests"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertNil(StorageRecoveryNotice.pending(defaults: defaults))
        StorageRecoveryNotice.recordSettingsReset(defaults: defaults)
        // Bleibt bis zum Schliessen, auch bei erneutem Prüfen
        XCTAssertEqual(StorageRecoveryNotice.pending(defaults: defaults), .settingsReset)
        XCTAssertEqual(StorageRecoveryNotice.pending(defaults: defaults), .settingsReset)
        StorageRecoveryNotice.acknowledge(.settingsReset, defaults: defaults)
        XCTAssertNil(StorageRecoveryNotice.pending(defaults: defaults))
    }
}
