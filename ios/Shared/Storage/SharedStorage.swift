import Foundation

/// Gemeinsamer Speicher von App und Widgets (App Group).
///
/// Die App Group muss in Xcode für beide Ziele eingerichtet sein
/// (Signing & Capabilities → App Groups). Fehlt sie, arbeitet die App mit
/// ihrem eigenen Ordner weiter — die Widgets bleiben dann aber leer.
enum SharedStorage {
    static let appGroup = "group.com.cryptochecker.app"

    /// WidgetKit-Arten der Merkliste- und Einzel-Widgets — auch für die App, die nach
    /// der Aktualisierung eines einzelnen Paares nur diese beiden neu laden lässt.
    static let watchlistWidgetKind = "WatchlistWidget"
    static let singleWidgetKind = "SingleWidget"

    static var defaults: UserDefaults {
        UserDefaults(suiteName: appGroup) ?? .standard
    }

    static var isAppGroupAvailable: Bool {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) != nil
    }

    static var directory: URL {
        if let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) {
            return url
        }
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static var watchlistURL: URL { directory.appendingPathComponent("watchlist.json") }

    /// Portfolio-Alarme (Beträge) in eigener Datei statt in `watchlist.json` — so gilt für sie wie für
    /// `portfolio.json` die Einstellung «Portfolio in Systemsicherung» (wie Android, wo die
    /// Portfolio-Alarme mit dem Portfolio aus der Systemsicherung fallen). Bis Runde 28 lagen sie in
    /// `watchlist.json`; beim ersten Schreiben wandern sie hierher (siehe `writeFiles`).
    static var portfolioAlarmsURL: URL { directory.appendingPathComponent("portfolio_alarms.json") }

    // MARK: Merkliste & Alarme

    struct Snapshot: Codable {
        var watches: [Watch] = []
        var alarms: [Alarm] = []
        var nextWatchId: Int64 = 1
        var nextAlarmId: Int64 = 1
        /// Alarme «Portfolio-Wert» (Runde 28). Optional: ältere Dateien haben sie nicht, und
        /// ältere Versionen ignorieren das Feld beim Lesen. Gespeichert in `portfolio_alarms.json`;
        /// nil = unbekannt/nicht geladen → die Datei der Portfolio-Alarme bleibt beim Schreiben unberührt.
        var portfolioAlarms: [PortfolioAlarm]?
        var nextPortfolioAlarmId: Int64?
    }

    /// Inhalt von `portfolio_alarms.json`.
    struct PortfolioAlarmsFile: Codable, Equatable {
        var alarms: [PortfolioAlarm] = []
        var nextId: Int64 = 1

        init(alarms: [PortfolioAlarm] = [], nextId: Int64 = 1) {
            self.alarms = alarms
            self.nextId = nextId
        }

        private enum CodingKeys: String, CodingKey { case alarms, nextId }

        /// Unbekannte Einträge (z. B. eine neuere Alarmart nach einem Downgrade) werden übersprungen,
        /// statt die ganze Datei unlesbar zu machen; die Id läuft nie hinter vergebene Ids zurück.
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            alarms = try SharedStorage.lenientArray(c, .alarms, decoder: decoder) ?? []
            let stored = (try? c.decodeIfPresent(Int64.self, forKey: .nextId)) ?? 1
            nextId = SharedStorage.nextId(stored: stored, used: alarms.map(\.id))
        }
    }

    /// Ergebnis beim Lesen der Merkliste (`readSnapshot`).
    enum SnapshotRead {
        /// Gelesen; einzelne unlesbare Einträge sind übersprungen (die Originaldatei ist dann
        /// als `watchlist.unreadable.json` gesichert).
        case ok(Snapshot)
        /// Noch keine Datei (erster Start).
        case missing
        /// Datei vorhanden, aber als Ganzes nicht lesbar (kaputt, unbekanntes Format) — gesichert als
        /// `watchlist.unreadable.json`. Hintergrund und Widget schreiben dann NICHT zurück.
        case unreadable
        /// Datei vorhanden, aber gerade nicht zugreifbar (z. B. Datenschutz vor dem ersten Entsperren).
        /// Niemand schreibt zurück — sonst ginge die Merkliste verloren.
        case inaccessible

        var snapshot: Snapshot? {
            if case .ok(let s) = self { return s }
            return nil
        }
    }

    /// Nur zum Lesen (Anzeige, Widgets): bei fehlender oder unlesbarer Datei eine leere Merkliste.
    /// Wer liest, ändert und zurückschreibt, nimmt `updateSnapshot` (prozessübergreifend abgestimmt).
    static func loadSnapshot() -> Snapshot {
        readSnapshot().snapshot ?? Snapshot()
    }

    /// Liest Merkliste und Portfolio-Alarme, abgestimmt mit den anderen Prozessen (App, Widget).
    static func readSnapshot() -> SnapshotRead {
        // Noch nicht geschriebene Stände der App zuerst auf die Platte (`saveSnapshotInBackground`)
        flushSnapshotWrites()
        let watchlist = watchlistURL, portfolio = portfolioAlarmsURL
        return coordinated(writing: false) { readFiles(watchlist: watchlist, portfolioAlarms: portfolio) }
    }

    /// Schreibt den ganzen Stand (Merkliste, Alarme, Portfolio-Alarme), abgestimmt mit den anderen Prozessen.
    static func saveSnapshot(_ snapshot: Snapshot) {
        let watchlist = watchlistURL, portfolio = portfolioAlarmsURL
        let include = loadSettings().portfolioSystemBackup
        coordinated(writing: true) {
            writeFiles(snapshot, watchlist: watchlist, portfolioAlarms: portfolio, includePortfolioInBackup: include)
        }
    }

    /// Lesen → ändern → schreiben als EIN abgestimmter Schritt (prozessübergreifend über
    /// `NSFileCoordinator`): für Hintergrund-Aktualisierung und Widget, die nur Kurse und
    /// Alarm-Zustand je Id auf den frisch gelesenen Stand übertragen (`PriceRefresher.Outcome.apply`).
    /// So kann kein gleichzeitig in der App hinzugefügtes Paar bzw. Alarm verloren gehen.
    ///
    /// Ist die Datei unlesbar, nicht zugreifbar oder fehlt sie, wird NICHT geschrieben.
    /// - Parameter body: darf `loadSnapshot`/`saveSnapshot` nicht aufrufen (verschachtelte
    ///   Abstimmung verklemmt sich).
    /// - Returns: der gespeicherte Stand; nil = nicht geschrieben.
    @discardableResult
    static func updateSnapshot(_ body: (inout Snapshot) -> Void) -> Snapshot? {
        flushSnapshotWrites()
        let watchlist = watchlistURL, portfolio = portfolioAlarmsURL
        let include = loadSettings().portfolioSystemBackup
        return coordinated(writing: true) { () -> Snapshot? in
            guard var s = readFiles(watchlist: watchlist, portfolioAlarms: portfolio).snapshot else { return nil }
            body(&s)
            guard writeFiles(s, watchlist: watchlist, portfolioAlarms: portfolio, includePortfolioInBackup: include)
            else { return nil }
            return s
        }
    }

    /// Schreibt der Reihe nach, nicht auf dem Haupt-Thread (JSON der ganzen Merkliste, bei Live-Kursen
    /// alle 10 Sekunden) — wie Android den IO-Dispatcher nutzt.
    private static let snapshotWriter = DispatchQueue(label: "com.cryptochecker.snapshot-writer", qos: .utility)

    /// Speichert im Hintergrund (Reihenfolge bleibt); [done] danach auf dem Haupt-Thread (z. B. Widgets neu laden).
    ///
    /// Der Stand der App ist massgebend (Paare, Alarme, Reihenfolge …). Nur Kurse, die das Widget
    /// inzwischen neuer geholt hat, bleiben erhalten (`mergeNewerPrices`) — sonst überschriebe ein
    /// älterer Stand im Speicher der App sie wieder.
    static func saveSnapshotInBackground(_ snapshot: Snapshot, done: (() -> Void)? = nil) {
        let watchlist = watchlistURL, portfolio = portfolioAlarmsURL
        snapshotWriter.async {
            let include = loadSettings().portfolioSystemBackup
            coordinated(writing: true) { () -> Void in
                var s = snapshot
                switch readFiles(watchlist: watchlist, portfolioAlarms: portfolio) {
                case .ok(let disk):
                    mergeNewerPrices(from: disk, into: &s)
                case .inaccessible:
                    // Gerade nicht lesbar (Datenschutz): auch nicht überschreiben
                    return
                case .missing, .unreadable:
                    // Unlesbare Datei ist beim Lesen gesichert worden (`preserveUnreadable`)
                    break
                }
                writeFiles(s, watchlist: watchlist, portfolioAlarms: portfolio, includePortfolioInBackup: include)
            }
            if let done { DispatchQueue.main.async(execute: done) }
        }
    }

    // MARK: Dateien (ohne Abstimmung — nur innerhalb von `coordinated` aufrufen; testbar über URLs)

    /// Prozessübergreifend abgestimmter Zugriff über `NSFileCoordinator` auf `watchlist.json`; die Datei
    /// der Portfolio-Alarme wird immer unter derselben Abstimmung gelesen und geschrieben. Bewusst
    /// kein gehaltenes Datei-Lock (iOS beendet angehaltene Prozesse mit Lock im geteilten Container,
    /// 0xdead10cc — siehe `AlarmLease`). Nie verschachteln: innerhalb nur `readFiles`/`writeFiles`.
    /// Schlägt die Abstimmung fehl, läuft der Zugriff wie bisher ohne sie.
    @discardableResult
    private static func coordinated<T>(writing: Bool, _ body: () -> T) -> T {
        let coordinator = NSFileCoordinator(filePresenter: nil)
        var error: NSError?
        var result: T?
        if writing {
            coordinator.coordinate(writingItemAt: watchlistURL, options: .forMerging, error: &error) { _ in
                result = body()
            }
        } else {
            coordinator.coordinate(readingItemAt: watchlistURL, options: [], error: &error) { _ in
                result = body()
            }
        }
        if let result { return result }
        return body()
    }

    /// Liest `watchlist.json` (tolerant, siehe `Snapshot.init(from:)`) und `portfolio_alarms.json`.
    static func readFiles(watchlist: URL, portfolioAlarms: URL) -> SnapshotRead {
        let fm = FileManager.default
        var snap: Snapshot
        if fm.fileExists(atPath: watchlist.path) {
            guard let data = try? Data(contentsOf: watchlist) else { return .inaccessible }
            let counter = DropCounter()
            let decoder = JSONDecoder()
            decoder.userInfo[DropCounter.key] = counter
            guard let decoded = try? decoder.decode(Snapshot.self, from: data) else {
                preserveUnreadable(data, of: watchlist)
                return .unreadable
            }
            if counter.count > 0 { preserveUnreadable(data, of: watchlist) }
            snap = decoded
        } else if fm.fileExists(atPath: portfolioAlarms.path) {
            snap = Snapshot()
        } else {
            return .missing
        }
        // Portfolio-Alarme: die eigene Datei geht vor (das Feld in `watchlist.json` stammt von vor der Umstellung)
        if fm.fileExists(atPath: portfolioAlarms.path) {
            if let data = try? Data(contentsOf: portfolioAlarms) {
                let counter = DropCounter()
                let decoder = JSONDecoder()
                decoder.userInfo[DropCounter.key] = counter
                if let file = try? decoder.decode(PortfolioAlarmsFile.self, from: data) {
                    if counter.count > 0 { preserveUnreadable(data, of: portfolioAlarms) }
                    snap.portfolioAlarms = file.alarms
                    snap.nextPortfolioAlarmId = max(file.nextId, snap.nextPortfolioAlarmId ?? 1)
                } else {
                    // Kaputt: gesichert; ohne Feld in `watchlist.json` bleibt nil → Datei wird nicht überschrieben
                    preserveUnreadable(data, of: portfolioAlarms)
                }
            } else {
                // Nicht zugreifbar: nichts davon übernehmen und die Datei beim Schreiben nicht anfassen
                snap.portfolioAlarms = nil
                snap.nextPortfolioAlarmId = nil
            }
        }
        return .ok(snap)
    }

    /// Schreibt zuerst die Portfolio-Alarme (falls bekannt), dann `watchlist.json` ohne sie. Erst wenn die
    /// eigene Datei sicher geschrieben ist, fällt das Feld aus `watchlist.json` (Umstellung ohne Verlust,
    /// beliebig oft wiederholbar).
    /// - Returns: true, wenn `watchlist.json` geschrieben wurde.
    @discardableResult
    static func writeFiles(_ snapshot: Snapshot, watchlist: URL, portfolioAlarms: URL,
                           includePortfolioInBackup: Bool) -> Bool {
        var main = snapshot
        if let alarms = snapshot.portfolioAlarms {
            let next = nextId(stored: snapshot.nextPortfolioAlarmId ?? 1, used: alarms.map(\.id))
            let file = PortfolioAlarmsFile(alarms: alarms, nextId: next)
            if writePortfolioAlarms(file, to: portfolioAlarms, includeInBackup: includePortfolioInBackup) {
                main.portfolioAlarms = nil
                main.nextPortfolioAlarmId = nil
            }
        }
        guard let data = try? JSONEncoder().encode(main) else { return false }
        do {
            try data.write(to: watchlist, options: [.atomic])
            return true
        } catch {
            return false
        }
    }

    /// Schreibt nur bei geändertem Inhalt (die Merkliste wird mit Live-Kursen alle paar Sekunden
    /// gespeichert) und setzt danach das Backup-Merkmal neu (das atomare Schreiben verliert es).
    private static func writePortfolioAlarms(_ file: PortfolioAlarmsFile, to url: URL, includeInBackup: Bool) -> Bool {
        // Feste Schlüssel-Reihenfolge: sonst kann derselbe Inhalt jedes Mal anders aussehen und der
        // Vergleich unten schreibt unnötig
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(file) else { return false }
        if let existing = try? Data(contentsOf: url), existing == data { return true }
        do {
            try data.write(to: url, options: [.atomic])
        } catch {
            return false
        }
        BackupExclusion.set(excluded: !includeInBackup, for: url)
        return true
    }

    /// Kopie einer (ganz oder teilweise) unlesbaren Datei neben dem Original, z. B.
    /// `watchlist.unreadable.json` — bevor ein Schreiben die übersprungenen Einträge verwirft.
    /// Eine schon vorhandene gleiche Kopie bleibt, wie sie ist. Die Kopie der Portfolio-Alarme bleibt
    /// wie die Datei selbst ausserhalb der Geräte-Backups, solange «Portfolio in Systemsicherung» aus ist.
    private static func preserveUnreadable(_ data: Data, of url: URL) {
        let copy = unreadableCopyURL(of: url)
        if let existing = try? Data(contentsOf: copy), existing == data { return }
        try? data.write(to: copy, options: [.atomic])
        if url.lastPathComponent == portfolioAlarmsURL.lastPathComponent {
            BackupExclusion.set(excluded: !loadSettings().portfolioSystemBackup, for: copy)
        }
    }

    static func unreadableCopyURL(of url: URL) -> URL {
        url.deletingPathExtension().appendingPathExtension("unreadable").appendingPathExtension("json")
    }

    /// Kurse, die ein anderer Prozess (Widget) neuer geschrieben hat, in den Stand der App übernehmen —
    /// nur für dasselbe Paar (gleiche Id und gleiches Paar an derselben Börse), sonst gilt die App.
    static func mergeNewerPrices(from disk: Snapshot, into s: inout Snapshot) {
        let byId = Dictionary(disk.watches.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        for i in s.watches.indices {
            guard let d = byId[s.watches[i].id], d.samePair(as: s.watches[i]) else { continue }
            if d.lastUpdate > s.watches[i].lastUpdate {
                s.watches[i].lastPrice = d.lastPrice
                s.watches[i].previousPrice = d.previousPrice
                s.watches[i].lastUpdate = d.lastUpdate
                s.watches[i].lastError = d.lastError
                s.watches[i].change24h = d.change24h
            }
            if d.notifiedAt > s.watches[i].notifiedAt {
                s.watches[i].notifiedPrice = d.notifiedPrice
                s.watches[i].notifiedAt = d.notifiedAt
            }
        }
    }

    // MARK: Tolerantes Lesen

    /// Zählt beim Lesen übersprungene Einträge (über `JSONDecoder.userInfo`).
    final class DropCounter: @unchecked Sendable {
        static let key = CodingUserInfoKey(rawValue: "com.cryptochecker.dropCounter")!
        var count = 0
    }

    /// Ein Eintrag, der sich nicht lesen lässt, wird zu nil (statt die ganze Liste zu verwerfen).
    private struct Lenient<T: Decodable>: Decodable {
        let value: T?
        init(from decoder: Decoder) throws { value = try? T(from: decoder) }
    }

    /// Liste tolerant lesen: fehlt der Schlüssel (oder null) → nil; ist er keine Liste → Fehler
    /// (die Datei gilt dann als unlesbar und wird nicht überschrieben); einzelne unlesbare
    /// Einträge werden übersprungen und gezählt.
    static func lenientArray<T: Decodable, K: CodingKey>(_ c: KeyedDecodingContainer<K>, _ key: K,
                                                         decoder: Decoder) throws -> [T]? {
        guard let items = try c.decodeIfPresent([Lenient<T>].self, forKey: key) else { return nil }
        let values = items.compactMap(\.value)
        if values.count < items.count {
            (decoder.userInfo[DropCounter.key] as? DropCounter)?.count += items.count - values.count
        }
        return values
    }

    /// Nächste freie Id: nie kleiner als 1 und nie eine schon vergebene (Ids werden nicht wiederverwendet).
    static func nextId(stored: Int64, used: [Int64]) -> Int64 {
        guard let maxUsed = used.max() else { return max(stored, 1) }
        let after = maxUsed < Int64.max ? maxUsed + 1 : Int64.max
        return max(stored, after, 1)
    }

    /// Wartet, bis alle Hintergrund-Schreibvorgänge auf der Platte sind (vor dem Lesen, beim Wechsel
    /// in den Hintergrund). Nie vom Schreib-Thread selbst aufrufen.
    static func flushSnapshotWrites() {
        snapshotWriter.sync {}
    }

    // MARK: Einstellungen

    private static let settingsKey = "settings"

    static func loadSettings() -> AppSettings {
        guard let data = defaults.data(forKey: settingsKey),
              let s = try? JSONDecoder().decode(AppSettings.self, from: data)
        else { return AppSettings() }
        return s
    }

    static func saveSettings(_ settings: AppSettings) {
        if let data = try? JSONEncoder().encode(settings) {
            defaults.set(data, forKey: settingsKey)
        }
    }

    // MARK: Letzte Aktualisierung (Widget-Kopf)

    static var lastRefreshAt: Int64 {
        get { Int64(defaults.double(forKey: "last_refresh_at")) }
        set { defaults.set(Double(newValue), forKey: "last_refresh_at") }
    }

    /// Mit welcher %-Basis (und welchem Tagesbeginn) die gespeicherten Veränderungen zuletzt
    /// gerechnet wurden; nil = noch nie (gilt als rollend) — siehe `ChangeBasisMath.isCurrent`.
    static var changeStamp: ChangeStamp? {
        get { ChangeStamp.decode(defaults.string(forKey: "change_basis_stamp")) }
        set {
            if let newValue { defaults.set(newValue.encoded, forKey: "change_basis_stamp") }
            else { defaults.removeObject(forKey: "change_basis_stamp") }
        }
    }

    static var lastRefreshDuration: Int64 {
        get { Int64(defaults.double(forKey: "last_refresh_duration")) }
        set { defaults.set(Double(newValue), forKey: "last_refresh_duration") }
    }

    /// Pausen je Börse (`ExchangeBackoff`), Schlüssel = Börsen-Kennung. Geteilt mit Hintergrund
    /// und Widget, damit eine pausierte Börse nirgends angefragt wird.
    static var exchangeBackoff: [String: ExchangeBackoff.State] {
        get { ExchangeBackoff.decode(defaults.string(forKey: "exchange_backoff")) }
        set {
            if newValue.isEmpty { defaults.removeObject(forKey: "exchange_backoff") }
            else { defaults.set(ExchangeBackoff.encode(newValue), forKey: "exchange_backoff") }
        }
    }

    /// App-Start bis zum ersten Bild der Merkliste (zuletzt gemessen, ms); nil = noch nie. Nur lokal.
    static var appStartMillis: Int64? {
        get {
            let value = Int64(defaults.double(forKey: "app_start_millis"))
            return value > 0 ? value : nil
        }
        set {
            if let newValue { defaults.set(Double(newValue), forKey: "app_start_millis") }
            else { defaults.removeObject(forKey: "app_start_millis") }
        }
    }

    /// Bericht «Letzte Aktualisierung» (JSON); nil = noch keiner. Bis Runde 21 als Klartext
    /// unter `last_refresh_report` — der wird beim ersten neuen Bericht entfernt.
    static var lastRefreshReport: RefreshReport? {
        get {
            guard let data = defaults.data(forKey: "last_refresh_report_v2") else { return nil }
            return try? JSONDecoder().decode(RefreshReport.self, from: data)
        }
        set {
            defaults.removeObject(forKey: "last_refresh_report")
            if let newValue, let data = try? JSONEncoder().encode(newValue) {
                defaults.set(data, forKey: "last_refresh_report_v2")
            } else {
                defaults.removeObject(forKey: "last_refresh_report_v2")
            }
        }
    }

    // MARK: Favoriten (Hinzufügen-Tab)

    static func favorites(_ kind: FavoriteKind) -> Set<String> {
        Set(defaults.stringArray(forKey: "fav_" + kind.rawValue.lowercased()) ?? [])
    }

    static func setFavorites(_ kind: FavoriteKind, _ items: Set<String>) {
        defaults.set(Array(items).sorted(), forKey: "fav_" + kind.rawValue.lowercased())
    }
}

