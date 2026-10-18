import Foundation

/// Merkliste nach der Start-Auswahl schrittweise: Der erste Bildschirm hat EINE Aufgabe — Kurse sehen und den ersten
/// Alarm setzen; die Karte «Ersten Alarm setzen» (`FirstAlarmCard`) bleibt der einzige hervorgehobene Hinweis.
/// Gruppen-Hinweis, Puls über der Liste und Gesten-Hinweis kommen erst dazu, sobald es einen Alarm gibt (auch einen
/// ausgeschalteten) oder die App an `unlockDays` verschiedenen Tagen offen war. Wer schon Alarme hat (Update,
/// Wiederherstellen), sieht also alles sofort. Sortierzeile und Gruppen-Chips bleiben immer (Grundbedienung bzw.
/// vom Nutzer selbst angelegt). Reine Regel — wie Android `WatchlistOnboarding.kt` (gleiche Testfälle in
/// WatchlistOnboardingTests / WatchlistOnboardingTest).
enum WatchlistOnboarding {
    /// Ab so vielen Tagen mit offener App zeigt die Merkliste alles, auch ohne Alarm.
    static let unlockDays = 3

    /// Was die Merkliste über die Grundbedienung hinaus zeigen darf.
    struct Visible: Equatable {
        /// Grauer Hinweis «Hier erscheinen deine Gruppen» in der leeren Chip-Zeile.
        let groupsHint: Bool
        /// Puls («↗ 7 steigen · ↘ 3 fallen · Ø …») über der Liste.
        let pulse: Bool
        /// Gesten-Hinweis (Wischen, langes Drücken).
        let gestureHint: Bool
    }

    /// - Parameters:
    ///   - alarmCount: alle Alarme wie bei `FirstAlarmCard` (auch ausgeschaltete); nil = noch nicht geladen → wie
    ///     keiner, damit beim ersten Start nichts kurz aufblitzt
    ///   - usageDays: verschiedene Tage, an denen die App offen war (`UsageDays`)
    ///   - hasGroupsOrFavorites: eigene Gruppen oder Favoriten: dann stehen dort die Chips, kein Hinweis
    static func visible(alarmCount: Int?, usageDays: Int, hasGroupsOrFavorites: Bool) -> Visible {
        let unlocked = (alarmCount ?? 0) > 0 || usageDays >= unlockDays
        return Visible(groupsHint: unlocked && !hasGroupsOrFavorites, pulse: unlocked, gestureHint: unlocked)
    }
}

/// Zähler der verschiedenen Tage mit offener App: `count` Tage, zuletzt am lokalen Epoch-Tag `lastDay` (nil = nie).
/// Nur auf diesem Gerät, nicht in der Sicherung. Wie Android `UsageDays`.
struct UsageDays: Equatable {
    /// Obergrenze, damit der gespeicherte Wert nie überläuft (für die Regel zählt nur «mindestens 3»).
    static let maxCount = 9_999

    let count: Int
    let lastDay: Int?

    /// Stand nach einem Öffnen am Tag `today`: ein neuer, späterer Tag zählt einmal; derselbe Tag nicht. Liegt
    /// `today` vor `lastDay` (Uhr zurückgestellt, Zeitzone), wird nur der Tag übernommen — sonst zählte ein
    /// Hin und Her der Uhr mehrfach.
    func next(today: Int) -> UsageDays {
        guard let lastDay else { return UsageDays(count: Self.clamp(count + 1), lastDay: today) }
        if today > lastDay { return UsageDays(count: Self.clamp(count + 1), lastDay: today) }
        if today < lastDay { return UsageDays(count: count, lastDay: today) }
        return self
    }

    private static func clamp(_ value: Int) -> Int { Swift.min(Swift.max(value, 1), maxCount) }
}
