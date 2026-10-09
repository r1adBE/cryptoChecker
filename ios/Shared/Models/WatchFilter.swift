import Foundation

/// Ansichten der Merkliste über den Gruppen-Chips: «Alle» (nil), «FAV» (`favorites`) und die
/// eigenen Gruppen — wie `WatchFilter.kt`. FAV ist keine Gruppe, sondern ein Filter auf das
/// Favoriten-Kennzeichen: Ein Paar bleibt in seiner Gruppe und steht als Favorit zusätzlich unter
/// FAV — Favorit an, gleich drin; aus, gleich draussen. Gilt auch für das Merkliste-Widget.
enum WatchFilter {
    /// Gespeicherter Wert für «FAV» (Steuerzeichen, kann kein Gruppenname sein; gleich wie Android,
    /// damit Sicherungen die Auswahl behalten).
    static let favorites = "\u{1}FAV"

    /// Beschriftung des Chips, in allen Sprachen gleich (Kurzform von «Favoriten»).
    static let favoritesLabel = "FAV"

    static func isFavorites(_ selection: String?) -> Bool { selection == favorites }

    /// Steht ein Paar in der Ansicht `selection`?
    static func matches(_ selection: String?, groupName: String?, favorite: Bool) -> Bool {
        guard let selection else { return true }
        return selection == favorites ? favorite : groupName == selection
    }

    static func matches(_ selection: String?, _ watch: Watch) -> Bool {
        matches(selection, groupName: watch.groupName, favorite: watch.favorite)
    }
}
