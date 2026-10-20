import XCTest
@testable import CryptoChecker

/// Markt-Meldung aus- und mit derselben Grenze wieder eingeschaltet, ohne dass dazwischen geprüft wurde:
/// `MarketAlertReset` vergisst beim Ausschalten den gemerkten Stand, die nächste Prüfung bewertet neu. Dazu die neue
/// Grenze einer Bitcoin-Kennzahl (`IndicatorAlertRules.wasCrossed`). Wie Android `MarketAlertResetTest`.
final class MarketAlertResetTests: XCTestCase {
    private let suiteName = "MarketAlertResetTests"
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        UserDefaults().removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        UserDefaults().removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    func testFearGreedOffThenOnWithSameBoundNotifiesAgain() {
        defaults.set(20, forKey: ZoneCheck.keyFearGreed)
        defaults.set(25, forKey: ZoneCheck.keyFearGreedBelow)
        var old = AppSettings()
        old.fearGreedBelow = 25
        var off = old
        off.fearGreedBelow = 0
        MarketAlertReset.settingsChanged(from: old, to: off, defaults: defaults)
        XCTAssertEqual(defaults.object(forKey: ZoneCheck.keyFearGreedBelow) as? Int, 0)
        let changed = MarketNoticeGate.boundChanged(stored: defaults.object(forKey: ZoneCheck.keyFearGreedBelow) as? Int,
                                                    current: 25)
        let step = MarketNoticeGate.fearGreed(value: 18, last: 20, below: 25, above: 0, canNotify: true,
                                              belowChanged: changed)
        XCTAssertEqual(step.step, .notify)
        XCTAssertEqual(step.kind, .below)
    }

    func testFearGreedWithoutStoredStateWritesNothing() {
        MarketAlertReset.fearGreedOff(below: false, defaults: defaults)
        XCTAssertNil(defaults.object(forKey: ZoneCheck.keyFearGreedAbove))
    }

    func testGasOffThenOnIsArmedAgain() {
        defaults.set(false, forKey: GasAlertCheck.keyEthArmed)
        defaults.set(5.0, forKey: GasAlertCheck.keyEthThreshold)
        var old = AppSettings()
        old.gasAlertEthTenths = 50
        var off = old
        off.gasAlertEthTenths = 0
        MarketAlertReset.settingsChanged(from: old, to: off, defaults: defaults)
        let stored = defaults.object(forKey: GasAlertCheck.keyEthThreshold) as? Double
        XCTAssertEqual(stored, 0)
        XCTAssertTrue(GasAlertCheck.armedFor(storedArmed: false, storedThreshold: stored, threshold: 5))
        XCTAssertNil(defaults.object(forKey: GasAlertCheck.keyBtcThreshold))
    }

    func testIndicatorOffForgetsCrossedStateAndBound() {
        let kind = IndicatorAlertKind.MAYER_ABOVE
        defaults.set(true, forKey: ZoneCheck.crossedKey(kind))
        defaults.set(240, forKey: ZoneCheck.thresholdKey(kind))
        var old = AppSettings()
        old.indicatorAlerts = [kind.rawValue: 240]
        var off = old
        off.indicatorAlerts = [:]
        MarketAlertReset.settingsChanged(from: old, to: off, defaults: defaults)
        XCTAssertNil(defaults.object(forKey: ZoneCheck.crossedKey(kind)))
        XCTAssertNil(defaults.object(forKey: ZoneCheck.thresholdKey(kind)))
    }

    func testNewIndicatorBoundRearms() {
        XCTAssertFalse(IndicatorAlertRules.wasCrossed(storedCrossed: true, storedThreshold: 240, current: 200))
        XCTAssertTrue(IndicatorAlertRules.decide(.MAYER_ABOVE, value: 2.5, threshold: 2.0, wasCrossed: false).notify)
        XCTAssertTrue(IndicatorAlertRules.wasCrossed(storedCrossed: true, storedThreshold: 240, current: 240))
        XCTAssertTrue(IndicatorAlertRules.wasCrossed(storedCrossed: true, storedThreshold: nil, current: 200))
        XCTAssertFalse(IndicatorAlertRules.wasCrossed(storedCrossed: false, storedThreshold: 240, current: 200))
    }

    /// Prozent im Banner «Alarm gespeichert» im Format der Sprache wie Chip und Editor, nicht «5.00%».
    func testDescribeUsesLocalePercent() {
        let up = Alarm(id: 1, watchId: 1, condition: .CHANGE_PERCENT_UP, threshold: 5)
        XCTAssertEqual(AlarmTexts.describe(up), AlarmTexts.conditionName(.CHANGE_PERCENT_UP) + " " + AlarmTexts.percent(5))
        XCTAssertFalse(AlarmTexts.describe(up).contains("5.00"))
    }

    /// Hinweis «Betrag eingeben»: nur solange das Feld eines neuen Alarms leer ist (wie Android).
    func testEnterAmountHintOnlyForEmptyNewField() {
        var draft = AlarmDraft.blankFor()
        XCTAssertTrue(draft.showsEnterAmountHint)
        draft.thresholdText = "70000"
        draft.blankStart = false
        XCTAssertFalse(draft.showsEnterAmountHint)
        draft.thresholdText = ""
        XCTAssertFalse(draft.showsEnterAmountHint)
    }

    /// Stand vor der gemerkten Grenze (nur «jenseits»): übernehmen, ohne doppelt zu melden, aber den Zustand schreiben —
    /// samt Grenze. Danach meldet eine neue Grenze, obwohl der Wert jenseits bleibt. Wie Android `IndicatorAlertRulesTest`.
    func testLegacyCrossedStateWithoutBoundPicksUpBound() {
        let wasCrossed = IndicatorAlertRules.wasCrossed(storedCrossed: true, storedThreshold: nil, current: 240)
        let run = IndicatorAlertRules.decide(.MAYER_ABOVE, value: 2.6, threshold: 2.4, wasCrossed: wasCrossed)
        XCTAssertEqual(run, .init(notify: false, crossed: true))
        let afterChange = IndicatorAlertRules.wasCrossed(storedCrossed: true, storedThreshold: 240, current: 200)
        XCTAssertFalse(afterChange)
        XCTAssertTrue(IndicatorAlertRules.decide(.MAYER_ABOVE, value: 2.6, threshold: 2.0, wasCrossed: afterChange).notify)
    }

    /// «Aus» während einer laufenden Gas-Prüfung: Die Prüfung schreibt danach ihren Stand nicht mehr (sonst kehrte die
    /// alte Grenze zurück und das Wiedereinschalten mit derselben Grenze bliebe stumm). Wie Android (gemeinsame Sperre).
    func testGasOffDuringRunningCheckBlocksItsWrite() {
        let generation = defaults.integer(forKey: GasAlertCheck.keyResetGeneration)
        XCTAssertTrue(GasAlertCheck.mayStore(generation: generation, defaults: defaults))
        MarketAlertReset.gasOff(eth: true, defaults: defaults)
        XCTAssertFalse(GasAlertCheck.mayStore(generation: generation, defaults: defaults))
        XCTAssertEqual(defaults.object(forKey: GasAlertCheck.keyEthThreshold) as? Double, 0)
    }
}
