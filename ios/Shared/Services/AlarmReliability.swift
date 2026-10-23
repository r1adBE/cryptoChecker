import Foundation

/// Eine Zeile über der Merkliste: Kommen die Alarme an? Verdichtet denselben gelesenen Zustand wie «Damit
/// Alarme ankommen» (`AlarmReadiness.Input`) auf genau einen Satz — ruhig (grau) mit der letzten Prüfung, oder eine
/// Warnung (Bernstein) mit dem wichtigsten Grund; Tipp öffnet die Seite. Ohne Alarme keine Zeile. Reine Logik — wie
/// `AlarmReliability.kt`; gemeinsame Fälle in `Parity/alarm_reliability.json`.
enum AlarmReliability {
    /// Android: Letzte Prüfung im Hintergrund älter als das: Warnung. Strenger als die Seite (6 h): Die Zeile soll früh
    /// zeigen, dass Alarme gerade nicht geprüft werden — bei 15-Min.-Takt sind 2 h klar zu viel.
    static let androidStaleMillis: Int64 = 2 * 60 * 60_000
    /// iOS: gleiche Grenze wie die Seite (`AlarmReadiness.staleCheckMillis`, 6 h) — iOS startet `BGAppRefreshTask` nach
    /// eigenem Ermessen, oft nur alle paar Stunden; 2 h warnten sonst fast ständig ohne echten Fehler.
    static let iosStaleMillis: Int64 = AlarmReadiness.staleCheckMillis

    /// Grenze «zu alt» je Plattform.
    static func staleMillis(_ platform: AlarmReadiness.Platform) -> Int64 {
        platform == .android ? androidStaleMillis : iosStaleMillis
    }

    enum Kind: String, CaseIterable, Sendable {
        /// Keine Alarme: keine Zeile.
        case hidden
        /// Ruhig: «Letzte Prüfung vor 12 Min.».
        case recent
        /// Ruhig: noch keine Prüfung im Hintergrund.
        case never
        /// Mitteilungen nicht erlaubt bzw. Alarm-Kanal aus (Android).
        case notificationsOff = "notifications_off"
        /// Android: Merkliste nur als Kopie im Arbeitsspeicher (kein Schlüssel) — Prüfungen im Hintergrund ruhen. iOS kennt
        /// das nicht; für den gemeinsamen Testfall da.
        case storageNoKey = "storage_no_key"
        /// Android: Hintergrundnutzung eingeschränkt, oder Prüfung zu alt bei Akku-Optimierung/Akkusparen des Herstellers.
        case battery
        /// iOS: Hintergrundaktualisierung aus.
        case backgroundRefresh = "background_refresh"
        /// Letzte Prüfung älter als `staleMillis(_:)` (oder weit in der Zukunft — Uhr verstellt), oder alle gespeicherten
        /// geplanten Läufe gescheitert (`AlarmReadiness.Input.lastFailedBackgroundCheck`).
        case stale
    }

    /// `warn`: Bernstein statt Grau.
    struct Status: Equatable, Sendable {
        let kind: Kind
        let warn: Bool
    }

    /// Ein Satz aus dem gelesenen Zustand; wichtigster Grund zuerst. `oemVendorKnown`: nur Android (Hersteller mit
    /// eigenem Akkusparen, `OemBattery` auf Android) — auf iOS immer false; für den gemeinsamen Testfall da.
    static func status(_ input: AlarmReadiness.Input, oemVendorKnown: Bool = false) -> Status {
        guard input.hasAlarms else { return Status(kind: .hidden, warn: false) }
        let android = input.platform == .android
        if input.notifications != .granted || (android && input.channel == .off) {
            return Status(kind: .notificationsOff, warn: true)
        }
        if android && input.storageNoKey { return Status(kind: .storageNoKey, warn: true) }
        if android && input.backgroundRestricted { return Status(kind: .battery, warn: true) }
        if !android && !input.backgroundRefresh { return Status(kind: .backgroundRefresh, warn: true) }
        guard let at = input.lastBackgroundCheck, at > 0 else {
            // Geplante Läufe gab es, gelungen ist keiner: Warnung statt «noch keine Prüfung» (grau)
            let failing = input.lastFailedBackgroundCheck != nil
            return Status(kind: failing ? .stale : .never, warn: failing)
        }
        let fresh = at - input.now <= AlarmReadiness.futureToleranceMillis && input.now - at <= staleMillis(input.platform)
        if fresh { return Status(kind: .recent, warn: false) }
        // Zu alt: Unter Android ist Akku-Optimierung bzw. das Akkusparen des Herstellers der wahrscheinliche Grund —
        // für sich allein nur ein Hinweis (fast jedes Gerät optimiert), darum erst zusammen mit der alten Prüfung
        let batteryLikely = android && (!input.batteryUnrestricted || oemVendorKnown)
        return Status(kind: batteryLikely ? .battery : .stale, warn: true)
    }
}
