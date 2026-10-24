import XCTest
@testable import CryptoChecker

/// «Rückgängig» nach «Mehrere Paare auf einmal» löscht erst nach der Rückfrage (`BulkUndoConfirm`: nur «Entfernen» ruft
/// den Undo-Weg) und Meldungen mit Aktion tragen das Schliessen-✕ — wie Android `BulkUndoConfirmTest`.
@MainActor
final class BulkUndoConfirmTests: XCTestCase {

    private let end: Int64 = 1_000_000
    private var inWindow: Int64 { end + 5_000 }

    func testUndoOnlyAfterRemove() {
        var undone: [[Int64]] = []
        var confirm = BulkUndoConfirm()
        confirm.request([1, 2, 3], finishedAt: end)
        XCTAssertTrue(confirm.isPresented)
        XCTAssertEqual(confirm.pending, [1, 2, 3])
        XCTAssertTrue(undone.isEmpty, "noch nichts entfernt")

        confirm.remove(now: inWindow, expired: {}) { undone.append($0) }
        XCTAssertEqual(undone, [[1, 2, 3]])
        XCTAssertFalse(confirm.isPresented)
        // Doppelt getippt: nur einmal entfernt
        confirm.remove(now: inWindow, expired: {}) { undone.append($0) }
        XCTAssertEqual(undone.count, 1)
    }

    func testKeepRemovesNothing() {
        var undone: [[Int64]] = []
        var confirm = BulkUndoConfirm()
        confirm.request([7], finishedAt: end)
        confirm.keep()
        XCTAssertNil(confirm.pending)
        confirm.remove(now: inWindow, expired: {}) { undone.append($0) }
        XCTAssertTrue(undone.isEmpty)
    }

    /// SwiftUI kann den Dialog (`isPresented` → «Behalten») schliessen, bevor die Aktion von «Entfernen» läuft: dann
    /// zählen die Paare, die der Dialog zeigte.
    func testRemoveUsesPresentedIdsAfterDismissal() {
        var undone: [[Int64]] = []
        var confirm = BulkUndoConfirm()
        confirm.request([4, 5], finishedAt: end)
        confirm.keep()
        confirm.remove([4, 5], now: inWindow, expired: {}) { undone.append($0) }
        XCTAssertEqual(undone, [[4, 5]])
    }

    func testNoDialogWithoutNewPairs() {
        var confirm = BulkUndoConfirm()
        confirm.request([], finishedAt: end)
        XCTAssertFalse(confirm.isPresented)
        XCTAssertEqual(confirm.lastCount, 0)
    }

    /// Titel beim Ausblenden: die Zahl der letzten Anfrage bleibt (kein «0 Paare …», während der Dialog verschwindet).
    func testLastCountSurvivesDismissal() {
        var confirm = BulkUndoConfirm()
        confirm.request([1, 2, 3], finishedAt: end)
        XCTAssertEqual(confirm.lastCount, 3)
        confirm.keep()
        XCTAssertEqual(confirm.lastCount, 3)
        confirm.request([9], finishedAt: end)
        confirm.remove(now: inWindow, expired: {}) { _ in }
        XCTAssertEqual(confirm.lastCount, 1)
    }

    /// Wie Android: «Entfernen» nach Ablauf der 60 s löscht nichts, schliesst den Dialog und meldet `expired`; genau an
    /// der Grenze gilt es noch; ohne Ende (0) bzw. mit Uhr rückwärts nicht.
    func testRemoveAfterUndoWindowDeletesNothing() {
        var undone: [[Int64]] = []
        var expired = 0
        var confirm = BulkUndoConfirm()
        confirm.request([1, 2], finishedAt: end)
        confirm.remove(now: end + BulkUndoConfirm.undoWindowMillis + 1, expired: { expired += 1 }) { undone.append($0) }
        XCTAssertTrue(undone.isEmpty, "abgelaufen: nichts gelöscht")
        XCTAssertEqual(expired, 1)
        XCTAssertFalse(confirm.isPresented, "Dialog zu")

        confirm.request([3], finishedAt: end)
        confirm.remove(now: end + BulkUndoConfirm.undoWindowMillis, expired: { expired += 1 }) { undone.append($0) }
        XCTAssertEqual(undone, [[3]])
        XCTAssertEqual(expired, 1)

        XCTAssertFalse(BulkUndoConfirm.undoOffered(finishedAt: 0, now: 5_000))
        XCTAssertFalse(BulkUndoConfirm.undoOffered(finishedAt: end, now: end - 1), "Uhr rückwärts")
        XCTAssertTrue(BulkUndoConfirm.undoOffered(finishedAt: end, now: end))
        XCTAssertEqual(BulkUndoConfirm.undoWindowMillis, 60_000)
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
