import Foundation

// Bedarf der Hintergrund-Aktualisierung nachführen (Teil von `AppData`) — wie Android
// `PriceUpdateScheduler.followWork`: Paare, Alarme, Einstellungen und Portfolio-Alarme beobachten und neu planen bzw.
// absagen, sobald sich der Bedarf ändert; Kurs-Updates ändern ihn nicht. Widgets: iOS meldet Hinzufügen und Entfernen
// nicht — geprüft bei jedem Wechsel nach vorne bzw. in den Hintergrund (`BackgroundRefresh.schedule` fragt WidgetKit).

/// Zuletzt gesehene Eingaben (ohne Widgets); nil = noch keine. Global, weil Erweiterungen keine gespeicherten
/// Eigenschaften haben; nur auf dem MainActor.
@MainActor private var lastBackgroundDemand: BackgroundWorkDemand.WorkDemand?

extension AppData {
    /// Nach jeder Änderung des Stands bzw. der Einstellungen: Haben sich die Eingaben des Bedarfs geändert (neues Paar,
    /// Alarm an/aus, Mitteilung, Ansage, Portfolio, Gas/Depeg …), neu planen — `scheduleRefresh` rechnet dann mit
    /// Widgets und dem gespeicherten Stand und sagt ab bzw. merkt vor. Der erste Stand nach dem Start legt nur die Basis
    /// (geplant wird beim Start ohnehin) — schon nach dem Lesen beim Start (`primeBackgroundDemand`), sonst plante
    /// die erste echte Änderung nach dem Start nicht neu.
    func followBackgroundDemand() {
        let demand = BackgroundWorkDemand.demand(settings: settings, read: .ok(snapshot), widgets: .none)
        guard demand != lastBackgroundDemand else { return }
        let first = lastBackgroundDemand == nil
        lastBackgroundDemand = demand
        guard !first, settingsAccessible else { return }
        BackgroundRefresh.schedule(settings: settings)
    }

    /// Basis aus dem eben gelesenen Stand (nach dem Lesen beim Start, `finishLaunchLoad`).
    func primeBackgroundDemand() {
        lastBackgroundDemand = BackgroundWorkDemand.demand(settings: settings, read: .ok(snapshot), widgets: .none)
    }
}
