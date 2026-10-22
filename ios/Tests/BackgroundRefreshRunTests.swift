import XCTest
@testable import CryptoChecker

/// Ein ganzer Lauf der Hintergrund-Aufgabe (`BackgroundRefresh.handleRefresh`, seit Build 103, Review TST-03) mit einer
/// Fälschung statt `BGTask`: Das Ende wird genau einmal gemeldet — im normalen Lauf mit Erfolg, beim Ablauf vor dem Ende
/// mit `false`, und der Alarm bleibt dann scharf. Die Reihenfolge im `expirationHandler` (abbrechen, melden, danach
/// Protokoll) prüft `BackgroundExpiryTests`; hier geht es um das Zusammenspiel mit dem Arbeits-Task.
/// Läuft auf der Merkliste der Test-App (App Group): Der Test legt ein eigenes Paar mit Alarm an und entfernt es danach.
@MainActor
final class BackgroundRefreshRunTests: XCTestCase {
    /// Hält Ablauf-Handler und jede Meldung des Endes fest (thread-sicher).
    private final class FakeTask: BackgroundRefreshTask, @unchecked Sendable { // @unchecked: Zustand nur unter `lock`
        private let lock = NSLock()
        private var handler: (() -> Void)?
        private var results: [Bool] = []

        func onExpiration(_ handler: @escaping () -> Void) {
            lock.lock(); self.handler = handler; lock.unlock()
        }

        func setTaskCompleted(success: Bool) {
            lock.lock(); results.append(success); lock.unlock()
        }

        var completions: [Bool] { lock.lock(); defer { lock.unlock() }; return results }

        func expire() {
            lock.lock(); let current = handler; lock.unlock()
            current?()
        }
    }

    private static let watchId: Int64 = 990_001
    private static let alarmId: Int64 = 990_002
    private var lastAlarmCheckAt: Int64 = 0

    override func setUp() async throws {
        lastAlarmCheckAt = SharedStorage.lastAlarmCheckAt
        // Sonst endete der Lauf als «Alarme eben erst geprüft», ohne Arbeits-Task
        SharedStorage.lastAlarmCheckAt = 0
    }

    override func tearDown() async throws {
        _ = SharedStorage.updateSnapshot { s in
            s.watches.removeAll { $0.id == Self.watchId }
            s.alarms.removeAll { $0.id == Self.alarmId }
        }
        SharedStorage.lastAlarmCheckAt = lastAlarmCheckAt
    }

    /// Paar mit einem Alarm, der beim ersten Kurs sicher auslöste («über 1»). Datei nicht beschreibbar: übersprungen.
    private func insertArmedAlarm() throws {
        let stored = SharedStorage.updateSnapshot { s in
            s.watches.removeAll { $0.id == Self.watchId }
            s.alarms.removeAll { $0.id == Self.alarmId }
            s.watches.append(Watch(id: Self.watchId, marketKey: "Binance", marketName: "Binance", baseAsset: "BTC",
                                   quoteAsset: "USDT", pairId: "BTCUSDT"))
            s.alarms.append(Alarm(id: Self.alarmId, watchId: Self.watchId, condition: .PRICE_ABOVE, threshold: 1))
        }
        guard stored != nil else { throw XCTSkip("Merkliste der Test-App gerade nicht beschreibbar") }
    }

    private func storedAlarm() -> Alarm? {
        SharedStorage.loadSnapshot().alarms.first { $0.id == Self.alarmId }
    }

    func testExpiryBeforeTheEndCompletesOnceWithFalseAndKeepsAlarmArmed() async throws {
        try insertArmedAlarm()
        let task = FakeTask()
        let work = BackgroundRefresh.handleRefresh(task)
        XCTAssertNotNil(work, "ohne Arbeits-Task (übersprungen?)")
        // Ablauf, bevor der Arbeits-Task überhaupt beginnt (kein await dazwischen): sofort genau ein «false»
        task.expire()
        XCTAssertEqual(task.completions, [false])
        // Der abgebrochene Arbeits-Task endet danach und meldet nicht noch einmal
        await work?.value
        XCTAssertEqual(task.completions, [false])
        // Nichts gespeichert, nichts gemeldet: der Alarm ist unverändert scharf
        let alarm = try XCTUnwrap(storedAlarm())
        XCTAssertTrue(alarm.enabled)
        XCTAssertEqual(alarm.lastTriggeredAt, 0)
        XCTAssertNil(alarm.lastTriggeredPrice)
    }

    /// Normaler Lauf: genau ein Ende mit Erfolg, erst wenn der Arbeits-Task fertig ist. Das Paar ist deaktiviert, damit
    /// kein Netz nötig ist (die Kurs- und Mitteilungswege prüfen PriceRefresher- und Notifier-Tests; Mitteilungen
    /// erlaubt der Test-Host nicht, eine Zustellung lässt sich hier nicht beobachten).
    func testNormalRunCompletesExactlyOnceWithSuccess() async throws {
        try insertArmedAlarm()
        _ = SharedStorage.updateSnapshot { s in
            if let i = s.watches.firstIndex(where: { $0.id == Self.watchId }) { s.watches[i].disabled = true }
        }
        let task = FakeTask()
        let work = try XCTUnwrap(BackgroundRefresh.handleRefresh(task), "ohne Arbeits-Task (übersprungen?)")
        XCTAssertEqual(task.completions, [], "Ende erst nach der Arbeit")
        await work.value
        XCTAssertEqual(task.completions, [true])
        // Ein später Ablauf (nach dem Ende) meldet nicht noch einmal
        task.expire()
        XCTAssertEqual(task.completions, [true])
    }
}
