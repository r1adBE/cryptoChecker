import XCTest
@testable import CryptoChecker

/// Reine Kurs-Speicherungen des Live-Stroms zusammengefasst (`PriceSaveThrottle`, AppData+PriceSave.swift): höchstens
/// alle 30 s (die erste nach einer Pause sofort), Widget-Wünsche vereinigt; alles andere (ausgelöste Alarme, Änderungen)
/// sofort wie bisher; Wechsel in den Hintergrund schreibt wartende Kurse sofort.
@MainActor
final class PriceSaveThrottleTests: XCTestCase {
    private let interval = PriceSaveThrottle.intervalMillis

    func testFirstSaveNowThenAtMostOncePerInterval() {
        var t = PriceSaveThrottle()
        XCTAssertEqual(t.request(now: 100_000, reloadWidgets: false, widgetKinds: nil), .saveNow)
        XCTAssertTrue(t.take(now: 100_000).isEmpty)
        // 10 s später: vorgemerkt bis 30 s nach der letzten Speicherung
        XCTAssertEqual(t.request(now: 110_000, reloadWidgets: true, widgetKinds: ["single"]),
                       .schedule(afterMillis: 20_000, token: 1))
        XCTAssertTrue(t.pending)
        // Weitere Takte: warten mit, Widget-Wünsche vereinigt
        XCTAssertEqual(t.request(now: 120_000, reloadWidgets: true, widgetKinds: ["list"]), .waiting)
        XCTAssertEqual(t.request(now: 125_000, reloadWidgets: false, widgetKinds: nil), .waiting)
        XCTAssertTrue(t.fires(token: 1))
        XCTAssertFalse(t.fires(token: 0))
        let widgets = t.take(now: 130_000)
        XCTAssertEqual(widgets, PriceSaveThrottle.Widgets(all: false, kinds: ["single", "list"]))
        XCTAssertFalse(t.pending)
        XCTAssertFalse(t.fires(token: 1), "geschrieben: die Vormerkung gilt nicht mehr")
        // Nach dem vollen Abstand wieder sofort
        XCTAssertEqual(t.request(now: 130_000 + interval, reloadWidgets: true, widgetKinds: nil), .saveNow)
        XCTAssertEqual(t.take(now: 130_000 + interval), PriceSaveThrottle.Widgets(all: true, kinds: []))
    }

    func testNeverTwoSavesWithinTheInterval() {
        // Takt alle 2 s über 5 Minuten: höchstens eine Speicherung je 30 s (Vormerkungen zum fälligen Zeitpunkt)
        var t = PriceSaveThrottle()
        var saves: [Int64] = []
        var due: (at: Int64, token: Int)?
        var now: Int64 = 1_000_000
        while now < 1_300_000 {
            if let d = due, d.at <= now {
                if t.fires(token: d.token) { _ = t.take(now: d.at); saves.append(d.at) }
                due = nil
            }
            switch t.request(now: now, reloadWidgets: false, widgetKinds: nil) {
            case .saveNow:
                _ = t.take(now: now)
                saves.append(now)
            case .schedule(let after, let token):
                due = (now + after, token)
            case .waiting:
                break
            }
            now += 2_000
        }
        XCTAssertGreaterThanOrEqual(saves.count, 9)
        for (a, b) in zip(saves, saves.dropFirst()) { XCTAssertGreaterThanOrEqual(b - a, interval) }
    }

    func testFlushBeforeDueAndClockBackwards() {
        var t = PriceSaveThrottle()
        _ = t.request(now: 50_000, reloadWidgets: false, widgetKinds: nil)
        _ = t.take(now: 50_000)
        guard case .schedule(_, let token) = t.request(now: 55_000, reloadWidgets: false, widgetKinds: nil) else {
            return XCTFail("vorgemerkt erwartet")
        }
        // Hintergrund: sofort geschrieben; die alte Vormerkung schreibt danach nicht noch einmal
        XCTAssertTrue(t.pending)
        _ = t.take(now: 56_000)
        XCTAssertFalse(t.fires(token: token))
        // Neue Vormerkung ersetzt die alte (eigenes Zeichen)
        guard case .schedule(_, let next) = t.request(now: 57_000, reloadWidgets: false, widgetKinds: nil) else {
            return XCTFail("vorgemerkt erwartet")
        }
        XCTAssertNotEqual(next, token)
        XCTAssertFalse(t.fires(token: token))
        XCTAssertTrue(t.fires(token: next))
        // Uhr zurückgestellt: wie nach einer Pause sofort
        var back = PriceSaveThrottle()
        _ = back.request(now: 500_000, reloadWidgets: false, widgetKinds: nil)
        _ = back.take(now: 500_000)
        XCTAssertEqual(back.request(now: 400_000, reloadWidgets: false, widgetKinds: nil), .saveNow)
    }

