import Foundation

/// Gemerkten Stand einer Markt-Meldung (Fear & Greed, eigene Grenzen der Bitcoin-Kennzahlen, Gas, USDT-Depeg) vergessen,
/// sobald ihre Grenze bzw. ihr Schalter in den Einstellungen ausgeschaltet wird. Läuft während «Aus» keine Prüfung (keine
/// Planung), bliebe sonst der alte Stand liegen, und nach dem Wiedereinschalten mit derselben Grenze bliebe die Meldung
/// stumm. Jedes «Aus» zählt den Zähler der Prüfung hoch: eine laufende Prüfung schreibt danach nicht den alten Stand
/// zurück (Android: gemeinsame Sperre). Aufgerufen aus `AppData.settingsChanged` (auch nach dem Wiederherstellen einer
/// Sicherung). Wie Android `MarketAlertReset`.
enum MarketAlertReset {
    /// Vergleicht alte und neue Einstellungen und setzt für jede ausgeschaltete Grenze den gemerkten Stand zurück.
    static func settingsChanged(from old: AppSettings, to new: AppSettings, defaults: UserDefaults) {
        if old.fearGreedBelow > 0 && new.fearGreedBelow <= 0 { fearGreedOff(below: true, defaults: defaults) }
        if old.fearGreedAbove > 0 && new.fearGreedAbove <= 0 { fearGreedOff(below: false, defaults: defaults) }
        let oldActive = IndicatorAlertRules.active(old.indicatorAlerts)
        let newActive = IndicatorAlertRules.active(new.indicatorAlerts)
        for kind in IndicatorAlertKind.allCases where oldActive[kind] != nil && newActive[kind] == nil {
            indicatorOff(kind, defaults: defaults)
        }
        if old.gasAlertEthTenths > 0 && new.gasAlertEthTenths <= 0 { gasOff(eth: true, defaults: defaults) }
        if old.gasAlertBtc > 0 && new.gasAlertBtc <= 0 { gasOff(eth: false, defaults: defaults) }
        if old.depegAlarm && !new.depegAlarm { depegOff(defaults: defaults) }
    }

    /// Fear & Greed: Grenze «unter» (`below`) bzw. «über» aus — gemerkte Grenze gilt als «aus» (0), die nächste bewertet neu.
    static func fearGreedOff(below: Bool, defaults: UserDefaults) {
        // Eine gerade laufende Prüfung schreibt danach nichts mehr zurück (`ZoneCheck.mayStore`)
        bump(ZoneCheck.keyResetGeneration, defaults: defaults)
        // Kein gemerkter Stand: nichts zu vergessen
        guard defaults.object(forKey: ZoneCheck.keyFearGreed) != nil else { return }
        defaults.set(0, forKey: below ? ZoneCheck.keyFearGreedBelow : ZoneCheck.keyFearGreedAbove)
    }

    /// Grenze einer Bitcoin-Kennzahl aus: «schon gemeldet» und die zugehörige Grenze vergessen.
    static func indicatorOff(_ kind: IndicatorAlertKind, defaults: UserDefaults) {
        bump(ZoneCheck.keyResetGeneration, defaults: defaults)
        defaults.removeObject(forKey: ZoneCheck.crossedKey(kind))
        defaults.removeObject(forKey: ZoneCheck.thresholdKey(kind))
    }

    /// Gas-Alarm Ethereum (`eth`) bzw. Bitcoin aus: «aus» (0) merken — beim Wiedereinschalten ist er scharf.
    static func gasOff(eth: Bool, defaults: UserDefaults) {
        defaults.set(0.0, forKey: eth ? GasAlertCheck.keyEthThreshold : GasAlertCheck.keyBtcThreshold)
        // Eine gerade laufende Prüfung schreibt danach nichts mehr zurück (`GasAlertCheck.mayStore`)
        bump(GasAlertCheck.keyResetGeneration, defaults: defaults)
    }

    /// USDT-Depeg-Alarm aus: Schwelle «aus» (0) merken — beim Wiedereinschalten ist er scharf (`DepegAlarm.armedFor`).
    static func depegOff(defaults: UserDefaults) {
        defaults.set(0, forKey: DepegAlarmCheck.keyArmedTenths)
        // Eine gerade laufende Prüfung schreibt danach nichts mehr zurück (`DepegAlarmCheck.mayStore`)
        bump(DepegAlarmCheck.keyResetGeneration, defaults: defaults)
    }

    /// Zähler der «Aus»-Schaltungen einer Prüfung um eins erhöhen.
    private static func bump(_ key: String, defaults: UserDefaults) {
        defaults.set(defaults.integer(forKey: key) &+ 1, forKey: key)
    }
}
