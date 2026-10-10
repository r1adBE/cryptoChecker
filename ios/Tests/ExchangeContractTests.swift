import XCTest
@testable import CryptoChecker

/// Vertragstests der Börsen-Adapter — wie `ExchangeContractTest.kt`: dieselben Dateien
/// `exchange_*.json` (Quelle `android/testdata/exchanges`, vom Generator nach `Tests/Exchanges`
/// kopiert) mit Antworten im Format der Börsen (Einzel- und Sammelabfrage, WebSocket) und den
/// erwarteten Werten. Benennt eine Börse ein Feld um, schlagen Android und iOS gleich an.
///
/// Ablauf wie in der App (`MarketHTTP.updateTicker`): `parseTickerMain`; wirft das oder bleibt
/// `last` bei `noData`, gilt die Antwort als Fehler und `parseErrorMain` liefert den Fehlertext
/// der Börse (oder wirft: dann gibt es keinen).
///
/// `knownIssue` mit Plattform „ios“: bekannte Abweichung, läuft unter `XCTExpectFailure`
/// (nicht strikt — ist der Fehler behoben, bleibt der Test grün; dann den Eintrag entfernen).
final class ExchangeContractTests: XCTestCase {

    private typealias S = TestSupport

    private struct Fixture {
        let name: String
        let cases: [[String: Any]]
    }

    private func fixtures() throws -> [Fixture] {
        let bundle = Bundle(for: ExchangeContractTests.self)
        let urls = (bundle.urls(forResourcesWithExtension: "json", subdirectory: nil) ?? [])
            .filter { $0.lastPathComponent.hasPrefix("exchange_") }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        return try urls.map { url in
            let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
            let data = try XCTUnwrap(object as? [String: Any], url.lastPathComponent)
            return Fixture(name: url.lastPathComponent, cases: S.list(data["cases"]))
        }
    }

    private func close(_ expected: Double, _ actual: Double) -> Bool {
        if expected == actual { return true }
        return abs(expected - actual) <= max(abs(expected) * 1e-9, 1e-15)
    }

    /// nil = in Ordnung, sonst Beschreibung der Abweichung.
    private func check(_ label: String, _ expected: Any?, _ actual: Double?) -> String? {
        guard let want = S.number(expected) else {
            return actual == nil ? nil : "\(label): expected null, got \(actual!)"
        }
        guard let actual, close(want, actual) else { return "\(label): expected \(want), got \(String(describing: actual))" }
        return nil
    }

    /// Vergleicht nur die Felder, die der Fall nennt.
    private func checkTicker(_ prefix: String, _ expect: [String: Any], _ t: Ticker) -> String? {
        for key in expect.keys.sorted() {
            let value = expect[key]
            let problem: String?
            switch key {
            case "last": problem = check("\(prefix) last", value, t.last)
            case "bid": problem = check("\(prefix) bid", value, t.bid)
            case "ask": problem = check("\(prefix) ask", value, t.ask)
            case "high": problem = check("\(prefix) high", value, t.high)
            case "low": problem = check("\(prefix) low", value, t.low)
            case "vol": problem = check("\(prefix) vol", value, t.vol)
            case "volQuote": problem = check("\(prefix) volQuote", value, t.volQuote)
            case "change24hPercent": problem = check("\(prefix) change24hPercent", value, t.change24hPercent)
            case "timestamp":
                // Begrenzt wie die App (JSONValue.int64): Int64(Double) stürzte bei Werten ab 2^63 ab
                let want = JSONValue.int64(S.number(value) ?? 0)
                problem = want == t.timestamp ? nil : "\(prefix) timestamp: expected \(want), got \(t.timestamp)"
            default: problem = "\(prefix): unknown expectation '\(key)'"
            }
            if let problem { return problem }
        }
        return nil
    }

