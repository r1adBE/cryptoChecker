import XCTest
@testable import CryptoChecker

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus WatchlistGroupsHintTests

/// Gruppen-Hinweis immer, wenn keine Chips da sind — gleiche Fälle wie `WatchlistGroupsHintTest`.
final class WatchlistGroupsHintTests: XCTestCase {

    private func hint(groups: [String] = [], favorites: Bool = false, searching: Bool = false,
                      sorting: Bool = false, quickView: QuickView? = nil) -> Bool {
        WatchlistGroupChips.showsHint(groups: groups, selected: nil, hasFavorites: favorites, hasActivity: false,
                                      quickView: quickView, searching: searching, sorting: sorting)
    }

    func testHintWithoutGroupsFromFirstStart() {
        // Ohne Gruppen und Favoriten immer der Hinweis — auch mit 0–4 Paaren und ohne Alarm
        // (die Regel kennt weder Paarzahl noch ersten Gebrauch)
        XCTAssertTrue(hint())
    }

    func testChipsInsteadOfHintWithGroupOrFavorite() {
        XCTAssertTrue(WatchlistGroupChips.showsChips(groups: ["DeFi"], selected: nil, hasFavorites: false))
        XCTAssertTrue(WatchlistGroupChips.showsChips(groups: [], selected: nil, hasFavorites: true))
        XCTAssertFalse(hint(groups: ["DeFi"]))
        XCTAssertFalse(hint(favorites: true))
    }

    func testNoHintWhileSearchingSortingOrInQuickView() {
        XCTAssertFalse(hint(searching: true))
        XCTAssertFalse(hint(sorting: true))
        XCTAssertFalse(hint(quickView: .stale))
    }
}

// ---- aus WatchlistJumpTests

/// Wie `WatchJumpTest` (Sprungknopf der Merkliste).
final class WatchlistJumpTests: XCTestCase {

    func testEligibleOnlyAboveThirtyAndNotSorting() {
        XCTAssertFalse(WatchlistJump.eligible(pairs: 30, sorting: false))
        XCTAssertTrue(WatchlistJump.eligible(pairs: 31, sorting: false))
        XCTAssertFalse(WatchlistJump.eligible(pairs: 100, sorting: true))
    }

    func testPointsDownInTheUpperHalf() {
        XCTAssertTrue(WatchlistJump.pointsDown(firstVisible: 0, lastVisible: 10, total: 50))
        XCTAssertTrue(WatchlistJump.pointsDown(firstVisible: 15, lastVisible: 25, total: 50))
        XCTAssertFalse(WatchlistJump.pointsDown(firstVisible: 20, lastVisible: 30, total: 50))
        XCTAssertFalse(WatchlistJump.pointsDown(firstVisible: 40, lastVisible: 49, total: 50))
    }

    func testAnimateOnlyShortDistancesWithoutReduceMotion() {
        XCTAssertTrue(WatchlistJump.animate(distance: -40, reduceMotion: false))
        XCTAssertFalse(WatchlistJump.animate(distance: 41, reduceMotion: false))
        XCTAssertFalse(WatchlistJump.animate(distance: 5, reduceMotion: true))
    }
}

// ---- aus WatchlistLimitsTests

/// Obergrenzen der Merkliste — gleiche Fälle wie `WatchlistLimitsTest.kt`, dazu das Einfügen an der Grenze.
@MainActor
final class WatchlistLimitsTests: XCTestCase {

    func testGrenzenWieEntschieden() {
        XCTAssertEqual(WatchlistLimits.maxPairs, 2_000)
        XCTAssertEqual(WatchlistLimits.maxAlarmsPerPair, 10)
        // Eine Quelle: die Sicherung nimmt dieselben Grenzen
        XCTAssertEqual(BackupRestoreLimits.maxPairs, WatchlistLimits.maxPairs)
        XCTAssertEqual(BackupRestoreLimits.maxAlarmsPerPair, WatchlistLimits.maxAlarmsPerPair)
    }

    func testZehnterAlarmJaElfterNein() {
        XCTAssertTrue(WatchlistLimits.canAddAlarm(0))
        XCTAssertTrue(WatchlistLimits.canAddAlarm(9))
        XCTAssertFalse(WatchlistLimits.canAddAlarm(10))
        // Alte Daten über der Grenze: weiterhin kein neuer
        XCTAssertFalse(WatchlistLimits.canAddAlarm(12))
    }

