import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `alpha_list.json` (Antwort der Binance-Alpha-Liste mit FROGGIE) und
/// `logo_fallback.json` (mitgeliefertes Icon-Paket, Pausen der Bild-Server) — Android: `LogoFallbackParityTest.kt`.
final class LogoFallbackTests: XCTestCase {

    private typealias S = TestSupport

    /// Paare `[a, b]` einer Liste (b darf null sein).
    private func pairs(_ value: Any?) -> [(Any, Any?)] {
        ((value as? [Any]) ?? []).compactMap { item in
            guard let pair = item as? [Any], pair.count == 2 else { return nil }
            return (pair[0], pair[1] is NSNull ? nil : pair[1])
        }
    }

    /// Rohtext der Antwort (wie vom Server), damit der echte Leser (`CoinLogos.parseAlpha`) läuft.
    private func alphaResponseText(_ fixture: [String: Any]) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: try XCTUnwrap(fixture["response"]))
        return try XCTUnwrap(String(data: data, encoding: .utf8))
    }

    func testAlphaListParsesFroggie() throws {
        let a = try S.fixture("alpha_list")
        let entries = try XCTUnwrap(CoinLogos.parseAlpha(try alphaResponseText(a)))
        XCTAssertEqual(entries.map(\.symbol), try XCTUnwrap(a["expectedOrder"] as? [String]))
        let symbols = CoinLogos.alphaSymbols(entries)
        XCTAssertTrue(symbols.contains("FROGGIE"), "FROGGIE fehlt: \(symbols)")
        XCTAssertEqual(symbols, Set(try XCTUnwrap(a["expectedSymbols"] as? [String])))
        var icons: [String: String] = [:]
        var order: [String] = []
        CoinLogos.pick(entries.map { (symbol: $0.symbol, image: $0.icon) }, into: &icons, order: &order)
        XCTAssertEqual(icons, try XCTUnwrap(a["expectedIcons"] as? [String: String]))
        // Wie in der App: applyAlpha liefert dieselben Kürzel
        var map: [String: String] = [:]
        var mapOrder: [String] = []
        var names: [String: String] = [:]
        var nameOrder: [String] = []
        XCTAssertEqual(CoinLogos.applyAlpha(entries, into: &map, order: &mapOrder, names: &names, nameOrder: &nameOrder), symbols)
        XCTAssertEqual(map["FROGGIE"], "https://bin.bnbstatic.com/images/web3-data/public/token/logos/froggie.png")
    }

    func testAlphaListInvalidResponses() throws {
        let a = try S.fixture("alpha_list")
        for text in try XCTUnwrap(a["invalid"] as? [String]) {
            XCTAssertNil(CoinLogos.parseAlpha(text), "invalid: \(text)")
        }
        XCTAssertNil(CoinLogos.parseAlpha(nil))
    }

    func testAlphaCleanSymbolAndIcon() throws {
        let a = try S.fixture("alpha_list")
        for (raw, expected) in pairs(a["cleanSymbol"]) {
            XCTAssertEqual(CoinLogos.cleanSymbol(raw as? String), expected as? String, "clean: \(raw)")
        }
        for (raw, expected) in pairs(a["iconUrl"]) {
            XCTAssertEqual(CoinLogos.alphaIconURL(raw as? String), expected as? String, "icon: \(raw)")
        }
    }

    func testIconPackIndex() throws {
        let f = try S.fixture("logo_fallback")
        let pack = try XCTUnwrap(f["iconPack"] as? [String: Any])
        let text = try XCTUnwrap(pack["text"] as? String)
        let index = try XCTUnwrap(CoinLogos.parseIconPack(Array(text.utf8)))
        var expected: [String: CoinLogos.IconSlice] = [:]
        for (name, value) in try XCTUnwrap(pack["expected"] as? [String: [NSNumber]]) {
            expected[name] = CoinLogos.IconSlice(offset: value[0].intValue, length: value[1].intValue)
        }
        XCTAssertEqual(index, expected)
        let empty = try XCTUnwrap(pack["emptyText"] as? String)
        XCTAssertEqual(CoinLogos.parseIconPack(Array(empty.utf8)), [:])
        for invalid in try XCTUnwrap(f["invalidPack"] as? [String]) {
            XCTAssertNil(CoinLogos.parseIconPack(Array(invalid.utf8)), "invalid: \(invalid)")
        }
    }

    func testIconPackNames() throws {
        let f = try S.fixture("logo_fallback")
        for (symbol, expected) in pairs(f["bundledName"]) {
            XCTAssertEqual(CoinLogos.bundledIconName(symbol as? String ?? ""), expected as? String, "name: \(symbol)")
        }
    }

    /// Das mitgelieferte Paket der App: lesbar, BTC und ETH dabei, Icons als Bild lesbar.
    func testBundledIconPack() throws {
        XCTAssertNotNil(CoinLogoStore.bundled("BTC"))
        XCTAssertNotNil(CoinLogoStore.bundled("eth"))
        XCTAssertNil(CoinLogoStore.bundled("TRADFI:NVDA"))
    }

    func testImagePauses() throws {
        let f = try S.fixture("logo_fallback")
        for row in (f["pauseResponse"] as? [[Any]]) ?? [] {
            XCTAssertEqual(CoinLogoRetry.pausesImages(httpCode: (row[0] as? NSNumber)?.intValue ?? 0, retryAfter: row[1] as? Bool ?? false),
                           row[2] as? Bool, "code: \(row)")
        }
        // Folge von 403 je Server: erst das dritte Bild in Folge pausiert
        for (sequence, expected) in pairs(f["forbiddenStreak"]) {
            var streak = 0
            let actual = ((sequence as? [Bool]) ?? []).map { forbidden -> Bool in
                streak = CoinLogoRetry.nextForbiddenStreak(streak, forbidden: forbidden)
                return CoinLogoRetry.forbiddenStreakPauses(streak)
            }
            XCTAssertEqual(actual, expected as? [Bool], "streak: \(sequence)")
        }
        for (retry, expected) in pairs(f["pauseMillis"]) {
            XCTAssertEqual(CoinLogoRetry.imagePauseMillis(retryAfterSeconds: (retry as? NSNumber)?.int64Value),
                           (expected as? NSNumber)?.int64Value, "pause: \(retry)")
        }
        for (url, expected) in pairs(f["host"]) {
            XCTAssertEqual(CoinLogoRetry.hostOf(url as? String ?? ""), expected as? String, "host: \(url)")
        }
        let p = try XCTUnwrap(f["pauses"] as? [String: Any])
        let now = try XCTUnwrap((p["now"] as? NSNumber)?.int64Value)
        let decoded = CoinLogoRetry.decodePauses(p["text"] as? String, now: now)
        let expected = try XCTUnwrap(p["expected"] as? [String: NSNumber]).mapValues(\.int64Value)
        XCTAssertEqual(decoded, expected)
        XCTAssertEqual(CoinLogoRetry.encodePauses(decoded), p["encoded"] as? String)
    }
}
