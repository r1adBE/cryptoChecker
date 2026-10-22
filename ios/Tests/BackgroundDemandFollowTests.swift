import XCTest
@testable import CryptoChecker

/// Bedarfs-Entscheid nach dem Vormerken (`BackgroundRefresh.followDemand`, seit Build 104, Review IOS2-R02): Kommen die
/// Entscheide zweier Planungen in umgekehrter Reihenfolge, sagt der ältere nicht mehr ab; mit Bedarf, aber ohne
/// vorgemerkte Anfrage wird nachgeholt; geprüft wird mit frisch geladenen Einstellungen.
final class BackgroundDemandFollowTests: XCTestCase {
    /// Hält Schritte und die wartenden Bedarfsprüfungen fest (thread-sicher).
    private final class Recorder: @unchecked Sendable { // @unchecked: Zustand nur unter `lock`
        private let lock = NSLock()
        private var steps: [String] = []
        private var pendingChecks: [@Sendable (Bool) -> Void] = []
        private var checkedIntervals: [Int] = []

        func add(_ step: String) { lock.lock(); steps.append(step); lock.unlock() }
        var all: [String] { lock.lock(); defer { lock.unlock() }; return steps }

        func waitCheck(_ settings: AppSettings, _ done: @escaping @Sendable (Bool) -> Void) {
            lock.lock(); pendingChecks.append(done); checkedIntervals.append(settings.backgroundIntervalMinutes); lock.unlock()
        }
        /// Bedarfsprüfung Nummer `index` (in Startreihenfolge) beantworten.
        func answer(_ index: Int, needed: Bool) {
            lock.lock(); let done = pendingChecks[index]; lock.unlock()
            done(needed)
        }
        var intervals: [Int] { lock.lock(); defer { lock.unlock() }; return checkedIntervals }
    }

    private func actions(_ recorder: Recorder, pending: Bool, interval: Int = 15) -> BackgroundDemandActions {
        BackgroundDemandActions(
            loadSettings: {
                var settings = AppSettings()
                settings.backgroundIntervalMinutes = interval
                return settings
            },
            workNeeded: { recorder.waitCheck($0, $1) },
            hasPending: { $0(pending) },
            cancel: { recorder.add("cancel") },
            submit: { _ in recorder.add("submit"); return nil },
            submitFailed: { _ in recorder.add("failed") })
    }

    func testOlderDecisionArrivingLastDoesNotCancel() {
        let gate = BackgroundDemandGate()
        let recorder = Recorder()
        let first = gate.next()
        BackgroundRefresh.followDemand(generation: first, gate: gate, actions: actions(recorder, pending: true))
        let second = gate.next()
        BackgroundRefresh.followDemand(generation: second, gate: gate, actions: actions(recorder, pending: true))
        // Neuere Planung zuerst: Bedarf, Anfrage steht → nichts zu tun
        recorder.answer(1, needed: true)
        // Ältere danach mit «kein Bedarf» (veralteter Stand): darf nicht mehr absagen
        recorder.answer(0, needed: false)
        XCTAssertEqual(recorder.all, [])
    }

    /// Gegenprobe: in normaler Reihenfolge sagt die neueste Planung ohne Bedarf ab.
    func testNewestDecisionWithoutDemandCancels() {
        let gate = BackgroundDemandGate()
        let recorder = Recorder()
        let first = gate.next()
        BackgroundRefresh.followDemand(generation: first, gate: gate, actions: actions(recorder, pending: true))
        let second = gate.next()
        BackgroundRefresh.followDemand(generation: second, gate: gate, actions: actions(recorder, pending: true))
        recorder.answer(0, needed: false)
        XCTAssertEqual(recorder.all, [], "überholte Planung sagt nicht ab")
        recorder.answer(1, needed: false)
        XCTAssertEqual(recorder.all, ["cancel"])
    }

    func testNeededWithoutPendingRequestSubmitsAgain() {
        let gate = BackgroundDemandGate()
        let recorder = Recorder()
        let first = gate.next()
        BackgroundRefresh.followDemand(generation: first, gate: gate, actions: actions(recorder, pending: false))
        recorder.answer(0, needed: true)
        XCTAssertEqual(recorder.all, ["submit"])
    }

    func testOutdatedNeededDecisionDoesNotSubmit() {
        let gate = BackgroundDemandGate()
        let recorder = Recorder()
        let first = gate.next()
        BackgroundRefresh.followDemand(generation: first, gate: gate, actions: actions(recorder, pending: false))
        _ = gate.next()
        recorder.answer(0, needed: true)
        XCTAssertEqual(recorder.all, [])
    }

    func testFailedSubmitIsReported() {
        let gate = BackgroundDemandGate()
        let recorder = Recorder()
        var failing = actions(recorder, pending: false)
        failing.submit = { _ in recorder.add("submit"); return CocoaError(.featureUnsupported) }
        BackgroundRefresh.followDemand(generation: gate.next(), gate: gate, actions: failing)
        recorder.answer(0, needed: true)
        XCTAssertEqual(recorder.all, ["submit", "failed"])
    }

    /// Geprüft wird mit den beim Entscheid geladenen Einstellungen, nicht mit denen des Aufrufers.
    func testDecisionUsesFreshSettings() {
        let gate = BackgroundDemandGate()
        let recorder = Recorder()
        BackgroundRefresh.followDemand(generation: gate.next(), gate: gate,
                                       actions: actions(recorder, pending: true, interval: 240))
        XCTAssertEqual(recorder.intervals, [240])
    }

    /// Seit Build 105 (Review IOS3-R05): Eine ältere, nicht ersetzende Planung, deren Abfrage erst nach einer neueren
    /// (ersetzenden) Planung zurückkommt, merkt nicht mehr vor — sie überschriebe sonst deren Anfrage.
    func testOlderFirstSubmitAfterNewerPlanDoesNotReplace() {
        let gate = BackgroundDemandGate()
        let recorder = Recorder()
        let older = gate.next()
        // Neuere Planung (replace, anderes Intervall) läuft sofort durch
        _ = gate.next()
        recorder.add("submit 30")
        // Abfrage der älteren kommt zurück: «keine Anfrage» → würde heute mit dem alten Intervall vormerken
        let done = BackgroundRefresh.submitIfCurrent(generation: older, gate: gate,
                                                     submit: { recorder.add("submit 15"); return nil },
                                                     submitFailed: { _ in recorder.add("failed") })
        XCTAssertFalse(done)
        XCTAssertEqual(recorder.all, ["submit 30"])
    }

    /// Gegenprobe: die neueste Planung merkt vor; ein Fehler wird ausserhalb der Sperre gemeldet.
    func testCurrentFirstSubmitRunsAndReportsFailure() {
        let gate = BackgroundDemandGate()
        let recorder = Recorder()
        let current = gate.next()
        XCTAssertTrue(BackgroundRefresh.submitIfCurrent(generation: current, gate: gate,
                                                        submit: { recorder.add("submit"); return nil },
                                                        submitFailed: { _ in recorder.add("failed") }))
        struct Failure: Error {}
        XCTAssertTrue(BackgroundRefresh.submitIfCurrent(generation: current, gate: gate,
                                                        submit: { recorder.add("submit"); return Failure() },
                                                        submitFailed: { _ in recorder.add("failed") }))
        XCTAssertEqual(recorder.all, ["submit", "submit", "failed"])
    }
}
