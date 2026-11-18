import SwiftUI
import XCTest
@testable import CryptoChecker

/// Gerät für `AppLock` im Test: Einstellung, Uhr und Entsperren vorgegeben, Sichtschutz nur festgehalten.
@MainActor
final class FakeAppLockEffects: AppLockEffects {
    var lockEnabled: Bool
    var now: TimeInterval = 1_000
    var deviceSecure = true
    var authenticationSucceeds = true
    private(set) var authenticationCount = 0
    /// Zuletzt verlangter Zustand des Sichtschutzes; nil = nie gefragt.
    private(set) var coverShown: Bool?

    init(lockEnabled: Bool) { self.lockEnabled = lockEnabled }

    func monotonicSeconds() -> TimeInterval { now }

    func canAuthenticate() -> Bool { deviceSecure }

    func authenticate() async -> Bool {
        authenticationCount += 1
        return authenticationSucceeds
    }

    func showCover(_ show: Bool) { coverShown = show }
}

/// Portfolio-Sperre als Verhalten von `AppLock` — wie Android `AppLockTest`: Kaltstart, Rückkehr aus dem Hintergrund
/// (mehr als `PortfolioLockPolicy.backgroundLimitSeconds`), Entsperren, fehlender Gerätecode, Ausschalten und Sichtschutz.
@MainActor
final class AppLockTests: XCTestCase {
    private func makeLock(enabled: Bool = true) -> (AppLock, FakeAppLockEffects) {
        let effects = FakeAppLockEffects(lockEnabled: enabled)
        return (AppLock(effects: effects), effects)
    }

    /// Entsperrt, dann `seconds` im Hintergrund und zurück nach vorne.
    private func returnAfter(_ seconds: TimeInterval, _ lock: AppLock, _ effects: FakeAppLockEffects) {
        lock.scenePhaseChanged(.background)
        effects.now += seconds
        lock.scenePhaseChanged(.active)
    }

    func testColdStartLocksOnlyWhenEnabled() {
        let (on, _) = makeLock(enabled: true)
        XCTAssertTrue(on.locked)
        XCTAssertEqual(on.access, .locked)
        let (off, _) = makeLock(enabled: false)
        XCTAssertFalse(off.locked)
        XCTAssertEqual(off.access, .open)
    }

    func testUnlockSucceedsOnce() async {
        let (lock, effects) = makeLock()
        await lock.unlock()
        XCTAssertFalse(lock.locked)
        XCTAssertFalse(lock.authenticating)
        XCTAssertEqual(effects.authenticationCount, 1)
        // Schon entsperrt: keine weitere Abfrage
        await lock.unlock()
        XCTAssertEqual(effects.authenticationCount, 1)
    }

    func testFailedAuthenticationStaysLocked() async {
        let (lock, effects) = makeLock()
        effects.authenticationSucceeds = false
        await lock.unlock()
        XCTAssertTrue(lock.locked)
        XCTAssertFalse(lock.authenticating)
        XCTAssertFalse(lock.screenLockMissing)
    }

    /// Ohne Gerätecode nie still entsperren: gesperrt lassen und es nennen; nach vorne zurück wird neu geprüft.
    func testMissingScreenLockKeepsLockedAndIsRecheckedOnReturn() async {
        let (lock, effects) = makeLock()
        effects.deviceSecure = false
        await lock.unlock()
        XCTAssertTrue(lock.locked)
        XCTAssertTrue(lock.screenLockMissing)
        XCTAssertEqual(effects.authenticationCount, 0)
        lock.scenePhaseChanged(.active)
        XCTAssertFalse(lock.screenLockMissing)
        XCTAssertTrue(lock.locked)
    }

    func testRelocksAfterMoreThanBackgroundLimit() async {
        let (lock, effects) = makeLock()
        await lock.unlock()
        returnAfter(PortfolioLockPolicy.backgroundLimitSeconds + 1, lock, effects)
        XCTAssertTrue(lock.locked)
    }

    func testShortBackgroundKeepsUnlocked() async {
        let (lock, effects) = makeLock()
        await lock.unlock()
        returnAfter(PortfolioLockPolicy.backgroundLimitSeconds, lock, effects)
        XCTAssertFalse(lock.locked)
        // Nur `.inactive` (z. B. die Face-ID-Abfrage selbst) misst keine Zeit
        lock.scenePhaseChanged(.inactive)
        effects.now += 3_600
        lock.scenePhaseChanged(.active)
        XCTAssertFalse(lock.locked)
    }

    /// Uhr rückwärts (sollte bei der monotonen Uhr nie vorkommen): lieber sperren.
    func testClockBackwardsRelocks() async {
        let (lock, effects) = makeLock()
        await lock.unlock()
        returnAfter(-5, lock, effects)
        XCTAssertTrue(lock.locked)
    }

    func testRequireUnlockAsksOnlyWhenNeeded() async {
        let (lock, effects) = makeLock()
        let notNeeded = await lock.requireUnlock { _ in false }
        XCTAssertTrue(notNeeded)
        XCTAssertEqual(effects.authenticationCount, 0)
        XCTAssertTrue(lock.locked)

        effects.authenticationSucceeds = false
        let refused = await lock.requireUnlock { $0 }
        XCTAssertFalse(refused)
        effects.authenticationSucceeds = true
        let granted = await lock.requireUnlock { $0 }
        XCTAssertTrue(granted)
        XCTAssertEqual(effects.authenticationCount, 2)
        XCTAssertFalse(lock.locked)
    }

    /// Einstellung inzwischen aus (z. B. durch eine Wiederherstellung): nicht mehr gesperrt.
    func testSettingOffReleasesOnReturnAndReload() {
        let (lock, effects) = makeLock()
        effects.lockEnabled = false
        lock.scenePhaseChanged(.active)
        XCTAssertFalse(lock.locked)

        let (reloaded, reloadedEffects) = makeLock()
        reloadedEffects.lockEnabled = false
        reloaded.settingsReloaded()
        XCTAssertFalse(reloaded.locked)
    }

    func testDisabledReleasesAtOnce() async {
        let (lock, effects) = makeLock()
        effects.deviceSecure = false
        await lock.unlock()
        XCTAssertTrue(lock.screenLockMissing)
        lock.disabled()
        XCTAssertFalse(lock.locked)
        XCTAssertFalse(lock.screenLockMissing)
    }

    func testPrivacyCoverOnlyWhileEnabledAndSceneInactive() {
        let (lock, effects) = makeLock()
        lock.scenePhaseChanged(.inactive)
        XCTAssertEqual(effects.coverShown, true)
        XCTAssertFalse(lock.sceneActive)
        lock.scenePhaseChanged(.active)
        XCTAssertEqual(effects.coverShown, false)

        let (open, openEffects) = makeLock(enabled: false)
        open.scenePhaseChanged(.background)
        XCTAssertEqual(openEffects.coverShown, false, "ohne Sperre kein Sichtschutz")
    }
}
