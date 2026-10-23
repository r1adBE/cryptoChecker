import XCTest
@testable import CryptoChecker

/// Review IOS4-R01, FUN4-R1: Vermerke «wird gemeldet» beim Löschen erst nach dem Ergebnis DIESES
/// Schreibens kürzen; Portfolio-Bereinigung setzt ihren Merker nur bei wirklich gelesenen Portfolio-Alarmen.
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

    /// FUN4-R1: `nil` bei vorhandener Datei = nicht gelesen → kein Merker; ohne Datei bzw. mit Liste → Merker.
    func testPortfolioBereinigungNurNachLesen() {
        XCTAssertFalse(FallAlarmCleanup.portfolioAlarmsRead(nil, fileExists: true))
        XCTAssertTrue(FallAlarmCleanup.portfolioAlarmsRead(nil, fileExists: false))
        let invalid = PortfolioAlarm(id: 7, kind: .CHANGE_DOWN, threshold: 150, currency: nil)
        XCTAssertTrue(FallAlarmCleanup.portfolioAlarmsRead([invalid], fileExists: true))
        XCTAssertTrue(FallAlarmCleanup.portfolioAlarmsRead([], fileExists: true))
        // Danach mit gelesener Liste: CHANGE_DOWN 150 wird ausgeschaltet
        XCTAssertEqual(FallAlarmCleanup.portfolioIdsToDisable([invalid]), [7])
    }

    /// Der Guard steht vor dem Merker.
    func testPortfolioMerkerErstNachDemGuard() throws {
        let text = try source("AppData+FallAlarmCleanup.swift")
        let guardAt = try XCTUnwrap(text.range(of: "guard FallAlarmCleanup.portfolioAlarmsRead("))
        let markAt = try XCTUnwrap(text.range(of: "defaults.set(true, forKey: FallAlarmCleanup.portfolioDoneKey)"))
        XCTAssertLessThan(guardAt.lowerBound, markAt.lowerBound)
    }

    private func source(_ name: String) throws -> String {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("App/Services/\(name)")
        return try String(contentsOf: file, encoding: .utf8)
    }
}
