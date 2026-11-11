import Foundation

/// Schwebender «+»-Knopf des Portfolios beim Scrollen: Nach unten gescrollt (man liest die Liste) blendet er aus, damit
/// er keine Beträge verdeckt; nach oben gescrollt kommt er wieder. Ganz oben und ganz unten ist er immer da — unten
/// gibt der Einschub über der Tableiste ihm Platz. Reine Logik, gleich wie Android `AddButtonScroll`; gemeinsame
/// Fälle in `Parity/add_button_scroll.json`.
enum AddButtonScroll {
    /// So weit (pt bzw. dp) muss es am Stück in eine Richtung gehen, bevor der Knopf wechselt — kein Flackern.
    static let travel: Double = 12

    /// `travel`: bisheriger Weg in die laufende Richtung (positiv = nach unten gescrollt).
    struct State: Equatable, Sendable {
        var visible = true
        var travel: Double = 0
    }

    /// Zustand nach einem Scroll-Schritt. `delta` in pt bzw. dp, positiv = Inhalt nach unten gescrollt (spätere Zeilen
    /// kommen). `atTop`/`atEnd`: Liste steht am Anfang bzw. am Ende (auch eine Liste, die gar nicht scrollt).
    static func next(_ state: State, delta: Double, atTop: Bool, atEnd: Bool) -> State {
        if atTop || atEnd { return State(visible: true) }
        if delta == 0 { return state }
        // Richtungswechsel: der Weg beginnt neu
        let sameDirection = state.travel == 0 || (state.travel > 0) == (delta > 0)
        let moved = sameDirection ? state.travel + delta : delta
        if moved >= travel { return State(visible: false) }
        if moved <= -travel { return State(visible: true) }
        return State(visible: state.visible, travel: moved)
    }
}