    func testPaarNurUnterDerGrenze() {
        XCTAssertTrue(WatchlistLimits.canAddPair(0))
        XCTAssertTrue(WatchlistLimits.canAddPair(1_999))
        XCTAssertFalse(WatchlistLimits.canAddPair(2_000))
        XCTAssertFalse(WatchlistLimits.canAddPair(2_500))
    }

    func testFreiePlaetzeNieNegativ() {
        XCTAssertEqual(WatchlistLimits.freePairSlots(0), 2_000)
        XCTAssertEqual(WatchlistLimits.freePairSlots(1_999), 1)
        XCTAssertEqual(WatchlistLimits.freePairSlots(2_000), 0)
        XCTAssertEqual(WatchlistLimits.freePairSlots(2_100), 0)
    }

    private func pool(_ i: Int) -> ExchangePair {
        ExchangePair("T\(i)", "USD (ethereum)", "ethereum/0x\(i)")
    }

    func testVolleMerklisteNimmtKeinNeuesPaarAberMeldetVorhandeneAlsDoppel() {
        var snapshot = SharedStorage.Snapshot()
        let exchange = DexScreener()
        for i in 0..<(WatchlistLimits.maxPairs - 1) {
            XCTAssertNotNil(AppData.insert(&snapshot, exchange: exchange, pair: pool(i)))
        }
        // Das 2000. Paar passt noch
        guard case .added = AppData.insertOutcome(&snapshot, exchange: exchange, pair: pool(1_999)) else {
            return XCTFail("2000. Paar nicht angelegt")
        }
        XCTAssertEqual(snapshot.watches.count, WatchlistLimits.maxPairs)
        // Das 2001. nicht; ein schon vorhandenes bleibt «schon da» (kein Fehler)
        XCTAssertEqual(AppData.insertOutcome(&snapshot, exchange: exchange, pair: pool(5_000)), .full)
        XCTAssertNil(AppData.insert(&snapshot, exchange: exchange, pair: pool(5_001)))
        XCTAssertEqual(AppData.insertOutcome(&snapshot, exchange: exchange, pair: pool(3)), .duplicate)
        XCTAssertEqual(snapshot.watches.count, WatchlistLimits.maxPairs)
    }

    /// «Rückgängig» umgeht die Obergrenze nicht — Überzählige bleiben aus und werden
    /// gezählt; vorhandene Paare zählen nicht als «voll». Wie Android `WatchRepository.restore`.
    func testRueckgaengigNurBisZurGrenze() {
        var snapshot = SharedStorage.Snapshot()
        let exchange = DexScreener()
        for i in 0..<(WatchlistLimits.maxPairs - 1) {
            XCTAssertNotNil(AppData.insert(&snapshot, exchange: exchange, pair: pool(i)))
        }
        func deleted(_ id: Int64) -> DeletedWatch {
            DeletedWatch(watch: Watch(id: id, exchangeKey: "Binance", exchangeName: "Binance", baseAsset: "B\(id)", quoteAsset: "USDT"),
                         alarms: [], activity: nil, selectedGroup: nil)
        }
        let existing = DeletedWatch(watch: snapshot.watches[0], alarms: [], activity: nil, selectedGroup: nil)
        let result = AppData.restoreInto(&snapshot, [existing, deleted(90_001), deleted(90_002), deleted(90_003)])
        XCTAssertEqual(result.restored.map(\.watch.id), [90_001])
        XCTAssertEqual(result.full, 2)
        XCTAssertEqual(snapshot.watches.count, WatchlistLimits.maxPairs)
    }
}

// ---- aus WatchlistOnboardingTests

/// Merkliste nach der Start-Auswahl schrittweise — gleiche Fälle wie `WatchlistOnboardingTest.kt`.
final class WatchlistOnboardingTests: XCTestCase {

    func testFirstUseHidesPulseAndGestureHint() {
        let v = WatchlistOnboarding.visible(alarmCount: 0, usageDays: 1)
        XCTAssertFalse(v.pulse)
        XCTAssertFalse(v.gestureHint)
    }

    func testAlarmsNotLoadedYetCountAsNone() {
        let v = WatchlistOnboarding.visible(alarmCount: nil, usageDays: 0)
        XCTAssertFalse(v.pulse)
        XCTAssertFalse(v.gestureHint)
    }

