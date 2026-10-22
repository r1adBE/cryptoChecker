import Foundation

// Seit Build 105 (Review ARC3-R03): Vermerke «wird gemeldet» (`AlarmDelivery`) beim Löschen nur kürzen, wenn der Stand
// dieses Laufs auch bleibt — wie Android `PrefsAlarmDeliveryMarkers.prunable`.
// Seit Build 106 (Review IOS4-R01): entschieden nach dem Ergebnis DIESES Lösch-Schreibens (nicht des vorigen) — erst
// wenn die Schreib-Warteschlange es ausgeführt hat (`SharedStorage.snapshotWritesSettled`).

extension AppData {
    /// Vermerke kürzen? Nur wenn das Lösch-Schreiben die Datei wirklich geschrieben hat (`written`), die Merkliste nicht
    /// gesperrt ist und der Stand nicht nur im Speicher wartet (`pending`), während die Datei noch den alten Stand hält —
    /// endet die App vorher, kommen die Alarme der Datei zurück, und ihr Vermerk muss noch stehen (wie Android bei einer
    /// Kopie im Speicher, PlaintextKept). Rein, testbar.
    nonisolated static func markersPrunable(written: Bool, locked: Bool, pending: Bool, fileExists: Bool) -> Bool {
        written && AlarmDelivery.shouldClear(storedKept: locked, volatileCopy: pending && fileExists)
    }

    /// Direkt nach dem Lösch-`mutate` aufrufen: Vermerke dieser Paar-Alarme weg, sobald das Schreiben gelungen ist.
    func removePairMarkersAfterWrite(_ ids: Set<Int64>) {
        Task { if await self.markersPrunableAfterThisWrite(portfolio: false) { AlarmDelivery.pairs.remove(ids) } }
    }

    /// Wie `removePairMarkersAfterWrite`, aber nur die Vermerke der übrigen Paar-Alarme behalten (`ids` = Stand beim
    /// Löschen; inzwischen wieder eingefügte bzw. neue Alarme bleiben ebenfalls).
    func retainPairMarkersAfterWrite(_ ids: Set<Int64>) {
        Task {
            guard await self.markersPrunableAfterThisWrite(portfolio: false) else { return }
            AlarmDelivery.pairs.retain(ids.union(self.snapshot.alarms.map(\.id)))
        }
    }

    /// Wie `removePairMarkersAfterWrite` für die Portfolio-Alarme (eigene Datei, wartet ohne Schlüssel:
    /// `portfolioAlarmsPending`).
    func removePortfolioMarkersAfterWrite(_ ids: Set<Int64>) {
        Task { if await self.markersPrunableAfterThisWrite(portfolio: true) { AlarmDelivery.portfolio.remove(ids) } }
    }

    /// Wartet auf das zuletzt eingereihte Schreiben (das Lösch-Schreiben oder ein späteres, das die Löschung ebenso
    /// enthält) und prüft mit dessen Ergebnis.
    private func markersPrunableAfterThisWrite(portfolio: Bool) async -> Bool {
        let written = await SharedStorage.snapshotWritesSettled()
        let pending = portfolio ? SharedStorage.portfolioAlarmsPending : SharedStorage.watchlistPending
        let url = portfolio ? SharedStorage.portfolioAlarmsURL : SharedStorage.watchlistURL
        return Self.markersPrunable(written: written, locked: watchlistLocked, pending: pending,
                                    fileExists: FileManager.default.fileExists(atPath: url.path))
    }
}
