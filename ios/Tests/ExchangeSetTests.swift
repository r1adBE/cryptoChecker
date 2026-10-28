import XCTest
@testable import CryptoChecker

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus ExchangeBackoffParityTests

/// Pausen nach «zu viele Anfragen» — vor allem: eine unmögliche gespeicherte Pause (weiter als 6 h in der Zukunft)
/// zählt nirgends (`exchange_backoff.json`, geteilt mit Android). Die Fälle «merge» gibt es nur auf iOS.
/// Wie `ExchangeBackoffParityTest.kt`.
final class ExchangeBackoffParityTests: XCTestCase {
    private typealias S = TestSupport

    private func state(_ raw: Any?) -> ExchangeBackoff.State? {
        guard let s = raw as? [String: Any] else { return nil }
        return ExchangeBackoff.State(
            strikes: Int(S.number(s["strikes"]) ?? 0),
            pausedUntil: S.millis(s["pausedUntil"]),
            reason: (s["reason"] as? String).flatMap(RefreshFailure.init(rawValue:)),
            timeoutRuns: Int(S.number(s["timeoutRuns"]) ?? 0))
    }

    private func states(_ raw: Any?) -> [String: ExchangeBackoff.State] {
        (raw as? [String: Any] ?? [:]).compactMapValues { state($0) }
    }

    func testNextNachZuVielenAnfragen() throws {
        let data = try S.fixture("exchange_backoff")
        let now = S.millis(data["now"])
        for c in S.list(data["next"]) {
            let result = ExchangeBackoff.next(
                state(c["state"]), outcome: .rateLimited, now: now,
                retryAfterMillis: S.number(c["retryAfterMillis"]).map { Int64($0) },
                banned: c["banned"] as? Bool ?? false, shared: c["shared"] as? Bool ?? false)
            XCTAssertEqual(result, state(c["expected"]), c["name"] as? String ?? "")
        }
    }

    func testGemeinsamePause() throws {
        let data = try S.fixture("exchange_backoff")
        let now = S.millis(data["now"])
        for c in S.list(data["groupPause"]) {
            let result = ExchangeBackoff.groupPause(
                state(c["state"]), now: now, httpCode: Int(S.number(c["httpCode"]) ?? 0),
                retryAfterSeconds: S.number(c["retryAfterSeconds"]).map { Int64($0) })
            XCTAssertEqual(result, state(c["expected"]), c["name"] as? String ?? "")
        }
    }

    func testUebernommenePause() throws {
        let data = try S.fixture("exchange_backoff")
        let now = S.millis(data["now"])
        for c in S.list(data["adoptedPause"]) {
            let result = ExchangeBackoff.adoptedPause(state(c["old"]), until: S.millis(c["until"]), now: now)
            XCTAssertEqual(result, state(c["expected"]), c["name"] as? String ?? "")
        }
    }

    func testZusammenfuehrenNurIos() throws {
        let data = try S.fixture("exchange_backoff")
        let now = S.millis(data["now"])
        for c in S.list(data["merge"]) {
            let result = ExchangeBackoff.merge(ours: states(c["ours"]), initial: states(c["initial"]),
                                               stored: states(c["stored"]), now: now)
            XCTAssertEqual(result, states(c["expected"]), c["name"] as? String ?? "")
        }
    }
}

// ---- aus ExchangeErrorContractTests

/// Synthetische Fehlerantworten der Börsen (`exchange_errors_synthetic.json`, Quelle `android/testdata/exchanges`,
/// nach `Tests/Exchanges` kopiert): kaputtes JSON, leere Antworten, HTTP 429 und die Ratenbegrenzungen, die
/// `RateLimitSignal` kennt. Den Parser selbst prüft `ExchangeContractTests` (gewöhnliches `exchange_*.json`); hier
/// die Regeln darum herum: jeder Fall ist synthetisch gekennzeichnet, ergibt keinen Kurs, `httpStatus` + Antwort
/// ergeben genau dann 429, wenn `rateLimit` gilt, und `messageRateLimit` sagt, ob schon der Fehlertext der Börse
/// als «zu viele Anfragen» zählt. Wie `ExchangeErrorContractTest.kt`.
final class ExchangeErrorContractTests: XCTestCase {

