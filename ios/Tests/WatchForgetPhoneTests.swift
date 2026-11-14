import XCTest
@testable import CryptoChecker

/// iPhone-App nicht mehr installiert: die Apple Watch vergisst, was vom iPhone stammt (`WatchModel.forgetPhone`) —
/// Portfolio-Wert weg, Kurse nicht mehr vom iPhone, Wahl wie ohne iPhone (`WatchSettings.forgetPhone`, Parität in
/// `watch_link.json`). Wie Wear OS bei gelöschten Einträgen (`WatchDataDeletionTest`). Die Uhr-Dateien gehören nicht zum
/// Test-Ziel: geprüft wird die Quelle.
final class WatchForgetPhoneTests: XCTestCase {
    private func source(_ path: String) throws -> String {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        return try String(contentsOf: root.appendingPathComponent(path), encoding: .utf8)
    }

    func testLinkForgetsWithoutCompanionApp() throws {
        let link = try source("Watch/WatchLink.swift")
        XCTAssertTrue(link.contains("func sessionCompanionAppInstalledDidChange(_ session: WCSession)"))
        XCTAssertTrue(link.contains("guard !session.isCompanionAppInstalled else { return }"))
        XCTAssertTrue(link.contains("let companion = session.isCompanionAppInstalled"))
        XCTAssertEqual(link.components(separatedBy: "WatchModel.shared.forgetPhone()").count - 1, 2)
    }

    func testModelClearsPhoneData() throws {
        let model = try source("Watch/WatchModel.swift")
        XCTAssertTrue(model.contains("let next = WatchSettings.forgetPhone(settings)"))
        XCTAssertTrue(model.contains("WatchStore.forgetPhoneQuotes()"))
        XCTAssertTrue(model.contains("WatchStore.takePortfolio(.clear)"))
        let store = try source("Watch/Device/WatchStore.swift")
        XCTAssertTrue(store.contains("defaults.removeObject(forKey: keyPhoneCoins)"))
        XCTAssertTrue(store.contains("defaults.removeObject(forKey: keyPhoneTime)"))
    }

    /// Ohne Stand des iPhones bleibt die geänderte Wahl der Uhr, der iPhone-Teil fällt auf Standard.
    func testForgetPhoneKeepsOwnChoice() {
        var local = WatchSettings()
        local.mode = .phone
        local.phone = ["SOL"]
        local.revision = 2
        local.source = .alwaysSelf
        local.interval = 60
        let forgotten = WatchSettings.forgetPhone(local)
        XCTAssertEqual(forgotten.mode, .phone)
        XCTAssertEqual(forgotten.phone, [])
        XCTAssertEqual(forgotten.source, .phone)
        XCTAssertEqual(forgotten.interval, WatchInterval.defaultMinutes)
    }
}
