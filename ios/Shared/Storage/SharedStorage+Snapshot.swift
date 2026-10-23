import Foundation

// Merkliste und Alarme in der App Group (Teil von `SharedStorage`): Stand lesen, schreiben und ändern, prozessübergreifend
// abgestimmt, tolerantes Lesen. Die Dateien selbst (Merkliste, geschützte Portfolio-Alarme) in SharedStorage+Files.swift.

extension SharedStorage {
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

    // `saveSnapshot` (synchron, ohne Übernahme neuerer Kurse und Auslösungen) entfällt: Der ganze Stand
    // der App geht nur noch über `saveSnapshotInBackground` hinaus (sonst meldete sich ein Alarm doppelt).

    /// Lesen → ändern → schreiben als EIN abgestimmter Schritt (prozessübergreifend über
    /// `NSFileCoordinator`): für Hintergrund-Aktualisierung und Widget, die nur Kurse und
    /// Alarm-Zustand je Id auf den frisch gelesenen Stand übertragen (`PriceRefresher.Outcome.apply`).
    /// So kann kein gleichzeitig in der App hinzugefügtes Paar bzw. Alarm verloren gehen.
    ///
    /// Ist die Datei unlesbar, nicht zugreifbar oder fehlt sie, wird NICHT geschrieben.
    /// - Parameter body: darf `loadSnapshot`/`updateSnapshot` nicht aufrufen (verschachtelte
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
    /// - Parameter durability: `.barrier` nur für reine Kurs-Speicherungen (Live-Takt).
    static func saveSnapshotInBackground(_ snapshot: Snapshot, durability: ProtectedFile.Durability = .full,
                                         done: (() -> Void)? = nil) {
        let watchlist = watchlistURL, portfolio = portfolioAlarmsURL
        snapshotWriter.async {
            let include = loadSettings().portfolioSystemBackup
            let written = coordinated(writing: true) { () -> Bool in
                mergeAndWriteFiles(snapshot, watchlist: watchlist, portfolioAlarms: portfolio,
                                   includePortfolioInBackup: include, durability: durability)
            }
            // Ergebnis für `snapshotWritesSettled` (gemeldeter Alarm wirklich auf der Platte?)
            lastQueuedWriteBox.current = written
            if let done { DispatchQueue.main.async(execute: done) }
        }
    }

    /// Ergebnis des zuletzt über die Warteschlange ausgeführten Schreibens (true = `watchlist.json`
    /// geschrieben). Nur auf `snapshotWriter` gesetzt und gelesen.
    private static let lastQueuedWriteBox = LockedBox(true)

    /// Ein Schritt von `saveSnapshotInBackground` ohne Abstimmung (nur innerhalb von `coordinated`; testbar über URLs,
    /// eigene Funktion): neuere Kurse und Auslösungen der Datei übernehmen, dann schreiben.
    /// - Returns: true, wenn `watchlist.json` geschrieben wurde; false auch bei gerade nicht zugreifbarer Datei.
    @discardableResult
    static func mergeAndWriteFiles(_ snapshot: Snapshot, watchlist: URL, portfolioAlarms: URL,
                                   includePortfolioInBackup: Bool, keys: StorageKey = .shared,
                                   durability: ProtectedFile.Durability = .full) -> Bool {
        var s = snapshot
        switch readFiles(watchlist: watchlist, portfolioAlarms: portfolioAlarms, keys: keys) {
        case .ok(let disk):
            mergeNewerPrices(from: disk, into: &s)
            mergeNewerAlarmTriggers(from: disk, into: &s)
        case .inaccessible:
            // Gerade nicht lesbar (Datenschutz): auch nicht überschreiben
            return false
        case .missing, .unreadable:
            // Unlesbare Datei ist beim Lesen gesichert worden (`preserveUnreadable`)
            break
        }
        return writeFiles(s, watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                          includePortfolioInBackup: includePortfolioInBackup, keys: keys, durability: durability)
    }

    // MARK: Abstimmung über NSFileCoordinator

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

