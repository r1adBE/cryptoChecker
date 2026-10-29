import Foundation
import Network
import os

/// Hat das Gerät gerade Internet (#22/#23)? Ohne Netz wird nicht aktualisiert (keine
/// Fehlerzustände, die Merkliste zeigt «Offline · Stand 19:41»); kommt es zurück, folgt eine
/// Aktualisierung (`AppData`). Solange noch kein Pfad gemeldet wurde, gilt «online» —
/// lieber versuchen als grundlos aussetzen (frischer Prozess im Hintergrund oder im Widget).
final class NetworkStatus: Sendable {
    static let shared = NetworkStatus()

    private struct State: Sendable {
        var known: Bool?
        var observers: [@Sendable (Bool) -> Void] = []
    }

    private let monitor = NWPathMonitor()
    private let state = OSAllocatedUnfairLock(initialState: State())

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            self?.update(path.status == .satisfied)
        }
        monitor.start(queue: DispatchQueue(label: "cryptochecker.network-status"))
    }

    /// true = online oder (noch) unbekannt.
    var isOnline: Bool {
        state.withLock { $0.known ?? true }
    }

    /// Wie `isOnline`, aber ein gemerktes «offline» frisch am aktuellen Pfad des Monitors nachprüfen: Nach einer
    /// Pause des Prozesses (Hintergrund-Aufgabe, Widget) kann der gemerkte Stand veraltet sein.
    var isOnlineFresh: Bool {
        if isOnline { return true }
        return monitor.currentPath.status == .satisfied
    }

    /// Meldet jede Änderung (auf einer Hintergrund-Warteschlange), solange der Prozess läuft.
    func observe(_ handler: @escaping @Sendable (Bool) -> Void) {
        state.withLock { $0.observers.append(handler) }
    }

    private func update(_ online: Bool) {
        let (changed, handlers) = state.withLock { s -> (Bool, [@Sendable (Bool) -> Void]) in
            let changed = s.known != online
            s.known = online
            return (changed, s.observers)
        }
        if changed { handlers.forEach { $0(online) } }
    }
}
