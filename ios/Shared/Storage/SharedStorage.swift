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
    /// inzwischen neuer geholt hat, und Alarme, die es inzwischen gemeldet hat, bleiben erhalten
    /// (`mergeNewerPrices`, `mergeNewerAlarmTriggers`) — sonst überschriebe ein älterer Stand im Speicher
    /// der App sie wieder (und der Alarm meldete sich ein zweites Mal).
    static func saveSnapshotInBackground(_ snapshot: Snapshot, done: (() -> Void)? = nil) {
        let watchlist = watchlistURL, portfolio = portfolioAlarmsURL
        snapshotWriter.async {
            let include = loadSettings().portfolioSystemBackup
            coordinated(writing: true) { () -> Void in
                var s = snapshot
                switch readFiles(watchlist: watchlist, portfolioAlarms: portfolio) {
                case .ok(let disk):
                    mergeNewerPrices(from: disk, into: &s)
                    mergeNewerAlarmTriggers(from: disk, into: &s)
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
            // Verschlüsselt (Build 59, `ProtectedFile`): ohne Schlüssel «nicht zugreifbar», beschädigt wie kaputt
            let data: Data
            do {
                data = try ProtectedFile.read(watchlist)
            } catch ProtectedFile.ReadError.damaged(let raw) {
                preserveUnreadable(raw, of: watchlist)
                return .unreadable
            } catch {
                return .inaccessible
            }
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
            let alarmsData: Data?
            do {
                alarmsData = try ProtectedFile.read(portfolioAlarms)
            } catch ProtectedFile.ReadError.damaged(let raw) {
                alarmsData = raw // beschädigt verschlüsselt: wie eine kaputte Datei (gesichert, nicht überschrieben)
            } catch {
                alarmsData = nil
            }
            if let data = alarmsData {
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
    /// beliebig oft wiederholbar); bis dahin ist `watchlist.json` ohne «Portfolio in Systemsicherung»
    /// vom Geräte-Backup ausgenommen.
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
            try ProtectedFile.write(data, to: watchlist)
        } catch {
            return false
        }
        // Schlug das Schreiben von `portfolio_alarms.json` fehl, bleiben die Portfolio-Schwellen in
        // `watchlist.json` (Entfernen könnte sie verlieren, falls die App vor dem nächsten Speichern
        // endet; das nächste Speichern versucht die eigene Datei erneut). Solange sie dort stehen und
        // «Portfolio in Systemsicherung» aus ist, kommt `watchlist.json` nicht ins Geräte-Backup.
        // Sonst nichts zu tun: das atomare Schreiben hat eine neue Datei ohne Merkmal angelegt
        // (= wieder im Backup), sobald die Schwellen draussen sind.
        if main.portfolioAlarms != nil && !includePortfolioInBackup {
            BackupExclusion.set(excluded: true, for: watchlist)
        }
        return true
    }

    /// Schreibt nur bei geändertem Inhalt (die Merkliste wird mit Live-Kursen alle paar Sekunden
    /// gespeichert) und setzt danach das Backup-Merkmal neu (das atomare Schreiben verliert es).
    private static func writePortfolioAlarms(_ file: PortfolioAlarmsFile, to url: URL, includeInBackup: Bool) -> Bool {
        // Feste Schlüssel-Reihenfolge: sonst kann derselbe Inhalt jedes Mal anders aussehen und der
        // Vergleich unten schreibt unnötig
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(file) else { return false }
        if ProtectedFile.holds(data, at: url) { return true }
        do {
            try ProtectedFile.write(data, to: url)
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
        // Verschlüsselt wie das Original (`ProtectedFile.writeCopy`); eine gleiche Kopie bleibt
        guard (try? ProtectedFile.writeCopy(data, to: copy)) != nil else { return }
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
                s.watches[i].priceChangedAt = d.priceChangedAt
                s.watches[i].exchangeTime = d.exchangeTime
            }
            if d.notifiedAt > s.watches[i].notifiedAt {
                s.watches[i].notifiedPrice = d.notifiedPrice
                s.watches[i].notifiedAt = d.notifiedAt
            }
        }
    }

    /// Auslösungen, die ein anderer Prozess (Widget) gemeldet und gespeichert hat, in den Stand der App
    /// übernehmen — nur für denselben, unveränderten Alarm (gleiches Paar, Bedingung, Schwelle, Fenster,
    /// Wiederholung, Währung) und nur, wenn die Auslösung neuer ist als die der App. Abgeschaltet bleibt,
    /// was der Nutzer in der App abgeschaltet hat; Wieder-scharf-Stellen und Bezüge des anderen Prozesses
    /// holt die nächste Prüfung ohnehin nach.
    static func mergeNewerAlarmTriggers(from disk: Snapshot, into s: inout Snapshot) {
        let byId = Dictionary(disk.alarms.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        for i in s.alarms.indices {
            let app = s.alarms[i]
            guard let d = byId[app.id], d.lastTriggeredAt > app.lastTriggeredAt,
                  d.watchId == app.watchId, d.condition == app.condition, d.threshold == app.threshold,
                  d.windowHours == app.windowHours, d.repeating == app.repeating, d.currency == app.currency
            else { continue }
            s.alarms[i].enabled = app.enabled && d.enabled
            s.alarms[i].referencePrice = d.referencePrice
            s.alarms[i].referenceAt = d.referenceAt
            s.alarms[i].lastTriggeredAt = d.lastTriggeredAt
            s.alarms[i].lastTriggeredPrice = d.lastTriggeredPrice
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

    /// Letzte Prüfung MIT allen Alarmen (App, Live-Strom, Hintergrund-Aufgabe), ms; 0 = noch nie. Das Widget
    /// prüft nur Kursalarme (setzt `lastRefreshAt`) — die Hintergrund-Aufgabe darf sich darum nur hiernach
    /// überspringen.
    static var lastAlarmCheckAt: Int64 {
        get { Int64(defaults.double(forKey: "last_alarm_check_at")) }
        set { defaults.set(Double(newValue), forKey: "last_alarm_check_at") }
    }

    /// Wann `watchlist.json` zuletzt geschrieben wurde (ms); 0 = unbekannt (keine Datei).
    /// Das Widget vergleicht das mit `lastRefreshAt` (`WidgetDataFreshness`).
    static var watchlistWrittenAt: Int64 {
        let attributes = try? FileManager.default.attributesOfItem(atPath: watchlistURL.path)
        guard let date = attributes?[.modificationDate] as? Date else { return 0 }
        return Int64(date.timeIntervalSince1970 * 1000)
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

// MARK: Gruppe des Merkliste-Widgets

/// Gruppe eines Merkliste-Widgets auflösen — wie `WidgetGroupSelection.kt` (Android). Die App kann die
/// Einrichtung eines Widgets (AppIntent) nicht umschreiben; deshalb merkt sie sich Umbenennungen
/// (`renames`, alt → neu), denen das Widget folgt — Android schreibt den neuen Namen direkt in die
/// Widget-Einstellung (`WidgetPrefs.replaceGroup`). Reine Funktionen bis auf `renames`/`markSeen`.
enum WidgetGroupSelection {
    private static let renamesKey = "widget_group_renames"
    private static let seenKey = "watchlist_widget_configs_seen"
    /// Einrichtungen, die so lange nicht gezeichnet wurden, gelten wieder als neu.
    static let seenRetentionMillis: Int64 = 24 * 3_600_000

    /// - nil bleibt nil («Alle»)
    /// - FAV, «Token», «Alpha» und die übrigen eingebauten Filter (`WatchFilter.isBuiltIn`) bleiben (ohne passende
    ///   Paare zeigt das Widget alle)
    /// - eine vorhandene Gruppe bleibt
    /// - eine umbenannte folgt `renames` (auch über mehrere Umbenennungen), sofern es den neuen Namen gibt
    /// - sonst (gelöscht, letztes Paar entfernt) nil = «Alle», nie ein leeres Widget
    static func resolve(_ selection: String?, groupNames: [String?], renames: [String: String] = [:]) -> String? {
        guard let selection else { return nil }
        if WatchFilter.isBuiltIn(selection) { return selection }
        let existing = Set(groupNames.compactMap { $0 })
        var name = selection
        // Höchstens so viele Schritte wie Einträge: schützt vor Schleifen (A → B → A)
        for _ in 0...renames.count {
            if existing.contains(name) { return name }
            guard let next = renames[name] else { return nil }
            name = next
        }
        return nil
    }

    /// Umbenennungen nach «`old` heisst jetzt `new`»: Einträge, die auf `old` zeigten, zeigen auf `new`;
    /// `new` selbst ist kein alter Name mehr.
    static func afterRename(_ renames: [String: String], old: String, new: String) -> [String: String] {
        guard old != new else { return renames }
        var result = renames.mapValues { $0 == old ? new : $0 }
        result[old] = new
        result[new] = nil
        return result.filter { $0.key != $0.value }
    }

    /// Umbenennungen nach dem Löschen der Gruppe `name`: Wer ihr folgte, zeigt wieder «Alle».
    static func afterDelete(_ renames: [String: String], name: String) -> [String: String] {
        var result = renames.filter { $0.value != name }
        result[name] = nil
        return result
    }

    /// Gemerkte Umbenennungen (App Group, die App schreibt, das Widget liest).
    static var renames: [String: String] {
        get { SharedStorage.defaults.dictionary(forKey: renamesKey) as? [String: String] ?? [:] }
        set {
            if newValue.isEmpty { SharedStorage.defaults.removeObject(forKey: renamesKey) }
            else { SharedStorage.defaults.set(newValue, forKey: renamesKey) }
        }
    }

    static func recordRename(old: String, new: String) { renames = afterRename(renames, old: old, new: new) }

    static func recordDelete(_ name: String) { renames = afterDelete(renames, name: name) }

    /// Zuletzt gezeichnete Einrichtungen (`key` → Zeit) nach dem Zeichnen von `key`; `isNew` = diese
    /// Einrichtung wurde seit `seenRetentionMillis` nicht gezeichnet (eben eingerichtet oder geändert).
    /// Alte Einträge fallen weg.
    static func seen(_ seen: [String: Int64], key: String, now: Int64) -> (isNew: Bool, seen: [String: Int64]) {
        var result = seen.filter { now - $0.value >= 0 && now - $0.value < seenRetentionMillis }
        let isNew = result[key] == nil
        result[key] = now
        return (isNew, result)
    }

    /// Schlüssel einer Einrichtung des Merkliste-Widgets (Hintergrund und Gruppe).
    static func configKey(theme: String, group: String?) -> String { theme + "|" + (group ?? "") }

    /// Merkt sich, dass die Einrichtung `key` gezeichnet wird; true = neu (siehe `seen`).
    static func markSeen(_ key: String, now: Int64 = TimeUtils.nowMillis) -> Bool {
        let stored = SharedStorage.defaults.dictionary(forKey: seenKey) as? [String: Double] ?? [:]
        let (isNew, updated) = seen(stored.mapValues { Int64($0) }, key: key, now: now)
        SharedStorage.defaults.set(updated.mapValues { Double($0) }, forKey: seenKey)
        return isNew
    }

    /// Wirksame Gruppe und ihre Paare aus `items` (App-Reihenfolge): `selection` wird erst aufgelöst
    /// (`resolve`), dann gefiltert (`WatchFilter.matches`; `alpha` = Paar ist ein Alpha-Token, nur für
    /// «Alpha» gefragt). Gibt es die Gruppe nicht (mehr) oder passt kein Paar, wieder alle Paare und
    /// Gruppe nil («Alle», Titel «Merkliste») — nie ein leeres Widget. Wie `WidgetGroupSelection.pick`
    /// (Android).
    static func pick<T>(_ selection: String?, items: [T], groupName: (T) -> String?, favorite: (T) -> Bool,
                        alpha: (T) -> Bool = { _ in false },
                        renames: [String: String] = [:]) -> (group: String?, items: [T]) {
        guard let group = resolve(selection, groupNames: items.map(groupName), renames: renames) else {
            return (nil, items)
        }
        let needsAlpha = WatchFilter.isAlpha(group)
        let matching = items.filter {
            WatchFilter.matches(group, groupName: groupName($0), favorite: favorite($0), alpha: needsAlpha && alpha($0))
        }
        return matching.isEmpty ? (nil, items) : (group, matching)
    }

    /// Wählbare Einträge der Widget-Einrichtung in der Reihenfolge der Chips der App: «Alle» (nil), FAV,
    /// «Token» (nur mit `WatchFilter.showsTokens`), «Alpha» (nur mit `WatchFilter.showsAlpha`), dann die
    /// eigenen Gruppen alphabetisch (auch «TradFi», «QTLY»). `groupNames`/`alphaFlags` je Paar. Wie
    /// `WidgetGroupSelection.options` (Android).
    static func options(groupNames: [String?], alphaFlags: [Bool]) -> [String?] {
        var result: [String?] = [nil, WatchFilter.favorites]
        if WatchFilter.showsTokens(groupNames) { result.append(WatchFilter.tokens) }
        if WatchFilter.showsAlpha(alphaFlags) { result.append(WatchFilter.alpha) }
        let groups = Array(Set(groupNames.compactMap { $0 }))
            .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
        return result + groups.map { Optional($0) }
    }

    /// So viele Zeichen eines Gruppennamens zeigt der Kopf des Widgets; längere enden mit «…».
    static let titleMaxChars = 16

    /// Titel im Kopf des Merkliste-Widgets: `fallback` («Merkliste») bei «Alle» (`group` nil), sonst die
    /// Beschriftung des Filters (`filterLabel` für FAV, «Token», «Alpha») bzw. der Gruppenname (auch
    /// «TradFi»), auf `titleMaxChars` Zeichen gekürzt. Wie `WidgetGroupSelection.title` (Android).
    static func title(_ group: String?, fallback: String, filterLabel: (String) -> String) -> String {
        guard let group else { return fallback }
        let name = WatchFilter.isBuiltIn(group) ? filterLabel(group) : group
        return truncate(name, max: titleMaxChars)
    }

    /// Höchstens `max` Zeichen; länger: `max` − 1 Zeichen + «…» (wie Android).
    static func truncate(_ text: String, max: Int) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard max >= 1, trimmed.count > max else { return trimmed }
        let head = String(trimmed.prefix(max - 1))
        return head.replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression) + "…"
    }
}

/// Gehören die gespeicherten Kurse zur Uhrzeit im Kopf des Merkliste-Widgets? Die App setzt
/// `lastRefreshAt` sofort (UserDefaults), schreibt die Kurse aber im Hintergrund
/// (`saveSnapshotInBackground`). Zeichnet das Widget dazwischen (anderes Neuladen, zusammengelegte
/// Anfragen), stünde «Stand 14:55» über den alten Kursen. Reine Logik, testbar — wie Android
/// `ListWidgetDataVersion` (dort: Zeilen und Kopf aus demselben Zeichnen).
enum WidgetDataFreshness {
    /// So viel darf die Uhrzeit nach dem Schreiben liegen (Widget-Aktualisierung: erst Datei, dann Zeit).
    static let toleranceMillis: Int64 = 2_000

    /// true = die Uhrzeit ist neuer als die Datei mit den Kursen (Kurse noch nicht geschrieben).
    /// Unbekannt (0) zählt nie als «dahinter».
    static func dataBehind(refreshAt: Int64, writtenAt: Int64, tolerance: Int64 = toleranceMillis) -> Bool {
        refreshAt > 0 && writtenAt > 0 && refreshAt - writtenAt > tolerance
    }
}
