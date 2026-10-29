import XCTest
@testable import CryptoChecker

/// `expirationHandler` der Hintergrund-Aufgabe (`BackgroundExpiry`): zuerst abbrechen und das Ende
/// melden, Protokoll und neues Vormerken erst danach und abseits des Aufrufers — `setTaskCompleted` wartet nie auf
/// Datei-Abstimmung.
final class BackgroundExpiryTests: XCTestCase {
    /// Thread-sicheres Protokoll der Schritte.
    private final class Steps: @unchecked Sendable { // @unchecked: Zugriff nur unter `lock`
        private let lock = NSLock()
        private var items: [String] = []
        func add(_ step: String) { lock.lock(); items.append(step); lock.unlock() }
        var all: [String] { lock.lock(); defer { lock.unlock() }; return items }
    }

    func testCompletesBeforeAnyFileWork() {
        let steps = Steps()
        var detached: [@Sendable () -> Void] = []
        var expiry = BackgroundExpiry(cancel: { steps.add("cancel") }, complete: { steps.add("complete") },
                                      afterwards: { steps.add("log") })
        expiry.detach = { detached.append($0) }
        expiry.run()
        // Beim Rückkehren aus dem Handler: abgebrochen und abgeschlossen, Protokoll noch nicht geschrieben
        XCTAssertEqual(steps.all, ["cancel", "complete"])
        XCTAssertEqual(detached.count, 1)
        detached.forEach { $0() }
        XCTAssertEqual(steps.all, ["cancel", "complete", "log"])
    }

    /// Mit dem echten Abkoppeln: Der Handler kehrt zurück, solange das Protokoll (Datei-Abstimmung) noch hängt —
    /// geprüft über die Reihenfolge, nicht über eine Zeitgrenze (die prüfte nur Laufzeit-Rauschen, z. B. unter TSAN).
    /// Der Handler läuft abseits des Tests: Wartete er auf das Protokoll, bliebe `returned` aus und der Test schlüge
    /// nach der Frist fehl, statt zu hängen.
    func testHandlerReturnsWhileLogBlocks() {
        let steps = Steps()
        let release = DispatchSemaphore(value: 0)
        let logged = expectation(description: "Protokoll nachgeholt")
        let returned = expectation(description: "Handler zurück")
        DispatchQueue.global(qos: .userInitiated).async {
            BackgroundExpiry(cancel: { steps.add("cancel") }, complete: { steps.add("complete") },
                             afterwards: {
                                 release.wait()
                                 steps.add("log")
                                 logged.fulfill()
                             }).run()
            returned.fulfill()
        }
        wait(for: [returned], timeout: 10)
        XCTAssertEqual(steps.all, ["cancel", "complete"], "setTaskCompleted darf nicht auf das Protokoll warten")
        release.signal()
        wait(for: [logged], timeout: 5)
        XCTAssertEqual(steps.all, ["cancel", "complete", "log"])
    }
}
