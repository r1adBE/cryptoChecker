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
        // Jede Übernahme (Ändern, Lesen) zählt: Ein im Hintergrund gelesener Stand gilt nur ohne Übernahme seither
        didSet { snapshotGeneration &+= 1 }
    }
    /// Zähler der Übernahmen in `snapshot` (`reloadFromDiskInBackground`, `DiskReloadTicket`).
    private(set) var snapshotGeneration = 0
    /// Zuletzt begonnenes Lesen im Hintergrund: nur dieses darf übernehmen.
    var diskReloadSequence = 0
    /// Nur während `mutate(…, quiet: true)`: Änderung nicht veröffentlichen.
    private var quietPriceSave = false
    @Published var settings: AppSettings {
        didSet {
            guard settings != oldValue else { return }
            // Nicht zugängliche Einstellungen nie mit Standardwerten überschreiben
            if settingsAccessible { SharedStorage.saveSettings(settings) }
            settingsChanged(from: oldValue)
        }
    }
    /// false = Einstellungen beim Lesen nicht zugänglich (App nach einem Neustart vor dem ersten Entsperren
    /// vorgewärmt): `settings` sind Standardwerte nur zur Anzeige, werden nie gespeichert, und die
    /// Portfolio-Sperre gilt als an (`appLockEffective`). Neu gelesen in `reloadSettingsIfInaccessible`.
    @Published private(set) var settingsAccessible: Bool
    @Published var refreshing = false
    @Published var lastRefreshMillis: Int64 = SharedStorage.lastRefreshDuration
    @Published var lastRefreshReport: RefreshReport? = SharedStorage.lastRefreshReport
    /// Ende der Pause je Börsen-Schlüssel (`RowPause.pauses`): «Binance · pausiert bis 19:45» in der Zeitzeile ihrer
    /// Paare; ob eine Pause noch läuft, entscheidet die Zeile mit der aktuellen Zeit (`RowPause.shownUntil`).
    @Published var exchangePauses: [String: Int64] = RowPause.pauses(SharedStorage.exchangeBackoff)
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
    /// Gibt es Transaktionen oder beobachtete Adressen (ein Portfolio, das bewertet wird)? Dann misst
    /// `DepegAlarmCheck` USDT/USD auch ohne Alarm — wie `PortfolioRepository.hasData` (Android).
    var hasPortfolioData: Bool { !portfolio.isEmpty || !watchedAddresses.isEmpty }
    var watchedAddressIntact = true
    /// `watched_addresses.json` konnte nicht versiegelt gespeichert werden (Schlüsselbund gerade nicht lesbar): der Stand
    /// wartet im Speicher, nie im Klartext — zählt wie das Portfolio zu `portfolioSavePending` (gleicher Hinweis, gleiches
    /// erneutes Speichern in `retryPendingPortfolioSaves`).
    var watchedAddressSavePending = false
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
    /// «Paar bearbeiten» je Watch-Id (ms): Live-Kurse von davor gehören zum alten Paar (`applyLive` verwirft sie).
    var pairChangedAt: [Int64: Int64] = [:]
    /// Live-Kurse werden gerade gespeichert (`applyLive`): eine volle bzw. einzelne Aktualisierung
    /// wartet so lange; umgekehrt lässt `applyLive` aus, solange eine Aktualisierung läuft.
    var liveApplying = false

    var portfolioFile: PortfolioFile
    /// false = `portfolio.json` war beim Lesen da, aber nicht (ganz) lesbar: vor jedem Schreiben neu lesen
    /// bzw. die Datei sichern, statt sie mit einem leeren Stand zu überschreiben.
    var portfolioIntact = true
    /// Portfolio bzw. Portfolio-Alarme konnten nicht versiegelt gespeichert werden (Schlüsselbund gerade nicht lesbar) und
    /// warten im Speicher — nie im Klartext geschrieben. Das Portfolio nennt es; das nächste Speichern bzw. die Rückkehr
    /// der App nach vorne versucht es erneut (`retryPendingPortfolioSaves`).
    @Published var portfolioSavePending = false
    /// `watchlist.json` konnte nicht versiegelt gespeichert werden (Schlüsselbund gerade nicht lesbar, nie im Klartext):
    /// Merkliste und Alarme warten im Speicher — `reloadFromDisk` ersetzt sie dann nicht durch den älteren Stand der Datei,
    /// versucht das Speichern erneut, und jedes weitere Speichern (`mutate`) schreibt den ganzen Stand.
    @Published private(set) var watchlistSavePending = false
    /// `watchlist.json` ist da, aber gerade nicht lesbar (`WatchlistStorageLock`): Die Merkliste steht leer im Speicher und
    /// wird nicht gespeichert. Die Merkliste nennt es dauerhaft; Alarme werden dann nicht angelegt. Neu bei jedem Lesen.
    @Published private(set) var watchlistLocked = false
    /// Noch keine `watchlist.json` und kein Schlüssel zum Versiegeln (`WatchlistStorageLock.waitsForKey`): Was jetzt
    /// angelegt wird, kann nicht gespeichert werden — die Merkliste sagt es gleich, nicht erst nach dem ersten Speichern.
    @Published private(set) var watchlistWaitsForKey = false
    /// Dauerhinweis der Merkliste (gleich wie Android): gesperrt bzw. «wird nicht gespeichert».
    var watchlistStorageNotice: WatchlistStorageLock.Notice? {
        WatchlistStorageLock.notice(locked: watchlistLocked, savePending: watchlistSavePending || watchlistWaitsForKey)
    }
    /// Merkliste in diesem Lauf schon einmal gelesen (`WatchlistStorageLock.holdsStoredState`): Dann hält der Speicher
    /// den echten Stand, und ein später unlesbarer Zugriff setzt `watchlistLocked` nicht mehr.
    private var watchlistReadOnce = false
    var appActive = false
    /// Dateien mit Nutzerdaten blieben beim Start unverschlüsselt (`LaunchOutcome.encryptionPending`): Einstellungen ›
    /// Sicherung nennt «Verschlüsselung noch nicht aktiv» — wie Android `DatabaseEncryptionNotice`.
    private(set) var encryptionPending = false
    /// Zähler für «App zurücksetzen»: Ein Ablauf, der über ein `await` hinweg läuft (z. B. `applyLive`),
    /// übernimmt sein Ergebnis nicht mehr, wenn inzwischen zurückgesetzt wurde.
    internal(set) var resetGeneration = 0

    /// «In Gruppe» beim Hinzufügen: nur für die laufende Sitzung, nicht gespeichert.
    /// nil = «Keine Gruppe».
    @Published var addTargetGroup: String?

    private init() {
        // Vor dem ersten Lesen: Dateien mit Nutzerdaten verschlüsseln (einmalig, Build 59); ohne Schlüssel
        // unlesbare beiseitelegen und das nennen, bis der Nutzer es bestätigt (`StorageRecoveryNotice`); öffnen
        // beiseitegelegte Dateien doch mit dem Schlüssel, kommen sie zurück (einmal genannt)
        let protection = ProtectedStorage.prepareAtLaunch()
        if !protection.setAside.isEmpty { StorageRecoveryNotice.record() }
        if !protection.restored.isEmpty && protection.setAside.isEmpty { StorageRecoveryNotice.recordRestored() }
        encryptionPending = protection.encryptionPending
        let read = SharedStorage.readSnapshot()
        snapshot = read.snapshot ?? SharedStorage.Snapshot()
        watchlistLocked = WatchlistStorageLock.isLocked(read)
        watchlistReadOnce = WatchlistStorageLock.holdsStoredState(read)
        watchlistWaitsForKey = WatchlistStorageLock.waitsForKey(read, keyAvailable: StorageKey.shared.isAvailable)
        let loadedSettings = AppData.readSettings()
        settings = loadedSettings.settings
        settingsAccessible = loadedSettings.state != .inaccessible
        if loadedSettings.state == .missing { SharedStorage.markSettingsReadable() }
        // Unlesbare Einstellungen (seit Build 97): einmal melden und gleich durch die Standardwerte ersetzen wie Android —
        // sonst meldete jeder Start dieselben unlesbaren Daten erneut
        if loadedSettings.corrupt { AppData.settingsWereReset(loadedSettings.settings) }
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
        // «Portfolio in Systemsicherung»: auch für eine schon vorhandene Datei (Update, Wiederherstellung) —
        // nicht mit Standardwerten, solange die Einstellungen nicht zugänglich sind
        if settingsAccessible { PortfolioStore.applyBackupPolicy(includeInBackup: settings.portfolioSystemBackup) }
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
        // Einstellungen fehlten oder waren unlesbar (Standardwerte), Portfolio-Daten sind aber da: Portfolio an — auch
        // mit offenem Merker aus einem früheren Start (`portfolioRecoveryDeferred`, gespeichert)
        if settingsAccessible && (loadedSettings.state == .missing || portfolioRecoveryDeferred) {
            let recovered = portfolioRecovered(settings, state: loadedSettings.state)
            if recovered.portfolioEnabled != settings.portfolioEnabled {
                settings = recovered
                SharedStorage.saveSettings(settings)
            }
        }
        // Update mit vorhandenen Alarmen: die Erst-Alarm-Bestätigung gilt als gezeigt
        if settingsAccessible && !settings.firstAlarmShown && !snapshot.alarms.isEmpty {
            settings.firstAlarmShown = true
            SharedStorage.saveSettings(settings)
        }
        // Update mit vorhandenen Paaren: das erste Hinzufügen gilt als erledigt
        if settingsAccessible && !settings.firstPairAdded && !snapshot.watches.isEmpty {
            settings.firstPairAdded = true
            SharedStorage.saveSettings(settings)
        }
        // Netz weg/zurück (#22/#23): Statuszeile; zurück → genau eine Aktualisierung
        NetworkStatus.shared.observe { online in
            Task { @MainActor in AppData.shared.networkChanged(online) }
        }
        // Gerät entsperrt: Einstellungen nachlesen, falls sie beim Start nicht zugänglich waren
        _ = NotificationCenter.default.addObserver(forName: UIApplication.protectedDataDidBecomeAvailableNotification,
                                                   object: nil, queue: .main) { _ in
            Task { @MainActor in AppData.shared.reloadSettingsIfInaccessible() }
        }
    }

    /// Einstellungen lesen; «nicht zugänglich» erkennt die App an `isProtectedDataAvailable`.
    private static func readSettings() -> SharedStorage.LoadedSettings {
        SharedStorage.loadSettingsChecked(protectedDataAvailable: UIApplication.shared.isProtectedDataAvailable)
    }

    /// Einstellungen waren unlesbar: Standardwerte speichern und den Hinweis vormerken (`StorageRecoveryNotice.settingsReset`).
    private static func settingsWereReset(_ standard: AppSettings) {
        StorageRecoveryNotice.recordSettingsReset()
        SharedStorage.saveSettings(standard)
    }

    /// Portfolio-Sperre wirksam: eingeschaltet — oder unbekannt, solange die Einstellungen nicht zugänglich sind.
    var appLockEffective: Bool { !settingsAccessible || settings.appLock }

    /// Einstellungen waren beim Start nicht zugänglich: jetzt neu lesen (Gerät entsperrt, App aktiv) und
    /// übernehmen — mit allen Folgen (`settingsChanged`) und dem Sperrzustand.
    func reloadSettingsIfInaccessible() {
        // Unabhängig von den Einstellungen: Portfolio, Adressen und Verschlüsselung nachholen
        reloadProtectedFilesIfNeeded()
        guard !settingsAccessible else { return }
        let loaded = AppData.readSettings()
        guard loaded.state != .inaccessible else { return }
        if loaded.state == .missing { SharedStorage.markSettingsReadable() }
        if loaded.corrupt { AppData.settingsWereReset(loaded.settings) }
        settingsAccessible = true
        // didSet: speichert denselben Stand und wendet die Folgen der Unterschiede an
        settings = portfolioRecovered(loaded.settings, state: loaded.state)
        PortfolioStore.applyBackupPolicy(includeInBackup: settings.portfolioSystemBackup)
        AppLock.shared.settingsReloaded()
        BackgroundRefresh.schedule(settings: settings)
    }

    /// Beim Start vor dem ersten Entsperren nicht lesbar: `portfolio.json` und `watched_addresses.json` blieben
    /// leer, und die Verschlüsselung (`prepareAtLaunch`) stand aus. Sobald die geschützten Daten zugänglich sind
    /// (Entsperren, App aktiv, `reloadFromDisk`), hier nachholen — sonst bliebe das Portfolio die ganze Sitzung leer.
    func reloadProtectedFilesIfNeeded() {
        // Dateien schon lesbar (z. B. inzwischen über `current…File()` gelesen): aufgeschobene Prüfung nicht verpassen
        recoverPortfolioIfDeferred()
        guard UIApplication.shared.isProtectedDataAvailable else { return }
        guard encryptionPending || !portfolioIntact || !watchedAddressIntact else { return }
        // Zuerst verschlüsseln (wie beim Start vor dem ersten Lesen), dann neu lesen
        if encryptionPending {
            let protection = ProtectedStorage.prepareAtLaunch()
            if !protection.setAside.isEmpty { StorageRecoveryNotice.record() }
            encryptionPending = protection.encryptionPending
        }
        // `current…File()` liest nur neu, solange der Stand als nicht lesbar gilt, und übernimmt ihn dann
        if !portfolioIntact { _ = currentPortfolioFile() }
        if !watchedAddressIntact { _ = currentWatchedAddressFile() }
        recoverPortfolioIfDeferred()
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
        // Speichern im Hintergrund; die Widgets erst danach neu laden (sie lesen die Datei). Danach: warten Portfolio-Alarme
        // auf den Schlüssel (nie Klartext), nennt das Portfolio es
        SharedStorage.saveSnapshotInBackground(s, done: { [weak self] in
            if SharedStorage.portfolioAlarmsPending { self?.portfolioSavePending = true }
            // Nur bei Änderung veröffentlichen (gespeichert wird bei Live-Kursen alle paar Sekunden)
            let waiting = SharedStorage.watchlistPending
            if self?.watchlistSavePending != waiting { self?.watchlistSavePending = waiting }
            guard reloadWidgets else { return }
            if let widgetKinds {
                widgetKinds.forEach { WidgetCenter.shared.reloadTimelines(ofKind: $0) }
            } else {
                WidgetCenter.shared.reloadAllTimelines()
            }
        })
    }

    /// Stand von der Platte neu laden (z. B. nachdem das Widget aktualisiert hat) — blockiert den Haupt-Thread; beim
    /// Wechsel nach vorne liest `reloadFromDiskInBackground`.
    func reloadFromDisk() {
        guard prepareDiskReload() else { return }
        applyDiskRead(SharedStorage.readSnapshot(), keyAvailable: StorageKey.shared.isAvailable)
    }

    /// Vor dem Lesen (Haupt-Thread). false = nicht lesen: Der Speicher bleibt massgebend.
    func prepareDiskReload() -> Bool {
        // Wartet noch ein nicht versiegelt gespeicherter Stand: erst schreiben — sonst ersetzte der ältere Stand der Datei
        // die Änderungen im Speicher. Geht es noch nicht, bleibt der Speicher massgebend
        guard retryPendingPortfolioSaves() else { return false }
        // Wartet die Merkliste auf den Schlüssel: erneut speichern, auf demselben Weg wie `mutate` (neuere Kurse und
        // Auslösungen des Widgets bleiben); `readSnapshot` wartet dieses Schreiben ab
        if watchlistSavePending { SharedStorage.saveSnapshotInBackground(snapshot) }
        return true
    }

    /// Gelesenen Stand übernehmen (Haupt-Thread); `read` nil = verworfen (seither geändert), nur die übrigen Werte neu.
    func applyDiskRead(_ read: SharedStorage.SnapshotRead?, keyAvailable: Bool) {
        let waiting = SharedStorage.watchlistPending
        if watchlistSavePending != waiting { watchlistSavePending = waiting }
        if let read {
            let locked = WatchlistStorageLock.isLocked(read, readBefore: watchlistReadOnce)
            if watchlistLocked != locked { watchlistLocked = locked }
            if WatchlistStorageLock.holdsStoredState(read) { watchlistReadOnce = true }
            let waitsForKey = WatchlistStorageLock.waitsForKey(read, keyAvailable: keyAvailable)
            if watchlistWaitsForKey != waitsForKey { watchlistWaitsForKey = waitsForKey }
            // Nur einen gelesenen Stand übernehmen: Ist die Datei gerade unlesbar oder nicht zugreifbar,
            // bleibt der Stand im Speicher (sonst stünde eine leere Merkliste da) — ebenso, solange die Merkliste
            // noch nicht versiegelt gespeichert ist (die Datei hat dann einen älteren Stand)
            if !waiting, let fresh = read.snapshot { snapshot = fresh }
        }
        reloadSettingsIfInaccessible()
        lastRefreshMillis = SharedStorage.lastRefreshDuration
        lastRefreshReport = SharedStorage.lastRefreshReport
        reloadExchangePauses()
        changeStamp = SharedStorage.changeStamp
        activityReports = ActivityRepository.reports()
        dropMissingWatchlistGroup()
    }

    /// «Basis der %-Änderung» zum Zeitpunkt `now`: passt der Stempel der gespeicherten Werte nicht
    /// (Basis gewechselt, neuer Tag), zeigen Änderungen und Puls «—», bis neu gerechnet ist.
    func changeView(now: Int64 = TimeUtils.nowMillis) -> ChangeView {
        ChangeView.of(stamp: changeStamp, basis: settings.changeBasis, now: now, showPeriod: settings.showChangePeriod)
    }
}
