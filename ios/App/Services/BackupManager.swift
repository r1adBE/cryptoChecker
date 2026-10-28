import Foundation
import SwiftUI
import UniformTypeIdentifiers
import CryptoKit

/// Sichern und Wiederherstellen: Merkliste, Alarme, Favoriten, Portfolio und
/// Einstellungen als eine JSON-Datei, die der Nutzer selbst ablegt — wie `BackupManager.kt`.
///
/// Das Format ist genau das der Android-Fassung, damit Sicherungen zwischen
/// Android und iOS austauschbar bleiben (mit Passwort zusätzlich verschlüsselt, `BackupCrypto`):
/// `{"format":"cryptochecker-backup","version":1,"createdAt":…,"watches":[…],
///   "alarms":[…],"favorites":{"MARKET":[…],"COIN":[…],"QUOTE":[…]},"settings":{…},
///   "portfolio":[{"id","coin","type","amount","amountText","priceUsdt","priceText","time","note","fee","feeText",
///                 "feeUnit","exchange","transferFrom","transferTo","feeCoin","feeCoinAmountText","feeCoinPriceText",
///                 "importKey","acquiredTime"?}…],
///   "portfolioAlarms":[{"id","kind","threshold","currency","enabled","repeating"}…],
///   "watchedAddresses":[{"chain","address","label","addedAt","observations"?,"tokenObservations"?,"costLots"?,…}…]}`
///
/// Bewusst `JSONSerialization` statt `Codable`: fehlende Werte werden als
/// `null` geschrieben (z. B. `pairId`, `darkMode`), wie `JSONObject.NULL`.
enum BackupManager {
    static let format = "cryptochecker-backup"
    /// Geschriebene Version = höchste lesbare (`BackupVersion.supported`).
    static let version = BackupVersion.supported

    enum Failure: Error {
        /// Keine Crypto-Checker-Sicherung.
        case notABackup
        /// Pflichtfeld fehlt oder hat den falschen Typ.
        case invalid(String)
        /// Mehr ungültige Portfolio-Einträge, als der Nutzer bestätigt hat — nichts wird ersetzt.
        case unconfirmedInvalid(Int)
        /// Journal des Wiederherstellens nicht geschrieben (Schlüssel gerade nicht verfügbar oder
        /// Speicherfehler) — nichts wird ersetzt, wie Android `BackupJournalUnavailableException`.
        case journalUnavailable
    }

    /// Sicherungsdatei lesen, höchstens `BackupLimits.maxBytes` (nie eine beliebig grosse Datei in den Speicher).
    static func readFile(_ url: URL) throws -> Data {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let data = try handle.read(upToCount: BackupLimits.maxBytes + 1) ?? Data()
        guard data.count <= BackupLimits.maxBytes else { throw BackupLimits.Failure.tooLarge }
        return data
    }

