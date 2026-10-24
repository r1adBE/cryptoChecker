import XCTest
@testable import CryptoChecker

/// Gesamtrechnung des Portfolio-Tabs abseits des Hauptthreads (`PortfolioSummaryCache`): gleiche Eingaben nur einmal
/// gerechnet, bis zum neuen Ergebnis das letzte gezeigt, Ergebnis gleich wie die direkte Rechnung.
@MainActor
final class PortfolioSummaryCacheTests: XCTestCase {
    /// Zählt Rechnungen (aus dem Hintergrund aufgerufen).
    private final class Counter: @unchecked Sendable { // @unchecked: `value` nur unter `lock` (NSLock)
        private let lock = NSLock()
        private var value = 0
        func hit() { lock.lock(); value += 1; lock.unlock() }
        var count: Int { lock.lock(); defer { lock.unlock() }; return value }
    }

    private let trades = [
        PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 100, time: 1_000),
        PortfolioTx(id: 2, coin: "BTC", type: .SELL, amount: 0.25, priceUsdt: 200, time: 2_000),
    ]

    private func key(_ price: Double) -> PortfolioSummaryKey {
        PortfolioSummaryKey(trades: trades, prices: ["BTC": price], method: .FIFO, closesStamp: 0)
    }

    private func input(_ price: Double) -> PortfolioSummaryInput {
        PortfolioSummaryInput(key: key(price), closes: [:])
    }

    private func cache(_ counter: Counter) -> PortfolioSummaryCache {
        PortfolioSummaryCache(compute: { input in
            counter.hit()
            return PortfolioSummaryInput.summarize(input)
        })
    }

    func testErsterAufbauRechnetSofortUndNurEinmal() {
        let counter = Counter()
        let sut = cache(counter)
        let first = sut.shown(key(300)) { input(300) }
        let second = sut.shown(key(300)) { input(300) }
        XCTAssertEqual(counter.count, 1)
        XCTAssertEqual(first, second)
        XCTAssertEqual(first, PortfolioCalculator.summarize(trades, prices: ["BTC": 300], method: .FIFO))
    }

    func testNeueKurseZeigenDasLetzteErgebnisBisDasNeueDaIst() async {
        let counter = Counter()
        let sut = cache(counter)
        let old = sut.shown(key(300)) { input(300) }
        var published = 0
        sut.onPublish = { published += 1 }
        // Neue Kurse: kein leeres Portfolio, sondern der alte Stand, bis die Rechnung fertig ist
        XCTAssertEqual(sut.shown(key(400)) { input(400) }, old)
        // «7 T» fragt gleichzeitig: hängt sich an dieselbe Rechnung
        let fresh = await sut.fresh(key(400)) { input(400) }
        XCTAssertEqual(counter.count, 2)
        XCTAssertEqual(published, 1)
        XCTAssertEqual(fresh, PortfolioCalculator.summarize(trades, prices: ["BTC": 400], method: .FIFO))
        XCTAssertEqual(sut.shown(key(400)) { input(400) }, fresh)
        XCTAssertEqual(sut.now(key(400)) { input(400) }, fresh)
        XCTAssertEqual(counter.count, 2)
    }

    func testJeAktualisierungEineRechnung() async {
        let counter = Counter()
        let sut = cache(counter)
        _ = sut.now(key(300)) { input(300) }
        // Ablauf einer Aktualisierung: Ansicht, «7 T», Widget und Einstand fragen nach denselben Eingaben
        _ = sut.shown(key(500)) { input(500) }
        _ = await sut.fresh(key(500)) { input(500) }
        _ = await sut.fresh(key(500)) { input(500) }
        _ = sut.now(key(500)) { input(500) }
        XCTAssertEqual(counter.count, 2)
    }

    func testAelteresErgebnisUeberschreibtNeueresNicht() async {
        let counter = Counter()
        let sut = cache(counter)
        _ = sut.now(key(300)) { input(300) }
        // Rechnung zu 400 läuft im Hintergrund, inzwischen wird 600 sofort gerechnet
        _ = sut.shown(key(400)) { input(400) }
        let newer = sut.now(key(600)) { input(600) }
        _ = await sut.fresh(key(400)) { input(400) }
        XCTAssertEqual(sut.hit(key(600)), newer)
        XCTAssertNil(sut.hit(key(400)))
        XCTAssertEqual(counter.count, 3)
    }
}
