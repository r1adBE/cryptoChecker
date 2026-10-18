import Foundation
import Network

/// Hat das Gerät gerade Internet (#22/#23)? Ohne Netz wird nicht aktualisiert (keine
/// Fehlerzustände, die Merkliste zeigt «Offline · Stand 19:41»); kommt es zurück, folgt eine
/// Aktualisierung (`AppData`). Solange noch kein Pfad gemeldet wurde, gilt «online» —
/// lieber versuchen als grundlos aussetzen (frischer Prozess im Hintergrund oder im Widget).
final class NetworkStatus: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    static let shared = NetworkStatus()

    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var known: Bool?
    private var observers: [UUID: @Sendable (Bool) -> Void] = [:]

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            self?.update(path.status == .satisfied)
        }
        monitor.start(queue: DispatchQueue(label: "cryptochecker.network-status"))
    }

    /// true = online oder (noch) unbekannt.
    var isOnline: Bool {
        lock.lock()
        defer { lock.unlock() }
        return known ?? true
    }

    /// Wie `isOnline`, aber ein gemerktes «offline» frisch am aktuellen Pfad des Monitors nachprüfen: Nach einer
    /// Pause des Prozesses (Hintergrund-Aufgabe, Widget) kann der gemerkte Stand veraltet sein.
    var isOnlineFresh: Bool {
        if isOnline { return true }
        return monitor.currentPath.status == .satisfied
    }

    /// Meldet jede Änderung (auf einer Hintergrund-Warteschlange); Rückgabe zum Abmelden.
    @discardableResult
    func observe(_ handler: @escaping @Sendable (Bool) -> Void) -> UUID {
        let id = UUID()
        lock.lock()
        observers[id] = handler
        lock.unlock()
        return id
    }

    func removeObserver(_ id: UUID) {
        lock.lock()
        observers[id] = nil
        lock.unlock()
    }

    private func update(_ online: Bool) {
        lock.lock()
        let changed = known != online
        known = online
        let handlers = Array(observers.values)
        lock.unlock()
        if changed { handlers.forEach { $0(online) } }
    }
}
