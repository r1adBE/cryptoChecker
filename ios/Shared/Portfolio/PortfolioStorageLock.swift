import Foundation

/// Gespeichertes Portfolio gerade gesperrt: Kein lesbarer Schlüssel, aber ein gespeicherter Stand liegt da (Portfolio- oder
/// Adress-Datei). Was jetzt geschrieben würde, gälte als gespeichert und ginge mit dem Prozess verloren — darum lehnt die
/// App die Schreibwege des Nutzers ab (Transaktionen über `PortfolioStore.keepsUnsealed`, beobachtete Adressen samt
/// Einstandsposten über `AppData.portfolioStorageLocked`) und sagt «portfolio_storage_locked». Guthaben-Abrufe und
/// automatische Umstellungen laufen weiter. Wie Android `PortfolioStorageLock`.
///
/// Nicht gesperrt: ohne Schlüssel und ohne gespeicherten Stand — dort gibt es nichts zu verlieren, der Stand wartet im
/// Speicher, bis ein Schlüssel da ist (Hinweis «portfolio_save_pending»).
enum PortfolioStorageLock {
    static func isLocked(keyAvailable: Bool, storedFileExists: Bool) -> Bool {
        !keyAvailable && !PortfolioStore.keepsUnsealed(storedFileExists: storedFileExists)
    }
}
