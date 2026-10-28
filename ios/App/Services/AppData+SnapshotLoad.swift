import Foundation
import os

// Lesen beim Start abseits des Haupt-Threads (Teil von `AppData`): Verschlüsselung
// (`ProtectedStorage.prepareAtLaunch`), Merkliste (`SharedStorage.readSnapshot`) und beobachtete Adressen laufen im
// Hintergrund (`LaunchStorageLoader`); das erste Bild zeigt bis dahin Platzhalter-Zeilen (`WatchlistLoadingRows`).
// Jeder Zugriff vorher (`snapshot`, Adressen,
// Sperrzustand, Portfolio) liest sofort selbst und wartet dabei ein schon laufendes Lesen ab (`ensureSnapshotLoaded`) —
// es gibt keinen sichtbaren «noch nicht gelesenen» Stand, und kein Schreiben kann einen ungelesenen Stand
// überschreiben. Was nur auf den gelesenen Stand warten soll (Lesen nach dem Wechsel nach vorne, Aufräumen nach dem
// Start, «1 h später»), reiht sich ein (`whenSnapshotLoaded`, `waitForSnapshot`).

/// Ergebnis des Lesens beim Start (abseits des Haupt-Threads).
struct LaunchRead: Sendable {
    /// Verschlüsselte Dateien ohne Schlüssel beiseitegelegt (`ProtectedStorage.LaunchOutcome.setAside`).
    let setAside: Bool
    /// Beiseitegelegte Dateien wieder an ihrem Platz (`ProtectedStorage.LaunchOutcome.restored`).
    let restored: Bool
    /// Dateien bleiben unverschlüsselt (`ProtectedStorage.LaunchOutcome.encryptionPending`).
    let encryptionPending: Bool
    let read: SharedStorage.SnapshotRead
    let keyAvailable: Bool
    let watched: WatchedAddressFile
    let watchedIntact: Bool

    /// Zuerst verschlüsseln, dann lesen — wie früher der synchrone Start. Im Systemprotokoll als Intervall
    /// «launchRead» (Instruments, Kategorie «launch»).
    static func read() -> LaunchRead {
        let signposter = OSSignposter(subsystem: AppLog.subsystem, category: "launch")
        let interval = signposter.beginInterval("launchRead")
        defer { signposter.endInterval("launchRead", interval) }
        let protection = ProtectedStorage.prepareAtLaunch()
        let read = SharedStorage.readSnapshot()
        let keyAvailable = StorageKey.shared.isAvailable
        let watched = WatchedAddressStore.loadChecked()
        return LaunchRead(setAside: !protection.setAside.isEmpty, restored: !protection.restored.isEmpty,
                          encryptionPending: protection.encryptionPending, read: read, keyAvailable: keyAvailable,
                          watched: watched.file, watchedIntact: watched.intact)
    }
}

/// Einmaliges Lesen beim Start: Der erste Aufruf liest, jeder weitere — auch gleichzeitig, z. B. der Haupt-Thread
/// während des Lesens im Hintergrund — wartet und bekommt dasselbe Ergebnis. Nie zweimal verschlüsseln oder lesen.
final class LaunchStorageLoader: @unchecked Sendable { // @unchecked: `cached` nur unter `lock`
    private let lock = NSLock()
    private var cached: LaunchRead?
    private let reader: @Sendable () -> LaunchRead

    init(reader: @escaping @Sendable () -> LaunchRead = { LaunchRead.read() }) {
        self.reader = reader
    }

    /// Ergebnis des einen Lesens (liest beim ersten Aufruf).
    func value() -> LaunchRead {
        lock.withLock {
            if let cached { return cached }
            let read = reader()
            cached = read
            return read
        }
    }
}

/// Beobachtete Adressen in `AppData`: Liste (Reihenfolge des Hinzufügens), Datei-Stand und ob sie lesbar war.
struct WatchedAddressState {
    var addresses: [WatchedAddress] = []
    var file = WatchedAddressFile()
    /// false = Datei da, aber nicht lesbar (dann vor dem Schreiben neu lesen).
    var intact = true
}

extension AppData {
    /// Beobachtete Adressen in Reihenfolge des Hinzufügens (wie `PortfolioRepository.watchedAddresses`); Zugriff über
    /// `AppData+WatchedAddresses.swift`. Veröffentlicht jede Zuweisung wie `@Published`.
    var watchedAddresses: [WatchedAddress] {
        get {
            ensureSnapshotLoaded()
            return watchedState.addresses
        }
        set {
            ensureSnapshotLoaded()
            objectWillChange.send()
            watchedState.addresses = newValue
        }
    }

    /// Stand von `watched_addresses.json`.
    var watchedAddressFile: WatchedAddressFile {
        get {
            ensureSnapshotLoaded()
            return watchedState.file
        }
        set {
            ensureSnapshotLoaded()
            watchedState.file = newValue
        }
    }

    /// false = `watched_addresses.json` da, aber nicht lesbar (dann vor dem Schreiben neu lesen).
    var watchedAddressIntact: Bool {
        get {
            ensureSnapshotLoaded()
            return watchedState.intact
        }
        set {
            ensureSnapshotLoaded()
            watchedState.intact = newValue
        }
    }

