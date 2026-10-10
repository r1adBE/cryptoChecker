import SwiftUI

/// Coin-Listen wie eine Einheit (Merkliste, Start-Auswahl, Heute auffällig, Portfolio): jede Zeile
/// eine eigene Karte mit nur `gap` Abstand, aussen stark gerundet (oben an der ersten, unten an der
/// letzten Zeile), innen nur leicht — so sieht man die einzelnen Zeilen, die Liste wirkt aber als
/// Ganzes. Wie die Listen in den Einstellungen von iOS und Android 16; wie `ListSegment` (Android).
enum ListSegment {
    /// Fuge zwischen zwei Zeilen.
    static let gap: CGFloat = 2
    /// Abstand zu anderen Elementen über bzw. unter der Liste.
    static let spacing: CGFloat = 6
    /// Logo in allen Coin-Listen gleich gross.
    static let logo: CGFloat = 36

    private static let outer: CGFloat = 16
    private static let inner: CGFloat = 4

    /// Form der Zeile `index` von `count` Zeilen.
    static func shape(_ index: Int, _ count: Int) -> UnevenRoundedRectangle {
        let top = index <= 0 ? outer : inner
        let bottom = index >= count - 1 ? outer : inner
        return UnevenRoundedRectangle(topLeadingRadius: top, bottomLeadingRadius: bottom,
                                      bottomTrailingRadius: bottom, topTrailingRadius: top, style: .continuous)
    }
}
