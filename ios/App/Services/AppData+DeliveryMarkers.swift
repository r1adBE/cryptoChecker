import Foundation

// Seit Build 105 (Review ARC3-R03): Vermerke «wird gemeldet» (`AlarmDelivery`) beim Löschen nur kürzen, wenn der Stand
// dieses Laufs auch bleibt — wie Android `PrefsAlarmDeliveryMarkers.prunable`.

extension AppData {
    /// Vermerke der Paar-Alarme kürzen? Nicht bei gesperrter Merkliste und nicht, solange der Stand im Speicher nur wartet
    /// (`watchlistSavePending`), während die Datei noch den alten Stand hält — endet die App vorher, kommen die Alarme der
    /// Datei zurück, und ihr Vermerk muss noch stehen (wie Android bei einer Kopie im Speicher, PlaintextKept).
    var pairMarkersPrunable: Bool {
        AlarmDelivery.shouldClear(storedKept: watchlistLocked,
                                  volatileCopy: watchlistSavePending && Self.fileExists(SharedStorage.watchlistURL))
    }

    /// Wie `pairMarkersPrunable` für die Portfolio-Alarme (eigene Datei, wartet ohne Schlüssel: `portfolioAlarmsPending`).
    var portfolioMarkersPrunable: Bool {
        AlarmDelivery.shouldClear(storedKept: watchlistLocked,
                                  volatileCopy: SharedStorage.portfolioAlarmsPending
                                      && Self.fileExists(SharedStorage.portfolioAlarmsURL))
    }

    private static func fileExists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }
}
