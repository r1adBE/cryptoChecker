import Foundation

/// Höchstens `MarketHTTP.maxRequests` Anfragen gleichzeitig, davon höchstens `MarketHTTP.maxRequestsPerHost` je Host
/// (wie der OkHttp-Dispatcher auf Android). Weitere warten hier, bevor ihre URLSession-Aufgabe entsteht — so laufen
/// `timeoutIntervalForRequest`/`ForResource` erst ab der Ausführung, und eine wartende Anfrage endet nie als
/// Schein-Zeitüberschreitung, die die Börse pausierte (`ExchangeBackoff`). Wer beim Warten abbricht, verlässt die
/// Schlange sofort.
actor RequestGate {
    static let shared = RequestGate()

    private struct Waiter {
        let id: UUID
        let host: String
        let continuation: CheckedContinuation<Void, Error>
    }

    private let maxTotal: Int
    private let maxPerHost: Int
    private var running = 0
    private var runningByHost: [String: Int] = [:]
    private var waiting: [Waiter] = []

    init(maxTotal: Int = MarketHTTP.maxRequests, maxPerHost: Int = MarketHTTP.maxRequestsPerHost) {
        self.maxTotal = max(1, maxTotal)
        self.maxPerHost = max(1, maxPerHost)
    }

    /// `body` mit einem Platz für `host`; der Platz wird danach in jedem Fall wieder frei.
    nonisolated func run<T: Sendable>(host: String, _ body: @Sendable () async throws -> T) async throws -> T {
        try await acquire(host: host)
        do {
            let value = try await body()
            await release(host: host)
            return value
        } catch {
            await release(host: host)
            throw error
        }
    }

    /// Laufende Anfragen (für Tests).
    var runningCount: Int { running }

    /// Wartende Anfragen (für Tests: auf den Zustand warten statt auf eine feste Zeit).
    var waitingCount: Int { waiting.count }

    private func canStart(_ host: String) -> Bool {
        running < maxTotal && runningByHost[host, default: 0] < maxPerHost
    }

    private func start(_ host: String) {
        running += 1
        runningByHost[host, default: 0] += 1
    }

    private func acquire(host: String) async throws {
        try Task.checkCancellation()
        // Niemand vor uns für diesen Host: sofort (die Reihenfolge der Wartenden bleibt gewahrt)
        if canStart(host) && !waiting.contains(where: { $0.host == host }) {
            start(host)
            return
        }
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                if Task.isCancelled {
                    // Schon abgebrochen, bevor die Abbruch-Meldung den Wartenden finden konnte
                    continuation.resume(throwing: CancellationError())
                } else if canStart(host) && !waiting.contains(where: { $0.host == host }) {
                    // Inzwischen frei geworden: nicht warten (sonst bliebe die Anfrage ohne weitere Freigabe liegen)
                    start(host)
                    continuation.resume()
                } else {
                    waiting.append(Waiter(id: id, host: host, continuation: continuation))
                }
            }
        } onCancel: {
            Task { await self.cancelWaiter(id) }
        }
    }

    private func release(host: String) {
        running = max(0, running - 1)
        let left = runningByHost[host, default: 1] - 1
        runningByHost[host] = left > 0 ? left : nil
        startWaiting()
    }

    /// Wartende in ihrer Reihenfolge starten, soweit Plätze frei sind (ein voller Host hält andere nicht auf).
    private func startWaiting() {
        var index = 0
        while index < waiting.count && running < maxTotal {
            if canStart(waiting[index].host) {
                let waiter = waiting.remove(at: index)
                start(waiter.host)
                waiter.continuation.resume()
            } else {
                index += 1
            }
        }
    }

    private func cancelWaiter(_ id: UUID) {
        guard let index = waiting.firstIndex(where: { $0.id == id }) else { return }
        waiting.remove(at: index).continuation.resume(throwing: CancellationError())
    }
}
