import Foundation

/// Blätterzustand der Paarliste für genau eine Anfrage einer Synchronisierung (erste Seite samt Folgeseiten).
///
/// `Market`-Instanzen gibt es je Börse nur einmal, gleichzeitige Abfragen teilen sie. Ein Cursor in der Börse selbst
/// würde zwei gleichzeitige Synchronisierungen derselben Börse vermischen — darum holt der Abgleich für jede Anfrage
/// einen frischen Zustand (`Market.pairListPaging()`) und fragt nur ihn nach der nächsten Seite.
/// Android: `PairListPaging.kt`.
protocol PairListPaging: AnyObject {
    /// Adresse der Seite nach `body` (Antwort auf Anfrage `call`); nil = keine weitere Seite. Wirft bei einer
    /// unlesbaren Antwort, damit die Anfrage scheitert statt Paare unbemerkt wegzulassen (wie Android).
    func nextPage(call: Int, body: String) throws -> String?
}

/// Für Börsen, deren nächste Seite nicht allein aus der letzten Antwort folgt (Gemini: Warteschlange der mehrdeutigen
/// Symbole aus der Liste). Sie liefern je Anfrage einen eigenen Zustand.
protocol PagedPairList {
    func newPairListPaging() -> PairListPaging
}

extension Market {
    /// Frischer Blätterzustand für eine Anfrage der Paarliste: der eigene der Börse (`PagedPairList`), sonst
    /// zustandslos über `pairListNextPage` (der Cursor steht in der Antwort, etwa Bybit `nextPageCursor`).
    func pairListPaging() -> PairListPaging {
        (self as? PagedPairList)?.newPairListPaging() ?? ResponseCursorPaging(market: self)
    }
}

/// Ohne eigenen Zustand: Die nächste Adresse folgt allein aus der letzten Antwort.
private final class ResponseCursorPaging: PairListPaging {
    private let market: Market

    init(market: Market) { self.market = market }

    func nextPage(call: Int, body: String) throws -> String? { try market.pairListNextPage(call: call, body: body) }
}
