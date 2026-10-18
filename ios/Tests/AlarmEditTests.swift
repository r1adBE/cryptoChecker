import XCTest
@testable import CryptoChecker

/// Speichern aus dem Editor: sofort erreicht, neu scharf oder Zustand behalten; Vorschlag je
/// Richtung; «nicht geprüft seit …» — wie `AlarmEditTest.kt`.
final class AlarmEditTests: XCTestCase {

    // MARK: firesNow

    func testFiresNowComparesLikeTheEvaluator() {
        XCTAssertTrue(AlarmEdit.firesNow(condition: .PRICE_ABOVE, threshold: 60_000, price: 60_000))
        XCTAssertTrue(AlarmEdit.firesNow(condition: .PRICE_ABOVE, threshold: 60_000, price: 61_000))
        XCTAssertFalse(AlarmEdit.firesNow(condition: .PRICE_ABOVE, threshold: 60_000, price: 59_999))
        XCTAssertTrue(AlarmEdit.firesNow(condition: .PRICE_BELOW, threshold: 60_000, price: 60_000))
        XCTAssertTrue(AlarmEdit.firesNow(condition: .PRICE_BELOW, threshold: 60_000, price: 59_000))
        XCTAssertFalse(AlarmEdit.firesNow(condition: .PRICE_BELOW, threshold: 60_000, price: 60_001))
    }

    func testFiresNowNeedsPriceAndPriceCondition() {
        XCTAssertFalse(AlarmEdit.firesNow(condition: .PRICE_ABOVE, threshold: 60_000, price: nil))
        XCTAssertFalse(AlarmEdit.firesNow(condition: .PRICE_ABOVE, threshold: 60_000, price: .nan))
        XCTAssertFalse(AlarmEdit.firesNow(condition: .PRICE_BELOW, threshold: 60_000, price: 0))
        XCTAssertFalse(AlarmEdit.firesNow(condition: .PRICE_BELOW, threshold: nil, price: 1))
        XCTAssertFalse(AlarmEdit.firesNow(condition: .CHANGE_PERCENT_UP, threshold: 1, price: 60_000))
        XCTAssertFalse(AlarmEdit.firesNow(condition: .FUNDING_ABOVE, threshold: 0.01, price: 60_000))
    }

    // MARK: Speichern: neu scharf oder Zustand behalten

    /// Ausgelöster einmaliger Kursalarm (aus, gemeldet).
    private let triggered = Alarm(
        id: 7, watchId: 1, condition: .PRICE_ABOVE, threshold: 60_000,
        enabled: false, repeating: false, sound: true, vibrate: true, speak: false,
        referencePrice: nil, lastTriggeredAt: 1_000, lastTriggeredPrice: 60_100,
        windowHours: 4, referenceAt: 1_000, currency: nil)

    /// So speichert der Editor: Eingaben + Zustand «neu scharf».
    private func edited(_ from: Alarm, _ change: (inout Alarm) -> Void = { _ in }) -> Alarm {
        var a = from
        a.enabled = true
        a.lastTriggeredAt = 0
        a.lastTriggeredPrice = nil
        a.referenceAt = 0
        change(&a)
        return a
    }

    func testNewAlarmIsSavedAsEdited() {
        let new = edited(triggered) { $0.id = 0 }
        XCTAssertTrue(AlarmEdit.rearms(existing: nil, edited: new))
        XCTAssertEqual(AlarmEdit.merged(existing: nil, edited: new), new)
    }

    func testSoundVibrationSpeechKeepTheTriggeredState() {
        let input = edited(triggered) { $0.sound = false; $0.vibrate = false; $0.speak = true }
        XCTAssertFalse(AlarmEdit.rearms(existing: triggered, edited: input))
        let saved = AlarmEdit.merged(existing: triggered, edited: input)
        XCTAssertFalse(saved.enabled)
        XCTAssertEqual(saved.lastTriggeredAt, 1_000)
        XCTAssertEqual(saved.lastTriggeredPrice, 60_100)
        XCTAssertEqual(saved.referenceAt, 1_000)
        XCTAssertFalse(saved.sound)
        XCTAssertFalse(saved.vibrate)
        XCTAssertTrue(saved.speak)
    }

    func testRepeatingAlarmKeepsReportedStateWhenSwitchedToOnce() {
        var reported = triggered
        reported.enabled = true
        reported.repeating = true
        let saved = AlarmEdit.merged(existing: reported, edited: edited(reported) { $0.repeating = false })
        XCTAssertTrue(saved.enabled)
        XCTAssertFalse(saved.repeating)
        XCTAssertEqual(saved.referenceAt, 1_000)
        XCTAssertEqual(saved.lastTriggeredAt, 1_000)
    }

