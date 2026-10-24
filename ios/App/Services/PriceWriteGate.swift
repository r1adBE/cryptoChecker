import Foundation

/// Schreib-Gate der Kurse im App-Prozess: Vollständige und einzelne Aktualisierung (`AppData.refreshAll`/`refreshOne`),
/// Live-Kurse (`AppData.applyLive`) und das Speichern der Hintergrund-Aufgabe (`BackgroundRefresh`) wenden ihre
/// Ergebnisse nacheinander an — nie zwei zugleich. Die Netzabfragen laufen davor und dürfen sich überlappen; nur das
/// Übernehmen und Speichern ist serialisiert, wie Android `RefreshLock` um das Speichern. Ein älterer Kurs, der erst
/// nach einem neueren ankommt, bleibt zusätzlich weg (`PriceWriteOrder`).
///
/// Wartende kommen der Reihe nach dran (FIFO, Fortsetzungen statt Abfragen in Schleife); `release` gibt das Gate direkt
/// an den nächsten weiter, niemand drängelt vor. Nicht wiedereintrittsfähig: Wer es hält, belegt es nicht erneut.
@MainActor
final class PriceWriteGate {
    static let shared = PriceWriteGate()

    /// true, solange jemand das Gate hält.
    private(set) var isBusy = false
    private var waiters: [(id: UInt64, continuation: CheckedContinuation<Bool, Never>)] = []
    private var nextWaiterId: UInt64 = 0

    /// Anzahl Wartender (Tests, Protokoll).
    var waitingCount: Int { waiters.count }

    /// Belegt das Gate sofort, wenn es frei ist; sonst false (der Aufrufer versucht es später, z. B. Live-Kurse).
    func tryAcquire() -> Bool {
        guard !isBusy else { return false }
        isBusy = true
        return true
    }

    /// Wartet, bis das Gate an der Reihe frei wird, und belegt es.
    /// - Parameter cancellable: true = bricht der Task ab, endet das Warten sofort mit false (Gate nicht belegt);
    ///   false = wartet trotz Abbruch, bis es belegt ist (ein schon geholtes Ergebnis soll noch gespeichert werden).
    /// - Returns: true = belegt; der Aufrufer muss `release()` aufrufen.
    func acquire(cancellable: Bool = true) async -> Bool {
        if cancellable && Task.isCancelled { return false }
        if tryAcquire() { return true }
        nextWaiterId &+= 1
        let id = nextWaiterId
        guard cancellable else {
            return await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
                waiters.append((id, continuation))
            }
        }
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
                waiters.append((id, continuation))
            }
        } onCancel: {
            // Läuft auf beliebigem Faden: zurück auf den MainActor. Das Anstellen oben geschieht vorher (synchron auf
            // dem MainActor), der Wartende wird also sicher gefunden — ausser er ist schon an der Reihe gewesen.
            Task { @MainActor in self.cancelWaiter(id) }
        }
    }

    /// Gibt das Gate frei bzw. direkt an den nächsten Wartenden weiter.
    func release() {
        guard isBusy else { return }
        if waiters.isEmpty {
            isBusy = false
        } else {
            // Bleibt belegt: geht an den ersten Wartenden über
            waiters.removeFirst().continuation.resume(returning: true)
        }
    }

    /// Wartet, bis alle vorher Angestellten fertig sind (belegt kurz und gibt gleich wieder frei).
    /// - Returns: false = abgebrochen, bevor das Gate an der Reihe war.
    func waitUntilFree() async -> Bool {
        guard await acquire() else { return false }
        release()
        return true
    }

    /// Abgebrochener Wartender: aus der Reihe nehmen und mit false fortsetzen (schon an der Reihe gewesen: nichts tun).
    private func cancelWaiter(_ id: UInt64) {
        guard let index = waiters.firstIndex(where: { $0.id == id }) else { return }
        waiters.remove(at: index).continuation.resume(returning: false)
    }
}
