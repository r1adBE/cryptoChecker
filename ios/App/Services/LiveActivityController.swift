import ActivityKit
import Foundation

/// Startet, aktualisiert und beendet die Live-Aktivität eines Paars (nur iOS).
/// Höchstens eine gleichzeitig: Eine neue beendet die vorige. Aktualisiert wird
/// nach jeder Kursprüfung der App (Vordergrund, Live-Modus, Hintergrund) —
/// ohne Push-Token und ohne Server.
@MainActor
enum LiveActivityController {
    enum StartResult { case started, disabled, failed }

    private static var activities: [Activity<PriceActivityAttributes>] {
        Activity<PriceActivityAttributes>.activities
    }

    /// Live-Aktivitäten für die App in den iOS-Einstellungen erlaubt?
    static var areEnabled: Bool { ActivityAuthorizationInfo().areActivitiesEnabled }

    /// Läuft gerade eine (nicht beendete) Live-Aktivität für dieses Paar?
    static func isRunning(watchId: Int64) -> Bool {
        activities.contains { $0.attributes.watchId == watchId && isLive($0.activityState) }
    }

    /// Neue Live-Aktivität für `watch`; eine laufende (auch für ein anderes Paar) endet vorher.
    static func start(_ watch: Watch) async -> StartResult {
        guard areEnabled else { return .disabled }
        await endAll()
        do {
            _ = try Activity.request(attributes: PriceActivityAttributes(watch: watch),
                                     content: PriceActivityAttributes.content(watch),
                                     pushType: nil)
            return .started
        } catch {
            return areEnabled ? .failed : .disabled
        }
    }

    static func stop(watchId: Int64) async {
        for activity in activities where activity.attributes.watchId == watchId {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }

    static func endAll() async {
        for activity in activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }

    /// Nach einer Kursprüfung: laufende Aktivität mit dem neuen Stand des Paars
    /// aktualisieren (veraltet nach 30 Min.). Gibt es das Paar nicht mehr, endet sie.
    static func update(watches: [Watch]) async {
        for activity in activities where isLive(activity.activityState) {
            guard let watch = watches.first(where: { $0.id == activity.attributes.watchId }) else {
                await activity.end(nil, dismissalPolicy: .immediate)
                continue
            }
            await activity.update(PriceActivityAttributes.content(watch))
        }
    }

    private static func isLive(_ state: ActivityState) -> Bool {
        state == .active || state == .stale
    }
}
