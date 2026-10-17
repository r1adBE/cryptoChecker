import BackgroundTasks
import Foundation
import SwiftUI
import WidgetKit
import UIKit
import UserNotifications

/// Ergebnis beim Hinzufügen mehrerer Paare.
struct BulkAddResult {
    var added: Int
    var skipped: Int
}

/// Verschieben im Sortiermodus.
enum WatchMove { case top, up, down, bottom }

/// «Erst-Hinzufügen»: neu hinzugefügte Paare, deren kurzer Moment in der Merkliste
/// noch aussteht (Zeile erscheint, Mini-Chart zeichnet sich, Häkchen). Nur im Speicher.
struct AddCelebration: Equatable, Identifiable {
    let id = UUID()
    /// Paare in der Reihenfolge des Hinzufügens (Versatz 90 ms je Zeile).
    let watchIds: [Int64]
    /// «BTC/USDT wird jetzt überwacht» bzw. «BTC, ETH, XRP, BNB, SOL wird jetzt überwacht».
    let message: String
    /// Banner, Haptik und Ansage zeigt die Merkliste (Start-Tipp). Beim Hinzufügen-Tab
    /// übernimmt das der Tab selbst; die Merkliste spielt nur die Zeilen ab.
    let announceInWatchlist: Bool
}

/// Per Wischen gelöschtes Paar — alles, was «Rückgängig» braucht, um es genau so
/// wiederherzustellen (gleiche Id, Platz, Gruppe, Notiz, Mitteilung, Favorit und
/// Alarme mit ihren Ids). Nur im Speicher.
struct DeletedWatch {
    let watch: Watch
    let alarms: [Alarm]
    let activity: ActivityReport?
    /// Gewählte Gruppe der Merkliste vor dem Löschen (verschwindet mit dem letzten Paar).
    let selectedGroup: String?
    /// Lief eine Live-Aktivität für das Paar? Dann startet «Rückgängig» sie neu.
    var liveActivity = false
}

/// Zentrale Datenhaltung der App: Merkliste, Alarme, Einstellungen.
/// Entspricht WatchRepository + SettingsRepository + den ViewModels der Android-Fassung.
@MainActor
final class AppData: ObservableObject {
    static let shared = AppData()

    /// Gespeicherter Stand. Von Hand veröffentlicht statt `@Published`: Speichert der Live-Strom
    /// nur Kurs, Änderung und Zeit (`applyLive`), bleibt die Veröffentlichung aus — die Zeilen
    /// zeigen diese Werte schon über ihre `LiveQuoteBox` (`LivePrices`), und Markt, Portfolio und
    /// Einstellungen bauen nicht alle 10 s neu. Gelesen wird trotzdem immer der aktuelle Stand.
    private(set) var snapshot: SharedStorage.Snapshot {
        willSet { if !quietPriceSave { objectWillChange.send() } }
    }
    /// Nur während `mutate(…, quiet: true)`: Änderung nicht veröffentlichen.
    private var quietPriceSave = false
    @Published var settings: AppSettings {
        didSet {
            guard settings != oldValue else { return }
            SharedStorage.saveSettings(settings)
            settingsChanged(from: oldValue)
        }
    }
    @Published var refreshing = false
    @Published var lastRefreshMillis: Int64 = SharedStorage.lastRefreshDuration
    @Published var lastRefreshReport: RefreshReport? = SharedStorage.lastRefreshReport
    /// %-Basis und Tagesbeginn, mit denen die gespeicherten Veränderungen gerechnet wurden.
    @Published var changeStamp: ChangeStamp? = SharedStorage.changeStamp
    /// Paare, die gerade einzeln aktualisiert werden.
    @Published var refreshingWatchIds: Set<Int64> = []
    @Published var favorites: [FavoriteKind: Set<String>] = [:]
    /// «Ungewöhnliche Aktivität» je Paar (Watch-Id); abgelaufene Signale filtert
    /// `ActivityReport.active(now:)`.
    @Published var activityReports: [Int64: ActivityReport] = [:]
    /// Portfolio-Transaktionen, neueste zuerst (wie `PortfolioDao.observeAll`).
    @Published var portfolio: [PortfolioTx] = []
    /// Beobachtete Adressen in Reihenfolge des Hinzufügens (wie `PortfolioRepository.watchedAddresses`);
    /// Zugriff über `AppData+WatchedAddresses.swift`.
    @Published internal(set) var watchedAddresses: [WatchedAddress] = []
    /// Stand von `watched_addresses.json`; false = Datei da, aber nicht lesbar (dann vor dem Schreiben neu lesen).
    var watchedAddressFile = WatchedAddressFile()
    var watchedAddressIntact = true
    /// Ausstehender «Erst-Hinzufügen»-Moment; die Merkliste holt ihn ab (`takeAddCelebration`).
    @Published var addCelebration: AddCelebration?
    /// Gerät hat Internet? Ohne: ruhige Statuszeile «Offline · Stand …», keine Aktualisierung.
    @Published private(set) var online = NetworkStatus.shared.isOnline
    /// App-Start bis zum ersten Bild der Merkliste (zuletzt gemessen), für den Bericht «Ablauf».
    @Published private(set) var appStartMillis: Int64? = SharedStorage.appStartMillis
    // Live-Kurse je Paar: `LivePrices` (eigener, je Zeile beobachteter Speicher) — ein Tick
    // veröffentlicht hier nichts, sonst würden Markt, Portfolio und Einstellungen neu aufgebaut.
    /// Börsen, deren Live-Strom gerade Kurse liefert («LIVE» in der Status-Pille, Bericht).
    @Published var liveExchanges: [String] = []
    /// Watch-Ids der Paare am Live-Strom (Börse liefert gerade): Frische 2 Min., «Live» nur mit allen (K-1).
    @Published var liveWatchIds: Set<Int64> = []
    /// Letzter Live-Kurs je Watch-Id (ms) — Paare mit frischem Kurs lässt die REST-Abfrage aus.
    var liveTickAt: [Int64: Int64] = [:]
    /// Live-Kurse werden gerade gespeichert (`applyLive`): eine volle bzw. einzelne Aktualisierung
    /// wartet so lange; umgekehrt lässt `applyLive` aus, solange eine Aktualisierung läuft.
    var liveApplying = false

