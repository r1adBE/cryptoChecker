import XCTest
@testable import CryptoChecker

/// Gemini: Symbole, die die Liste nicht trennen kann, holen Basis/Quote aus /v1/symbols/details als Folgeseiten der
/// Paarliste (keine vertauschte Schätzung; über der Grenze ungeklärt bis zur nächsten Synchronisierung; Warteschlange je
/// Synchronisierung, nicht in der geteilten Börse) — wie `GeminiDetailsTest.kt`.
final class GeminiDetailsTests: XCTestCase {

    func testAmbiguousSymbolsComeFromDetailsPages() throws {
        let exchange = Gemini()
        let listBody = #"["btcusd","btcgusd","mogusd","abcgusd","ethusd"]"#
        let list = try exchange.readPairList(call: 0, body: listBody)
        // Mehrdeutig (gusd/usd) → immer Details, auch btcgusd trotz bekanntem BTC: nichts geraten
        XCTAssertEqual(list.map { $0.pairId ?? "" }, ["btcusd", "ethusd"])
        let paging = exchange.pairListPaging()
        XCTAssertEqual(try paging.nextPage(call: 0, body: listBody),
                       "https://api.gemini.com/v1/symbols/details/btcgusd")
        // Details entscheiden: BTC/GUSD
        let btc = #"{"symbol":"BTCGUSD","base_currency":"BTC","quote_currency":"GUSD","status":"open"}"#
        let btcPair = try exchange.readPairList(call: 0, body: btc)
        XCTAssertEqual(btcPair.count, 1)
        XCTAssertEqual(btcPair.first?.pairId, "btcgusd")
        XCTAssertEqual(btcPair.first?.base, "BTC")
        XCTAssertEqual(btcPair.first?.quote, "GUSD")
        XCTAssertEqual(try paging.nextPage(call: 0, body: btc),
                       "https://api.gemini.com/v1/symbols/details/mogusd")
        // Fehlerantwort: nicht raten — mogusd bleibt ungeklärt (fehlt in der Paarliste)
        let error = #"{"result":"error","reason":"InvalidSymbol","message":"x"}"#
        let mog = try exchange.readPairList(call: 0, body: error)
        XCTAssertTrue(mog.isEmpty)
        XCTAssertEqual(try paging.nextPage(call: 0, body: error),
                       "https://api.gemini.com/v1/symbols/details/abcgusd")
        // Details entscheiden: ABC/GUSD (die Liste hätte ABCG/USD gesagt)
        let detail = #"{"symbol":"ABCGUSD","base_currency":"ABC","quote_currency":"GUSD","status":"open"}"#
        let abc = try exchange.readPairList(call: 0, body: detail)
        XCTAssertEqual(abc.count, 1)
        XCTAssertEqual(abc.first?.base, "ABC")
        XCTAssertEqual(abc.first?.quote, "GUSD")
        XCTAssertNil(try paging.nextPage(call: 0, body: detail))
    }

    func testConcurrentSyncsKeepTheirOwnQueue() throws {
        // Zwei Synchronisierungen derselben (geteilten) Börse greifen ineinander: jede behält ihre Warteschlange
        let exchange = Gemini()
        let listA = #"["btcgusd","mogusd"]"#
        let listB = #"["abcgusd"]"#
        let syncA = exchange.pairListPaging()
        let syncB = exchange.pairListPaging()
        XCTAssertEqual(try syncA.nextPage(call: 0, body: listA), "https://api.gemini.com/v1/symbols/details/btcgusd")
        XCTAssertEqual(try syncB.nextPage(call: 0, body: listB), "https://api.gemini.com/v1/symbols/details/abcgusd")
        let btc = #"{"symbol":"BTCGUSD","base_currency":"BTC","quote_currency":"GUSD"}"#
        XCTAssertEqual(try syncA.nextPage(call: 0, body: btc), "https://api.gemini.com/v1/symbols/details/mogusd")
        let abc = #"{"symbol":"ABCGUSD","base_currency":"ABC","quote_currency":"GUSD"}"#
        XCTAssertNil(try syncB.nextPage(call: 0, body: abc))
        let mog = #"{"symbol":"MOGUSD","base_currency":"MOG","quote_currency":"USD"}"#
        XCTAssertNil(try syncA.nextPage(call: 0, body: mog))
    }

    func testExchangesWithoutOwnStatePageFromTheResponse() throws {
        // Bybit: Cursor steht in der Antwort — der Blätterzustand leitet nur an pairListNextPage weiter
        let body = #"{"retCode":0,"result":{"category":"spot","list":[],"nextPageCursor":"abc"}}"#
        XCTAssertEqual(try Bybit().pairListPaging().nextPage(call: 0, body: body),
                       "https://api.bybit.com/v5/market/instruments-info?category=spot&limit=1000&cursor=abc")
    }

