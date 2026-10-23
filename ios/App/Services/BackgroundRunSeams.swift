import Foundation

/// Austauschbare Schritte eines Laufs von `BackgroundRefresh.handleRefresh`: In der App die echten
/// Wege, in Tests Fälschungen — z. B. ein Ablauf genau zwischen dem Speichern der Kurse und dem Melden.
struct BackgroundRunSeams: Sendable {
    /// Kurse holen und Alarme auswerten (`PriceRefresher.refresh`).
    var refresh: @Sendable (SharedStorage.Snapshot, AppSettings, RefreshDeadline) async -> PriceRefresher.Outcome
    /// Gleich nach dem Speichern der Kurse, vor dem Melden (Tests: Ablauf auslösen).
    var afterPriceSave: @Sendable () async -> Void = {}
    /// Alarme mit Zusatzdaten prüfen (`PriceRefresher.checkDeferredAlarms`; Tests: ohne Netz).
    var checkDeferred: @Sendable (PriceRefresher.DeferredAlarms, AppSettings, RefreshDeadline) async
        -> PriceRefresher.Outcome = { deferred, settings, deadline in
            await PriceRefresher.checkDeferredAlarms(deferred, settings: settings, deadline: deadline)
        }
    /// Gleich nach dem Speichern der Zusatzdaten-Alarme, vor dem Melden (Tests: Ablauf auslösen).
    var afterExtraSave: @Sendable () async -> Void = {}
    /// Mitteilungs-Weg der Paar-Alarme (Kurs- und auch Zusatzdaten-Pfad).
    var notifier: PriceRefresher.AlarmNotifier = .system
    /// Startet Protokoll und neues Vormerken nach einem Ablauf (`BackgroundExpiry.detach`; Tests: sofort, damit nichts
    /// nach dem Test noch schreibt).
    var detachAfterExpiry: @Sendable (@escaping @Sendable () -> Void) -> Void = { work in
        _ = Task.detached(priority: .utility) { work() }
    }

    static let live = BackgroundRunSeams(refresh: { snapshot, settings, deadline in
        await PriceRefresher.refresh(snapshot: snapshot, settings: settings,
                                     networkBudgetMillis: RefreshBudget.networkMillis, deadline: deadline)
    })
}
