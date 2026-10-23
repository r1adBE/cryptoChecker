import Foundation

// Einstellungen neu angelegt (fehlend oder unlesbar → Standardwerte, Portfolio aus), Portfolio-Daten aber da: Portfolio
// wieder an — wie Android `PortfolioDataCheck`.

/// Was mit «Portfolio wieder an» jetzt geschieht (`AppData.portfolioRecoveryDecision`).
enum PortfolioRecoveryDecision: Equatable, Sendable {
    /// Weder neu angelegte Einstellungen noch ein offener Merker: nichts zu tun.
    case skip
    /// Nichts gefunden, eine Datei aber gerade nicht lesbar: Merker setzen bzw. behalten, später erneut.
    case wait
    /// Entscheiden (bei Daten einschalten) und den Merker entfernen.
    case decide
}

extension SharedStorage {
    /// Merker «Portfolio wieder an» noch offen — dauerhaft wie Android der Merker im DataStore: Nur im
    /// Arbeitsspeicher ginge er verloren, sobald die (neu angelegten) Einstellungen gespeichert sind, und das Portfolio
    /// bliebe trotz Daten für immer aus.
    static var portfolioRecoveryPending: Bool {
        get { defaults.bool(forKey: "portfolio_recovery_pending") }
        set {
            if newValue { defaults.set(true, forKey: "portfolio_recovery_pending") }
            else { defaults.removeObject(forKey: "portfolio_recovery_pending") }
        }
    }
}

extension AppData {
    /// Einstellungen neu angelegt, aber Portfolio- oder Adressdatei beim Prüfen nicht lesbar: «Portfolio
    /// wieder an» wird nach dem Nachlesen entschieden (`recoverPortfolioIfDeferred`) — wie Android `PortfolioDataCheck`.
    /// Gespeichert (`SharedStorage.portfolioRecoveryPending`), damit es auch einen Neustart übersteht.
    var portfolioRecoveryDeferred: Bool {
        get { SharedStorage.portfolioRecoveryPending }
        set { SharedStorage.portfolioRecoveryPending = newValue }
    }

    /// Einstellungen ohne gelesenen Stand (oder Merker noch offen), aber Portfolio-Daten vorhanden: bestehende
    /// Installation, Portfolio an (`AppSettings.enablePortfolioForData`, wie Android). Portfolio- oder
    /// Adressdatei gerade nicht lesbar (gesperrt, ohne Schlüssel) und nichts gefunden → nicht als «keine Daten» entscheiden,
    /// sondern nach dem Nachlesen (`recoverPortfolioIfDeferred`). Der Merker geht erst mit dem Entscheid weg.
    func portfolioRecovered(_ loaded: AppSettings, state: SharedStorage.SettingsReadState) -> AppSettings {
        let hasData = !portfolioFile.transactions.isEmpty || !watchedAddressFile.addresses.isEmpty
            || !(snapshot.portfolioAlarms ?? []).isEmpty
        switch Self.portfolioRecoveryDecision(settingsMissing: state == .missing, markerPending: portfolioRecoveryDeferred,
                                              hasData: hasData, filesIntact: portfolioIntact && watchedAddressIntact) {
        case .skip:
            return loaded
        case .wait:
            portfolioRecoveryDeferred = true
            return loaded
        case .decide:
            portfolioRecoveryDeferred = false
        }
        guard AppSettings.enablePortfolioForData(resetDefaults: true, portfolioEnabled: loaded.portfolioEnabled,
                                                 hasPortfolioData: hasData) else { return loaded }
        var recovered = loaded
        recovered.portfolioEnabled = true
        return recovered
    }

    /// Entscheid aufschieben? Ja, wenn nichts gefunden wurde, eine der Dateien aber gerade nicht lesbar ist: ihr gespeicherter
    /// Stand ist dann nicht zu sehen. Gefundene Daten entscheiden sofort (einschalten). Wie Android `PortfolioDataCheck.keepMarker`.
    nonisolated static func deferPortfolioRecovery(hasData: Bool, filesIntact: Bool) -> Bool { !hasData && !filesIntact }

    /// Rein (testbar): Einstellungen neu angelegt (`settingsMissing`) oder Merker aus einem früheren Lauf noch offen
    /// (`markerPending`) → aufschieben (`deferPortfolioRecovery`) oder entscheiden; sonst nichts.
    nonisolated static func portfolioRecoveryDecision(settingsMissing: Bool, markerPending: Bool, hasData: Bool,
                                                      filesIntact: Bool) -> PortfolioRecoveryDecision {
        guard settingsMissing || markerPending else { return .skip }
        return deferPortfolioRecovery(hasData: hasData, filesIntact: filesIntact) ? .wait : .decide
    }

    /// Aufgeschobene Prüfung nachholen, sobald beide Dateien lesbar sind: zu Beginn von `reloadProtectedFilesIfNeeded`
    /// und wenn `currentPortfolioFile` / `currentWatchedAddressFile` eine Datei wieder lesen konnten. Einstellungen noch
    /// nicht zugänglich: später (`reloadSettingsIfInaccessible` entscheidet dann mit dem Merker).
    func recoverPortfolioIfDeferred() {
        guard portfolioRecoveryDeferred, settingsAccessible, portfolioIntact, watchedAddressIntact else { return }
        let recovered = portfolioRecovered(settings, state: .missing)
        // didSet speichert und wendet die Folgen an
        if recovered.portfolioEnabled != settings.portfolioEnabled { settings = recovered }
    }
}