    var portfolioFile: PortfolioFile
    /// false = `portfolio.json` war beim Lesen da, aber nicht (ganz) lesbar: vor jedem Schreiben neu lesen
    /// bzw. die Datei sichern, statt sie mit einem leeren Stand zu überschreiben.
    var portfolioIntact = true
    var appActive = false

    /// «In Gruppe» beim Hinzufügen: nur für die laufende Sitzung, nicht gespeichert.
    /// nil = «Keine Gruppe».
    @Published var addTargetGroup: String?

    private init() {
        // Vor dem ersten Lesen: Dateien mit Nutzerdaten verschlüsseln (einmalig, Build 59); ohne Schlüssel
        // unlesbare beiseitelegen und das einmal nennen (`StorageRecoveryNotice`)
        let protection = ProtectedStorage.prepareAtLaunch()
        if !protection.setAside.isEmpty { StorageRecoveryNotice.record() }
        snapshot = SharedStorage.loadSnapshot()
        settings = SharedStorage.loadSettings()
        let loaded = PortfolioStore.loadChecked()
        portfolioFile = loaded.file
        portfolioIntact = loaded.intact
        // Unlesbare Einträge (z. B. aus einem Geräte-Backup) nicht still verwerfen: beim nächsten Öffnen
        // des Portfolios einmal nennen (`PortfolioRestoreNotice`)
        if loaded.file.dropped > 0, let raw = try? ProtectedFile.read(PortfolioStore.url) {
            PortfolioRestoreNotice.record(dropped: loaded.file.dropped, fileData: raw)
        }
        // Einmalig: Portfolio-Alarme aus `watchlist.json` in die eigene Datei (Backup-Merkmal wie
        // `portfolio.json`); wiederholbar, ohne Verlust (siehe `SharedStorage.writeFiles`)
        if snapshot.portfolioAlarms != nil
            && !FileManager.default.fileExists(atPath: SharedStorage.portfolioAlarmsURL.path) {
            SharedStorage.updateSnapshot { _ in }
        }
        // «Portfolio in Systemsicherung»: auch für eine schon vorhandene Datei (Update, Wiederherstellung)
        PortfolioStore.applyBackupPolicy(includeInBackup: settings.portfolioSystemBackup)
        // didSet läuft im init nicht — Kursfarben hier setzen
        PriceColors.scheme = settings.priceColorScheme
        HighContrast.setting = settings.highContrast
        PriceColors.inverted = settings.priceColorsInverted
        PriceColors.accent = settings.accentColor
        for kind in FavoriteKind.allCases { favorites[kind] = SharedStorage.favorites(kind) }
        activityReports = ActivityRepository.reports()
        portfolio = PortfolioStore.newestFirst(portfolioFile.transactions)
        let watched = WatchedAddressStore.loadChecked()
        watchedAddressFile = watched.file
        watchedAddressIntact = watched.intact
        watchedAddresses = Self.sortedWatched(watched.file.addresses)
        // Einmalig: alter Bestand aus der Merkliste wird zu Käufen im Portfolio
        migrateHoldingsOnce()
        // Update mit vorhandenen Alarmen: die Erst-Alarm-Bestätigung gilt als gezeigt
        if !settings.firstAlarmShown && !snapshot.alarms.isEmpty {
            settings.firstAlarmShown = true
            SharedStorage.saveSettings(settings)
        }
        // Update mit vorhandenen Paaren: das erste Hinzufügen gilt als erledigt
        if !settings.firstPairAdded && !snapshot.watches.isEmpty {
            settings.firstPairAdded = true
            SharedStorage.saveSettings(settings)
        }
        // Netz weg/zurück (#22/#23): Statuszeile; zurück → genau eine Aktualisierung
        NetworkStatus.shared.observe { online in
            Task { @MainActor in AppData.shared.networkChanged(online) }
        }
    }

