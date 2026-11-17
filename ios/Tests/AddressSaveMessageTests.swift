import XCTest
@testable import CryptoChecker

/// Meldung zu «Adresse hinzufügen»: nicht geschrieben heisst nie «ungültig» — die Prüfung lief davor grün —, sondern
/// «gesperrt» bzw. «nicht gespeichert». Wie Android `AddressSaveMessageTest.kt`.
@MainActor
final class AddressSaveMessageTests: XCTestCase {
    func testNotWrittenIsNeverInvalid() {
        XCTAssertEqual(PortfolioAddressSheet.message(AddWatchedResult.writeFailed), L("portfolio_write_failed"))
        XCTAssertEqual(PortfolioAddressSheet.message(AddWatchedResult.locked), L("portfolio_storage_locked"))
    }

    func testEveryOtherResultNamesItsReason() {
        XCTAssertNil(PortfolioAddressSheet.message(AddWatchedResult.added(7)))
        XCTAssertEqual(PortfolioAddressSheet.message(AddWatchedResult.invalid(.BAD_CHECKSUM)), L("address_checksum"))
        XCTAssertEqual(PortfolioAddressSheet.message(AddWatchedResult.duplicate), L("address_duplicate"))
        XCTAssertEqual(PortfolioAddressSheet.message(AddWatchedResult.limitReached), L("address_limit"))
    }
}