    func testSwitchingTriggeredOnceAlarmToEveryTimeReEnablesIt() {
        let input = edited(triggered) { $0.repeating = true }
        XCTAssertTrue(AlarmEdit.reenables(existing: triggered, edited: input))
        XCTAssertTrue(AlarmEdit.rearms(existing: triggered, edited: input))
        let saved = AlarmEdit.merged(existing: triggered, edited: input)
        XCTAssertTrue(saved.enabled)
        XCTAssertTrue(saved.repeating)
        XCTAssertEqual(saved.referenceAt, 0) // scharf wie mit dem Schalter
        XCTAssertEqual(saved.lastTriggeredAt, 1_000) // «zuletzt …» bleibt
    }

    func testSwitchedOffNeverTriggeredAlarmStaysOffOnEveryTime() {
        var off = triggered
        off.lastTriggeredAt = 0
        off.lastTriggeredPrice = nil
        off.referenceAt = 0
        let input = edited(off) { $0.repeating = true }
        XCTAssertFalse(AlarmEdit.reenables(existing: off, edited: input))
        XCTAssertFalse(AlarmEdit.merged(existing: off, edited: input).enabled)
    }

    func testConditionThresholdCurrencyPairOrWindowReArm() {
        let inputs = [
            edited(triggered) { $0.condition = .PRICE_BELOW },
            edited(triggered) { $0.threshold = 61_000 },
            edited(triggered) { $0.currency = "CHF" },
            edited(triggered) { $0.watchId = 2 },
        ]
        for input in inputs {
            XCTAssertTrue(AlarmEdit.conditionChanged(triggered, input))
            XCTAssertEqual(AlarmEdit.merged(existing: triggered, edited: input), input)
        }
        // Fenster zählt nur, wo es zur Bedingung gehört
        let window = edited(triggered) { $0.windowHours = 1 }
        XCTAssertFalse(AlarmEdit.conditionChanged(triggered, window))
        XCTAssertEqual(AlarmEdit.merged(existing: triggered, edited: window).windowHours, 4)
        var move = triggered
        move.condition = .MOVE_PERCENT_WINDOW
        move.threshold = 3
        var moveLonger = move
        moveLonger.windowHours = 12
        XCTAssertTrue(AlarmEdit.conditionChanged(move, moveLonger))
        // Währung: Gross-/Kleinschreibung und Leer egal
        var chf = triggered
        chf.currency = "CHF"
        var chfLower = chf
        chfLower.currency = "chf"
        XCTAssertFalse(AlarmEdit.conditionChanged(chf, chfLower))
        var blank = triggered
        blank.currency = " "
        XCTAssertFalse(AlarmEdit.conditionChanged(triggered, blank))
        // Gleiche Schwelle, minimal anders zurückgelesen
        var near = triggered
        near.threshold = 60_000 * (1 + 1e-12)
        XCTAssertFalse(AlarmEdit.conditionChanged(triggered, near))
    }

    func testPercentAlarmKeepsReferenceWhenOnlyOptionsChange() {
        var percent = triggered
        percent.condition = .CHANGE_PERCENT_UP
        percent.threshold = 5
        percent.enabled = true
        percent.repeating = true
        percent.referencePrice = 58_000
        percent.referenceAt = 0
        let saved = AlarmEdit.merged(existing: percent, edited: edited(percent) { $0.referencePrice = 63_000; $0.speak = true })
        XCTAssertEqual(saved.referencePrice, 58_000)
        XCTAssertTrue(saved.speak)
    }

    // MARK: Vorschlag im Editor folgt der Richtung

    func testUntouchedSuggestionFollowsDirection() {
        var draft = AlarmDraft.newFor(lastPrice: 63_412.57)
        draft.priceHint = 63_412.57
        XCTAssertEqual(draft.thresholdText, "66600")
        XCTAssertTrue(draft.isUntouchedSuggestion)
        draft = draft.withCondition(.PRICE_BELOW)
        XCTAssertEqual(draft.thresholdText, "60200")
        draft = draft.withCondition(.PRICE_ABOVE)
        XCTAssertEqual(draft.thresholdText, "66600")
    }

    func testTypedAmountStaysOnDirectionSwitch() {
        var draft = AlarmDraft.newFor(lastPrice: 63_412.57)
        draft.priceHint = 63_412.57
        draft.thresholdText = "65000"
        XCTAssertFalse(draft.isUntouchedSuggestion)
        draft = draft.withCondition(.PRICE_BELOW)
        XCTAssertEqual(draft.thresholdText, "65000")
        // Bestehender Alarm: kein Vorschlag
        var existing = AlarmDraft.from(triggered)
        existing.priceHint = 63_412.57
        XCTAssertNil(existing.suggestedText)
        XCTAssertEqual(existing.withCondition(.PRICE_BELOW).thresholdText, existing.thresholdText)
    }

    func testSuggestionWithoutPriceIsKept() {
        let draft = AlarmDraft.newFor(lastPrice: 63_412.57).withCondition(.PRICE_BELOW)
        XCTAssertEqual(draft.thresholdText, "66600")
    }

    // MARK: Bezug neuer Prozentalarme nur aus frischem Kurs

