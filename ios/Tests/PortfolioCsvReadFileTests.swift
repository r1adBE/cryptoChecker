import XCTest
@testable import CryptoChecker

/// CSV-Datei höchstens bis zur Grenze lesen (`PortfolioCsvImporter.readFile`, seit Build 99): auch wenn die Grösse
/// vorab unbekannt ist, liest der Import nie mehr als Grenze + 1 Byte — wie `BackupManager.readFile`.
final class PortfolioCsvReadFileTests: XCTestCase {
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("csv-read-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func file(_ bytes: Int) throws -> URL {
        let url = dir.appendingPathComponent("\(bytes).csv")
        try Data(repeating: UInt8(ascii: "a"), count: bytes).write(to: url)
        return url
    }

    func testFileAtLimitIsReadCompletely() throws {
        XCTAssertEqual(PortfolioCsvImporter.readFile(try file(64), maxBytes: 64)?.count, 64)
    }

    func testFileOverLimitIsRejected() throws {
        XCTAssertNil(PortfolioCsvImporter.readFile(try file(65), maxBytes: 64))
    }

    func testMissingFileIsNil() {
        XCTAssertNil(PortfolioCsvImporter.readFile(dir.appendingPathComponent("fehlt.csv"), maxBytes: 64))
    }

    /// Mit Grund (seit Build 99): über der Grenze «zu gross» (eigene Meldung), fehlende Datei «nicht lesbar».
    func testReadCheckedNamesTheReason() throws {
        XCTAssertEqual(try PortfolioCsvImporter.readChecked(try file(64), maxBytes: 64).get().count, 64)
        XCTAssertEqual(PortfolioCsvImporter.readChecked(try file(65), maxBytes: 64), .failure(.tooLarge))
        XCTAssertEqual(PortfolioCsvImporter.readChecked(dir.appendingPathComponent("fehlt.csv"), maxBytes: 64),
                       .failure(.unreadable))
    }

    func testDefaultLimitIsSixteenMegabytes() throws {
        XCTAssertEqual(PortfolioCsvImporter.maxBytes, 16 * 1024 * 1024)
        XCTAssertEqual(PortfolioCsvImporter.readFile(try file(10))?.count, 10)
    }
}