    /// Lesen → ändern → schreiben an einer anderen Datei als `watchlist.json` prozessübergreifend abstimmen
    /// (`NSFileCoordinator`, wie `updateSnapshot`) — z. B. Zähler am App-Symbol, «Letzte Prüfungen». Die Datei muss nicht
    /// existieren. Schlägt die Abstimmung fehl, läuft `body` trotzdem. Nie für dieselbe Datei verschachteln.
    @discardableResult
    static func coordinatedWrite<T>(at url: URL, _ body: () -> T) -> T {
        var error: NSError?
        var ran = false
        var result: T?
        NSFileCoordinator(filePresenter: nil).coordinate(writingItemAt: url, options: .forMerging, error: &error) { _ in
            ran = true
            result = body()
        }
        if ran, let result { return result }
        return body()
    }

    /// Liest `watchlist.json` (tolerant, siehe `Snapshot.init(from:)`) und `portfolio_alarms.json`.
    // MARK: Tolerantes Lesen

    /// Zählt beim Lesen übersprungene Einträge (über `JSONDecoder.userInfo`).
    final class DropCounter: Sendable {
        static let key = CodingUserInfoKey(rawValue: "com.cryptochecker.dropCounter")!
        // `userInfo` verlangt Sendable: Zähler hinter einer Sperre statt `@unchecked`
        private let box = LockedBox(0)
        var count: Int {
            get { box.current }
            set { box.current = newValue }
        }
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

    /// Einträge mit schon gesehener Id weglassen (der erste bleibt), Reihenfolge unverändert.
    static func uniqueById<T, ID: Hashable>(_ items: [T], id: (T) -> ID) -> [T] {
        var seen = Set<ID>()
        return items.filter { seen.insert(id($0)).inserted }
    }

    /// Nächste freie Id: nie kleiner als 1 und nie eine schon vergebene (Ids werden nicht wiederverwendet).
    static func nextId(stored: Int64, used: [Int64]) -> Int64 {
        guard let maxUsed = used.max() else { return max(stored, 1) }
        let after = maxUsed < Int64.max ? maxUsed + 1 : Int64.max
        return max(stored, after, 1)
    }

    /// Wartet, bis alle Hintergrund-Schreibvorgänge auf der Platte sind (vor dem Lesen, vor «App zurücksetzen»).
    /// Blockiert den aufrufenden Thread: Ein Schreibvorgang (JSON, AES, `NSFileCoordinator`) kann auf das Widget
    /// warten — auf dem Haupt-Thread darum `snapshotWritesSettled()`. Nie vom Schreib-Thread selbst aufrufen.
    static func flushSnapshotWrites() {
        snapshotWriter.sync {}
    }

    /// Wie `flushSnapshotWrites`, aber ohne zu blockieren: kehrt zurück, sobald alles bis jetzt Eingereihte
    /// geschrieben ist (die Warteschlange arbeitet der Reihe nach). Für den Haupt-Thread, z. B. bevor die Alarm-Lease
    /// frei wird oder der nächste Alarm gemeldet wird — sonst hängt die Oberfläche (Ziehen zum Aktualisieren), solange
    /// das Widget die Datei hält.
    /// - Returns: das Ergebnis des zuletzt eingereihten Schreibens (true = geschrieben; ohne eingereihtes
    ///   Schreiben das des vorigen) — z. B. damit ein gemeldeter Alarm nur bei echtem Schreiben als gespeichert gilt.
    @discardableResult
    static func snapshotWritesSettled() async -> Bool {
        await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            snapshotWriter.async { continuation.resume(returning: lastQueuedWriteBox.current) }
        }
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
        // Doppelte Paar-Ids (z. B. von Hand bearbeitete Datei, alter Fehler) behalten nur den ersten Eintrag —
        // sonst stürzt jedes `Dictionary(uniqueKeysWithValues:)` über die Paare ab. Gezählt wie ein
        // übersprungener Eintrag, damit eine Kopie der Datei erhalten bleibt.
        let decodedWatches: [Watch] = try SharedStorage.lenientArray(c, .watches, decoder: decoder) ?? []
        watches = SharedStorage.uniqueById(decodedWatches) { $0.id }
        if watches.count < decodedWatches.count {
            (decoder.userInfo[SharedStorage.DropCounter.key] as? SharedStorage.DropCounter)?.count +=
                decodedWatches.count - watches.count
        }
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
