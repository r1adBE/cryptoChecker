import XCTest
@testable import CryptoChecker

/// Referenzkurse aus mehreren Antworten (`MultiResponses`, NBP: Tabelle A und B): eine Anfrage je Adresse, egal wie viele
/// Paare und ob Einzel- oder Sammelkurs; behalten bis zur nächsten Veröffentlichung (mindestens eine Stunde). Wie Android
/// `MultiResponsesTest.kt` (dort gegen einen lokalen Server).
final class MultiResponsesTests: XCTestCase {
    private static let tableA = #"[{"table":"A","effectiveDate":"2026-10-28","rates":[{"code":"USD","mid":3.9624},"#
        + #"{"code":"EUR","mid":4.409}]},{"table":"A","effectiveDate":"2026-11-04","rates":[{"code":"USD","mid":3.9097},"#
        + #"{"code":"EUR","mid":4.4014}]}]"#
    private static let tableB = #"[{"table":"B","effectiveDate":"2026-10-28","rates":[{"code":"NGN","mid":0.002951}]},"#
        + #"{"table":"B","effectiveDate":"2026-11-04","rates":[{"code":"NGN","mid":0.00291}]}]"#

    override func setUp() {
        super.setUp()
        MultiResponses.clear()
    }

    override func tearDown() {
        MultiResponses.clear()
        super.tearDown()
    }

    func testOneRequestPerTableAndKeptForTheNextRead() async throws {
        let source = NbpReference()
        let log = RequestLog()
        let fetch: @Sendable (String) async throws -> String = { url in
            await log.add(url)
            return url.contains("/tables/A/") ? MultiResponsesTests.tableA : MultiResponsesTests.tableB
        }
        let first = try await MultiResponses.body(source, fetch: fetch)
        let second = try await MultiResponses.body(source, fetch: fetch)
        let requested = await log.urls
        XCTAssertEqual(requested, [ReferenceRates.nbpTableAURL, ReferenceRates.nbpTableBURL], "Tabelle A und B je einmal")
        XCTAssertEqual(first, second)

        let tickers = try source.parseBulkTickersMain(call: 0, body: first)
        // 0.00291 / 3.9097 und 0.00291 / 4.4014, je 5 Stellen
        XCTAssertEqual(try XCTUnwrap(tickers["NGNUSD"]).last, 0.0007443, accuracy: 1e-12)
        XCTAssertEqual(try XCTUnwrap(tickers["NGNEUR"]).last, 0.00066115, accuracy: 1e-12)
    }
}

/// Gestellte Anfragen (Adressen) der Attrappe.
private actor RequestLog {
    private(set) var urls: [String] = []

    func add(_ url: String) { urls.append(url) }
}
