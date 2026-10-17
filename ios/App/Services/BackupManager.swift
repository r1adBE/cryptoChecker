import Foundation
import SwiftUI
import UniformTypeIdentifiers

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
///   "watchedAddresses":[{"chain","address","label","addedAt","observations"?}…]}`
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
        guard let password else { return plain }
        return try encryptedFile(plain, password: password)
    }

    /// Klartext verschlüsseln (`BackupCrypto`, 600 000 Runden PBKDF2 — auf älteren Geräten etwa 1 s):
    /// nicht auf dem Main-Thread aufrufen.
    static func encryptedFile(_ plain: Data, password: String) throws -> Data {
        BackupCrypto.json(try BackupCrypto.encrypt(plain, password: password))
    }

    /// Lesbares JSON der Sicherung (Merkliste, Alarme, Favoriten, Einstellungen, Portfolio).
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
            "createdAt": NSNumber(value: TimeUtils.nowMillis),
            "watches": watches.map(watchToJson),
            "alarms": alarms.map(alarmToJson),
            "favorites": favorites,
            "settings": settingsJson(data.settings),
            // Neueste zuerst — wie `portfolioDao.getAll()`
            "portfolio": data.portfolio.map(txToJson),
            // Alarme «Portfolio-Wert» (Runde 28), nach Id — wie Android
            "portfolioAlarms": data.portfolioAlarms.map(portfolioAlarmToJson),
            // Seit Build 46: beobachtete Adressen (Netz, Adresse, Bezeichnung, Datum und beobachteter Guthaben-Verlauf)
            WatchedAddressRules.backupKey: watchedToJson(WatchedAddressRules.backupEntries(data.watchedAddresses)),
        ]
        return try JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])
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
    /// Mit Passwort spürbar langsam (PBKDF2 mit dem «iter» der Datei) — nicht auf dem Main-Thread aufrufen.
    /// - Throws: `BackupCrypto.Failure.wrongPassword` bei falschem Passwort oder veränderter Datei.
    static func plainJSON(_ file: Data, password: String?) throws -> Data {
        try BackupLimits.checkDepth(file)
        guard let root = try JSONSerialization.jsonObject(with: file) as? [String: Any] else { throw Failure.notABackup }
        guard BackupCrypto.isEnvelope(root) else {
            guard root["format"] as? String == format else { throw Failure.notABackup }
            return file
        }
        guard let password else { throw BackupCrypto.Failure.wrongPassword }
        let envelope = try BackupCrypto.envelope(from: root)
        let plain = try BackupCrypto.decrypt(envelope, password: password)
        try BackupLimits.checkDepth(plain)
        return plain
    }

    /// Wie viele Einträge ungültig sind und nicht übernommen würden: Paar-Alarme (z. B. Alarmart einer neueren
    /// Version), Portfolio-Transaktionen (z. B. Menge fehlt) und Portfolio-Alarme — vor der Rückfrage, damit der
    /// Nutzer davon erfährt statt sie still zu verlieren (wie Android `invalidEntries`).
    static func invalidEntries(_ json: Data) throws -> Int {
        guard let root = try JSONSerialization.jsonObject(with: json) as? [String: Any],
              root["format"] as? String == format
        else { throw Failure.notABackup }
        // Neuere Version: schon vor der Rückfrage ablehnen (klare Meldung, nichts übernommen)
        _ = try BackupVersion.check(root["version"])
        return parsedAlarms(root).invalid + parsedPortfolio(root).invalid + parsedPortfolioAlarms(root).invalid
    }

    /// «version» des Klartexts prüfen (`BackupVersion.check`): neuere → `BackupVersion.Failure.newer`, ohne etwas zu lesen.
    static func checkVersion(_ json: Data) throws {
        guard let root = try JSONSerialization.jsonObject(with: json) as? [String: Any] else { throw Failure.notABackup }
        _ = try BackupVersion.check(root["version"])
    }

    /// Paar-Alarme der Sicherung; ungültige (unbekannte Alarmart, Schwelle nicht endlich) werden gezählt.
    private static func parsedAlarms(_ root: [String: Any]) -> (items: [Alarm], invalid: Int) {
        let list = root["alarms"] as? [Any] ?? []
        let items = list.compactMap { ($0 as? [String: Any]).flatMap(alarmFromJson) }
        return (items, list.count - items.count)
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
    /// Anzahl aus `invalidEntries`); sind es mehr, bricht der Import ab, bevor etwas ersetzt wird.
    /// `encrypted` = Sicherung mit Passwort; ohne schaltet sie die Portfolio-Sperre nicht aus
    /// (`BackupRestorePolicy`, Ergebnis `appLockKept`).
    @MainActor
    static func restore(_ json: Data, into data: AppData, acceptInvalid: Int = 0,
                        encrypted: Bool = false) throws -> (watches: Int, alarms: Int, appLockKept: Bool) {
        guard let root = try JSONSerialization.jsonObject(with: json) as? [String: Any],
              root["format"] as? String == format
        else { throw Failure.notABackup }
        // Sicherung einer neueren App-Version: ganz ablehnen, bevor etwas gelesen oder ersetzt wird
        _ = try BackupVersion.check(root["version"])

        // Merkliste und Alarme erst lesen, dann bereinigen (`WatchlistRestoreCleanup`, wie Android):
        // gleiche id → letzter gilt; gleiches Paar unter mehreren ids → erstes bleibt, seine Alarme
        // übernehmen die der weggelassenen; Alarme ohne Paar fallen weg.
        var parsedWatches: [Watch] = []
        for item in root["watches"] as? [Any] ?? [] {
            guard let o = item as? [String: Any] else { throw Failure.invalid("watches") }
            parsedWatches.append(try jsonToWatch(o))
        }
        let alarmsRead = parsedAlarms(root)
        let (watches, alarms) = WatchlistRestoreCleanup.clean(watches: parsedWatches, alarms: alarmsRead.items)

        var favorites: [FavoriteKind: Set<String>] = [:]
        if let fav = root["favorites"] as? [String: Any] {
            for kind in FavoriteKind.allCases {
                if let arr = fav[kind.rawValue] as? [Any] {
                    favorites[kind] = Set(arr.compactMap { $0 as? String })
                }
            }
        }

        let settingsJson = root["settings"] as? [String: Any]
        var newSettings = settingsJson.map { applySettings($0, onto: data.settings) }
        // Portfolio-Sperre: ohne Passwort keine Echtheitsprüfung — nie ausschalten, solange sie an ist
        let lock = BackupRestorePolicy.appLock(wanted: settingsJson.flatMap { bool($0, "appLock") },
                                               current: data.settings.appLock, encrypted: encrypted,
                                               canAuthenticate: AppLock.canAuthenticate())
        if let v = lock.apply { newSettings?.appLock = v }

        // Ältere Sicherungen haben noch kein Portfolio: dann bleibt das bestehende stehen.
        // Ungültige Einträge nur mit Bestätigung weglassen (nie still)
        let parsedTx = parsedPortfolio(root)
        // Seit Runde 28; ältere Sicherungen ohne Portfolio-Alarme lassen die bestehenden stehen
        let parsedPfAlarms = parsedPortfolioAlarms(root)
        let invalid = alarmsRead.invalid + parsedTx.invalid + parsedPfAlarms.invalid
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
            // Seit Build 46; fehlt der Schlüssel (ältere Sicherung), bleiben die bestehenden Adressen stehen.
            // Sonst ersetzen wie die Transaktionen (ungültige fallen weg, `WatchedAddressRules.merge`)
            if let watched {
                try steps.run({
                    guard data.replaceWatchedAddresses(watched) != nil else { throw Failure.invalid(WatchedAddressRules.backupKey) }
                }, undo: { data.rollBackWatchedAddresses(oldWatched, wasIntact: oldWatchedIntact) })
            }
        } catch {
            steps.rollBack()
            throw error
        }
        // Kurs-Mitteilungen der bisherigen Paare erst nach Erfolg entfernen (beim Rückrollen bleiben sie)
        old.watches.forEach { Notifier.cancelPrice($0.id) }
        return (watches.count, alarms.count, lock.keptOn)
    }

    // MARK: Lesen mit Typ-Toleranz (wie optInt/optBoolean von org.json)

    /// Minute des Tages (0…1439) oder nil, wenn fehlend, gebrochen oder ausserhalb — wie `optMinute`.
    static func minute(_ o: [String: Any], _ key: String) -> Int? {
        guard !isNull(o, key), let value = double(o, key), value.isFinite,
              value.truncatingRemainder(dividingBy: 1) == 0,
              // `exactly:` statt `Int(value)`: ein riesiger Wert aus der Datei (z. B. 1e300) brach sonst ab
              let m = Int(exactly: value) else { return nil }
        return QuietHours.isValidMinute(m) ? m : nil
    }

    static func isNull(_ o: [String: Any], _ key: String) -> Bool {
        o[key] == nil || o[key] is NSNull
    }

    static func string(_ o: [String: Any], _ key: String) -> String? {
        if let s = o[key] as? String { return s }
        if let n = o[key] as? NSNumber { return n.stringValue }
        return nil
    }

    static func int64(_ o: [String: Any], _ key: String) -> Int64? {
        if let n = o[key] as? NSNumber { return n.int64Value }
        if let s = o[key] as? String { return Int64(s) ?? Double(s).flatMap(int64Truncating) }
        return nil
    }

    /// Ganzzahl aus einer Kommazahl der Sicherung, nach Null hin abgeschnitten (wie bisher
    /// `Int64(d)`); nil bei NaN, ±Unendlich oder ausserhalb von Int64 — `Int64(d)` brach dort ab.
    static func int64Truncating(_ value: Double) -> Int64? {
        guard value.isFinite else { return nil }
        return Int64(exactly: value.rounded(.towardZero))
    }

    static func int(_ o: [String: Any], _ key: String) -> Int? {
        int64(o, key).map { Int($0) }
    }

    static func double(_ o: [String: Any], _ key: String) -> Double? {
        if let n = o[key] as? NSNumber { return n.doubleValue }
        if let s = o[key] as? String { return Double(s) }
        return nil
    }

    static func bool(_ o: [String: Any], _ key: String) -> Bool? {
        if let n = o[key] as? NSNumber { return n.boolValue }
        if let s = o[key] as? String {
            switch s.lowercased() {
            case "true": return true
            case "false": return false
            default: return nil
            }
        }
        return nil
    }
}

/// JSON-Datei für `.fileExporter`.
struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    static var writableContentTypes: [UTType] { [.json] }

    var data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        guard let contents = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        data = contents
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
