import Foundation

/// Eine Statuszeile auf der Karte eines eingeschalteten Alarms: Warum meldet er sich gerade nicht?
/// Es zählt nur der wichtigste Grund, damit die Karte ruhig bleibt:
///  1. `notificationsOff` — Mitteilungen nicht zustellbar (`Notifier.isAuthorized`): selbst ein ausgelöster
///     Alarm käme nicht an, darum vor allem anderen.
///  2. `lowLiquidity` — DEX-Pool unter der Alarm-Liquidität oder unbekannt (`DexLiquidity.blocksAlarms`):
///     der Alarm wird gar nicht ausgewertet.
///  3. `waitingForPrice` — Zeit laut Börse älter als 2 Stunden (`AlarmFreshness`) oder letzter Abruf älter
///     als die Veraltet-Grenze der Merkliste (`OutdatedRule`): es fehlt ein frischer Kurs.
///  4. `unchecked` — zuletzt nicht geprüft (`AlarmCheckStatus`); den Text liefert dessen Grund.
/// Sonst nil (keine Zeile). Ausgeschaltete Alarme werden nicht geprüft und bekommen nie eine Zeile.
/// Wie `AlarmCardStatus.kt`.
enum AlarmCardStatus {
    enum Kind: Equatable { case notificationsOff, lowLiquidity, waitingForPrice, unchecked }

    /// - Parameters:
    ///   - exchangeTime: Zeit des Kurses laut Börse (nil/≤ 0 = unbekannt)
    ///   - lastUpdate: letzter erfolgreicher Abruf (≤ 0 = noch nie, dann kein «Wartet»)
    ///   - staleAfterMillis: Veraltet-Grenze der abgefragten Paare (wie die Merkliste)
    static func of(
        alarmEnabled: Bool,
        notificationsAllowed: Bool,
        dexBlocksAlarms: Bool,
        exchangeTime: Int64?,
        lastUpdate: Int64,
        now: Int64,
        staleAfterMillis: Int64,
        unchecked: Bool
    ) -> Kind? {
        guard alarmEnabled else { return nil }
        if !notificationsAllowed { return .notificationsOff }
        if dexBlocksAlarms { return .lowLiquidity }
        if AlarmFreshness.exchangeTimeTooOld(exchangeTime, now: now)
            || OutdatedRule.isOutdated(lastUpdate, now: now, afterMillis: staleAfterMillis) {
            return .waitingForPrice
        }
        return unchecked ? .unchecked : nil
    }
}
