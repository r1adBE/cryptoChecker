import Combine
import Foundation
import Observation

/// Zustand EINER Zeile der Merkliste, einzeln beobachtbar: wird gerade einzeln aktualisiert, hängt am Live-Strom.
/// Ändert sich die Menge in `RefreshState`, zeichnen nur die Zeilen neu, deren Wert wechselt (wie `LiveQuoteBox`).
@MainActor
@Observable
final class RowRefreshBox {
    var refreshing = false
    var live = false
}

/// Läuft eine volle Aktualisierung? Eigenes Kästchen für die Zeilen: Nur Zeilen ohne Kurs lesen es
/// (`RefreshState.rowLoading`), Zeilen mit Kurs zeichnen beim Start und Ende nicht neu.
@MainActor
@Observable
final class FullRefreshFlag {
    var running = false
}

/// Reine Status-Werte der Aktualisierung und des Live-Stroms, getrennt von `AppData` gehalten
/// (`AppData.refreshState`): Eine Änderung veröffentlicht nur hier, `AppData.objectWillChange` bleibt still —
/// die Merkliste rechnet ihren `body` deshalb nicht neu. Neu zeichnen nur die Ansichten, die einen dieser Werte
/// zeigen und dieses Objekt beobachten (Statuszeile mit Kreis, Menü, Bericht, Aktionsblatt) bzw. je Zeile ihr
/// Kästchen (`rowLoading`, `rowOnLive`).
/// Geschrieben wird nur von `AppData` (Aktualisieren, Live-Strom, Lesen von der Platte).
@MainActor
final class RefreshState: ObservableObject {
    /// Volle Aktualisierung läuft (`AppData.refreshAll`).
    @Published var refreshing = false {
        didSet { if fullFlag.running != refreshing { fullFlag.running = refreshing } }
    }
    /// Dauer der letzten vollen Aktualisierung (ms, gespeichert); 0 = noch keine.
    @Published var lastRefreshMillis: Int64
    /// Bericht der letzten vollen Aktualisierung (gespeichert, Blatt «Bericht»).
    @Published var lastRefreshReport: RefreshReport?
    /// Paare, die gerade einzeln aktualisiert werden.
    @Published var refreshingWatchIds: Set<Int64> = [] {
        didSet {
            for id in oldValue.symmetricDifference(refreshingWatchIds) {
                boxes[id]?.refreshing = refreshingWatchIds.contains(id)
            }
        }
    }
    /// Börsen, deren Live-Strom gerade Kurse liefert («LIVE» im Bericht).
    @Published var liveExchanges: [String] = []
    /// Watch-Ids der Paare am Live-Strom (Börse liefert gerade): Frische 2 Min., «Live» nur mit allen.
    @Published var liveWatchIds: Set<Int64> = [] {
        didSet {
            for id in oldValue.symmetricDifference(liveWatchIds) {
                boxes[id]?.live = liveWatchIds.contains(id)
            }
        }
    }

    /// Kästchen je Watch-Id; bleiben bestehen (eine Zeile, die ein Kästchen beobachtet, soll jeden Wechsel sehen).
    private var boxes: [Int64: RowRefreshBox] = [:]
    private let fullFlag = FullRefreshFlag()

    init(lastRefreshMillis: Int64 = 0, lastRefreshReport: RefreshReport? = nil) {
        self.lastRefreshMillis = lastRefreshMillis
        self.lastRefreshReport = lastRefreshReport
    }

    private func box(_ id: Int64) -> RowRefreshBox {
        if let existing = boxes[id] { return existing }
        let created = RowRefreshBox()
        created.refreshing = refreshingWatchIds.contains(id)
        created.live = liveWatchIds.contains(id)
        boxes[id] = created
        return created
    }

    /// Zeile zeigt «wird aktualisiert»: einzeln in Arbeit oder volle Aktualisierung bei einem Paar noch ohne Kurs.
    /// Im `body` einer Zeile gelesen: nur diese Zeile beobachtet ihren Wert.
    func rowLoading(_ watchId: Int64, priceMissing: Bool) -> Bool {
        if box(watchId).refreshing { return true }
        return priceMissing && fullFlag.running
    }

    /// Paar am Live-Strom (eigene Frische-Schwelle). Im `body` einer Zeile gelesen: nur diese Zeile beobachtet es.
    func rowOnLive(_ watchId: Int64) -> Bool {
        box(watchId).live
    }
}
