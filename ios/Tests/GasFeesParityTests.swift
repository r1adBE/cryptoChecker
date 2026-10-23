import XCTest
@testable import CryptoChecker

/// Netzwerkgebühren (`Parity/gas_fees.json`): eth_feeHistory (Grundgebühr des nächsten Blocks plus Median der
/// Trinkgelder, unlesbare Einträge übersprungen), eth_gasPrice, mempool.space, Überweisungskosten und Anzeige.
/// Gegenstück: `GasFeesParityTest.kt`.
final class GasFeesParityTests: XCTestCase {
    private typealias S = TestSupport

    /// Relativ verglichen: Werte von 1e-9 gwei (1 wei) bis 1e11 gwei.
    private func assertClose(_ actual: Double?, _ expected: Double?, tolerance: Double, _ name: String,
                             file: StaticString = #filePath, line: UInt = #line) {
        S.assertNumber(actual, expected, accuracy: tolerance * max(abs(expected ?? 0), 1e-12), name, file: file, line: line)
    }

    /// Fehlerfall: muss werfen (nil zurück); sonst das Ergebnis.
    private func parsed<T>(_ c: [String: Any], _ name: String, _ parse: () throws -> T) throws -> T? {
        if c["error"] as? Bool == true {
            XCTAssertThrowsError(try parse(), "\(name): Fehler erwartet")
            return nil
        }
        return try parse()
    }

    func testFeeHistoryWieGemeinsameTestfaelle() throws {
        let data = try S.fixture("gas_fees")
        let tolerance = try XCTUnwrap(S.number(data["tolerance"]))
        let cases = S.list(data["feeHistory"])
        XCTAssertGreaterThanOrEqual(cases.count, 20)
        for c in cases {
            let name = c["description"] as? String ?? ""
            let response = try XCTUnwrap(c["response"] as? String)
            guard let fees = try parsed(c, name, { try GasFees.parseFeeHistory(response) }) else { continue }
            assertClose(fees.0, S.number(c["slow"]), tolerance: tolerance, "\(name) langsam")
            assertClose(fees.1, S.number(c["normal"]), tolerance: tolerance, "\(name) normal")
            assertClose(fees.2, S.number(c["fast"]), tolerance: tolerance, "\(name) schnell")
        }
    }

    func testGasPriceWieGemeinsameTestfaelle() throws {
        let data = try S.fixture("gas_fees")
        let tolerance = try XCTUnwrap(S.number(data["tolerance"]))
        for c in S.list(data["gasPrice"]) {
            let name = c["description"] as? String ?? ""
            let response = try XCTUnwrap(c["response"] as? String)
            guard let gwei = try parsed(c, name, { try GasFees.parseGasPrice(response) }) else { continue }
            assertClose(gwei, S.number(c["gwei"]), tolerance: tolerance, name)
        }
    }

    func testMempoolWieGemeinsameTestfaelle() throws {
        let data = try S.fixture("gas_fees")
        let tolerance = try XCTUnwrap(S.number(data["tolerance"]))
        for c in S.list(data["mempool"]) {
            let name = c["description"] as? String ?? ""
            let response = try XCTUnwrap(c["response"] as? String)
            guard let fees = try parsed(c, name, { try GasFees.parseMempool(response) }) else { continue }
            assertClose(fees.0, S.number(c["fast"]), tolerance: tolerance, "\(name) schnell")
            assertClose(fees.1, S.number(c["normal"]), tolerance: tolerance, "\(name) normal")
            assertClose(fees.2, S.number(c["slow"]), tolerance: tolerance, "\(name) langsam")
        }
    }

    func testUeberweisungskostenWieGemeinsameTestfaelle() throws {
        let data = try S.fixture("gas_fees")
        let tolerance = try XCTUnwrap(S.number(data["tolerance"]))
        for c in S.list(data["evmTransferUsd"]) {
            let gwei = try XCTUnwrap(S.number(c["gwei"]))
            assertClose(GasFees.evmTransferUsd(gwei, S.number(c["coinUsd"])), S.number(c["usd"]),
                        tolerance: tolerance, "EVM \(gwei) gwei")
        }
        for c in S.list(data["btcTransferUsd"]) {
            let sat = try XCTUnwrap(S.number(c["satPerVb"]))
            assertClose(GasFees.btcTransferUsd(sat, S.number(c["btcUsd"])), S.number(c["usd"]),
                        tolerance: tolerance, "BTC \(sat) sat/vB")
        }
    }

    func testAnzeigeWieGemeinsameTestfaelle() throws {
        let format = try XCTUnwrap(try S.fixture("gas_fees")["format"] as? [String: Any])
        let locale = Locale(identifier: try XCTUnwrap(format["locale"] as? String))
        for c in S.list(format["gwei"]) {
            let gwei = try XCTUnwrap(S.number(c["gwei"]))
            XCTAssertEqual(GasFees.formatGwei(gwei, locale: locale), c["expected"] as? String, "gwei \(gwei)")
        }
        for c in S.list(format["usd"]) {
            let usd = try XCTUnwrap(S.number(c["usd"]))
            XCTAssertEqual(GasFees.formatUsd(usd, locale: locale), c["expected"] as? String, "usd \(usd)")
        }
    }
}