    func testUnreadableCursorPageFailsInsteadOfStopping() throws {
        // Bybit: Eine unlesbare Seite wirft — still aufhören liesse die Paare der weiteren Seiten unbemerkt weg.
        // Lesbar ohne «result» oder ohne Cursor: letzte Seite. Wie `GeminiDetailsTest.kt`.
        XCTAssertThrowsError(try Bybit().pairListPaging().nextPage(call: 0, body: "<html>502 Bad Gateway</html>"))
        XCTAssertThrowsError(try Bybit().pairListPaging().nextPage(call: 0, body: "[]"))
        XCTAssertNil(try Bybit().pairListPaging().nextPage(call: 0, body: #"{"retCode":10001,"retMsg":"x"}"#))
        XCTAssertNil(try Bybit().pairListPaging().nextPage(call: 0, body: #"{"retCode":0,"result":{"nextPageCursor":""}}"#))
    }

    func testOneKnownBaseDoesNotShortcutTheSplit() {
        // «mousd» macht MO bekannt — «mogusd» darf deshalb nicht MO/GUSD werden, sondern geht über die Details
        let plan = Gemini.planSymbols(["mousd", "mogusd"])
        XCTAssertEqual(plan.direct.map { $0.symbol }, ["mousd"])
        XCTAssertEqual(plan.deferred.map { $0.symbol }, ["mogusd"])
    }

    private static let overflowSymbols: [String] = (1...12).map { i -> String in
        let letter = Character(UnicodeScalar(UInt8(97 + i)))
        return "x\(letter)gusd"
    }

    func testDetailsCappedByPageLimit() throws {
        let plan = Gemini.planSymbols(Self.overflowSymbols)
        XCTAssertEqual(plan.deferred.count, Gemini.maxDetails)
        // Über der Grenze: nie geraten — ungeklärt, nicht in der Paarliste
        XCTAssertEqual(plan.direct.count, 0)
        XCTAssertEqual(plan.unresolved.count, 12 - Gemini.maxDetails)
        // Perpetual in den Details: kein Spot-Paar
        let perp = try JObject(string: #"{"symbol":"BTCGUSDPERP","base_currency":"BTC","quote_currency":"GUSD","product_type":"swap"}"#)
        XCTAssertNil(Gemini.detailPair(perp))
    }

    /// Eine Synchronisierung: Liste, dann alle Detail-Folgeseiten. Ergebnis: Paare und Zahl der Detail-Abfragen.
    private func sync(_ exchange: Gemini, _ listBody: String) throws -> (pairs: [ExchangePair], asked: Int) {
        var pairs: [ExchangePair] = try exchange.readPairList(call: 0, body: listBody)
        let paging = exchange.pairListPaging()
        var url: String? = try paging.nextPage(call: 0, body: listBody)
        var asked = 0
        while let current = url {
            let symbol: String = String(current.split(separator: "/").last ?? "")
            let base: String = String(symbol.dropLast(4)).uppercased()
            let body = "{\"symbol\":\"\(symbol.uppercased())\",\"base_currency\":\"\(base)\",\"quote_currency\":\"GUSD\"}"
            pairs += try exchange.readPairList(call: 0, body: body)
            url = try paging.nextPage(call: 0, body: body)
            asked += 1
        }
        return (pairs: pairs, asked: asked)
    }

    func testOverflowResolvedByLaterSync() throws {
        let exchange = Gemini()
        let listBody = "[" + Self.overflowSymbols.map { "\"\($0)\"" }.joined(separator: ",") + "]"
        // 1. Synchronisierung: 9 Details, die übrigen 3 fehlen (ungeklärt statt geraten)
        let first = try sync(exchange, listBody)
        XCTAssertEqual(first.asked, Gemini.maxDetails)
        XCTAssertEqual(first.pairs.count, Gemini.maxDetails)
        XCTAssertTrue(first.pairs.allSatisfy { $0.quote == "GUSD" })
        // 2. Synchronisierung: die 9 aus dem Zwischenspeicher, die 3 übrigen über Details — danach vollständig
        let second = try sync(exchange, listBody)
        XCTAssertEqual(second.asked, 12 - Gemini.maxDetails)
        XCTAssertEqual(Set(second.pairs.compactMap { $0.pairId }), Set(Self.overflowSymbols))
        XCTAssertTrue(second.pairs.allSatisfy { $0.quote == "GUSD" && $0.base.count == 2 })
    }

    func testDetailsCachedBySymbolNotCursor() throws {
        // Antworten versetzt (zuerst ABCGUSD, obwohl btcgusd gefragt war): Ablage unter dem Symbol der Antwort
        let exchange = Gemini()
        let listBody = #"["btcgusd","abcgusd"]"#
        _ = try exchange.readPairList(call: 0, body: listBody)
        let paging = exchange.pairListPaging()
        _ = try paging.nextPage(call: 0, body: listBody)
        let abc = #"{"symbol":"ABCGUSD","base_currency":"ABC","quote_currency":"GUSD"}"#
        _ = try exchange.readPairList(call: 0, body: abc)
        _ = try paging.nextPage(call: 0, body: abc)
        let btc = #"{"symbol":"BTCGUSD","base_currency":"BTC","quote_currency":"GUSD"}"#
        _ = try exchange.readPairList(call: 0, body: btc)
        // Nächste Synchronisierung aus dem Zwischenspeicher: jedes Symbol mit seinen eigenen Details
        let cached = try exchange.readPairList(call: 0, body: listBody)
        var bases: [String: String] = [:]
        for pair in cached { bases[pair.pairId ?? ""] = pair.base }
        XCTAssertEqual(bases, ["btcgusd": "BTC", "abcgusd": "ABC"])
    }

    func testHyperliquidReportsMidPriceWithoutExchangeTime() {
        // Mittelkurs statt letztem Handel: das Aktionsblatt nennt «Mittelkurs»; Zeit = Abruf (Fixture: exchangeTime null)
        XCTAssertTrue(Hyperliquid().priceIsMid)
        XCTAssertFalse(Gemini().priceIsMid)
    }
}
