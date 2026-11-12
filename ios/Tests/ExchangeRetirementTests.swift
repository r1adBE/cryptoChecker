import XCTest
@testable import CryptoChecker

/// Stillgelegte Börsen (`ExchangeRetirement`, Bitpanda ab 1. Februar 2027 00:00 UTC): gemeinsame Testfälle
/// `exchange_retirement.json` (Android: `ExchangeRetirementParityTest.kt`, `WatchRetirementParityTest.kt`) — Uhr vor/nach
/// dem Zeitpunkt, Marker, Hinweis, Uhr-App — und was daraus folgt: der Abruf fragt nichts (jedes Paar mit Marker,
/// «nicht gehandelt»), der Marker bleibt beim Speichern, die Anzeige nennt den Grund, Alarme ruhen mit Grund.
final class ExchangeRetirementTests: XCTestCase {

    private typealias S = TestSupport

    private func fixture() throws -> [String: Any] { try S.fixture("exchange_retirement") }

    func testConstantsAsFixture() throws {
        let f = try fixture()
        XCTAssertEqual(S.millis(f["endMillis"]), ExchangeRetirement.bitpandaEndMillis)
        XCTAssertEqual(f["markerPrefix"] as? String, ExchangeRetirement.markerPrefix)
        XCTAssertEqual(S.t("2027-02-01T00:00:00Z"), ExchangeRetirement.bitpandaEndMillis)
    }