    private func pair(_ spec: [String: Any]) -> CheckerInfo {
        CurrencyPairInfo(spec["base"] as? String ?? "", spec["quote"] as? String ?? "", spec["id"] as? String,
                         FuturesContractType(backupName: spec["contract"] as? String ?? "NONE"))
    }

    private func market(_ c: [String: Any]) -> Market? {
        MarketsConfig.market(c["market"] as? String ?? "")
    }

    private func runSingle(_ c: [String: Any]) -> String? {
        guard let market = market(c) else { return "unknown market \(c["market"] ?? "")" }
        let info = pair(c["pair"] as? [String: Any] ?? [:])
        let requestId = Int(S.number(c["requestId"]) ?? 0)
        let body = c["body"] as? String ?? ""
        let expect = c["expect"] as? [String: Any] ?? [:]

        var ticker = Ticker()
        var failure: Error?
        do {
            try market.parseTickerMain(requestId: requestId, response: body, ticker: &ticker, info: info)
            if ticker.last <= Ticker.noData { failure = JSONError(message: "no ticker data") }
        } catch {
            failure = error
        }

        if expect["error"] as? Bool != true {
            if let failure { return "expected a ticker, parser failed: \(failure)" }
            return checkTicker("ticker", expect, ticker)
        }

        guard let failure else { return "expected an error, got last=\(ticker.last)" }
        if let thrown = expect["thrown"] as? String {
            let message = (failure as? JSONError)?.message
            if message != thrown { return "thrown: expected '\(thrown)', got '\(message ?? "\(failure)")'" }
        }
        let message: String?
        do {
            message = try market.parseErrorMain(requestId: requestId, response: body, info: info)
        } catch {
            message = nil
        }
        let wanted: String?
        if let prefix = S.number(expect["messageBodyPrefix"]) {
            wanted = String(body.prefix(Int(prefix)))
        } else {
            wanted = expect["message"] as? String
        }
        return message == wanted ? nil : "error text: expected '\(wanted ?? "nil")', got '\(message ?? "nil")'"
    }

    private func runBulk(_ c: [String: Any]) -> String? {
        guard let market = market(c) else { return "unknown market \(c["market"] ?? "")" }
        let requestId = Int(S.number(c["requestId"]) ?? 0)
        let expect = c["expect"] as? [String: Any] ?? [:]
        let wantsError = expect["error"] as? Bool == true

        let tickers: [String: Ticker]
        do {
            tickers = try market.parseBulkTickersMain(requestId: requestId, response: c["body"] as? String ?? "")
        } catch {
            return wantsError ? nil : "bulk parser failed: \(error)"
        }
        if wantsError { return "expected the batch to fail, got \(tickers.keys.sorted())" }

        if let count = S.number(expect["count"]), tickers.count != Int(count) {
            return "count: expected \(Int(count)), got \(tickers.count) \(tickers.keys.sorted())"
        }
        for id in expect["absent"] as? [String] ?? [] where tickers[id] != nil {
            return "'\(id)' should be absent"
        }
        for (id, fields) in (expect["tickers"] as? [String: Any] ?? [:]).sorted(by: { $0.key < $1.key }) {
            guard let t = tickers[id] else { return "missing ticker '\(id)' (have \(tickers.keys.sorted()))" }
            if let problem = checkTicker("[\(id)]", fields as? [String: Any] ?? [:], t) { return problem }
        }
        return nil
    }

    private func runLive(_ c: [String: Any]) -> String? {
        guard let exchange = LiveExchange(rawValue: c["exchange"] as? String ?? "") else {
            return "unknown live exchange \(c["exchange"] ?? "")"
        }
        let ticks = LiveParser.parse(exchange, c["message"] as? String ?? "")
        let expected = S.list(c["ticks"])
        if ticks.count != expected.count { return "tick count: expected \(expected.count), got \(ticks)" }
        for (i, want) in expected.enumerated() {
            let got = ticks[i]
            if got.symbol != want["symbol"] as? String {
                return "tick \(i) symbol: expected \(want["symbol"] ?? ""), got \(got.symbol)"
            }
            if let problem = check("tick \(i) price", want["price"], got.price) { return problem }
            if let problem = check("tick \(i) change24h", want["change24h"], got.change24h) { return problem }
            let time = S.number(want["time"]).map { JSONValue.int64($0) }
            if time != got.time { return "tick \(i) time: expected \(String(describing: time)), got \(String(describing: got.time))" }
        }
        return nil
    }

