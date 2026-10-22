import XCTest
@testable import CryptoChecker

/// Endzeit eines Widget-Durchlaufs aus dem Zeichnen (`WidgetCadence.drawRunBudgetMillis`, seit Build 103): an die
/// Zeichen-Frist gekoppelt, damit nach der Rückgabe der Timeline keine Arbeit mehr läuft (vorher 25 s).
final class WidgetDrawBudgetTests: XCTestCase {
    func testBudgetEndsBeforeDrawDeadline() {
        // Zeichen-Frist der Widgets: 8 s (`WidgetRefresh.drawDeadlineSeconds`, Widget-Ziel) → 7 s Kurse, 1 s Reserve
        XCTAssertEqual(WidgetCadence.drawRunBudgetMillis(deadlineSeconds: 8), 7_000)
        for seconds in [2.0, 5, 8, 12] {
            let budget = WidgetCadence.drawRunBudgetMillis(deadlineSeconds: seconds)
            XCTAssertLessThanOrEqual(budget + WidgetCadence.drawReserveMillis, Int64(seconds * 1_000), "\(seconds) s")
        }
    }

    func testBudgetHasFloorAndIgnoresInvalidDeadlines() {
        XCTAssertEqual(WidgetCadence.drawRunBudgetMillis(deadlineSeconds: 0.5), WidgetCadence.minDrawRunMillis)
        XCTAssertEqual(WidgetCadence.drawRunBudgetMillis(deadlineSeconds: 0), WidgetCadence.minDrawRunMillis)
        XCTAssertEqual(WidgetCadence.drawRunBudgetMillis(deadlineSeconds: -3), WidgetCadence.minDrawRunMillis)
        XCTAssertEqual(WidgetCadence.drawRunBudgetMillis(deadlineSeconds: .nan), WidgetCadence.minDrawRunMillis)
        XCTAssertEqual(WidgetCadence.drawRunBudgetMillis(deadlineSeconds: .infinity), WidgetCadence.minDrawRunMillis)
        // Weit unter dem Budget des Knopfs (25 s)
        XCTAssertLessThan(WidgetCadence.drawRunBudgetMillis(deadlineSeconds: 8), 25_000)
    }
}