    /// Vorschlag für den Dateinamen (ohne Endung), z. B. `cryptochecker-backup-2026-10-03`.
    static func defaultFilename(now: Date = Date()) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.dateFormat = "yyyy-MM-dd"
        return "cryptochecker-backup-" + f.string(from: now)
    }

    // MARK: Sichern

    /// Sicherung als Datei; mit `password` verschlüsselt (`BackupCrypto`, gleiches Format wie
    /// unter Android), sonst wie bisher als lesbares JSON. Die App nimmt den Weg über
    /// `plainExportData` + `encryptedFile` (Schlüsselableitung im Hintergrund).
    @MainActor
    static func exportData(_ data: AppData, password: String?) throws -> Data {
        let plain = try plainExportData(data)
        try BackupLimits.checkWritable(plain)
        guard let password else { return plain }
        return try encryptedFile(plain, password: password)
    }

    /// Klartext verschlüsseln (`BackupCrypto` v3, Argon2id mit 64 MiB — einige 100 ms bis wenige Sekunden):
    /// nicht auf dem Main-Thread aufrufen. Die Passwort-Bytes werden danach überschrieben.
    /// - Throws: `BackupCrypto.Failure.notEnoughMemory`, wenn der Speicher für Argon2id nicht reicht;
    ///   `BackupLimits.Failure.exportTooLarge`, wenn die Hülle (Base64, grösser als der Klartext) nicht mehr lesbar wäre.
    static func encryptedFile(_ plain: Data, password: String) throws -> Data {
        var bytes = BackupCrypto.passwordBytes(password)
        defer { Argon2id.wipe(&bytes) }
        let file = BackupCrypto.json(try BackupCrypto.encrypt(plain, password: bytes))
        try BackupLimits.checkWritable(file)
        return file
    }

    /// Lesbares JSON der Sicherung (Merkliste, Alarme, Favoriten, Einstellungen, Portfolio). Ein Stand: alles wird in einem
    /// Durchgang auf dem Main Actor aus `AppData` gelesen — keine Änderung kann dazwischen kommen (wie Android, dort je
    /// Datenbank eine Lesetransaktion). Vor dem Schreiben als Datei `BackupLimits.checkWritable` (das Journal braucht es nicht).
    @MainActor
    static func plainExportData(_ data: AppData) throws -> Data {
        let snapshot = data.snapshot
        let watches = snapshot.watches.sorted {
            $0.sortOrder != $1.sortOrder ? $0.sortOrder < $1.sortOrder : $0.id < $1.id
        }
        let alarms = snapshot.alarms.sorted { $0.id < $1.id }

        var favorites: [String: Any] = [:]
        for kind in FavoriteKind.allCases {
            favorites[kind.rawValue] = Array(data.favorites[kind] ?? []).sorted()
        }

        let root: [String: Any] = [
            "format": format,
            "version": version,
            "createdAt": NSNumber(value: EpochTime.nowMillis),
            "watches": watches.map(watchToJson),
            "alarms": alarms.map(alarmToJson),
            "favorites": favorites,
            "settings": settingsJson(data.settings),
            // Neueste zuerst — wie `portfolioDao.getAll()`
            "portfolio": data.portfolio.map(txToJson),
            // Alarme «Portfolio-Wert» (Runde 28), nach Id — wie Android
            "portfolioAlarms": data.portfolioAlarms.map(portfolioAlarmToJson),
            // Beobachtete Adressen (Netz, Adresse, Bezeichnung, Datum, Guthaben-Verlauf und Einstandsposten)
            WatchedAddressRules.backupKey: watchedToJson(WatchedAddressRules.backupEntries(data.watchedAddresses)),
        ]
        return try encode(root)
    }

    /// Kompakt (ältere Versionen schrieben `.prettyPrinted`, gut ein Drittel grösser); das Einlesen nimmt beides.
    /// Sortierte Schlüssel wie bisher: gleicher Stand ergibt dieselbe Datei.
    static func encode(_ root: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
    }

    // MARK: Wiederherstellen

    /// Verschlüsselte Sicherung? Dann vor dem Wiederherstellen nach dem Passwort fragen.
    /// - Throws: `Failure.notABackup`, wenn die Datei keine Sicherung ist.
    static func needsPassword(_ file: Data) throws -> Bool {
        try BackupLimits.checkDepth(file)
        guard let root = try JSONSerialization.jsonObject(with: file) as? [String: Any] else { throw Failure.notABackup }
        // Verschlüsselt: eigenes «format» (cryptochecker-backup-enc), ältere Versionen lehnen sie ab
        if BackupCrypto.isEnvelope(root) { return true }
        guard root["format"] as? String == format else { throw Failure.notABackup }
        return false
    }

    /// Klartext-JSON der Sicherung; verschlüsselt ohne Passwort gilt als falsches Passwort.
    /// Mit Passwort spürbar langsam (Argon2id bzw. bei v2 PBKDF2 mit den Parametern der Datei) — nicht auf dem
    /// Main-Thread aufrufen.
    /// - Throws: `BackupCrypto.Failure.wrongPassword` bei falschem Passwort oder veränderter Datei,
    ///   `BackupCrypto.Failure.notEnoughMemory`, wenn der Speicher für Argon2id nicht reicht.
    static func plainJSON(_ file: Data, password: String?) throws -> Data {
        try BackupLimits.checkDepth(file)
        guard let root = try JSONSerialization.jsonObject(with: file) as? [String: Any] else { throw Failure.notABackup }
        guard BackupCrypto.isEnvelope(root) else {
            guard root["format"] as? String == format else { throw Failure.notABackup }
            return file
        }
        guard let password else { throw BackupCrypto.Failure.wrongPassword }
        let envelope = try BackupCrypto.envelope(from: root)
        var bytes = BackupCrypto.passwordBytes(password)
        defer { Argon2id.wipe(&bytes) }
        let plain = try BackupCrypto.decrypt(envelope, password: bytes)
        try BackupLimits.checkDepth(plain)
        return plain
    }

    /// Was die Rückfrage vor dem Wiederherstellen nennt (wie Android `RestorePreview`): `invalid` = ungültige Einträge
    /// (zu bestätigen, `acceptInvalid`), `droppedPairs`/`droppedAlarms` = über den Grenzen (`BackupRestoreLimits`).
    struct RestorePreview: Equatable {
        var invalid = 0
        var droppedPairs = 0
        var droppedAlarms = 0
    }

    /// Ungültige Einträge, die nicht übernommen würden — Paar-Alarme (z. B. Alarmart einer neueren Version),
    /// Portfolio-Transaktionen (z. B. Menge fehlt) und Portfolio-Alarme — sowie Paare und Alarme über den Grenzen:
    /// vor der Rückfrage, damit der Nutzer davon erfährt, bevor etwas ersetzt wird (wie Android `preview`).
    static func preview(_ json: Data) throws -> RestorePreview {
        guard let root = try JSONSerialization.jsonObject(with: json) as? [String: Any],
              root["format"] as? String == format
        else { throw Failure.notABackup }
        // Neuere Version: schon vor der Rückfrage ablehnen (klare Meldung, nichts übernommen)
        _ = try BackupVersion.check(root["version"])
        // Unlesbares Paar: hier nichts über die Grenzen sagen — das Wiederherstellen lehnt die Datei danach ohnehin ab
        let watchlist = try? restoredWatchlist(root)
        return RestorePreview(invalid: parsedAlarms(root).invalid + parsedPortfolio(root).invalid + parsedPortfolioAlarms(root).invalid,
                              droppedPairs: watchlist?.droppedPairs ?? 0, droppedAlarms: watchlist?.droppedAlarms ?? 0)
    }

    /// Was eine Wiederherstellung übernommen hat (wie Android `RestoreResult`); `appLockKept` = eine Sicherung ohne
    /// Passwort wollte die Portfolio-Sperre ausschalten, sie blieb an; `droppedPairs`/`droppedAlarms` = über den Grenzen
    /// nicht übernommen; `addressServerNote` = Hinweis zum eigenen Bitcoin-Server (`BackupRestorePolicy`).
    struct RestoreResult: Equatable {
        let watches: Int
        let alarms: Int
        let appLockKept: Bool
        var droppedPairs = 0
        var droppedAlarms = 0
        var addressServerNote: BackupRestorePolicy.AddressServerNote?
    }

    /// Nur die ungültigen Einträge aus `preview`.
    static func invalidEntries(_ json: Data) throws -> Int {
        try preview(json).invalid
    }

    /// «version» des Klartexts prüfen (`BackupVersion.check`): neuere → `BackupVersion.Failure.newer`, ohne etwas zu lesen.
    static func checkVersion(_ json: Data) throws {
        guard let root = try JSONSerialization.jsonObject(with: json) as? [String: Any] else { throw Failure.notABackup }
        _ = try BackupVersion.check(root["version"])
    }

    /// Transaktionen der Sicherung; nil = Sicherung ohne Portfolio. Ungültige werden gezählt.
    private static func parsedPortfolio(_ root: [String: Any]) -> (items: [PortfolioTx]?, invalid: Int) {
        guard let list = root["portfolio"] as? [Any] else { return (nil, 0) }
        let items = list.compactMap { ($0 as? [String: Any]).flatMap(jsonToTx) }
        return (items, list.count - items.count)
    }

    /// Portfolio-Alarme der Sicherung; nil = ohne. Ungültige werden gezählt.
    private static func parsedPortfolioAlarms(_ root: [String: Any]) -> (items: [PortfolioAlarm]?, invalid: Int) {
        guard let list = root["portfolioAlarms"] as? [Any] else { return (nil, 0) }
        let items = list.compactMap { ($0 as? [String: Any]).flatMap(jsonToPortfolioAlarm) }
        return (items, list.count - items.count)
    }

    /// Ersetzt Merkliste, Alarme, Favoriten und Einstellungen durch die Sicherung.
    /// Alarme, deren Paar fehlt, fallen weg (wie der Fremdschlüssel unter Android); doppelte Paare werden
    /// zusammengelegt (`WatchlistRestoreCleanup`).
    /// `json` ist der Klartext (`plainJSON`); eine verschlüsselte Hülle hat ein anderes «format» und wird abgelehnt.
    /// Ungültige Portfolio-Einträge fallen nur weg, wenn der Nutzer das bestätigt hat (`acceptInvalid` = gezeigte
    /// Anzahl aus `preview`); sind es mehr, bricht der Import ab, bevor etwas ersetzt wird.
    /// `encrypted` = Sicherung mit Passwort; ohne schaltet sie die Portfolio-Sperre nicht aus
    /// (`BackupRestorePolicy`, Ergebnis `appLockKept`).
    /// `journal`: vor dem ersten Schreiben den bisherigen Stand versiegelt merken (`RestoreJournal`) — aus beim Zurückrollen
    /// eines abgebrochenen Laufs selbst — dann auch ohne `BackupRestoreLimits` (eigener Stand kommt unverändert zurück).
    @MainActor
    static func restore(_ json: Data, into data: AppData, acceptInvalid: Int = 0,
                        encrypted: Bool = false, journal: Bool = true) throws -> RestoreResult {
        guard let root = try JSONSerialization.jsonObject(with: json) as? [String: Any],
              root["format"] as? String == format
        else { throw Failure.notABackup }
        // Sicherung einer neueren App-Version: ganz ablehnen, bevor etwas gelesen oder ersetzt wird
        _ = try BackupVersion.check(root["version"])

        // Merkliste und Alarme lesen, bereinigen und begrenzen (`restoredWatchlist`, wie Android): Paare mit unzulässigen
        // Kennungen und über den Grenzen fallen weg, gezählt für die Erfolgsmeldung
        let watchlist = try restoredWatchlist(root, limits: journal)
        let watches = watchlist.watches
        let alarms = watchlist.alarms

        var favorites: [FavoriteKind: Set<String>] = [:]
        if let fav = root["favorites"] as? [String: Any] {
            for kind in FavoriteKind.allCases {
                if let arr = fav[kind.rawValue] as? [Any] {
                    favorites[kind] = Set(arr.compactMap { $0 as? String })
                }
            }
        }

        let settingsJson = root["settings"] as? [String: Any]
        var newSettings = settingsJson.map { applySettings($0, onto: data.settings, trusted: encrypted) }
        // Portfolio-Sperre: ohne Passwort keine Echtheitsprüfung — nie ausschalten, solange sie an ist
        let lock = BackupRestorePolicy.appLock(wanted: settingsJson.flatMap { bool($0, "appLock") },
                                               current: data.settings.appLock, encrypted: encrypted,
                                               canAuthenticate: AppLock.canAuthenticate())
        if let v = lock.apply { newSettings?.appLock = v }
        // Eigener Bitcoin-Server: Hinweis in der Meldung (nicht übernommen, entfernt oder ersetzt)
        let serverNote = settingsJson.flatMap { o in
            BackupRestorePolicy.addressServerNote(present: o["addressServerBtc"] != nil, value: string(o, "addressServerBtc"),
                                                  current: data.settings.addressServerBtc, trusted: encrypted)
        }

        // Ältere Sicherungen haben noch kein Portfolio: dann bleibt das bestehende stehen.
        // Ungültige Einträge nur mit Bestätigung weglassen (nie still)
        let parsedTx = parsedPortfolio(root)
        // Seit Runde 28; ältere Sicherungen ohne Portfolio-Alarme lassen die bestehenden stehen
        let parsedPfAlarms = parsedPortfolioAlarms(root)
        let invalid = watchlist.invalidAlarms + parsedTx.invalid + parsedPfAlarms.invalid
        if invalid > acceptInvalid { throw Failure.unconfirmedInvalid(invalid) }
        let portfolio = parsedTx.items
        let portfolioAlarms = parsedPfAlarms.items

        let old = data.snapshot
        var snapshot = SharedStorage.Snapshot()
        snapshot.watches = watches
        snapshot.alarms = alarms
        // Wiederhergestellte Portfolio-Alarme sind scharf und ohne letzte Meldung
        snapshot.portfolioAlarms = portfolioAlarms.map { list in
            list.map { a in
                var copy = a
                copy.referenceAt = 0
                copy.lastTriggeredAt = 0
                copy.lastTriggeredValue = nil
                return copy
            }
        } ?? old.portfolioAlarms
        // Ids nie wiederverwenden: über dem bisherigen Zähler und über allen neuen ids
        // (`nextId` ohne Überlauf — eine id Int64.max aus der Datei brach mit «max + 1» ab)
        snapshot.nextPortfolioAlarmId = SharedStorage.nextId(stored: old.nextPortfolioAlarmId ?? 1,
                                                             used: (snapshot.portfolioAlarms ?? []).map(\.id))
        snapshot.nextWatchId = SharedStorage.nextId(stored: old.nextWatchId, used: watches.map(\.id))
        snapshot.nextAlarmId = SharedStorage.nextId(stored: old.nextAlarmId, used: alarms.map(\.id))

        // Bisherigen Stand merken: scheitert ein späterer Schritt (Portfolio, Adressen), wird alles
        // Geschriebene zurückgesetzt (`RestoreSteps`, wie Android) — nie halb ersetzt
        let oldSettings = data.settings
        let oldFavorites = data.favorites
        let oldColumnSort = UserDefaults.standard.string(forKey: ColumnSort.storageKey)
        let oldPortfolio = data.portfolioFileForRollback()
        let oldWatched = data.currentWatchedAddressFile()
        // Unlesbare Datei: Rückrollen legt sie unverändert zurück statt den leeren Stand zu schreiben
        let oldWatchedIntact = data.watchedAddressIntact
        let watched = watchedEntries(root)

        // Journal vor dem ersten Schreiben: bricht die App mittendrin ab, rollt der nächste Start zurück (wie Android).
        // Nie ohne: ein mittendrin beendeter Lauf liesse sonst einen halben Stand ohne Rückweg
        if journal { try requireJournal(try? plainExportData(data), write: { RestoreJournal.write($0) }) }
        var steps = RestoreSteps()
        do {
            steps.run({
                data.replaceAll(snapshot: snapshot, settings: newSettings, favorites: favorites)
                restoreColumnSort(root["settings"] as? [String: Any])
            }, undo: {
                data.replaceAll(snapshot: old, settings: oldSettings, favorites: oldFavorites)
                if let oldColumnSort {
                    UserDefaults.standard.set(oldColumnSort, forKey: ColumnSort.storageKey)
                } else {
                    UserDefaults.standard.removeObject(forKey: ColumnSort.storageKey)
                }
            })
            // Portfolio ersetzen und Bestand alter Sicherungen übernehmen (nur Coins ohne Transaktion)
            // Nicht still: konnte das Portfolio nicht geschrieben werden, meldet das Wiederherstellen einen Fehler
            try steps.run({
                guard data.restorePortfolio(portfolio) else { throw Failure.invalid("portfolio") }
            }, undo: { _ = data.rollBackPortfolioFile(oldPortfolio) })
            // Fehlt der Schlüssel (ältere Sicherung), bleiben die bestehenden Adressen stehen.
            // Sonst ersetzen wie die Transaktionen (ungültige fallen weg, `WatchedAddressRules.merge`)
            if let watched {
                try steps.run({
                    guard data.replaceWatchedAddresses(watched) != nil else { throw Failure.invalid(WatchedAddressRules.backupKey) }
                }, undo: { data.rollBackWatchedAddresses(oldWatched, wasIntact: oldWatchedIntact) })
            }
        } catch {
            steps.rollBack()
            if journal { RestoreJournal.delete() }
            throw error
        }
        if journal {
            RestoreJournal.delete()
            // Gespeicherte Daten liessen sich nicht entsperren (`StorageRecoveryNotice`): mit dieser Sicherung erledigt
            StorageRecoveryNotice.acknowledge(.setAside)
        }
        // Kurs-Mitteilungen der bisherigen Paare erst nach Erfolg entfernen (beim Rückrollen bleiben sie)
        old.watches.forEach { Notifier.cancelPrice($0.id) }
        // Alarme ersetzt (Ids der Sicherung können sich mit bisherigen decken) — alte Vermerke
        // «wird gemeldet» gelten nicht mehr (wie Android)
        AlarmDelivery.pairs.removeAll()
        if portfolioAlarms != nil { AlarmDelivery.portfolio.removeAll() }
        return RestoreResult(watches: watches.count, alarms: alarms.count, appLockKept: lock.keptOn,
                             droppedPairs: watchlist.droppedPairs, droppedAlarms: watchlist.droppedAlarms,
                             addressServerNote: serverNote)
    }

    /// Journal vor dem ersten Schreiben (Pflicht, wie Android `RestoreRun`): ohne bisherigen Stand
    /// (`previous` nil) oder ohne geschriebenes Journal `Failure.journalUnavailable` — dann wird nichts ersetzt.
    static func requireJournal(_ previous: Data?, write: (Data) -> Bool) throws {
        guard let previous, write(previous) else { throw Failure.journalUnavailable }
    }

    /// Eben geschriebene Sicherungsdatei zurücklesen und mit `expected` vergleichen (Grösse und SHA-256) — wie Android
    /// `BackupVerification`. nil = stimmt; sonst die Abweichung (fürs Protokoll).
    static func verifyWritten(_ url: URL, expected: Data) -> String? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let back = try? Data(contentsOf: url) else { return "nicht lesbar" }
        return verify(written: expected, readBack: back)
    }

    /// Reine Prüfung (Tests): gleiche Grösse und gleicher SHA-256.
    static func verify(written: Data, readBack: Data) -> String? {
        if readBack.count != written.count { return "Grösse \(readBack.count) statt \(written.count) Bytes" }
        if SHA256.hash(data: readBack) != SHA256.hash(data: written) { return "Inhalt weicht ab (SHA-256)" }
        return nil
    }
}
