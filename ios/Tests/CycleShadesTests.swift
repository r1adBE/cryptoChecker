import XCTest
@testable import CryptoChecker

/// V4-11: ab dem Halving 2028 (drei frühere Zyklen) hat jeder frühere Zyklus einen eigenen Grauton. Android: CycleShadesTest.
final class CycleShadesTests: XCTestCase {
    func testHeuteZweiFruehereZyklenWieBisher() {
        XCTAssertEqual(CycleShades.olderAlpha(0, olderCount: 2), 0.65, accuracy: 1e-9)
        XCTAssertEqual(CycleShades.olderAlpha(1, olderCount: 2), 0.85, accuracy: 1e-9)
    }

    func testAb2028JederZyklusEigenerTon() {
        let alphas = (0..<3).map { CycleShades.olderAlpha($0, olderCount: 3) }
        XCTAssertEqual(alphas.map { ($0 * 100).rounded() }, [65, 75, 85])
        for n in 1...6 { for i in 0..<n { XCTAssertGreaterThanOrEqual(CycleShades.olderAlpha(i, olderCount: n), CycleShades.oldest) } }
    }

    func testEinFruehererZyklus() {
        XCTAssertEqual(CycleShades.olderAlpha(0, olderCount: 1), CycleShades.newest)
    }
}