    func testWidgetWishesMergeIntoAFullSave() {
        let none = PriceSaveThrottle.Widgets()
        let kinds = PriceSaveThrottle.Widgets(all: false, kinds: ["single"])
        let all = PriceSaveThrottle.Widgets(all: true, kinds: [])
        // Nichts wartend: das volle Schreiben bleibt, wie es ist
        let unchanged = none.merged(reloadWidgets: true, widgetKinds: ["list", "chart"])
        XCTAssertTrue(unchanged.reload)
        XCTAssertEqual(unchanged.kinds, ["list", "chart"])
        XCTAssertFalse(none.merged(reloadWidgets: false, widgetKinds: nil).reload)
        // Wartende Arten kommen dazu, auch wenn das volle Schreiben selbst keine Widgets lädt
        let added = kinds.merged(reloadWidgets: false, widgetKinds: nil)
        XCTAssertTrue(added.reload)
        XCTAssertEqual(added.kinds, ["single"])
        XCTAssertEqual(kinds.merged(reloadWidgets: true, widgetKinds: ["list"]).kinds, ["list", "single"])
        // «Alle» auf einer Seite: alle
        XCTAssertNil(kinds.merged(reloadWidgets: true, widgetKinds: nil).kinds)
        let everything = all.merged(reloadWidgets: true, widgetKinds: ["list"])
        XCTAssertTrue(everything.reload)
        XCTAssertNil(everything.kinds)
    }

    /// In `AppData`: reine Kurse (`quiet`) gehen über die Zusammenfassung, jede andere Änderung (z. B. ein ausgelöster
    /// Alarm) nicht — sie wird sofort geschrieben und nimmt wartende Kurse samt Widget-Wünschen mit (danach kein
    /// zusätzliches Kurs-Schreiben, Abstand ab dann); der Wechsel in den Hintergrund schreibt wartende Kurse sofort.
    func testAppDataCoalescesOnlyPricesAndFlushesInBackground() async throws {
        // Merkliste der Test-App mit eigenem Testpaar, danach wiederhergestellt (wie AddWatchesBehaviorTests)
        let watchlist = try await AppDataTestWatchlist()
        try await watchlist.load([Watch(id: 990_401, marketKey: "Binance", marketName: "Binance", baseAsset: "BTC",
                                        quoteAsset: "USDT", pairId: "BTCUSDT", sortOrder: 0)])
        let data = AppData.shared
        data.discardPendingPriceSave()
        data.mutate({ _ in }, reloadWidgets: false, quiet: true)
        XCTAssertFalse(data.priceSaves.pending, "erste Kurs-Speicherung sofort")
        XCTAssertNotNil(data.priceSaves.lastSaveAt)
        data.mutate({ _ in }, reloadWidgets: true, widgetKinds: ["PriceSaveThrottleTests"], quiet: true)
        XCTAssertTrue(data.priceSaves.pending, "zweite innerhalb von 30 s wartet")
        XCTAssertEqual(data.priceSaves.widgets.kinds, ["PriceSaveThrottleTests"])
        let before = data.priceSaves
        data.mutate({ _ in }, reloadWidgets: false)
        XCTAssertFalse(data.priceSaves.pending, "volles Schreiben nimmt die wartenden Kurse mit")
        XCTAssertFalse(data.priceSaves.fires(token: before.token), "kein zusätzliches Kurs-Schreiben danach")
        XCTAssertTrue(data.priceSaves.widgets.isEmpty, "Widget-Wünsche ins volle Schreiben übernommen")
        XCTAssertGreaterThanOrEqual(data.priceSaves.lastSaveAt ?? 0, before.lastSaveAt ?? 0)
        // Abstand ab dem vollen Schreiben: der nächste Kurs wartet
        data.mutate({ _ in }, reloadWidgets: false, quiet: true)
        XCTAssertTrue(data.priceSaves.pending, "Kurs kurz nach dem vollen Schreiben wartet")
        let wasActive = data.appActive
        data.setAppActive(false)
        XCTAssertFalse(data.priceSaves.pending, "Hintergrund: sofort geschrieben")
        if wasActive { data.setAppActive(true) }
        await SharedStorage.snapshotWritesSettled()
        data.discardPendingPriceSave()
        await watchlist.restore()
    }
}
