import Foundation

/// Obergrenzen der Merkliste überall in der App (Entscheid Build 99): höchstens `maxPairs` Paare und je Paar
/// `maxAlarmsPerPair` Alarme — beim Hinzufügen (einzeln, Sammelaufnahme, Start-Coins, DEX, Vorschau), beim Anlegen
/// von Alarmen (Editor, Schnell-Alarme) und beim Wiederherstellen einer Sicherung (`BackupRestoreLimits`). Mehr Paare
/// machen jede Aktualisierung, die Widgets und die Sicherung träge; mehr als zehn Alarme an einem Paar überblickt
/// niemand. Portfolio-Alarme hängen an keinem Paar und sind nicht begrenzt. «Rückgängig» nach dem Leeren bzw. Löschen
/// holt den früheren Stand zurück (der lag schon unter der Grenze) und prüft nicht. Gleich wie Android
/// `WatchlistLimits.kt`.
enum WatchlistLimits {
    static let maxPairs = 2_000
    static let maxAlarmsPerPair = 10

    /// Darf zu `pairCount` Paaren noch eines dazukommen?
    static func canAddPair(_ pairCount: Int) -> Bool { pairCount < maxPairs }

    /// Darf ein Paar mit `alarmCount` Alarmen (auch ausgeschalteten) noch einen neuen bekommen?
    static func canAddAlarm(_ alarmCount: Int) -> Bool { alarmCount < maxAlarmsPerPair }

    /// Freie Plätze der Merkliste bei `pairCount` Paaren (nie negativ, auch wenn eine alte Liste darüber liegt).
    static func freePairSlots(_ pairCount: Int) -> Int { max(0, maxPairs - pairCount) }
}

/// «Deine Merkliste kann höchstens 2’000 Paare enthalten.» (Zahl im Format der Sprache) — wie Android
/// `watchlistFullText`.
enum WatchlistLimitText {
    static var full: String {
        L("watchlist_limit_reached", LocaleNumbers.integer(WatchlistLimits.maxPairs, grouping: true))
    }
}
