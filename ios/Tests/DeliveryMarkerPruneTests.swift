import XCTest
@testable import CryptoChecker

/// Vermerke «wird gemeldet» beim Löschen erst nach dem Ergebnis DIESES Schreibens kürzen.
final class DeliveryMarkerPruneTests: XCTestCase {
    /// (a) Lösch-Schreiben scheitert → Vermerk bleibt (auch wenn alle früheren Schreiben gelangen).
    func testDeleteWriteFailsMarkStays() {
        XCTAssertFalse(AppData.markersPrunable(written: false, locked: false, pending: false, fileExists: true))
        XCTAssertFalse(AppData.markersPrunable(written: false, locked: false, pending: true, fileExists: true))
    }

    /// (b) Lösch-Schreiben gelingt → Vermerk weg.
    func testDeleteWriteSucceedsMarkGone() {
        XCTAssertTrue(AppData.markersPrunable(written: true, locked: false, pending: false, fileExists: true))
        XCTAssertTrue(AppData.markersPrunable(written: true, locked: false, pending: false, fileExists: false))
    }

    /// (c) Gesperrte Merkliste bzw. Stand wartet nur im Speicher bei alter Datei → Vermerk bleibt.
    func testLockedOrPendingMarkStays() {
        XCTAssertFalse(AppData.markersPrunable(written: true, locked: true, pending: false, fileExists: true))
        // Portfolio-Datei wartet auf den Schlüssel (watchlist.json geschrieben, eigene Datei alt)
        XCTAssertFalse(AppData.markersPrunable(written: true, locked: false, pending: true, fileExists: true))
        XCTAssertTrue(AppData.markersPrunable(written: true, locked: false, pending: true, fileExists: false))
    }
}
