import SwiftUI

/// Hell/Dunkel der App aus den Einstellungen: «Nach Uhrzeit» (`ThemeSchedule`), fest Hell/Dunkel oder wie das System
/// (`preferredColorScheme` nil). Mit «Nach Uhrzeit» wechselt das Bild zur eingestellten Zeit von selbst und wird geprüft,
/// sobald die App wieder aktiv wird — wie Android `rememberAppDark`.
struct AppColorSchemeModifier: ViewModifier {
    let settings: AppSettings
    @State private var now = Date()
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content
            .preferredColorScheme(ThemeSchedule.forcedDark(settings, at: now).map { $0 ? .dark : .light })
            .task(id: "\(settings.themeScheduled)-\(settings.themeDarkFrom)-\(settings.themeLightFrom)") {
                await followSwitches()
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { now = Date() }
            }
    }

    /// Bis zum nächsten Wechsel warten (`ContinuousClock` zählt auch im Ruhezustand), dann neu zeichnen.
    private func followSwitches() async {
        now = Date()
        while !Task.isCancelled, let next = ThemeSchedule.nextSwitch(settings, after: Date()) {
            // Eine Sekunde nach dem Wechsel: die Minute des Wechsels ist sicher erreicht
            try? await Task.sleep(for: .seconds(max(next.timeIntervalSinceNow, 0) + 1))
            now = Date()
        }
    }
}

extension View {
    /// Hell/Dunkel der App (`AppColorSchemeModifier`).
    func appColorScheme(_ settings: AppSettings) -> some View {
        modifier(AppColorSchemeModifier(settings: settings))
    }
}
