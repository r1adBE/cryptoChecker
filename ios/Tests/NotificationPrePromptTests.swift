import XCTest
@testable import CryptoChecker

/// Erklärung vor der Mitteilungs-Abfrage: höchstens einmal bei neuen Paaren, nach «Später» noch einmal beim ersten
/// Alarm — wie `NotificationPrePromptTest.kt`. Auch wenn mehrere Stellen fragen oder die Erklärung ohne Antwort
/// verschwindet (App beendet).
final class NotificationPrePromptTests: XCTestCase {

    private typealias P = NotificationPrePrompt
    private let reasons: [NotificationAsk] = [.direct, .pairsAdded, .alarmCreated]
    private let stages = [P.stageNew, P.stageLaterOnce, P.stageDone, P.stageContinued]

    func testRuleTable() {
        for s in stages {
            for r in reasons {
                XCTAssertEqual(P.action(notDetermined: false, stage: s, reason: r), .system)
            }
            XCTAssertEqual(P.action(notDetermined: true, stage: s, reason: .direct), .system)
        }
        XCTAssertEqual(P.action(notDetermined: true, stage: P.stageNew, reason: .pairsAdded), .explain)
        XCTAssertEqual(P.action(notDetermined: true, stage: P.stageNew, reason: .alarmCreated), .explain)
        XCTAssertEqual(P.action(notDetermined: true, stage: P.stageLaterOnce, reason: .pairsAdded), .nothing)
        XCTAssertEqual(P.action(notDetermined: true, stage: P.stageLaterOnce, reason: .alarmCreated), .explain)
        XCTAssertEqual(P.action(notDetermined: true, stage: P.stageDone, reason: .pairsAdded), .nothing)
        XCTAssertEqual(P.action(notDetermined: true, stage: P.stageDone, reason: .alarmCreated), .nothing)
        XCTAssertEqual(P.action(notDetermined: true, stage: P.stageContinued, reason: .pairsAdded), .system)
    }

    func testPairsAddedExplainsOnceEvenWithoutAnswer() {
        var slot = PrePromptSlot(stage: P.stageNew)
        XCTAssertEqual(slot.request(notDetermined: true, reason: .pairsAdded), .explain)
        // Schon beim Zeigen weiter: App beendet ohne Antwort zählt als «Später»
        XCTAssertEqual(slot.stage, P.stageLaterOnce)
        var afterRestart = PrePromptSlot(stage: slot.stage)
        XCTAssertEqual(afterRestart.request(notDetermined: true, reason: .pairsAdded), .nothing)
        XCTAssertFalse(afterRestart.showing)
    }

    func testSecondRequestWhileShowingDoesNothing() {
        var slot = PrePromptSlot(stage: P.stageNew)
        XCTAssertEqual(slot.request(notDetermined: true, reason: .pairsAdded), .explain)
        XCTAssertEqual(slot.request(notDetermined: true, reason: .pairsAdded), .nothing)
        XCTAssertEqual(slot.request(notDetermined: true, reason: .alarmCreated), .nothing)
        XCTAssertEqual(slot.stage, P.stageLaterOnce)
        XCTAssertTrue(slot.showing)
    }

    func testLaterThenFirstAlarmOnceMoreThenNever() {
        var slot = PrePromptSlot(stage: P.stageNew)
        _ = slot.request(notDetermined: true, reason: .pairsAdded)
        XCTAssertTrue(slot.answer(proceed: false))
        XCTAssertFalse(slot.answer(proceed: false))
        XCTAssertEqual(slot.request(notDetermined: true, reason: .pairsAdded), .nothing)
        XCTAssertEqual(slot.request(notDetermined: true, reason: .alarmCreated), .explain)
        XCTAssertEqual(slot.stage, P.stageDone)
        _ = slot.answer(proceed: false)
        XCTAssertEqual(slot.request(notDetermined: true, reason: .alarmCreated), .nothing)
        XCTAssertEqual(slot.request(notDetermined: true, reason: .pairsAdded), .nothing)
    }

    func testContinueAsksSystemFromThenOn() {
        var slot = PrePromptSlot(stage: P.stageNew)
        _ = slot.request(notDetermined: true, reason: .pairsAdded)
        XCTAssertTrue(slot.answer(proceed: true))
        XCTAssertEqual(slot.stage, P.stageContinued)
        XCTAssertFalse(slot.showing)
        XCTAssertEqual(slot.request(notDetermined: true, reason: .pairsAdded), .system)
    }

    func testNoExplanationWhenSystemWouldNotAsk() {
        var slot = PrePromptSlot(stage: P.stageNew)
        XCTAssertEqual(slot.request(notDetermined: false, reason: .pairsAdded), .system)
        XCTAssertEqual(slot.stage, P.stageNew)
        XCTAssertFalse(slot.showing)
    }
}
