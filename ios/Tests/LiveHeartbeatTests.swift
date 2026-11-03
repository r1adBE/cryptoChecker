import XCTest
@testable import CryptoChecker

/// Ruhiges Paar an einem gesunden Live-Strom: Frische am letzten Takt, nicht am letzten Kurswechsel.
/// Wie `LiveHeartbeatTest.kt`.
final class LiveHeartbeatTests: XCTestCase {

    private let min: Int64 = 60_000
    private var now: Int64 { 1_000 * min }
    private var polling: Int64 { LiveStatus.pollingStaleAfterMillis(backgroundIntervalMinutes: 15) }

    /// Kurs seit 10 Min. unverändert (Binance schickt jede Sekunde denselben Kurs).
    private func quiet(id: Int64 = 1) -> Watch {
        var watch = Watch(id: id, exchangeKey: "Binance", exchangeName: "Binance", baseAsset: "BTC", quoteAsset: "USDT")
        watch.lastPrice = 50_000
        watch.lastUpdate = now - 10 * min
        return watch
    }

    func testQuietPairOnHealthyStreamIsNotStale() {
        let shown = quiet().atHeartbeat(onLiveSocket: true, now: now)
        XCTAssertEqual(LiveStatus.freshnessTime(lastUpdate: now - 10 * min, onLiveSocket: true, now: now), shown.lastUpdate)
        let staleAfter = LiveStatus.staleAfterMillis(onLiveSocket: true, pollingAfter: polling)
        XCTAssertFalse(WatchlistTime.isStale(shown, now: now, staleAfter: staleAfter))
        // «Live» bleibt in der Pille (kein veraltetes Paar)
        XCTAssertTrue(LiveStatus.allLive(tradedIds: [1], liveIds: [1], stale: 0))
    }

    func testWithoutStreamTheStoredTimeCounts() {
        let watch = quiet()
        XCTAssertEqual(watch, watch.atHeartbeat(onLiveSocket: false, now: now))
        // Ohne Strom: Abfrage-Schwelle (45 Min.) ab dem letzten Abruf — 10 Min. sind frisch, 50 Min. veraltet
        XCTAssertFalse(WatchlistTime.isStale(watch, now: now, staleAfter: polling))
        var old = watch
        old.lastUpdate = now - 50 * min
        XCTAssertTrue(WatchlistTime.isStale(old, now: now, staleAfter: polling))
    }

    func testDisabledPairIsLeftAlone() {
        var off = quiet()
        off.disabled = true
        XCTAssertEqual(off, off.atHeartbeat(onLiveSocket: true, now: now))
    }
}
