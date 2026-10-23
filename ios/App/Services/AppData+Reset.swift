import BackgroundTasks
import Foundation
import SwiftUI
import WidgetKit
import UIKit
import UserNotifications

// «App zurücksetzen» und «Nach einer Wiederherstellung alles übernehmen» in AppData.
// Eigene Datei, weil beides den ganzen Stand auf einmal ersetzt.

extension AppData {
    // MARK: App zurücksetzen

    /// «App zurücksetzen» (Einstellungen › Daten, nach Rückfrage): alles löschen wie nach der
    /// Installation — Merkliste, Alarme, Portfolio, Einstellungen, Zwischenspeicher, Logos,
    /// Mitteilungen, Live-Aktivitäten und geplante Hintergrund-Aufgaben. Android löscht mit
    /// `clearApplicationUserData()` und beendet die App; iOS leert die Speicher und setzt den
    /// Zustand im Speicher auf die Werte einer frischen Installation (kein Beenden der App).
    func resetApp() {
        // Laufendes anhalten
        Task { await LiveActivityController.endAll() }
        BGTaskScheduler.shared.cancelAllTaskRequests()
        let center = UNUserNotificationCenter.current()
        center.removeAllDeliveredNotifications()
        center.removeAllPendingNotificationRequests()
        Notifier.clearBadge()
        // Live-Strom anhalten (leere Paarliste: startet auch mit neuen Einstellungen nicht wieder) und laufende
        // `applyLive` verwerfen; dann alle noch eingereihten Speichervorgänge abwarten — sonst könnte ein
        // gepufferter Stand nach dem Löschen `watchlist.json` bzw. `portfolio_alarms.json` neu anlegen.
        // Hier auf dem MainActor kann zwischen dem Abwarten und dem Löschen nichts Neues eingereiht werden.
        resetGeneration &+= 1
        clearLive()
        Task { await LivePriceStream.shared.setPairs([]) }
        SharedStorage.flushSnapshotWrites()

        Self.clearStoredFiles()
        Self.clearTemporaryFiles()

        // Zustand im Speicher wie nach der Installation
        snapshot.watches.forEach { Notifier.cancelActivity($0.id) }
        ActivityRepository.retain([])
        activityReports = [:]
        mutate { s in s = SharedStorage.Snapshot() }
        for kind in FavoriteKind.allCases { setFavorites(kind, []) }
        setPortfolioFile(PortfolioFile())
        watchedAddressIntact = true
        setWatchedAddressFile(WatchedAddressFile())
        PortfolioStore.holdingsMigrated = true
        addTargetGroup = nil
        settings = AppSettings()
        // Ablage geleert, aber zugänglich: Widgets sollen das nicht für eine gesperrte Ablage halten
        SharedStorage.markSettingsReadable()
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Speicher leeren wie nach der Installation: Einstellungen (App und App Group), dann alle Dateien der
    /// App-Ordner. Ohne Zustand im Speicher — auch vor dem ersten Zugriff auf `shared` nutzbar (UI-Tests:
    /// `UITesting.prepareIfRequested`). Auch Cookies und URL-Cache sowie der Schlüssel der verschlüsselten
    /// Dateien: Er wird gelöscht und gleich neu angelegt (`StorageKey.renew`) — die App läuft weiter und schreibt ab
    /// hier mit dem neuen, nichts vom alten Stand bleibt entschlüsselbar zurück. Den Ordner `tmp` leert nur
    /// `resetApp` (`clearTemporaryFiles`): UI-Tests behalten dort eine Sicherung über den Neustart.
    nonisolated static func clearStoredFiles() {
        if let bundleId = Bundle.main.bundleIdentifier {
            UserDefaults.standard.removePersistentDomain(forName: bundleId)
        }
        SharedStorage.defaults.removePersistentDomain(forName: SharedStorage.appGroup)
        let fm = FileManager.default
        var folders = [SharedStorage.directory]
        for dir in [FileManager.SearchPathDirectory.applicationSupportDirectory, .documentDirectory, .cachesDirectory] {
            folders.append(contentsOf: fm.urls(for: dir, in: .userDomainMask))
        }
        for folder in folders {
            let items = (try? fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
            // «Library» im App-Group-Ordner enthält die (eben geleerten) Einstellungen selbst
            for item in items where item.lastPathComponent != "Library" {
                try? fm.removeItem(at: item)
            }
        }
        // Erst nach dem Löschen der Dateien: mit dem neuen Schlüssel ist nichts Altes mehr zu öffnen
        StorageKey.shared.renew()
        PrivateSessionConfig.clearSharedStores()
    }

    /// Ordner `tmp` leeren — z. B. ein liegen gebliebener Diagnosebericht (`DiagnosticsShareFile`) oder Export-Dateien.
    nonisolated static func clearTemporaryFiles(in folder: URL = FileManager.default.temporaryDirectory) {
        let fm = FileManager.default
        for item in (try? fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? [] {
            try? fm.removeItem(at: item)
        }
    }

    /// Nach einer Wiederherstellung: alles neu übernehmen.
    func replaceAll(snapshot newSnapshot: SharedStorage.Snapshot, settings newSettings: AppSettings?, favorites newFavorites: [FavoriteKind: Set<String>]) {
        // Alte ⚡-Ergebnisse gehören zu den alten Paaren — sonst könnte ein
        // Signal kurz beim falschen Paar stehen, wenn die Ids übereinstimmen.
        snapshot.watches.forEach { Notifier.cancelActivity($0.id) }
        ActivityRepository.retain([])
        activityReports = [:]
        mutate { s in s = newSnapshot }
        // Paar der Live-Aktivität gibt es evtl. nicht mehr (beendet sie dann)
        let watches = snapshot.watches
        Task { await LiveActivityController.update(watches: watches) }
        for (k, v) in newFavorites { setFavorites(k, v) }
        if let newSettings { settings = newSettings }
        dropMissingWatchlistGroup()
    }
}
