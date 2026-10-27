import WidgetKit

/// Komplikationen neu zeichnen lassen (neue Kurse oder neue Wahl).
enum WatchComplications {
    static func reload() {
        WidgetCenter.shared.reloadAllTimelines()
    }
}
