import XCTest
@testable import CryptoChecker

/// Review IOS4-R01: Vermerke «wird gemeldet» beim Löschen erst nach dem Ergebnis DIESES Schreibens kürzen.
final class DeliveryMarkerPruneTests: XCTestCase {
    /// (a) Lösch-Schreiben scheitert → Vermerk bleibt (auch wenn alle früheren Schreiben gelangen).
    func testLoeschSchreibenScheitertVermerkBleibt() {
        XCTAssertFalse(AppData.markersPrunable(written: false, locked: false, pending: false, fileExists: true))
        XCTAssertFalse(AppData.markersPrunable(written: false, locked: false, pending: true, fileExists: true))
    }

    /// (b) Lösch-Schreiben gelingt → Vermerk weg.
    func testLoeschSchreibenGelingtVermerkWeg() {
        XCTAssertTrue(AppData.markersPrunable(written: true, locked: false, pending: false, fileExists: true))
        XCTAssertTrue(AppData.markersPrunable(written: true, locked: false, pending: false, fileExists: false))
    }

    /// (c) Gesperrte Merkliste bzw. Stand wartet nur im Speicher bei alter Datei → Vermerk bleibt.
    func testGesperrtOderWartendVermerkBleibt() {
        XCTAssertFalse(AppData.markersPrunable(written: true, locked: true, pending: false, fileExists: true))
        // Portfolio-Datei wartet auf den Schlüssel (watchlist.json geschrieben, eigene Datei alt)
        XCTAssertFalse(AppData.markersPrunable(written: true, locked: false, pending: true, fileExists: true))
        XCTAssertTrue(AppData.markersPrunable(written: true, locked: false, pending: true, fileExists: false))
    }

    /// Die Löschwege kürzen nicht mehr sofort nach dem Einreihen, sondern über die Warteschlange.
    func testLoeschwegeKuerzenErstNachDemSchreiben() throws {
        let alarms = try source("AppData+Alarms.swift"), watchlist = try source("AppData+Watchlist.swift")
        let markers = try source("AppData+DeliveryMarkers.swift")
        XCTAssertTrue(alarms.contains("removePairMarkersAfterWrite([id])"))
        XCTAssertTrue(alarms.contains("removePortfolioMarkersAfterWrite([id])"))
        XCTAssertTrue(watchlist.contains("retainPairMarkersAfterWrite(ids)"))
        XCTAssertTrue(markers.contains("await SharedStorage.snapshotWritesSettled()"))
        for text in [alarms, watchlist] {
            XCTAssertFalse(text.contains("AlarmDelivery.pairs.remove("), "sofortiges Kürzen")
            XCTAssertFalse(text.contains("AlarmDelivery.pairs.retain("), "sofortiges Kürzen")
            XCTAssertFalse(text.contains("AlarmDelivery.portfolio.remove("), "sofortiges Kürzen")
        }
    }

    private func source(_ name: String) throws -> String {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("App/Services/\(name)")
        return try String(contentsOf: file, encoding: .utf8)
    }
}
