import XCTest
@testable import CryptoChecker

/// Kraken-Paare, die nicht «online» sind (z. B. «cancel_only»), an der echten Aufnahme vom 16.10.2026
/// (`recorded_kraken.json`, vier solche Paare): Sie stehen nicht zur Wahl, und eine bestehende Beobachtung endet als
/// «nicht mehr gehandelt» statt still mit dem alten Kurs. Wie `KrakenTradableTest.kt`.
final class KrakenTradableTests: XCTestCase {

    private let kraken = Kraken()

    /// Die vier nicht handelbaren Paare der Aufnahme.
    private let cancelOnly: Set<String> = ["ACAEUR", "ACAUSD", "ACXEUR", "ACXUSD"]

    private func recordedBody(_ kind: String) throws -> String {
        let data = try TestSupport.fixture("recorded_kraken")
        let cases = TestSupport.list(data["cases"])
        let match = try XCTUnwrap(cases.first { ($0["kind"] as? String) == kind })
        return try XCTUnwrap(match["body"] as? String)
    }

    private func resultRows(_ body: String) throws -> [String: Any] {
        let object = try JSONSerialization.jsonObject(with: Data(body.utf8))
        let root = try XCTUnwrap(object as? [String: Any])
        return try XCTUnwrap(root["result"] as? [String: Any])
    }

    func testRecordingReallyContainsTheFourCancelOnlyPairs() throws {
        let rows = try resultRows(recordedBody("pairs"))
        var notOnline = Set<String>()
        for (id, value) in rows {
            let row = value as? [String: Any] ?? [:]
            if (row["status"] as? String) != "online" { notOnline.insert(id) }
        }
        XCTAssertEqual(notOnline, cancelOnly)
    }

    func testPairListOffersOnlyOnlinePairs() throws {
        let pairs = try kraken.readPairList(call: 0, body: recordedBody("pairs"))
        let ids = Set(pairs.compactMap { $0.pairId })
        XCTAssertTrue(ids.contains("XXBTZUSD"))
        XCTAssertTrue(ids.intersection(cancelOnly).isEmpty, "cancel_only offered: \(ids.intersection(cancelOnly))")
    }

    func testBulkSkipsCancelOnlyRowsSilently() throws {
        let parsed = try kraken.parseBulkTickersDetailed(call: 0, body: recordedBody("bulk"))
        // Schlusskurs 0: weder Kurs noch Lesefehler — fehlt in der vollständigen Liste → Einzelabfrage
        XCTAssertTrue(Set(parsed.tickers.keys).intersection(cancelOnly).isEmpty)
        XCTAssertTrue(parsed.failed.intersection(cancelOnly).isEmpty)
        let btc = try XCTUnwrap(parsed.tickers["XXBTZUSD"])
        XCTAssertGreaterThan(btc.last, 0)
    }

    func testSingleTickerOfCancelOnlyPairHasNoPriceAndConfirmsNotTraded() throws {
        let rows = try resultRows(recordedBody("bulk"))
        for id in cancelOnly.sorted() {
            // Einzelantwort = dieselbe Zeile unter `result`, wie Kraken sie für ?pair=<id> liefert
            let row = try XCTUnwrap(rows[id])
            let single: [String: Any] = ["error": [Any](), "result": [id: row]]
            let data = try JSONSerialization.data(withJSONObject: single)
            let body = try XCTUnwrap(String(data: data, encoding: .utf8))
            var ticker = Ticker()
            try kraken.readTicker(call: 0, body: body, ticker: &ticker, info: PairQuery("ACA", "USD", id, .none))
            XCTAssertEqual(ticker.last, Ticker.missing, id)
        }
        // Ohne Kurs meldet die Einzelabfrage «Parsed ticker has no data» → bestätigt «nicht mehr gehandelt»
        XCTAssertTrue(NotTraded.confirmedBySingle(gotPrice: false, error: "Parsed ticker has no data"))
        XCTAssertFalse(NotTraded.confirmedBySingle(gotPrice: true, error: nil))
    }
}