// MARK: Tolerantes Lesen der Merkliste

extension SharedStorage.Snapshot {
    /// Ein unbekannter Wert in einem Eintrag (z. B. eine neuere `AlarmCondition`, ein neuer
    /// `FuturesContractType` oder eine neue `PortfolioAlarmKind` nach einem Downgrade) überspringt nur
    /// diesen Eintrag — vorher war die ganze Datei unlesbar, und das Zurückschreiben einer leeren
    /// Merkliste löschte alle Paare und Alarme. Die nächsten Ids laufen nie hinter vergebene zurück.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        watches = try SharedStorage.lenientArray(c, .watches, decoder: decoder) ?? []
        alarms = try SharedStorage.lenientArray(c, .alarms, decoder: decoder) ?? []
        // Auch die Paar-Ids der Alarme zählen: Ein übersprungenes Paar darf seine Id nicht an ein neues
        // weitergeben, sonst hinge dessen verwaister Alarm plötzlich an diesem
        nextWatchId = SharedStorage.nextId(stored: (try? c.decodeIfPresent(Int64.self, forKey: .nextWatchId)) ?? 1,
                                           used: watches.map(\.id) + alarms.map(\.watchId))
        nextAlarmId = SharedStorage.nextId(stored: (try? c.decodeIfPresent(Int64.self, forKey: .nextAlarmId)) ?? 1,
                                           used: alarms.map(\.id))
        portfolioAlarms = try SharedStorage.lenientArray(c, .portfolioAlarms, decoder: decoder)
        let storedPortfolioId: Int64? = (try? c.decodeIfPresent(Int64.self, forKey: .nextPortfolioAlarmId)) ?? nil
        if let portfolioAlarms {
            nextPortfolioAlarmId = SharedStorage.nextId(stored: storedPortfolioId ?? 1, used: portfolioAlarms.map(\.id))
        } else {
            nextPortfolioAlarmId = storedPortfolioId
        }
    }
}
