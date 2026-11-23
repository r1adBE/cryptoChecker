import SwiftUI
import UIKit

/// Auswahl-Schalter (`Picker` mit `.segmented`, z. B. «Einmal / Jedes Mal» im Alarm): gewählte Option in der
/// Akzent-Fläche mit passender Schrift statt hellgrau auf dunkelgrau — im dunklen Design sonst kaum zu erkennen.
/// Wie Android (SegmentedButton, gewählt in der Akzent-Fläche). Gilt für alle danach gezeigten Schalter; beim Wechsel
/// der Akzentfarbe neu gesetzt.
enum SegmentedAppearance {
    @MainActor
    static func apply(_ accent: AccentColor) {
        let control = UISegmentedControl.appearance()
        control.selectedSegmentTintColor = UIColor(accent.container)
        control.setTitleTextAttributes([.foregroundColor: UIColor(accent.onContainer)], for: .selected)
    }
}