    func testRetiredAsFixture() throws {
        let cases = S.list(try fixture()["retired"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            XCTAssertEqual(ExchangeRetirement.isRetired(c["exchange"] as? String, now: S.millis(c["now"])),
                           c["expected"] as? Bool, name)
        }
    }

    func testMarkersAsFixture() throws {
        for c in S.list(try fixture()["markers"]) {
            let name = c["name"] as? String ?? "?"
            let error = c["lastError"] as? String
            XCTAssertEqual(ExchangeRetirement.exchange(of: error), c["exchange"] as? String, name)
            XCTAssertEqual(NotTraded.isMarker(error), c["notTraded"] as? Bool, "\(name) › notTraded")
            XCTAssertEqual(NotTraded.askSingleWhenMissing(lastError: error), c["askSingle"] as? Bool, "\(name) › askSingle")
        }
        XCTAssertEqual(ExchangeRetirement.marker(" Bitpanda "), "Exchange retired: Bitpanda")
    }

    func testNoticeAsFixture() throws {
        for c in S.list(try fixture()["notice"]) {
            let got = ExchangeRetirement.noticeExchanges(c["exchanges"] as? [String] ?? [],
                                                         dismissed: Set(c["dismissed"] as? [String] ?? []),
                                                         now: S.millis(c["now"]))
            XCTAssertEqual(got, c["expected"] as? [String], c["name"] as? String ?? "?")
        }
    }

    func testWatchMissingAsFixture() throws {
        let cases = S.list(try fixture()["watchMissing"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let pair = try XCTUnwrap(WatchPair.parse(c["key"] as? String ?? ""), name)
            let now = S.millis(c["now"])
            XCTAssertEqual(WatchPairQuotes.missingReason(pair, outcome: nil, now: now).rawValue, c["expected"] as? String, name)
            XCTAssertEqual(WatchPairQuotes.venues(pair, now: now).map(\.label), c["venues"] as? [String], "\(name) › venues")
        }
    }

    /** Stillgelegte Börse: keine Anfrage, jedes Paar «nicht gehandelt» mit dem Marker, kein Fehler im Bericht. */
    func testRetiredGroupAsksNothing() {
        let group = (1...4).map { i in
            Watch(id: Int64(i), exchangeKey: "Bitpanda", exchangeName: "Bitpanda", baseAsset: "C\(i)", quoteAsset: "EUR")
        }
        let (results, signals) = PriceRefresher.retiredGroup(group, sample: group[0], started: EpochTime.nowMillis)
        XCTAssertEqual(Set(results.keys), Set(group.map(\.id)))
        for fetched in results.values {
            XCTAssertNil(fetched.ticker)
            XCTAssertTrue(fetched.notTraded)
            XCTAssertEqual(fetched.error, "Exchange retired: Bitpanda")
        }
        XCTAssertEqual(signals?.report.notTraded, 4)
        XCTAssertEqual(signals?.report.failed, 0)
        XCTAssertEqual(signals?.report.updated, 0)
        XCTAssertEqual(signals?.failures.isEmpty, true)
        // Gespeichert bleibt der Marker samt Name; die Anzeige nennt den Grund
        XCTAssertEqual(StoredError.sanitize("Exchange retired: Bitpanda"), "Exchange retired: Bitpanda")
        XCTAssertEqual(ConnectionErrors.display("Exchange retired: Bitpanda"), L("exchange_retired_short", "Bitpanda"))
        XCTAssertFalse(ConnectionErrors.isRetryable("Exchange retired: Bitpanda"))
        // Fehler der gesperrten Abfrage ergibt denselben Marker
        XCTAssertEqual(ConnectionErrors.describe(RetiredExchangeError(name: "Bitpanda")), "Exchange retired: Bitpanda")
    }

    func testAlarmsRestWithTheReason() {
        let end = ExchangeRetirement.bitpandaEndMillis
        XCTAssertEqual(AlarmCardStatus.of(alarmEnabled: true, notificationsAllowed: false, dexBlocksAlarms: true,
                                          exchangeTime: nil, lastUpdate: 0, now: end, staleAfterMillis: 1, unchecked: true,
                                          exchangeRetired: true), .exchangeRetired)
        XCTAssertNil(AlarmCardStatus.of(alarmEnabled: false, notificationsAllowed: true, dexBlocksAlarms: false,
                                        exchangeTime: nil, lastUpdate: 0, now: end, staleAfterMillis: 1, unchecked: false,
                                        exchangeRetired: true))
    }

    /** Widgets zeigen ein Paar der stillgelegten Börse ohne alten Kurs. */
    func testWidgetShowsNoOldPrice() {
        var watch = Watch(id: 1, exchangeKey: "Bitpanda", exchangeName: "Bitpanda", baseAsset: "BTC", quoteAsset: "EUR")
        watch.lastPrice = 80_000
        XCTAssertEqual(watch.retiredShown.lastPrice, 80_000)
        watch.lastError = ExchangeRetirement.marker("Bitpanda")
        XCTAssertNil(watch.retiredShown.lastPrice)
        XCTAssertTrue(watch.isNotTraded)
    }

    /** Angebunden: Abruf, Explorer, Portfolio, Zeile, Paarseite, Merkliste, Uhr (Quelltext). */
    func testWired() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        func text(_ path: String) throws -> String {
            try String(contentsOf: root.appendingPathComponent(path), encoding: .utf8)
        }
        XCTAssertTrue(try text("Shared/Services/PriceFetcher.swift")
            .contains("if ExchangeRetirement.isRetired(sample.exchangeKey, now: started) { return retiredGroup("))
        XCTAssertTrue(try text("Shared/Core/ExchangeService.swift")
            .contains("if ExchangeRetirement.isRetired(exchange.key) { throw RetiredExchangeError(name: exchange.name) }"))
        XCTAssertTrue(try text("App/Features/Explorer/ExplorerViewModel.swift")
            .contains("!($0 is UnknownExchange) && !ExchangeRetirement.isRetired($0.key)"))
        XCTAssertTrue(try text("Shared/Portfolio/PortfolioExchangeRoute.swift")
            .contains("hit.key != defaultExchange, !ExchangeRetirement.isRetired(hit.key)"))
        XCTAssertTrue(try text("App/Features/Watchlist/WatchlistHeader.swift")
            .contains("WatchlistRetiredNotice(watches: data.watches)"))
        XCTAssertTrue(try text("App/Services/PhoneWatchLink.swift")
            .contains("price: ExchangeRetirement.isMarker(w.lastError) ? nil : w.lastPrice"))
    }
}
