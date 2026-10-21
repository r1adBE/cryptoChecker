import SwiftUI
import UIKit

/// Haptik-Katalog nach Ereignis (nicht nach API), gleich wie Android `Haptics.kt` — beide Plattformen
/// vibrieren in denselben Momenten. Seit Build 100 der einzige Ort, der die `UI…FeedbackGenerator` anspricht
/// (`HapticsUsageTest` wacht darüber); auch Wertwechsel laufen über `.haptic(_:trigger:)` statt `.sensoryFeedback`.
///
/// | Ereignis      | wann                                                              | iOS                  | Android (API 30+ / 26–29) |
/// |---------------|-------------------------------------------------------------------|----------------------|---------------------------|
/// | selection     | Auswahl ändert sich: Chip, Segment, Schalter, Picker,             | Selection            | CLOCK_TICK                |
/// |               | Chart-Schritt beim Scrubben, Wisch-Schwelle                       |                      |                           |
/// | lightImpact   | leichte Aktion: Ablegen beim Sortieren, Sprung, Favorit, Gruppe   | Impact light         | KEYBOARD_TAP              |
/// | longPress     | langes Drücken öffnet Modus/Menü, Ziehen beginnt                  | Impact medium        | LONG_PRESS                |
/// | success       | gespeichert, Sicherung/Wiederherstellung fertig, Alarm angelegt,  | Notification success | CONFIRM / VIRTUAL_KEY     |
/// |               | Rückgängig gemacht, Adresse kopiert                               |                      |                           |
/// | warning       | Grenze erreicht (2000 Paare, 10 Alarme), Speichern mit Fehlern    | Notification warning | REJECT / LONG_PRESS       |
/// | error         | Vorgang fehlgeschlagen (Fehlerbanner)                             | Notification error   | REJECT / LONG_PRESS       |
/// | destructive   | Löschen bestätigt                                                 | Impact heavy         | LONG_PRESS                |
///
/// Eine eigene App-Einstellung für Haptik gibt es nicht; iOS dämpft die Generatoren selbst, wenn
/// «Systemhaptik» aus ist. Nie im Hintergrund, in Mitteilungen oder Widgets verwenden — nur als Antwort
/// auf eine Handlung im Bildschirm; kein Ticken je Kursänderung oder beim Scrollen.
enum HapticEvent: CaseIterable {
    case selection, lightImpact, longPress, success, warning, error, destructive
}

/// Welcher Generator ein Ereignis spielt (reine Zuordnung, testbar ohne Gerät).
enum HapticKind: Equatable {
    case selection
    case impact(UIImpactFeedbackGenerator.FeedbackStyle)
    case notification(UINotificationFeedbackGenerator.FeedbackType)
}

enum Haptics {
    static func kind(for event: HapticEvent) -> HapticKind {
        switch event {
        case .selection: return .selection
        case .lightImpact: return .impact(.light)
        case .longPress: return .impact(.medium)
        case .success: return .notification(.success)
        case .warning: return .notification(.warning)
        case .error: return .notification(.error)
        case .destructive: return .impact(.heavy)
        }
    }

    @MainActor static func play(_ event: HapticEvent) {
        switch kind(for: event) {
        case .selection: UISelectionFeedbackGenerator().selectionChanged()
        case .impact(let style): UIImpactFeedbackGenerator(style: style).impactOccurred()
        case .notification(let type): UINotificationFeedbackGenerator().notificationOccurred(type)
        }
    }
}

@MainActor
extension View {
    /// Spielt `event`, wenn sich `trigger` ändert (wie `.sensoryFeedback`, aber über den Katalog).
    func haptic<T: Equatable>(_ event: HapticEvent, trigger: T) -> some View {
        onChange(of: trigger) { Haptics.play(event) }
    }

    /// Wie oben, aber nur wenn `condition(alt, neu)` zutrifft (etwa nur bei einem neuen Chart-Punkt).
    func haptic<T: Equatable>(_ event: HapticEvent, trigger: T, condition: @escaping (T, T) -> Bool) -> some View {
        onChange(of: trigger) { old, new in
            if condition(old, new) { Haptics.play(event) }
        }
    }
}