    func testFreshReferenceOnlyFromFreshPrice() {
        let now: Int64 = 1_700_000_000_000
        let stale: Int64 = 45 * 60_000
        XCTAssertEqual(AlarmEdit.freshReference(lastPrice: 100, lastUpdate: now - 60_000, now: now, staleAfterMillis: stale), 100)
        XCTAssertEqual(AlarmEdit.freshReference(lastPrice: 100, lastUpdate: now - stale, now: now, staleAfterMillis: stale), 100)
        XCTAssertNil(AlarmEdit.freshReference(lastPrice: 100, lastUpdate: now - stale - 1, now: now, staleAfterMillis: stale))
        XCTAssertNil(AlarmEdit.freshReference(lastPrice: 100, lastUpdate: 0, now: now, staleAfterMillis: stale))
        XCTAssertNil(AlarmEdit.freshReference(lastPrice: nil, lastUpdate: now, now: now, staleAfterMillis: stale))
        XCTAssertNil(AlarmEdit.freshReference(lastPrice: 0, lastUpdate: now, now: now, staleAfterMillis: stale))
    }

    // MARK: Neuer Alarm: leeres Feld, Kurs als Platzhalter

    func testBlankDraftStartsEmptyWithoutError() {
        var draft = AlarmDraft.blankFor()
        draft.priceHint = 63_412.57
        XCTAssertEqual(draft.thresholdText, "")
        XCTAssertEqual(draft.thresholdProblem, .EMPTY)
        XCTAssertNil(draft.visibleProblem)
        XCTAssertFalse(draft.isValid)
        // Kein «löst sofort aus» ohne getippten Betrag
        XCTAssertFalse(AlarmEdit.firesNow(condition: draft.condition, threshold: draft.threshold, price: draft.priceHint))
        // Nach dem ersten Tippen gilt «leer» wieder als Fehler
        draft.blankStart = false
        XCTAssertEqual(draft.visibleProblem, .EMPTY)
    }

    // MARK: Fehlertext unter dem Feld (warum «Speichern» gesperrt ist)

    func testThresholdProblemFollowsValidation() {
        func draft(_ condition: AlarmCondition, _ text: String) -> AlarmDraft {
            AlarmDraft(condition: condition, thresholdText: text)
        }
        XCTAssertNil(draft(.PRICE_ABOVE, "65000").thresholdProblem)
        XCTAssertEqual(draft(.PRICE_ABOVE, "  ").thresholdProblem, .EMPTY)
        XCTAssertEqual(draft(.PRICE_ABOVE, "abc").thresholdProblem, .INVALID)
        XCTAssertEqual(draft(.PRICE_ABOVE, "0").thresholdProblem, .INVALID)
        XCTAssertNil(draft(.FUNDING_ABOVE, "-1").thresholdProblem)
        XCTAssertEqual(draft(.FUNDING_ABOVE, "12").thresholdProblem, .FUNDING_RANGE)
        XCTAssertEqual(draft(.FUNDING_ABOVE, "x").thresholdProblem, .FUNDING_RANGE)
        XCTAssertNil(draft(.OI_UP, "10").thresholdProblem)
        XCTAssertEqual(draft(.OI_UP, "2000").thresholdProblem, .OI_RANGE)
        XCTAssertEqual(draft(.OI_UP, "0").thresholdProblem, .INVALID)
        // Jeder Fehler sperrt, kein Fehler gibt frei
        for condition in [AlarmCondition.PRICE_ABOVE, .FUNDING_ABOVE, .OI_UP] {
            for text in ["", "1", "abc", "5000"] {
                let d = draft(condition, text)
                XCTAssertEqual(d.isValid, d.thresholdProblem == nil)
            }
        }
        XCTAssertNil(AlarmThresholdError.key(nil))
        XCTAssertEqual(AlarmThresholdError.key(.OI_RANGE), "alarm_threshold_error_oi")
    }

    // MARK: «Nicht geprüft seit …»

    func testUncheckedKeepsFirstTimeAndLatestReason() {
        var map: [Int64: AlarmCheckStatus.Unchecked] = [:]
        map = AlarmCheckStatus.unchecked(map, alarmId: 1, reason: .noRate, now: 100)
        map = AlarmCheckStatus.unchecked(map, alarmId: 1, reason: .noCandles, now: 200)
        XCTAssertEqual(map[1], AlarmCheckStatus.Unchecked(reason: .noCandles, since: 100))
        map = AlarmCheckStatus.checked(map, alarmId: 1)
        XCTAssertNil(map[1])
    }

    func testCheckStatusStoreMarksAndClears() {
        let store = AlarmCheckStatus()
        store.markUnchecked(5, reason: .noRate, now: 10)
        store.markUnchecked(6, reason: .noCandles, now: 20)
        store.markChecked(5)
        XCTAssertNil(store.unchecked(5))
        XCTAssertEqual(store.unchecked(6)?.reason, .noCandles)
    }
}
