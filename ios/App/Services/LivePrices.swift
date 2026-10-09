import Foundation
import Observation

/// Live-Kurs EINES Paars, einzeln beobachtbar: Ein Tick zeichnet nur die Zeilen neu, die diesen
/// Kurs lesen — nicht Merkliste, Markt, Portfolio und Einstellungen (die hängen an `AppData`).
@MainActor
@Observable
final class LiveQuoteBox {
    var quote: LiveQuote?
}

/// Live-Kurse per WebSocket (`LivePriceStream`) je Watch-Id — nur Anzeige, höchstens zweimal je
/// Sekunde neu. Bewusst NICHT in `AppData` (`@Published` würde bei jedem Tick alle Ansichten
/// neu aufbauen). Lesen: `quote(for:)` im `body` einer Zeile bzw. über `AppData.watch(_:)`;
/// gespeichert wird weiterhin gesammelt alle 10 s (`AppData.applyLive` → `PriceRefresher`).
@MainActor
final class LivePrices {
    static let shared = LivePrices()

    /// Kästchen bleiben bestehen (eine Ansicht, die ein leeres beobachtet, soll den ersten Kurs
    /// sehen); es sind höchstens so viele wie Paare.
    private var boxes: [Int64: LiveQuoteBox] = [:]
    /// Watch-Ids mit Kurs (schnelles Leeren).
    private var filled: Set<Int64> = []

    private init() {}

    private func box(_ id: Int64) -> LiveQuoteBox {
        if let existing = boxes[id] { return existing }
        let created = LiveQuoteBox()
        boxes[id] = created
        return created
    }

    /// Live-Kurs eines Paars (nil = keiner). Im `body` gelesen: nur diese Ansicht beobachtet ihn.
    func quote(for id: Int64) -> LiveQuote? {
        box(id).quote
    }

    /// Neuer Stand vom Strom: nur geänderte Kurse lösen eine Neuzeichnung aus.
    func apply(_ quotes: [Int64: LiveQuote]) {
        for (id, quote) in quotes {
            let target = box(id)
            if target.quote != quote { target.quote = quote }
        }
        for id in filled where quotes[id] == nil {
            boxes[id]?.quote = nil
        }
        filled = Set(quotes.keys)
    }

    /// Strom beendet: wieder die gespeicherten Kurse zeigen.
    func clear() {
        for id in filled { boxes[id]?.quote = nil }
        filled = []
    }
}