    /// Bekannte Abweichung auf iOS? Dann der Hinweis aus der Datei.
    private func knownIssueOnIOS(_ c: [String: Any]) -> String? {
        guard let issue = c["knownIssue"] as? [String: Any] else { return nil }
        let platforms = issue["platforms"] as? [String] ?? ["ios"]
        return platforms.contains("ios") ? (issue["note"] as? String ?? "known issue") : nil
    }

    func testExchangeResponsesParseAsDocumented() throws {
        let files = try fixtures()
        XCTAssertGreaterThanOrEqual(files.count, 25, "exchange_*.json im Test-Bundle (tools/gen_xcodeproj.py ausführen)")

        var cases = 0
        var kinds: [String: Int] = [:]
        for file in files {
            for c in file.cases {
                let kind = c["kind"] as? String ?? ""
                let label = "\(file.name) › \(c["name"] ?? "")"
                cases += 1
                kinds[kind, default: 0] += 1
                let problem: String?
                switch kind {
                case "single": problem = runSingle(c)
                case "bulk": problem = runBulk(c)
                case "live": problem = runLive(c)
                default: problem = "unknown kind \(kind)"
                }
                guard let problem else { continue }
                if let note = knownIssueOnIOS(c) {
                    let options = XCTExpectedFailure.Options()
                    options.isStrict = false
                    XCTExpectFailure("known issue: \(note)", options: options) {
                        XCTFail("\(label): \(problem)")
                    }
                } else {
                    XCTFail("\(label): \(problem)")
                }
            }
        }
        XCTAssertGreaterThanOrEqual(cases, 150, "cases read")
        XCTAssertGreaterThanOrEqual(kinds["single"] ?? 0, 80, "single cases")
        XCTAssertGreaterThanOrEqual(kinds["bulk"] ?? 0, 30, "bulk cases")
        XCTAssertGreaterThanOrEqual(kinds["live"] ?? 0, 15, "live cases")
    }

    /// Jede registrierte Börse braucht mindestens einen Einzelabruf-Fall (und einen Sammelabruf-Fall,
    /// wenn sie eine Sammelabfrage hat) — neue Adapter fallen sonst auf.
    func testEveryRegisteredMarketHasAContractFixture() throws {
        var single = Set<String>()
        var bulk = Set<String>()
        for file in try fixtures() {
            for c in file.cases {
                guard let key = c["market"] as? String else { continue }
                if c["kind"] as? String == "single" { single.insert(key) }
                if c["kind"] as? String == "bulk" { bulk.insert(key) }
            }
        }
        let missing = MarketsConfig.all.map(\.key).filter { !single.contains($0) }.sorted()
        XCTAssertEqual(missing, [], "markets without a single-ticker contract case")
        let bulkMissing = MarketsConfig.all.filter { $0.bulkTickersNumOfRequests > 0 && !bulk.contains($0.key) }
            .map(\.key).sorted()
        XCTAssertEqual(bulkMissing, [], "markets with a batch endpoint but no bulk contract case")
    }

    /// Jede Börse mit WebSocket hat mindestens einen Live-Fall.
    func testEveryLiveExchangeHasAContractFixture() throws {
        var covered = Set<String>()
        for file in try fixtures() {
            for c in file.cases where c["kind"] as? String == "live" {
                covered.insert(c["exchange"] as? String ?? "")
            }
        }
        XCTAssertEqual(LiveExchange.allCases.map(\.rawValue).filter { !covered.contains($0) }, [],
                       "live exchanges without a contract case")
    }
}
