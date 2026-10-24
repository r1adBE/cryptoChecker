import XCTest
@testable import CryptoChecker

/// «Rückgängig» nach «Mehrere Paare auf einmal» löscht erst nach der Rückfrage (`BulkUndoConfirm`: nur «Entfernen» ruft
/// den Undo-Weg) und Meldungen mit Aktion tragen das Schliessen-✕ — wie Android `BulkUndoConfirmTest`.
@MainActor
final class BulkUndoConfirmTests: XCTestCase {

    func testUndoOnlyAfterRemove() {
        var undone: [[Int64]] = []
        var confirm = BulkUndoConfirm()
        confirm.request([1, 2, 3])
        XCTAssertTrue(confirm.isPresented)
        XCTAssertEqual(confirm.pending, [1, 2, 3])
        XCTAssertTrue(undone.isEmpty, "noch nichts entfernt")

        confirm.remove { undone.append($0) }
        XCTAssertEqual(undone, [[1, 2, 3]])
        XCTAssertFalse(confirm.isPresented)
        // Doppelt getippt: nur einmal entfernt
        confirm.remove { undone.append($0) }
        XCTAssertEqual(undone.count, 1)
    }

    func testKeepRemovesNothing() {
        var undone: [[Int64]] = []
        var confirm = BulkUndoConfirm()
        confirm.request([7])
        confirm.keep()
        XCTAssertNil(confirm.pending)
        confirm.remove { undone.append($0) }
        XCTAssertTrue(undone.isEmpty)
    }

    /// SwiftUI kann den Dialog (`isPresented` → «Behalten») schliessen, bevor die Aktion von «Entfernen» läuft: dann
    /// zählen die Paare, die der Dialog zeigte.
    func testRemoveUsesPresentedIdsAfterDismissal() {
        var undone: [[Int64]] = []
        var confirm = BulkUndoConfirm()
        confirm.request([4, 5])
        confirm.keep()
        confirm.remove([4, 5]) { undone.append($0) }
        XCTAssertEqual(undone, [[4, 5]])
    }

    func testNoDialogWithoutNewPairs() {
        var confirm = BulkUndoConfirm()
        confirm.request([])
        XCTAssertFalse(confirm.isPresented)
    }

    func testMessagesWithActionShowClose() {
        XCTAssertTrue(ExplorerSnackbar.hasAction(ExplorerMessage(text: "746", showView: false, undoIds: [1])))
        XCTAssertTrue(ExplorerSnackbar.hasAction(ExplorerMessage(text: "BTC/USDT")))
        XCTAssertTrue(ExplorerSnackbar.hasAction(ExplorerMessage(text: "x", showView: false, retryDexQuery: "pepe")))
        XCTAssertFalse(ExplorerSnackbar.hasAction(ExplorerMessage(text: "Kein Paar gewählt", showView: false)))

        XCTAssertTrue(WatchlistBannerMessage(text: "2 Paare entfernt", action: .undo {}).hasAction)
        XCTAssertFalse(WatchlistBannerMessage(text: "Favorit").hasAction)
    }
}