    private func networkChanged(_ isOnline: Bool) {
        let previous = online
        online = isOnline
        if OfflineGate.resumeOnChange(previous: previous, online: isOnline) && appActive {
            Task { await refreshAll() }
        }
        updateLiveStream()
        retryCoinLogosIfPending(online: isOnline)
    }

    /// Erstes Bild der Merkliste nach dem Start gemessen (nur lokal gespeichert).
    func recordAppStart(_ millis: Int64) {
        SharedStorage.appStartMillis = millis
        appStartMillis = millis
    }

    // MARK: Speichern

    /// - Parameter widgetKinds: nur diese Widget-Arten neu laden; nil = alle.
    /// Für die Erweiterungen (`AppData+…`): jede Änderung am gespeicherten Stand läuft hierüber.
    func mutate(_ body: (inout SharedStorage.Snapshot) -> Void, reloadWidgets: Bool = true, widgetKinds: [String]? = nil,
                quiet: Bool = false) {
        var s = snapshot
        body(&s)
        quietPriceSave = quiet
        snapshot = s
        quietPriceSave = false
        dropMissingWatchlistGroup()
        // Speichern im Hintergrund; die Widgets erst danach neu laden (sie lesen die Datei)
        SharedStorage.saveSnapshotInBackground(s, done: reloadWidgets ? {
            if let widgetKinds {
                widgetKinds.forEach { WidgetCenter.shared.reloadTimelines(ofKind: $0) }
            } else {
                WidgetCenter.shared.reloadAllTimelines()
            }
        } : nil)
    }

    /// Stand von der Platte neu laden (z. B. nachdem das Widget aktualisiert hat).
    func reloadFromDisk() {
        // Nur einen gelesenen Stand übernehmen: Ist die Datei gerade unlesbar oder nicht zugreifbar,
        // bleibt der Stand im Speicher (sonst stünde eine leere Merkliste da)
        if let fresh = SharedStorage.readSnapshot().snapshot { snapshot = fresh }
        lastRefreshMillis = SharedStorage.lastRefreshDuration
        lastRefreshReport = SharedStorage.lastRefreshReport
        changeStamp = SharedStorage.changeStamp
        activityReports = ActivityRepository.reports()
        dropMissingWatchlistGroup()
    }

    /// «Basis der %-Änderung» zum Zeitpunkt `now`: passt der Stempel der gespeicherten Werte nicht
    /// (Basis gewechselt, neuer Tag), zeigen Änderungen und Puls «—», bis neu gerechnet ist.
    func changeView(now: Int64 = TimeUtils.nowMillis) -> ChangeView {
        ChangeView.of(stamp: changeStamp, basis: settings.changeBasis, now: now, showPeriod: settings.showChangePeriod)
    }

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

        Self.clearStoredFiles()

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
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Speicher leeren wie nach der Installation: Einstellungen (App und App Group), dann alle Dateien der
    /// App-Ordner. Ohne Zustand im Speicher — auch vor dem ersten Zugriff auf `shared` nutzbar (UI-Tests:
    /// `UITesting.prepareIfRequested`). Der Ordner `tmp` bleibt.
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

/// Wechselt das App-Icon zur gewählten Akzentfarbe — nur nach einer Wahl in den
/// Einstellungen, nie automatisch (Hell/Dunkel: Varianten im Asset-Katalog).
enum AppIconSwitcher {
    @MainActor
    static func apply(accent: AccentColor) {
        guard UIApplication.shared.supportsAlternateIcons,
              UIApplication.shared.applicationState == .active else { return }
        let name = accent.iconName
        guard UIApplication.shared.alternateIconName != name else { return }
        UIApplication.shared.setAlternateIconName(name) { _ in }
    }
}
