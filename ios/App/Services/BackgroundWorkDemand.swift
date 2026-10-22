import Foundation

/// Braucht es die periodische Hintergrund-Aktualisierung? Spiegel von `BackgroundWorkPolicy.periodicNeeded` und
/// `portfolioWork` (Android, `PriceUpdateScheduler`), gemeinsame Fälle in `periodic_needed.json`. Seit Build 103 — vorher
/// plante iOS die Aufgabe immer. Ohne Bedarf sagt `BackgroundRefresh.scheduleRefresh` sie ab (kein Akku für nichts).
enum BackgroundWorkDemand {
    /// Was ein Paar im Hintergrund braucht.
    struct PairWork: Equatable, Sendable {
        var disabled: Bool
        var hasEnabledAlarm: Bool
        var notificationEnabled: Bool
        var ttsEnabled: Bool
    }

    /// Eingaben für `periodicNeeded` (Felder wie Android `WorkDemand`).
    struct WorkDemand: Equatable, Sendable {
        var pairs: [PairWork]
        /// Schalter «Kurs-Mitteilungen».
        var priceNotifications: Bool
        /// Sprachausgabe an und nicht «Nur Alarme» (Kursansagen je Paar).
        var ttsPrices: Bool
        /// Meldung «Ungewöhnliche Aktivität».
        var activityAlerts: Bool
        /// Merkliste- oder Einzel-Widget auf dem Home-Bildschirm.
        var pairWidgets: Bool
        /// Portfolio-Widget oder scharfer Portfolio-Alarm (`portfolioWork`).
        var portfolioWork: Bool
        /// Gas- oder USDT-Depeg-Alarm (prüft der Lauf auch ohne Paare).
        var marketAlarms: Bool
        /// Gespeicherte Merkliste gerade nicht lesbar: Die leere Liste sagt nichts über den Bedarf, darum geplant lassen.
        var watchlistKept: Bool = false
        /// Puls-Widget auf dem Home-Bildschirm (zeichnet mit jedem Lauf neu, auch ohne Paare).
        var pulseWidget: Bool = false
    }

    /// Portfolio-Teil von `WorkDemand.portfolioWork`. `portfolioKept`: gespeicherter Stand gerade nicht lesbar — wie ein
    /// scharfer Alarm behandeln, damit der leere Stand im Speicher nichts absagt.
    static func portfolioWork(portfolioEnabled: Bool, alarmOn: Bool, portfolioKept: Bool, widget: Bool) -> Bool {
        widget || (portfolioEnabled && (alarmOn || portfolioKept))
    }

    /// Nur wenn die Aktualisierung etwas bewirkt: Alarme, Kurs-Mitteilungen bzw. -Ansagen eines aktiven Paars,
    /// Aktivitäts-Meldung, Widgets mit Paaren, Portfolio-Widget/-Alarm, Puls-Widget, Gas/Depeg.
    static func periodicNeeded(_ d: WorkDemand) -> Bool {
        if d.watchlistKept { return true }
        if d.portfolioWork || d.marketAlarms || d.pulseWidget { return true }
        let active = d.pairs.filter { !$0.disabled }
        if active.isEmpty { return false }
        if d.pairWidgets || d.activityAlerts { return true }
        return active.contains {
            $0.hasEnabledAlarm || (d.priceNotifications && $0.notificationEnabled) || (d.ttsPrices && $0.ttsEnabled)
        }
    }

    /// Widgets auf dem Home-Bildschirm (`WidgetCenter.getCurrentConfigurations`).
    struct WidgetPresence: Equatable, Sendable {
        var pairs: Bool
        var portfolio: Bool
        var pulse: Bool

        /// Ohne Auskunft von WidgetKit: alle vorhanden (lieber planen, wie Android `hasWidgets`).
        static let assumeAll = WidgetPresence(pairs: true, portfolio: true, pulse: true)
        static let none = WidgetPresence(pairs: false, portfolio: false, pulse: false)

        init(pairs: Bool, portfolio: Bool, pulse: Bool) {
            self.pairs = pairs
            self.portfolio = portfolio
            self.pulse = pulse
        }

        init(kinds: [String]) {
            pairs = kinds.contains(SharedStorage.watchlistWidgetKind) || kinds.contains(SharedStorage.singleWidgetKind)
            portfolio = kinds.contains(PortfolioWidgetStore.kind)
            pulse = kinds.contains(BackgroundWorkDemand.pulseWidgetKind)
        }
    }

    /// = `PulseWidget.kind` (liegt im Widget-Ziel).
    static let pulseWidgetKind = "PulseWidget"

    /// Eingaben aus Einstellungen und gelesener Merkliste (wie Android `PriceUpdateScheduler.demand`).
    /// - Parameter read: nicht lesbar bzw. nicht zugreifbar → `watchlistKept` (lieber planen).
    /// - Parameter portfolioAlarmsKept: `portfolio_alarms.json` da, aber gerade nicht lesbar (`portfolioKept`).
    static func demand(settings: AppSettings, read: SharedStorage.SnapshotRead, widgets: WidgetPresence,
                       portfolioAlarmsKept: Bool = false) -> WorkDemand {
        let snapshot: SharedStorage.Snapshot
        let kept: Bool
        switch read {
        case .ok(let s): (snapshot, kept) = (s, false)
        case .missing: (snapshot, kept) = (SharedStorage.Snapshot(), false)
        case .unreadable, .inaccessible: (snapshot, kept) = (SharedStorage.Snapshot(), true)
        }
        let alarmWatchIds = Set(snapshot.alarms.filter(\.enabled).map(\.watchId))
        let portfolioAlarmOn = settings.portfolioEnabled && (snapshot.portfolioAlarms ?? []).contains { $0.enabled }
        return WorkDemand(
            pairs: snapshot.watches.map {
                PairWork(disabled: $0.disabled, hasEnabledAlarm: alarmWatchIds.contains($0.id),
                         notificationEnabled: $0.notificationEnabled, ttsEnabled: $0.ttsEnabled)
            },
            priceNotifications: settings.priceNotifications,
            ttsPrices: settings.ttsEnabled && !settings.ttsAlarmsOnly,
            activityAlerts: settings.activityAlerts,
            pairWidgets: widgets.pairs,
            portfolioWork: portfolioWork(portfolioEnabled: settings.portfolioEnabled, alarmOn: portfolioAlarmOn,
                                         portfolioKept: portfolioAlarmsKept, widget: widgets.portfolio),
            marketAlarms: settings.gasAlertEthTenths > 0 || settings.gasAlertBtc > 0 || settings.depegAlarm,
            watchlistKept: kept,
            pulseWidget: widgets.pulse)
    }
}
