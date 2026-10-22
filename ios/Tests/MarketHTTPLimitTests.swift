import XCTest
@testable import CryptoChecker

/// Höchstgrösse von Antworten (wie Android `HttpBodyLimitTest.kt`).
final class MarketHTTPLimitTests: XCTestCase {

    func testSingleSessionUsesSmallLimit() {
        XCTAssertNoThrow(try MarketHTTP.checkBodySize(MarketHTTP.maxBodyBytes, session: MarketHTTP.session))
        XCTAssertThrowsError(try MarketHTTP.checkBodySize(MarketHTTP.maxBodyBytes + 1, session: MarketHTTP.session)) {
            XCTAssertEqual($0.localizedDescription, ResponseTooLargeError.message)
        }
    }

    func testListSessionsUseLargeLimit() {
        for session in [MarketHTTP.bulkSession, MarketHTTP.tickerBulkSession] {
            XCTAssertNoThrow(try MarketHTTP.checkBodySize(MarketHTTP.maxBodyBytes + 1, session: session))
            XCTAssertThrowsError(try MarketHTTP.checkBodySize(MarketHTTP.maxBulkBodyBytes + 1, session: session))
        }
        XCTAssertEqual(MarketHTTP.maxBodyBytes, 2 * 1024 * 1024)
        XCTAssertEqual(MarketHTTP.maxBulkBodyBytes, 16 * 1024 * 1024)
    }

    /// Widget-Erweiterung: Listen höchstens 3 MB (seit Build 102; vorher 8 MB), Einzelabfragen weiter 2 MB. Jede
    /// aufgenommene Sammelabfrage ist höchstens 2 MB (die Aufnahme verwirft Grösseres) — passt also weiter.
    func testExtensionUsesLowerListLimit() {
        XCTAssertEqual(MarketHTTP.maxExtensionBodyBytes, 3 * 1024 * 1024)
        XCTAssertNoThrow(try MarketHTTP.checkBodySize(2 * 1024 * 1024, session: MarketHTTP.tickerBulkSession, inExtension: true))
        XCTAssertEqual(MarketHTTP.bodyLimit(session: MarketHTTP.bulkSession, inExtension: true), MarketHTTP.maxExtensionBodyBytes)
        XCTAssertEqual(MarketHTTP.bodyLimit(session: MarketHTTP.session, inExtension: true), MarketHTTP.maxBodyBytes)
        XCTAssertEqual(MarketHTTP.bodyLimit(session: MarketHTTP.bulkSession, inExtension: false), MarketHTTP.maxBulkBodyBytes)
        XCTAssertThrowsError(try MarketHTTP.checkBodySize(MarketHTTP.maxExtensionBodyBytes + 1,
                                                          session: MarketHTTP.tickerBulkSession, inExtension: true))
        // Tests laufen in der App, nicht in der Erweiterung
        XCTAssertFalse(MarketHTTP.isAppExtension)
    }

    /// Der feste Text bleibt beim Speichern erhalten (`StoredError`).
    func testMessageIsKeptWhenStored() {
        XCTAssertEqual(StoredError.sanitize(ResponseTooLargeError().localizedDescription), ResponseTooLargeError.message)
    }

    // MARK: Blockweises Lesen (`collectLimited`)

    private func stream(_ count: Int) -> AsyncStream<UInt8> {
        AsyncStream { continuation in
            for i in 0..<count { continuation.yield(UInt8(truncatingIfNeeded: i)) }
            continuation.finish()
        }
    }

    /// Genau an der Grenze geht es noch, auch über mehrere Blöcke und mit Rest; Inhalt bleibt in Reihenfolge.
    func testChunkedReadKeepsBytesUpToLimit() async throws {
        let data = try await MarketHTTP.collectLimited(stream(10), limit: 10, expected: -1, chunkSize: 4)
        XCTAssertEqual([UInt8](data), (0..<10).map { UInt8($0) })
        let empty = try await MarketHTTP.collectLimited(stream(0), limit: 10, expected: 0, chunkSize: 4)
        XCTAssertTrue(empty.isEmpty)
    }

    /// Ein Byte über der Grenze → Fehler — mitten in einem Block wie im letzten Rest.
    func testChunkedReadRejectsOneByteOverLimit() async {
        for (count, chunk) in [(11, 4), (11, 64), (9, 8)] {
            do {
                _ = try await MarketHTTP.collectLimited(stream(count), limit: count - 1, expected: -1, chunkSize: chunk)
                XCTFail("\(count) Bytes bei Grenze \(count - 1) müssen scheitern")
            } catch {
                XCTAssertTrue(error is ResponseTooLargeError)
            }
        }
    }

    /// Angekündigt zu gross: gar nicht erst lesen.
    func testChunkedReadRejectsAnnouncedLength() async {
        do {
            _ = try await MarketHTTP.collectLimited(stream(1), limit: 10, expected: 11)
            XCTFail("angekündigte Länge über der Grenze muss scheitern")
        } catch {
            XCTAssertTrue(error is ResponseTooLargeError)
        }
    }
}