    /// `watchlist.json` ist da, aber gerade nicht lesbar (`WatchlistStorageLock`, siehe `watchlistLockedValue`).
    var watchlistLocked: Bool {
        get {
            ensureSnapshotLoaded()
            return watchlistLockedValue
        }
        set {
            ensureSnapshotLoaded()
            watchlistLockedValue = newValue
        }
    }

    /// Beim Start: im Hintergrund lesen und übernehmen, falls bis dahin niemand sofort gelesen hat.
    func startSnapshotLoad() {
        let loader = launchLoader
        Task { [weak self] in
            let launch = await Task.detached(priority: .userInitiated) { loader.value() }.value
            self?.adoptLaunchRead(launch, immediate: false)
        }
    }

    /// Noch nicht übernommen: jetzt sofort lesen bzw. das laufende Lesen abwarten (Haupt-Thread, wie früher beim
    /// Start).
    func ensureSnapshotLoaded() {
        guard !snapshotLoaded else { return }
        adoptLaunchRead(launchLoader.value(), immediate: true)
    }

    /// `action` nach dem Lesen beim Start ausführen (schon gelesen: sofort).
    func whenSnapshotLoaded(_ action: @escaping @MainActor () -> Void) {
        if snapshotLoaded {
            action()
        } else {
            snapshotWaiters.append(action)
        }
    }

    /// Wartet, bis der Stand beim Start gelesen ist (ohne selbst auf dem Haupt-Thread zu lesen).
    func waitForSnapshot() async {
        guard !snapshotLoaded else { return }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            snapshotWaiters.append { continuation.resume() }
        }
    }

    /// Übernehmen — nur das erste Mal. `immediate`: mitten in einem Zugriff (still übernommen; Ansichten und Wartende
    /// erst im nächsten Durchlauf, nicht mitten im Zugriff).
    private func adoptLaunchRead(_ launch: LaunchRead, immediate: Bool) {
        guard !snapshotLoaded else { return }
        setLaunchSnapshot(launch, quiet: immediate)
        let watched = launch.watched
        watchedState = WatchedAddressState(addresses: Self.sortedWatched(watched.addresses), file: watched,
                                           intact: launch.watchedIntact)
        finishLaunchLoad(launch)
        guard immediate else {
            runSnapshotWaiters()
            return
        }
        Task { @MainActor [weak self] in
            // Platzhalter der Merkliste durch den gelesenen Stand ersetzen
            self?.objectWillChange.send()
            self?.runSnapshotWaiters()
        }
    }

    /// Wartende in der Reihenfolge des Einreihens ausführen (danach reiht sich niemand mehr ein: `whenSnapshotLoaded`
    /// führt gleich aus).
    private func runSnapshotWaiters() {
        let waiters = snapshotWaiters
        snapshotWaiters = []
        waiters.forEach { $0() }
    }

    /// Folgen des gelesenen Stands (früher am Ende von `init`, in derselben Reihenfolge).
    private func finishLaunchLoad(_ launch: LaunchRead) {
        // Ohne Schlüssel unlesbare Dateien beiseitegelegt: nennen, bis bestätigt (`StorageRecoveryNotice`); öffnen sie
        // doch mit dem Schlüssel, kommen sie zurück (einmal genannt)
        if launch.setAside { StorageRecoveryNotice.record(); AlarmDelivery.removeAllMarkers() }
        if launch.restored && !launch.setAside { StorageRecoveryNotice.recordRestored() }
        // Portfolio im Hintergrund lesen (nach dem Verschlüsseln); wer es vorher braucht, liest es beim ersten Zugriff
        // sofort (`ensurePortfolioLoaded`) — nie ein leerer «noch nicht gelesener» Stand
        startPortfolioPrefetch()
        // «Portfolio in Systemsicherung»: auch für eine schon vorhandene Datei (Update, Wiederherstellung) —
        // nicht mit Standardwerten, solange die Einstellungen nicht zugänglich sind
        if settingsAccessible { PortfolioStore.applyBackupPolicy(includeInBackup: settings.portfolioSystemBackup) }
        // Einstellungen fehlten oder waren unlesbar (Standardwerte), Portfolio-Daten sind aber da: Portfolio an — auch
        // mit offenem Merker aus einem früheren Start (`portfolioRecoveryDeferred`, gespeichert). didSet speichert.
        if settingsAccessible && (launchSettingsState == .missing || portfolioRecoveryDeferred) {
            let recovered = portfolioRecovered(settings, state: launchSettingsState)
            if recovered.portfolioEnabled != settings.portfolioEnabled { settings = recovered }
        }
        // Update mit vorhandenen Alarmen: die Erst-Alarm-Bestätigung gilt als gezeigt
        if settingsAccessible && !settings.firstAlarmShown && !snapshot.alarms.isEmpty {
            settings.firstAlarmShown = true
        }
        // Update mit vorhandenen Paaren: das erste Hinzufügen gilt als erledigt
        if settingsAccessible && !settings.firstPairAdded && !snapshot.watches.isEmpty {
            settings.firstPairAdded = true
        }
        primeBackgroundDemand() // Basis des Hintergrund-Bedarfs aus dem gelesenen Stand
    }
}
