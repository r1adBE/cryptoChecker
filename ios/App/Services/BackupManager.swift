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
///   "alarms":[…],"favorites":{"EXCHANGE":[…],"COIN":[…],"QUOTE":[…]},"settings":{…},
///   "portfolio":[{"id","coin","type","amountText","priceText","time","note","feeText",
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
        /// Gespeicherter Stand gerade nicht (ganz) lesbar (`storageKept`): Sichern ergäbe eine Datei ohne ihn, Einspielen
        /// legte das Journal mit einem leeren Stand an — nichts geschrieben, wie Android `BackupStorageKeptException`.
        case storageKept
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
    /// Durchgang auf dem Main Actor aus `AppData` gelesen (`exportInput`) — keine Änderung kann dazwischen kommen (wie
    /// Android, dort je Datenbank eine Lesetransaktion). Die Seite «Sichern» baut und kodiert danach abseits des
    /// Haupt-Threads (`plainExportData(_ input:)`). Vor dem Schreiben als Datei `BackupLimits.checkWritable` (das Journal
    /// braucht es nicht).
    @MainActor
    static func plainExportData(_ data: AppData) throws -> Data {
        try plainExportData(exportInput(data))
    }

    /// Wie `plainExportData(_ data:)` aus der Wertkopie `input` — auch abseits des Haupt-Threads. `encrypted` false
    /// (Datei ohne Passwort): ohne eigenen Bitcoin-Server (`AddressServer.exported`); Journal und Datei mit Passwort: mit.
    static func plainExportData(_ input: ExportInput, encrypted: Bool = true) throws -> Data {
        try encode(exportRoot(input, encrypted: encrypted))
    }

    /// Inhalt der Sicherung als JSON-Objekt (`plainExportData`, Journal `journalData`).
    static func exportRoot(_ input: ExportInput, encrypted: Bool) -> [String: Any] {
        let watches = input.watches.sorted {
            $0.sortOrder != $1.sortOrder ? $0.sortOrder < $1.sortOrder : $0.id < $1.id
        }
        let alarms = input.alarms.sorted { $0.id < $1.id }

        var favorites: [String: Any] = [:]
        for kind in FavoriteKind.allCases {
            favorites[kind.rawValue] = Array(input.favorites[kind] ?? []).sorted()
        }

        var settings = settingsJson(input.settings)
        settings["addressServerBtc"] = AddressServer.exported(input.settings.addressServerBtc, encrypted: encrypted)
            .map { $0 as Any } ?? NSNull()
        return [
            "format": format,
            "version": version,
            "createdAt": NSNumber(value: input.createdAt),
            "watches": watches.map(watchToJson),
            "alarms": alarms.map(alarmToJson),
            "favorites": favorites,
            "settings": settings,
            // Neueste zuerst — wie `portfolioDao.getAll()`
            "portfolio": input.portfolio.map(txToJson),
            // Alarme «Portfolio-Wert», nach Id — wie Android
            "portfolioAlarms": input.portfolioAlarms.map(portfolioAlarmToJson),
            // Beobachtete Adressen (Netz, Adresse, Bezeichnung, Datum, Guthaben-Verlauf und Einstandsposten)
            WatchedAddressRules.backupKey: watchedToJson(WatchedAddressRules.backupEntries(input.watchedAddresses)),
        ]
    }

    /// Kompakt (`.prettyPrinted` wäre gut ein Drittel grösser); das Einlesen nimmt beides.
    /// Sortierte Schlüssel: gleicher Stand ergibt dieselbe Datei.
    static func encode(_ root: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
    }

    // MARK: Wiederherstellen

    /// Verschlüsselte Sicherung? Dann vor dem Wiederherstellen nach dem Passwort fragen.
    /// - Throws: `Failure.notABackup`, wenn die Datei keine Sicherung ist.
    static func needsPassword(_ file: Data) throws -> Bool {
        try BackupLimits.checkDepth(file)
        guard let root = try JSONSerialization.jsonObject(with: file) as? [String: Any] else { throw Failure.notABackup }
        // Verschlüsselt: eigenes «format» (cryptochecker-backup-enc)
        if BackupCrypto.isEnvelope(root) { return true }
        guard root["format"] as? String == format else { throw Failure.notABackup }
        return false
    }

    /// Klartext-JSON der Sicherung; verschlüsselt ohne Passwort gilt als falsches Passwort.
    /// Mit Passwort spürbar langsam (Argon2id mit den Parametern der Datei) — nicht auf dem Main-Thread aufrufen.
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
    struct RestorePreview: Equatable, Sendable {
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
        try BackupFields.checkParts(root)
        let watchlist = try restoredWatchlist(root)
        let invalid = try parsedAlarms(root).invalid + parsedPortfolio(root).invalid + parsedPortfolioAlarms(root).invalid
        return RestorePreview(invalid: invalid, droppedPairs: watchlist.droppedPairs, droppedAlarms: watchlist.droppedAlarms)
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
        /// Hostname des neu gesetzten bzw. ersetzten Bitcoin-Servers (`BackupRestorePolicy.addressServerHost`); nil = keiner.
        var addressServerHost: String?
        /// Von der Sicherung abgeschalteter Schutz (`BackupRestorePolicy.loweredProtection`).
        var lowered = BackupRestorePolicy.LoweredProtection()
        /// Beschädigte Portfolio-Daten ersetzt, ihre Datei liegt unverändert daneben (R4-02, nur iOS: Android legt eine
        /// beschädigte Datenbank schon beim Öffnen beiseite und nennt es dann).
        var damagedSetAside = false
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

    /// Teil `key` der Sicherung mit Typ `T`; fehlt er oder hat er einen anderen Typ: `Failure.invalid`.
    static func part<T>(_ root: [String: Any], _ key: String) throws -> T {
        guard let value = root[key] as? T else { throw Failure.invalid(key) }
        return value
    }

    /// Einträge der Liste `key`, gelesen mit `read` (Felder geprüft, `BackupFields`); nil = ungültig, wird gezählt.
    static func parsedEntries<T>(_ root: [String: Any], _ key: String,
                                 _ read: ([String: Any]) throws -> T?) throws -> (items: [T], invalid: Int) {
        let list: [Any] = try part(root, key)
        var items: [T] = []
        for item in list {
            guard let o = item as? [String: Any] else { throw Failure.invalid(key) }
            if let value = try read(o) { items.append(value) }
        }
        return (items, list.count - items.count)
    }

    /// Transaktionen der Sicherung. Ungültige werden gezählt.
    /// Doppelte Kennungen zählen wie ungültige (`BackupRestorePolicy.uniqueIds`) — nie still ersetzt.
    static func parsedPortfolio(_ root: [String: Any]) throws -> (items: [PortfolioTx], invalid: Int) {
        BackupRestorePolicy.uniqueIds(try parsedEntries(root, "portfolio", jsonToTx), id: \.id)
    }

    /// Portfolio-Alarme der Sicherung. Ungültige und doppelte Kennungen werden gezählt.
    static func parsedPortfolioAlarms(_ root: [String: Any]) throws -> (items: [PortfolioAlarm], invalid: Int) {
        BackupRestorePolicy.uniqueIds(try parsedEntries(root, "portfolioAlarms", jsonToPortfolioAlarm), id: \.id)
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
        try BackupFields.checkParts(root)
        // Gespeicherter Stand gerade gesperrt: nichts ersetzen (das Journal hielte sonst einen leeren Stand, die Daten kämen
        // nach dem Entsperren zurück); beschädigt: ersetzen, die Datei kommt daneben (R4-02). Nicht beim Zurückrollen eines
        // abgebrochenen Laufs (`journal` false)
        if journal && storageAccess(data) == .locked { throw Failure.storageKept }

        // 1. Alles lesen und prüfen, bevor etwas geschrieben wird
        let input = try readRestoreInput(root, data: data, acceptInvalid: acceptInvalid, encrypted: encrypted,
                                         limits: journal)
        let watchlist = input.watchlist
        // 2. Bisherigen Stand merken (für das Zurückrollen)
        let previous = previousState(data)
        // 3. Schreiben; beim Zurückrollen mit dem Alarm-Zustand aus dem Journal (`journalAlarmState`)
        var snapshot = restoredSnapshot(input, old: previous.snapshot)
        if !journal { applyJournalAlarmState(journalAlarmState(root), to: &snapshot) }
        try writeRestored(input, snapshot: snapshot, previous: previous, into: data, journal: journal)
        // Zurückrollen nach einem Abbruch: beschädigte Dateien von damals unverändert zurück
        if !journal { putBackJournalDamaged(root, into: data) }
        // Kurs-Mitteilungen der bisherigen Paare erst nach Erfolg entfernen (beim Rückrollen bleiben sie)
        previous.snapshot.watches.forEach { Notifier.cancelPrice($0.id) }
        // Alarm-Mitteilungen der ersetzten Alarme samt «1 h später»-Pausen: ihre Aktionen wirken über die Id und träfen
        // sonst einen eingespielten Alarm gleicher Id (wie Android `cancelAlarms` + `clearAlarmSnoozes`)
        removeReplacedAlarmNotifications(previous.snapshot)
        // Alarme ersetzt (Ids der Sicherung können sich mit bisherigen decken) — alte Vermerke
        // «wird gemeldet» gelten nicht mehr (wie Android)
        AlarmDelivery.pairs.removeAll()
        AlarmDelivery.portfolio.removeAll()
        // Sicherung mit Passwort setzt den eigenen Server: nach ersetzten Einstellungen nicht mehr unbekannt (wie Android)
        if journal && input.newSettings.addressServerBtc != nil { StorageRecoveryNotice.confirmAddressServer() }
        return RestoreResult(watches: watchlist.watches.count, alarms: watchlist.alarms.count, appLockKept: input.lock.keptOn,
                             droppedPairs: watchlist.droppedPairs, droppedAlarms: watchlist.droppedAlarms,
                             addressServerNote: input.serverNote, addressServerHost: input.serverHost,
                             lowered: input.lowered, damagedSetAside: journal && previous.damagedSetAside)
    }

    /// Mitteilungen der Paar- und Portfolio-Alarme aus `old` (Stand vor dem Wiederherstellen) entfernen und alle
    /// «1 h später»-Pausen löschen.
    static func removeReplacedAlarmNotifications(_ old: SharedStorage.Snapshot) {
        old.alarms.forEach { Notifier.cancelAlarm($0.id) }
        (old.portfolioAlarms ?? []).forEach { Notifier.cancelPortfolioAlarm($0.id) }
        AlarmSnooze.clearAll()
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