    private typealias S = TestSupport

    private func cases() throws -> [[String: Any]] {
        let bundle = Bundle(for: ExchangeErrorContractTests.self)
        let url = try XCTUnwrap(bundle.url(forResource: "exchange_errors_synthetic", withExtension: "json"),
                                "exchange_errors_synthetic.json im Test-Bundle (tools/gen_xcodeproj.py ausführen)")
        let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
        return S.list(try XCTUnwrap(object as? [String: Any])["cases"])
    }

    private func pair(_ spec: [String: Any]) -> PairQuery {
        MarketPair(spec["base"] as? String ?? "", spec["quote"] as? String ?? "", spec["id"] as? String,
                   FuturesContractType(backupName: spec["contract"] as? String ?? "NONE"))
    }

    func testEveryCaseIsSyntheticAndYieldsNoPrice() throws {
        let all = try cases()
        XCTAssertGreaterThanOrEqual(all.count, 60, "synthetic error cases")
        for c in all {
            let name = c["name"] as? String ?? ""
            let expect = c["expect"] as? [String: Any] ?? [:]
            let isError = expect["error"] as? Bool == true
            let noPrice: Bool
            switch c["kind"] as? String {
            case "single": noPrice = isError
            case "bulk": noPrice = isError || S.number(expect["count"]) == 0
            default: noPrice = false
            }
            XCTAssertEqual(c["synthetic"] as? Bool, true, "\(name): not marked synthetic")
            XCTAssertTrue(noPrice, "\(name): an error response must not expect a price")
        }
    }

    func testRateLimitIsRecognisedExactlyWhereExpected() throws {
        for c in try cases() {
            let name = c["name"] as? String ?? ""
            let status = Int(S.number(c["httpStatus"]) ?? 0)
            let body = c["body"] as? String ?? ""
            let limited = RateLimitSignal.httpCode(status: status, body: body) == RateLimitSignal.httpCodeForRateLimit
            XCTAssertEqual(limited, c["rateLimit"] as? Bool, "\(name): rate limit")

            guard let wantText = c["messageRateLimit"] as? Bool else { continue }
            let market = try XCTUnwrap(MarketsConfig.market(c["market"] as? String ?? ""), name)
            let message = try? market.readError(call: Int(S.number(c["requestId"]) ?? 0), body: body,
                                                info: pair(c["pair"] as? [String: Any] ?? [:]))
            XCTAssertEqual(RateLimitSignal.inText(message), wantText,
                           "\(name): error text '\(String(describing: message))' as rate limit")
        }
    }
}

// ---- aus ExchangeVerificationTests

/// Datum «zuletzt gegen echte Antworten geprüft» passt zu den Aufnahmen — wie `ExchangeVerificationTest.kt`.
final class ExchangeVerificationTests: XCTestCase {

    func testLastVerifiedIsNotBeforeNewestRecording() throws {
        let bundle = Bundle(for: ExchangeVerificationTests.self)
        let urls = (bundle.urls(forResourcesWithExtension: "json", subdirectory: nil) ?? [])
            .filter { $0.lastPathComponent.hasPrefix("recorded_") }
        XCTAssertFalse(urls.isEmpty, "recorded responses")
        var days: [LocalDay] = []
        for url in urls {
            let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
            let at = try XCTUnwrap((object as? [String: Any])?["recordedAt"] as? String, url.lastPathComponent)
            // «2026-10-16T23:27:56Z» → UTC-Datum (die ersten 10 Zeichen)
            days.append(try XCTUnwrap(LocalDay(iso: String(at.prefix(10))), at))
        }
        let newest = try XCTUnwrap(days.max())
        let verified = ExchangeVerification.lastVerified
        XCTAssertFalse(verified < newest, "ExchangeVerification.lastVerified \(verified) is before the newest recording \(newest)")
        // Nicht in der Zukunft der Aufnahmen erfunden: höchstens einen Tag nach der neuesten (Aufnahme kurz vor Mitternacht)
        XCTAssertFalse(newest.plusDays(1) < verified)
    }
}
