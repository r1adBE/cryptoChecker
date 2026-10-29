import XCTest
@testable import CryptoChecker

/// Vertragstests der Börsen-Adapter — wie `ExchangeContractTest.kt`: dieselben Dateien
/// `exchange_*.json` (Quelle `android/testdata/exchanges`, vom Generator nach `Tests/Exchanges`
/// kopiert) mit Antworten im Format der Börsen (Einzel- und Sammelabfrage, WebSocket) und den
/// erwarteten Werten. Benennt eine Börse ein Feld um, schlagen Android und iOS gleich an.
///
/// Ablauf wie in der App (`ExchangeService.fetchTicker`): `readTicker`; wirft das oder ergibt es
/// keinen gültigen `last` (fehlt, 0, NaN/∞), gilt die Antwort als Fehler und `readError` liefert den Fehlertext
/// der Börse (oder wirft: dann gibt es keinen).
///
/// Dazu echte, datierte Antworten (`recorded_*.json`, Quelle `android/testdata/exchanges/recorded`,
/// nach `Tests/Exchanges/Recorded` kopiert), sofern jemand sie mit `tools/record_exchange_fixtures.py`
/// aufgezeichnet hat (`testRecordedResponsesStillParse`) — wie Android.
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

    private struct Recorded {
        let name: String
        let recordedAt: String?
        let cases: [[String: Any]]
    }

    /// Aufgezeichnete Antworten; leer, solange keine aufgezeichnet wurden (der Ordner ist optional).
    private func recordedFixtures() throws -> [Recorded] {
        let bundle = Bundle(for: ExchangeContractTests.self)
        let urls = (bundle.urls(forResourcesWithExtension: "json", subdirectory: nil) ?? [])
            .filter { $0.lastPathComponent.hasPrefix("recorded_") }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        return try urls.map { url in
            let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
            let data = try XCTUnwrap(object as? [String: Any], url.lastPathComponent)
            return Recorded(name: url.lastPathComponent, recordedAt: data["recordedAt"] as? String,
                            cases: S.list(data["cases"]))
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
            case "liquidityUsd": problem = check("\(prefix) liquidityUsd", value, t.liquidityUsd)
            case "markFallback":
                // Kurs ist der Mark-Preis, weil kein Mittelkurs kam (Hyperliquid)
                let want = value as? Bool
                problem = want == t.markFallback ? nil
                    : "\(prefix) markFallback: expected \(String(describing: want)), got \(t.markFallback)"
            case "timestamp":
                // Begrenzt wie die App (JSONValue.int64): Int64(Double) stürzte bei Werten ab 2^63 ab
                let want = JSONValue.int64(S.number(value) ?? 0)
                problem = want == t.timestamp ? nil : "\(prefix) timestamp: expected \(want), got \(t.timestamp)"
            case "exchangeTime":
                // null = die Börse liefert keine Zeit (timestamp ist dann die Abrufzeit)
                let want: Int64? = S.number(value).map { JSONValue.int64($0) }
                problem = want == t.exchangeTime ? nil
                    : "\(prefix) exchangeTime: expected \(String(describing: want)), got \(String(describing: t.exchangeTime))"
            case "exchangeTimeAgeMillis":
                // Börsenzeit als Abstand vor dem Abruf (Lighter ohne Handel in 24 h: spätestens 24 h zuvor)
                let want = JSONValue.int64(S.number(value) ?? 0)
                let age = t.exchangeTime.map { t.timestamp - $0 }
                problem = age.map { $0 >= want && $0 <= want + 60_000 } == true ? nil
                    : "\(prefix) exchangeTimeAgeMillis: expected \(want), got \(String(describing: age))"
            default: problem = "\(prefix): unknown expectation '\(key)'"
            }
            if let problem { return problem }
        }
        return nil
    }

    private func pair(_ spec: [String: Any]) -> PairQuery {
        ExchangePair(spec["base"] as? String ?? "", spec["quote"] as? String ?? "", spec["id"] as? String,
                         FuturesContractType(backupName: spec["contract"] as? String ?? "NONE"))
    }

    private func exchange(_ c: [String: Any]) -> Exchange? {
        ExchangesConfig.exchange(c["exchangeKey"] as? String ?? "")
    }

    private func runSingle(_ c: [String: Any]) -> String? {
        guard let exchange = exchange(c) else { return "unknown exchange \(c["exchangeKey"] ?? "")" }
        let info = pair(c["pair"] as? [String: Any] ?? [:])
        let requestId = Int(S.number(c["requestId"]) ?? 0)
        let body = c["body"] as? String ?? ""
        let expect = c["expect"] as? [String: Any] ?? [:]
        // Adresse der Einzelabfrage (optional): Laufzeit-Kontrakte mit dem Symbol der Paarliste, nie aus dem Kalender
        if let url = c["url"] as? String {
            let actual = exchange.tickerAddress(call: requestId, info: info)
            if actual != url { return "url: expected '\(url)', got '\(actual)'" }
        }

        var ticker = Ticker()
        var failure: Error?
        do {
            try exchange.readTicker(call: requestId, body: body, ticker: &ticker, info: info)
            if !(ticker.last.isFinite && ticker.last > 0) { failure = JSONError(message: "no ticker data") }
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
        // Abgelaufener Laufzeit-Kontrakt (optional): ab der Abrechnung bestätigt diese Antwort allein «nicht mehr
        // gehandelt» (`NotTraded.contractExpired` + `singleConfirms`), auch ohne Sammelabfrage — wie Android
        if let expiry = c["contractExpiry"] as? [String: Any] {
            let before = S.millis(expiry["before"])
            let after = S.millis(expiry["after"])
            if NotTraded.contractExpired(pairId: info.pairId, now: before) { return "contractExpiry: expired at \(before)" }
            if !NotTraded.contractExpired(pairId: info.pairId, now: after) { return "contractExpiry: not expired at \(after)" }
            // Wie die App (`ExchangeService.explained`): Lesefehler → Meldung der Börse
            let explained = ExchangeService.explained(failure, exchange: exchange, call: requestId, body: body, info: info)
            let confirms = NotTraded.singleConfirms(bulkTried: false, singleReadsFullList: false, contractExpired: true)
                && NotTraded.confirmedBySingle(gotPrice: false, error: ConnectionErrors.describe(explained),
                                               failure: ExchangeFailure.of(explained))
            if !confirms { return "contractExpiry: '\(failure)' does not confirm 'no longer traded'" }
        }
        let message: String?
        do {
            message = try exchange.readError(call: requestId, body: body, info: info)
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

    /// Ausweg des Einzelkurses nach einem Serverfehler (`singleFallback`): die Antwort der Ausweichadresse über
    /// `readTickerFallback`, geprüft wie ein Einzelkurs. Fehler = in der App bleibt der Serverfehler
    /// (`SingleTickerFallback`, dort samt «status» geprüft). Wie Android.
    private func runSingleFallback(_ c: [String: Any]) -> String? {
        guard let exchange = exchange(c) else { return "unknown exchange \(c["exchangeKey"] ?? "")" }
        let info = pair(c["pair"] as? [String: Any] ?? [:])
        let requestId = Int(S.number(c["requestId"]) ?? 0)
        let expect = c["expect"] as? [String: Any] ?? [:]
        guard let address = exchange.tickerFallbackAddress(call: requestId, info: info), !address.isEmpty else {
            return "no fallback address"
        }

        var ticker = Ticker()
        var failure: Error?
        do {
            try exchange.readTickerFallback(call: requestId, body: c["body"] as? String ?? "", ticker: &ticker, info: info)
            if !(ticker.last.isFinite && ticker.last > 0) { failure = JSONError(message: "no ticker data") }
        } catch {
            failure = error
        }
        if expect["error"] as? Bool != true {
            if let failure { return "expected a ticker, fallback parser failed: \(failure)" }
            return checkTicker("ticker", expect, ticker)
        }
        guard let failure else { return "expected an error, got last=\(ticker.last)" }
        if let thrown = expect["thrown"] as? String {
            let message = (failure as? JSONError)?.message
            if message != thrown { return "thrown: expected '\(thrown)', got '\(message ?? "\(failure)")'" }
        }
        return nil
    }

    private func runBulk(_ c: [String: Any]) -> String? {
        guard let exchange = exchange(c) else { return "unknown exchange \(c["exchangeKey"] ?? "")" }
        let requestId = Int(S.number(c["requestId"]) ?? 0)
        let expect = c["expect"] as? [String: Any] ?? [:]
        let wantsError = expect["error"] as? Bool == true

        let parsed: BulkTickerParse
        do {
            parsed = try exchange.parseBulkTickersDetailed(call: requestId, body: c["body"] as? String ?? "")
        } catch {
            return wantsError ? nil : "bulk parser failed: \(error)"
        }
        let tickers = parsed.tickers
        if wantsError { return "expected the batch to fail, got \(tickers.keys.sorted())" }
        // Unlesbare Zeilen werden je Paar gemeldet (`BulkTickerRows`), nicht still ausgelassen — wie Android
        let failed = Set(expect["failed"] as? [String] ?? [])
        if parsed.failed != failed { return "failed rows: expected \(failed.sorted()), got \(parsed.failed.sorted())" }
        // Antwort meldet sich selbst als lückenhaft (`Exchange.bulkResponseComplete`) — wie Android
        if let complete = expect["complete"] as? Bool, parsed.complete != complete {
            return "complete: expected \(complete), got \(parsed.complete)"
        }

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

    /// Paarliste: Anzahl, fehlende Kennungen und je Paar Basis, Kotierung, Vertragsart, TradFi.
    private func runPairs(_ c: [String: Any]) -> String? {
        guard let exchange = exchange(c) else { return "unknown exchange \(c["exchangeKey"] ?? "")" }
        let requestId = Int(S.number(c["requestId"]) ?? 0)
        let expect = c["expect"] as? [String: Any] ?? [:]

        let pairs: [ExchangePair]
        do {
            pairs = try exchange.readPairList(call: requestId, body: c["body"] as? String ?? "")
        } catch {
            return "pair parser failed: \(error)"
        }
        var byId: [String: ExchangePair] = [:]
        for p in pairs { byId[p.pairId ?? ""] = p }
        // Adresse der Anfrage (Abfrage-Parameter wie Binance `symbolStatus`)
        if expect.keys.contains("address") {
            let address = exchange.pairListAddress(call: requestId)
            let want = expect["address"] as? String
            if address != want { return "address: expected \(want ?? "nil"), got \(address ?? "nil")" }
        }
        // Folgeseite nach dieser Antwort, mit frischem Blätterzustand wie zu Beginn einer Synchronisierung
        if expect.keys.contains("nextPage") {
            let next: String?
            do {
                next = try exchange.pairListPaging().nextPage(call: requestId, body: c["body"] as? String ?? "")
            } catch {
                return "nextPage failed: \(error)"
            }
            let want = expect["nextPage"] as? String
            if next != want { return "nextPage: expected \(want ?? "nil"), got \(next ?? "nil")" }
        }

        if let count = S.number(expect["count"]), pairs.count != Int(count) {
            return "count: expected \(Int(count)), got \(pairs.count) \(byId.keys.sorted())"
        }
        for id in expect["absent"] as? [String] ?? [] where byId[id] != nil {
            return "pair '\(id)' should be absent"
        }
        for (id, raw) in (expect["pairs"] as? [String: Any] ?? [:]).sorted(by: { $0.key < $1.key }) {
            guard let p = byId[id] else { return "missing pair '\(id)' (have \(byId.keys.sorted()))" }
            if let problem = checkPair(id, raw as? [String: Any] ?? [:], p) { return problem }
        }
        return nil
    }

    private func checkPair(_ id: String, _ want: [String: Any], _ p: ExchangePair) -> String? {
        if let base = want["base"] as? String, p.base != base { return "[\(id)] base: expected \(base), got \(p.base)" }
        if let quote = want["quote"] as? String, p.quote != quote { return "[\(id)] quote: expected \(quote), got \(p.quote)" }
        if let contract = want["contract"] as? String, p.contractType != FuturesContractType(backupName: contract) {
            return "[\(id)] contract: expected \(contract), got \(p.contractType)"
        }
        if let tradFi = want["tradFi"] as? Bool, p.isTradFi != tradFi { return "[\(id)] tradFi: expected \(tradFi), got \(p.isTradFi)" }
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

    /// Ein aufgezeichneter Fall. Echte Kurse ändern sich, darum keine Sollwerte, nur Plausibilität:
    /// Einzelkurs liest ohne Fehler mit `last` > 0, Sammelabfrage liefert Kurse, Paarliste enthält
    /// das Paar des Einzelkurses (`contains`) — wie Android `runRecorded`. Beim Einzelkurs dazu die Werte selbst
    /// (`checkRecordedValues`), gemessen am Aufnahmezeitpunkt `recordedAt` (ms, nil = unbekannt).
    private func runRecorded(_ c: [String: Any], recordedAt: Int64?) -> String? {
        guard let exchange = exchange(c) else { return "unknown exchange \(c["exchangeKey"] ?? "")" }
        let requestId = Int(S.number(c["requestId"]) ?? 0)
        let body = c["body"] as? String ?? ""
        switch c["kind"] as? String ?? "" {
        case "single":
            var ticker = Ticker()
            do {
                try exchange.readTicker(call: requestId, body: body, ticker: &ticker, info: pair(c["pair"] as? [String: Any] ?? [:]))
            } catch {
                return "parser failed: \(error)"
            }
            guard ticker.last > 0 else { return "no positive last price (\(ticker.last))" }
            return checkRecordedValues(ticker, recordedAt: recordedAt)
        case "bulk":
            do {
                let parsed = try exchange.parseBulkTickersDetailed(call: requestId, body: body)
                return parsed.tickers.isEmpty ? "batch without tickers (failed rows: \(parsed.failed.count))" : nil
            } catch {
                return "bulk parser failed: \(error)"
            }
        case "pairs":
            let pairs: [ExchangePair]
            do {
                pairs = try exchange.readPairList(call: requestId, body: body)
            } catch {
                return "pair parser failed: \(error)"
            }
            if pairs.isEmpty { return "empty pair list" }
            if let id = c["contains"] as? String, !pairs.contains(where: { $0.pairId == id }) {
                return "pair list lacks '\(id)' (\(pairs.count) pairs)"
            }
            return nil
        case let kind:
            return "unknown kind \(kind)"
        }
    }

    /// Spielraum nach der Aufnahme: `recordedAt` ist der Beginn des ganzen Laufs, die Börsen werden danach der
    /// Reihe nach abgefragt (Upbit am 2026-10-16 65 s später). 5 min fangen jede Zeitzonen-Verschiebung (≥ 1 h).
    private static let recordedFutureSlackMillis: Int64 = 5 * 60 * 1000
    /// Älter als 30 Tage ist für einen gehandelten Kurs keine plausible letzte Handelszeit mehr.
    private static let recordedMaxAgeMillis: Int64 = 30 * 24 * 60 * 60 * 1000

    /// Werte eines aufgezeichneten Einzelkurses: Eine Börsenzeit liegt höchstens 30 Tage vor und
    /// kurz nach der Aufnahme (eine Zeitzone als Epoche geschrieben, wie Bithumbs KST-Zeit, fiele sonst erst beim
    /// Nutzer auf). Liefert die Börse Hoch und Tief, liegt der Kurs dazwischen (kleine Toleranz für Rundung) —
    /// sonst gehörten Hoch/Tief zu einem anderen Zeitraum als der Kurs. Nicht gelieferte Felder zählen nicht.
    /// Wie Android `checkRecordedValues`.
    private func checkRecordedValues(_ ticker: Ticker, recordedAt: Int64?) -> String? {
        if let time = ticker.exchangeTime, let recordedAt {
            if time > recordedAt + Self.recordedFutureSlackMillis {
                return "exchangeTime \(time) lies after recordedAt \(recordedAt)"
            }
            if time < recordedAt - Self.recordedMaxAgeMillis {
                return "exchangeTime \(time) lies more than 30 days before recordedAt \(recordedAt)"
            }
        }
        let (high, low) = (ticker.high, ticker.low)
        guard high.isFinite, high > 0, low.isFinite, low > 0 else { return nil }
        let slack = high * 1e-9
        if low > high + slack { return "low \(low) above high \(high)" }
        if ticker.last < low - slack || ticker.last > high + slack {
            return "last \(ticker.last) outside low \(low) … high \(high)"
        }
        return nil
    }

    /// Aufnahmezeitpunkt in ms; nil, wenn er fehlt oder sich nicht lesen lässt.
    private func recordedMillis(_ recordedAt: String?) -> Int64? {
        guard let recordedAt, let date = ISO8601DateFormatter().date(from: recordedAt) else { return nil }
        return Int64(date.timeIntervalSince1970 * 1000)
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
                case "singleFallback": problem = runSingleFallback(c)
                case "bulk": problem = runBulk(c)
                case "live": problem = runLive(c)
                case "pairs": problem = runPairs(c)
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

    /// Aufgezeichnete echte Antworten (`tools/record_exchange_fixtures.py`, Aktion «Record exchange
    /// fixtures»), Pflicht (liegen im Repo). `recordedAt` ordnet
    /// einen Fehlschlag einem Stand der Börse zu. Wie Android `recordedResponsesStillParse`.
    func testRecordedResponsesStillParse() throws {
        let files = try recordedFixtures()
        // Pflicht: die Aufzeichnungen liegen im Test-Bundle (gen_xcodeproj.py); fehlen sie, nicht still grün
        guard !files.isEmpty else { return XCTFail("keine recorded_*.json im Test-Bundle (gen_xcodeproj.py gelaufen?)") }
        for file in files {
            let recordedAt = file.recordedAt ?? ""
            if recordedAt.isEmpty { XCTFail("\(file.name): missing recordedAt") }
            let recordedAtMillis = recordedMillis(file.recordedAt)
            for c in file.cases {
                if let problem = runRecorded(c, recordedAt: recordedAtMillis) {
                    XCTFail("\(file.name) › \(c["kind"] ?? "") (recorded \(recordedAt)): \(problem)")
                }
            }
        }
    }

    /// Bithumb schreibt in «trade_timestamp» die koreanische Uhrzeit als Epoche (9 h zu spät). Die gelesene
    /// Börsenzeit der echten Antwort liegt darum höchstens 60 s nach der Aufnahme (Bithumb wird früh im Lauf
    /// abgefragt) und nicht weiter als eine Stunde davor. Wie Android `recordedBithumbTradeTimeIsUtc`.
    func testRecordedBithumbTradeTimeIsUtc() throws {
        let file = try XCTUnwrap(try recordedFixtures().first(where: { $0.name == "recorded_bithumb.json" }),
                                 "recorded_bithumb.json fehlt im Test-Bundle")
        let recordedAt = try XCTUnwrap(recordedMillis(file.recordedAt))
        let single = try XCTUnwrap(file.cases.first { $0["kind"] as? String == "single" })
        let exchange = try XCTUnwrap(ExchangesConfig.exchange("Bithumb"))
        var ticker = Ticker()
        try exchange.readTicker(call: 0, body: single["body"] as? String ?? "", ticker: &ticker,
                              info: pair(single["pair"] as? [String: Any] ?? [:]))
        let time = try XCTUnwrap(ticker.exchangeTime, "Bithumb: keine Börsenzeit gelesen")
        XCTAssertLessThanOrEqual(time, recordedAt + 60_000, "Bithumb: Börsenzeit nach Aufnahme + 60 s")
        XCTAssertGreaterThanOrEqual(time, recordedAt - 3_600_000, "Bithumb: Börsenzeit über 1 h vor Aufnahme")
    }

    /// Jede registrierte Börse braucht mindestens einen Einzelabruf-Fall (und einen Sammelabruf-Fall,
    /// wenn sie eine Sammelabfrage hat) — neue Adapter fallen sonst auf.
    func testEveryRegisteredExchangeHasAContractFixture() throws {
        var single = Set<String>()
        var bulk = Set<String>()
        for file in try fixtures() {
            for c in file.cases {
                guard let key = c["exchangeKey"] as? String else { continue }
                if c["kind"] as? String == "single" { single.insert(key) }
                if c["kind"] as? String == "bulk" { bulk.insert(key) }
            }
        }
        let missing = ExchangesConfig.all.map(\.key).filter { !single.contains($0) }.sorted()
        XCTAssertEqual(missing, [], "exchanges without a single-ticker contract case")
        let bulkMissing = ExchangesConfig.all.filter { $0.bulkTickersNumOfRequests > 0 && !bulk.contains($0.key) }
            .map(\.key).sorted()
        XCTAssertEqual(bulkMissing, [], "exchanges with a batch endpoint but no bulk contract case")
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
