import XCTest
@testable import CryptoChecker

/// Börsen-Logos (`exchange_logos.json`): Zuordnung jeder registrierten Börse (Logo, Symbol oder bewusst keins),
/// Reihenfolge, Dateinamen, Seiten und Einzelseite der CoinGecko-Börsenliste, erneuter Versuch.
/// Wie Android `ExchangeLogosParityTest.kt`.
final class ExchangeLogosParityTests: XCTestCase {

    private typealias S = TestSupport

    func testEveryExchangeHasLogoSymbolOrExplicitNone() throws {
        let keys = try XCTUnwrap(try S.fixture("exchange_logos")["keys"] as? [String: [String: Any]])
        // Die Testfälle nennen genau die registrierten Börsen (neue Börse → hier und in Android eintragen)
        XCTAssertEqual(Set(ExchangesConfig.all.map(\.key)), Set(keys.keys))
        for (key, spec) in keys {
            XCTAssertEqual(ExchangeLogos.logoKey(key), spec["logo"] as? String, key)
            XCTAssertEqual(ExchangeLogos.symbols[key]?.rawValue, spec["symbol"] as? String, key)
            XCTAssertEqual(ExchangeLogos.noLogo.contains(key), spec["none"] as? Bool == true, key)
            let kinds = [ExchangeLogos.logoKey(key) != nil, ExchangeLogos.symbols[key] != nil,
                         ExchangeLogos.noLogo.contains(key)]
            XCTAssertEqual(kinds.filter { $0 }.count, 1, key)
        }
    }

    func testOrderAndIdsMatchThePickerOrder() throws {
        let data = try S.fixture("exchange_logos")
        let head = try XCTUnwrap(data["order_head"] as? [String])
        XCTAssertEqual(Array(ExchangeLogos.order.prefix(head.count)), head)
        XCTAssertEqual(ExchangeLogos.order.last, data["order_tail"] as? String)
        XCTAssertEqual(ExchangeLogos.order.count, Set(ExchangeLogos.order).count)
        // Seite 1 ist dieselbe Abfrage wie für die Reihenfolge; gleiche Kennungen für Logo und Volumen (HashKey: «hashkey»)
        XCTAssertEqual(ExchangeLogos.pageURL(1), ExchangeOrder.spotURL)
        let logoIds = Dictionary(ExchangeLogos.spotIds.map { ($0.key, $0.id) }, uniquingKeysWith: { first, _ in first })
        for (key, id) in ExchangeOrder.spotIds { XCTAssertEqual(logoIds[key], id, key) }
        XCTAssertEqual(ExchangeOrder.spotIds["HashKey"], "hashkey")
        XCTAssertEqual(S.number(data["perPage"]).map { Int($0) }, ExchangeLogos.perPage)
        XCTAssertEqual(S.number(data["maxPages"]).map { Int($0) }, ExchangeLogos.maxPages)
    }

    func testFileNamesInOwnNamespace() throws {
        for c in S.list(try S.fixture("exchange_logos")["fileNames"]) {
            let key = c["key"] as? String ?? ""
            XCTAssertEqual(CoinLogos.fileName(key), c["file"] as? String, key)
        }
        XCTAssertNil(CoinLogos.bundledIconName("EXCHANGE:binance"))
        XCTAssertTrue(ExchangeLogos.order.allSatisfy { CoinLogos.fileName($0) != nil })
    }

    func testPageDetailAndNextPage() throws {
        let data = try S.fixture("exchange_logos")
        let page = try XCTUnwrap(data["page"] as? [String: Any])
        let body = try JSONSerialization.data(withJSONObject: page["rows"] ?? [])
        let parsed = try ExchangeLogos.parsePage(String(decoding: body, as: UTF8.self))
        XCTAssertEqual(parsed.images, page["expected"] as? [String: String])
        XCTAssertEqual(parsed.rows, S.number(page["expectedRows"]).map { Int($0) })
        XCTAssertEqual(ExchangeLogos.images(parsed.images), page["logoImages"] as? [String: String])
        XCTAssertEqual(ExchangeLogos.missing(parsed.images).count, ExchangeLogos.ids.count - 2)
        XCTAssertThrowsError(try ExchangeLogos.parsePage("{}"), "kein Array")
        for c in S.list(data["detail"]) {
            let json = try JSONSerialization.data(withJSONObject: c["body"] ?? [:])
            XCTAssertEqual(try ExchangeLogos.parseDetail(String(decoding: json, as: UTF8.self)), c["expected"] as? String)
        }
        for c in S.list(data["nextPage"]) {
            let actual = ExchangeLogos.needsNextPage(page: Int(S.number(c["page"]) ?? 0), rows: Int(S.number(c["rows"]) ?? 0),
                                                     missing: Int(S.number(c["missing"]) ?? 0))
            XCTAssertEqual(actual, c["expected"] as? Bool, "\(c)")
        }
        for c in S.list(data["retry"]) {
            let actual = ExchangeLogos.retryDue(complete: c["complete"] as? Bool ?? false,
                                                attemptAt: Int64(S.number(c["attemptAt"]) ?? 0),
                                                now: Int64(S.number(c["now"]) ?? 0))
            XCTAssertEqual(actual, c["expected"] as? Bool, "\(c)")
        }
    }
}