    func testFirstAlarmShowsEverything() {
        let v = WatchlistOnboarding.visible(alarmCount: 1, usageDays: 0)
        XCTAssertTrue(v.pulse)
        XCTAssertTrue(v.gestureHint)
    }

    func testThreeDifferentDaysShowEverythingWithoutAnAlarm() {
        XCTAssertFalse(WatchlistOnboarding.visible(alarmCount: 0, usageDays: WatchlistOnboarding.unlockDays - 1).pulse)
        let v = WatchlistOnboarding.visible(alarmCount: 0, usageDays: WatchlistOnboarding.unlockDays)
        XCTAssertTrue(v.pulse)
        XCTAssertTrue(v.gestureHint)
    }

    func testUsageDaysCountEachLaterDayOnce() {
        let first = UsageDays(count: 0, lastDay: nil).next(today: 100)
        XCTAssertEqual(first, UsageDays(count: 1, lastDay: 100))
        // Derselbe Tag zählt nicht nochmals
        XCTAssertEqual(first.next(today: 100), first)
        XCTAssertEqual(first.next(today: 101), UsageDays(count: 2, lastDay: 101))
        // Lücke von mehreren Tagen zählt als ein Tag
        XCTAssertEqual(first.next(today: 101).next(today: 110), UsageDays(count: 3, lastDay: 110))
    }

    func testUsageDaysIgnoreAClockSetBack() {
        XCTAssertEqual(UsageDays(count: 2, lastDay: 101).next(today: 99), UsageDays(count: 2, lastDay: 99))
        XCTAssertEqual(UsageDays(count: UsageDays.maxCount, lastDay: 5).next(today: 6),
                       UsageDays(count: UsageDays.maxCount, lastDay: 6))
    }

    /// Fehlende Felder: noch kein Tag gezählt; gespeicherte Werte bleiben erhalten.
    func testSettingsDecodeUsageDays() throws {
        let empty = try JSONDecoder().decode(AppSettings.self, from: Data("{}".utf8))
        XCTAssertEqual(empty.usageDays, 0)
        XCTAssertNil(empty.usageLastDay)
        var s = AppSettings()
        s.usageDays = 2
        s.usageLastDay = 20_000
        let back = try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(s))
        XCTAssertEqual(back.usageDays, 2)
        XCTAssertEqual(back.usageLastDay, 20_000)
    }
}

// ---- aus WatchlistRemovedBannerTests

/// Zweites Wisch-Löschen, solange der Banner steht: ein Sammel-Banner, «Rückgängig» holt beide zurück —
/// wie `RemovedBatchTest.kt`.
final class WatchlistRemovedBannerTests: XCTestCase {

    private func deleted(_ id: Int64, _ base: String) -> DeletedWatch {
        DeletedWatch(
            watch: Watch(id: id, exchangeKey: "binance", exchangeName: "Binance", baseAsset: base, quoteAsset: "USDT"),
            alarms: [], activity: nil, selectedGroup: nil
        )
    }

    func testErstesLoeschenZeigtEinzelBannerMitRueckgaengig() {
        let message = WatchlistBannerMessage.removed(deleted(1, "BTC"), after: nil) { _ in }
        XCTAssertEqual(message.removed.map(\.watch.id), [1])
        XCTAssertEqual(message.undo?.watch.id, 1)
        XCTAssertNil(message.action)
    }

    func testZweitesLoeschenFasstBeideZusammen() {
        var restored: [Int64] = []
        let first = WatchlistBannerMessage.removed(deleted(1, "BTC"), after: nil) { _ in }
        let second = WatchlistBannerMessage.removed(deleted(2, "ETH"), after: first) { all in
            restored = all.map(\.watch.id)
        }
        XCTAssertEqual(second.removed.map(\.watch.id), [1, 2])
        XCTAssertNil(second.undo)
        XCTAssertEqual(second.text, L("watchlist_removed_count", count: 2))
        second.action?.perform()
        XCTAssertEqual(restored, [1, 2])
    }

    func testAnderesBannerDavorWirdNichtMitgezaehlt() {
        let other = WatchlistBannerMessage(text: "Favorit")
        let message = WatchlistBannerMessage.removed(deleted(3, "SOL"), after: other) { _ in }
        XCTAssertEqual(message.removed.map(\.watch.id), [3])
        XCTAssertNotNil(message.undo)
    }
}
