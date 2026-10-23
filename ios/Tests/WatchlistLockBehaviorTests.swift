import XCTest
@testable import CryptoChecker

/// Notfall-Sperre der Merkliste als Verhalten (Review TST-02), wie Android `WatchlistLockDeviceTest`:
/// Mit `watchlistLocked` ruft der Test jeden Schreibweg des Nutzers von `AppData` auf und prüft, dass sich weder Merkliste
/// noch Alarme noch Einstellungen ändern und jeder Weg die Ablehnung meldet (`watchlistLockedHints`, Drosselung je Aufruf
/// zurückgesetzt). Ergänzt die Quelltext-Prüfung in `WatchlistWriteLockTests`. Läuft auf `AppData.shared` der Test-App.
/// Review TST2-R1: mit eigenen Testdaten (zwei Paare, ein Alarm), damit jeder Weg ungesperrt wirklich
/// etwas änderte; die Gegenprobe ohne Sperre zeigt das je Weg. Merkliste, Alarme und Einstellungen der Test-App kommen
/// danach zurück. Dazu ein Quelltext-Scan: Jede nicht-private Funktion der AppData-Merklisten-Dateien steht in genau
/// einer Liste (Schreibweg, Lesen, Hintergrund) — ein neuer Schreibweg fällt so auf.
@MainActor
final class WatchlistLockBehaviorTests: XCTestCase {
    private static let btc = Watch(id: 990_101, marketKey: "Binance", marketName: "Binance", baseAsset: "BTC",
                                   quoteAsset: "USDT", pairId: "BTCUSDT", sortOrder: 0)
    private static let eth = Watch(id: 990_102, marketKey: "Binance", marketName: "Binance", baseAsset: "ETH",
                                   quoteAsset: "USDT", pairId: "ETHUSDT", sortOrder: 1)
    private static let solId: Int64 = 990_103
    /// Löst nie aus (Schwelle weit über jedem Kurs): Eine Aktualisierung zwischendurch ändert ihn nicht.
    private static let alarm = Alarm(id: 990_104, watchId: 990_101, condition: .PRICE_ABOVE, threshold: 1e15)

    /// Stand der Test-App vor dem Test.
    private var original: SharedStorage.Snapshot?
    private var originalSettings: AppSettings?
    private var wasLocked = false
    private var hintAt: Int64?

    /// Ein Schreibweg; `key` wie im Quelltext-Scan (Name und erstes Argument).
    private struct Write {
        let key: String
        /// Meldet gesperrt die Ablehnung (Alarme anlegen: nein, der Bildschirm nennt es vorher).
        var hints = true
        /// Testdaten vor dem Aufruf anpassen (z. B. Gruppe, die gelöscht wird).
        var prepare: ((inout SharedStorage.Snapshot) -> Void)? = nil
        /// `locked` = gesperrter Durchlauf (Rückgabe «gesperrt» gleich mitprüfen).
        let run: (AppData, _ locked: Bool) -> Void
    }

    /// Vergleichbare Gestalt des Stands ohne Kurse (eine Aktualisierung zwischendurch zählt nicht als Änderung).
    private struct Shape: Equatable {
        struct Pair: Equatable {
            var id: Int64, market: String, base: String, quote: String, pairId: String?, sortOrder: Int
            var notification: Bool, tts: Bool, favorite: Bool, group: String?, note: String?, disabled: Bool
        }
        struct Rule: Equatable {
            var id: Int64, watchId: Int64, condition: AlarmCondition, threshold: Double, enabled: Bool
        }
        var pairs: [Pair]
        var rules: [Rule]
        var portfolioAlarmIds: [Int64]?

        init(_ s: SharedStorage.Snapshot) {
            pairs = s.watches.map {
                Pair(id: $0.id, market: $0.marketKey, base: $0.baseAsset, quote: $0.quoteAsset, pairId: $0.pairId,
                     sortOrder: $0.sortOrder, notification: $0.notificationEnabled, tts: $0.ttsEnabled,
                     favorite: $0.favorite, group: $0.groupName, note: $0.note, disabled: $0.disabled)
            }.sorted { $0.id < $1.id }
            rules = s.alarms.map {
                Rule(id: $0.id, watchId: $0.watchId, condition: $0.condition, threshold: $0.threshold, enabled: $0.enabled)
            }.sorted { $0.id < $1.id }
            portfolioAlarmIds = s.portfolioAlarms?.map(\.id).sorted()
        }
    }

    override func setUp() async throws {
        let data = AppData.shared
        wasLocked = data.watchlistLocked
        hintAt = data.watchlistLockedHintAt
        originalSettings = data.settings
        guard let stored = SharedStorage.readSnapshot().snapshot else {
            throw XCTSkip("Merkliste der Test-App gerade nicht lesbar")
        }
        original = stored
    }

    override func tearDown() async throws {
        let data = AppData.shared
        data.watchlistLocked = false
        if let original {
            _ = SharedStorage.updateSnapshot { s in
                s.watches = original.watches
                s.alarms = original.alarms
                s.nextWatchId = original.nextWatchId
                s.nextAlarmId = original.nextAlarmId
                if original.portfolioAlarms != nil {
                    s.portfolioAlarms = original.portfolioAlarms
                    s.nextPortfolioAlarmId = original.nextPortfolioAlarmId
                }
            }
            await data.reloadFromDisk()
        }
        data.watchlistLocked = wasLocked
        data.watchlistLockedHintAt = hintAt
        if let originalSettings, data.settings != originalSettings { data.settings = originalSettings }
        original = nil
    }

    /// Eigene Testdaten auf die Platte und in `AppData` (ungesperrt gelesen).
    private func loadTestData(_ prepare: ((inout SharedStorage.Snapshot) -> Void)? = nil) async throws {
        let data = AppData.shared
        data.watchlistLocked = false
        let portfolioAlarms = original?.portfolioAlarms, nextPortfolioAlarmId = original?.nextPortfolioAlarmId
        let stored = SharedStorage.updateSnapshot { s in
            s.watches = [Self.btc, Self.eth]
            s.alarms = [Self.alarm]
            s.nextWatchId = max(s.nextWatchId, Self.solId + 1)
            s.nextAlarmId = max(s.nextAlarmId, Self.alarm.id + 1)
            if portfolioAlarms != nil {
                s.portfolioAlarms = portfolioAlarms
                s.nextPortfolioAlarmId = nextPortfolioAlarmId
            }
            prepare?(&s)
        }
        let expected = try XCTUnwrap(stored, "Merkliste der Test-App nicht beschreibbar")
        // Ändert die App zwischendurch etwas, gilt der Speicher (`reloadFromDisk`): dann noch einmal lesen
        for _ in 0..<3 {
            await data.reloadFromDisk()
            if Shape(data.snapshot) == Shape(expected) { return }
        }
        XCTFail("Testdaten nicht übernommen")
        throw XCTSkip("Testdaten nicht übernommen")
    }

    private func encoded(_ snapshot: SharedStorage.Snapshot) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(snapshot)
    }

    /// Jeder Schreibweg des Nutzers mit Argumenten, die ungesperrt etwas ändern.
    private func userWrites(_ market: Market) -> [Write] {
        let sol = MarketPair("SOL", "USDT", "SOLUSDT")
        let btc = Self.btc, alarmId = Self.alarm.id
        let deleted = DeletedWatch(watch: Watch(id: Self.solId, marketKey: "Binance", marketName: "Binance",
                                                baseAsset: "SOL", quoteAsset: "USDT", pairId: "SOLUSDT"),
                                   alarms: [], activity: nil, selectedGroup: nil)
        let template = AlarmTemplates.Definition(condition: .PRICE_ABOVE, threshold: 1e15, windowHours: 1,
                                                 referencePrice: nil)
        func whenLocked(_ locked: Bool, _ ok: @autoclosure () -> Bool, _ name: String) {
            if locked { XCTAssertTrue(ok(), "\(name): Rückgabe nicht «gesperrt»") }
        }
        return [
            Write(key: "addStarterCoins(_ coins") { d, l in
                let n = d.addStarterCoins(["SOL"]); whenLocked(l, n == 0, "addStarterCoins")
            },
            Write(key: "changePair(_ watchId") { d, l in
                let r = d.changePair(btc.id, market: market, pair: sol); whenLocked(l, r == nil, "changePair")
            },
            Write(key: "delete(_ watch") { d, _ in d.delete(btc) },
            Write(key: "deleteForUndo(_ watch") { d, l in
                let r = d.deleteForUndo(btc); whenLocked(l, r == nil, "deleteForUndo(watch)")
            },
            Write(key: "restore(_ deleted") { d, l in
                let r = d.restore(deleted); whenLocked(l, r == UndoRestoreResult(), "restore(one)")
            },
            Write(key: "restore(_ deleted") { d, l in
                let r = d.restore([deleted]); whenLocked(l, r == UndoRestoreResult(), "restore(many)")
            },
            Write(key: "deleteForUndo(ids wanted") { d, l in
                let r = d.deleteForUndo(ids: [btc.id]); whenLocked(l, r.isEmpty, "deleteForUndo(ids)")
            },
            Write(key: "deleteNotTradedForUndo()", prepare: { $0.watches[0].lastError = NotTraded.marker }) { d, l in
                let r = d.deleteNotTradedForUndo(); whenLocked(l, r.isEmpty, "deleteNotTradedForUndo")
            },
            Write(key: "deleteAllForUndo()") { d, l in
                let r = d.deleteAllForUndo(); whenLocked(l, r.isEmpty, "deleteAllForUndo")
            },
            Write(key: "addWatch(market") { d, l in
                let r = d.addWatch(market: market, pair: sol); whenLocked(l, r == nil, "addWatch")
            },
            Write(key: "tryAddWatch(market") { d, l in
                let r = d.tryAddWatch(market: market, pair: sol); whenLocked(l, r == .locked, "tryAddWatch")
            },
            Write(key: "addWatches(market") { d, l in
                let r = d.addWatches(market: market, pairs: [sol]); whenLocked(l, r.locked, "addWatches")
            },
            Write(key: "setNotificationEnabled(_ watch") { d, _ in d.setNotificationEnabled(btc, false) },
            Write(key: "setAllNotifications(_ enabled") { d, _ in d.setAllNotifications(false) },
            Write(key: "setTtsEnabled(_ watch") { d, l in
                let r = d.setTtsEnabled(btc, true); whenLocked(l, !r, "setTtsEnabled")
            },
            Write(key: "setNote(_ watch") { d, _ in d.setNote(btc, "neu") },
            Write(key: "setGroup(_ watch") { d, _ in d.setGroup(btc, "Neu") },
            Write(key: "setGroup(ids") { d, _ in d.setGroup(ids: [btc.id], "Neu") },
            Write(key: "setFavorite(ids") { d, _ in d.setFavorite(ids: [btc.id], true) },
            Write(key: "setDisabled(ids") { d, _ in d.setDisabled(ids: [btc.id], true) },
            Write(key: "toggleFavorite(_ watch") { d, _ in d.toggleFavorite(btc) },
            Write(key: "move(_ watch") { d, _ in d.move(d.watches.first ?? btc, .bottom) },
            Write(key: "reorder(_ orderedIds") { d, _ in d.reorder(Array(d.watches.map(\.id).reversed())) },
            Write(key: "applyGroupEdit(oldName") { d, _ in
                d.applyGroupEdit(oldName: nil, newName: "Neu", memberIds: [btc.id])
            },
            Write(key: "deleteGroup(_ name", prepare: { $0.watches[0].groupName = "Neu" }) { d, _ in d.deleteGroup("Neu") },
            Write(key: "deleteAlarm(_ id") { d, _ in d.deleteAlarm(alarmId) },
            Write(key: "setAlarmEnabled(_ id") { d, _ in d.setAlarmEnabled(alarmId, false) },
            // Neue Alarme sperrt `alarmSaveBlocked` (ohne eigenen Hinweis: der Bildschirm nennt es vorher)
            Write(key: "saveAlarm(watchId", hints: false) { d, l in
                let r = d.saveAlarm(watchId: btc.id, id: 0, condition: .PRICE_ABOVE, threshold: 1e15, repeating: false,
                                    sound: true, vibrate: true, speak: false, windowHours: 1)
                whenLocked(l, r == .refused, "saveAlarm")
            },
            Write(key: "createAlarm(watchId", hints: false) { d, l in
                let r = d.createAlarm(watchId: btc.id, from: template); whenLocked(l, r.id == 0, "createAlarm")
            },
            Write(key: "addPortfolioAlarm(kind", hints: false) { d, l in
                let r = d.addPortfolioAlarm(kind: .VALUE_ABOVE, threshold: 1e15, currency: "USD", repeating: false)
                whenLocked(l, !r, "addPortfolioAlarm")
            },
        ]
    }

    func testLockedWatchlistIgnoresEveryUserWrite() async throws {
        let data = AppData.shared
        let market = try XCTUnwrap(MarketsConfig.market("Binance"))
        for write in userWrites(market) {
            try await loadTestData(write.prepare)
            data.watchlistLocked = true
            // Synchron bis zum Vergleich: kein Lesen der Datei kann die Sperre zwischendurch ändern
            let snapshotBefore = try encoded(data.snapshot)
            let settingsBefore = data.settings
            // Drosselung des Hinweises zurücksetzen: Jeder Weg muss die Ablehnung selbst melden
            data.watchlistLockedHintAt = nil
            let hints = data.watchlistLockedHints
            write.run(data, true)
            XCTAssertEqual(data.watchlistLockedHints, write.hints ? hints &+ 1 : hints, "\(write.key): Hinweis falsch")
            XCTAssertEqual(try encoded(data.snapshot), snapshotBefore, "\(write.key) hat trotz Sperre geschrieben")
            XCTAssertEqual(data.settings, settingsBefore, "\(write.key) hat trotz Sperre Einstellungen geändert")
        }
    }

    /// Gegenprobe: dieselben Aufrufe ohne Sperre ändern je den Stand und melden keine Sperre — sonst prüfte der Test
    /// oben nichts.
    func testSameWritesChangeTheWatchlistWhenUnlocked() async throws {
        let data = AppData.shared
        let market = try XCTUnwrap(MarketsConfig.market("Binance"))
        for write in userWrites(market) {
            // Portfolio-Alarme der Test-App unbekannt: das Anlegen bliebe danach stehen
            if write.key == "addPortfolioAlarm(kind" && original?.portfolioAlarms == nil { continue }
            try await loadTestData(write.prepare)
            let before = Shape(data.snapshot)
            let hints = data.watchlistLockedHints
            write.run(data, false)
            XCTAssertNotEqual(Shape(data.snapshot), before, "\(write.key) ändert auch ungesperrt nichts")
            XCTAssertEqual(data.watchlistLockedHints, hints, "\(write.key) meldet ungesperrt die Sperre")
        }
    }

    // MARK: Vollständigkeit (Quelltext)

    /// Schreibwege des Nutzers: oben je mit Sperre und Gegenprobe geprüft.
    private static let writeKeys: Set<String> = [
        "addStarterCoins(_ coins", "changePair(_ watchId", "delete(_ watch", "deleteForUndo(_ watch", "restore(_ deleted",
        "deleteForUndo(ids wanted", "deleteNotTradedForUndo()", "deleteAllForUndo()", "addWatch(market",
        "tryAddWatch(market", "addWatches(market", "setNotificationEnabled(_ watch", "setAllNotifications(_ enabled",
        "setTtsEnabled(_ watch", "setNote(_ watch", "setGroup(_ watch", "setGroup(ids", "setFavorite(ids",
        "setDisabled(ids", "toggleFavorite(_ watch", "move(_ watch", "reorder(_ orderedIds", "applyGroupEdit(oldName",
        "deleteGroup(_ name", "deleteAlarm(_ id", "setAlarmEnabled(_ id", "saveAlarm(watchId", "createAlarm(watchId",
        "addPortfolioAlarm(kind",
    ]

    /// Lesen bzw. reine Hilfen: schreiben selbst nichts.
    private static let readKeys: Set<String> = [
        "celebrateFirstAdd(_ watchId", "takeAddCelebration()", "watchingMessage(ids", "starterPair(_ coin",
        "contains(marketKey", "insert(_ s", "insertOutcome(_ s", "restoreInto(_ s", "placeInSlots(_ all",
        "isFavorite(_ kind", "groups(of watches", "watch(_ id", "alarms(for watchId", "staleAfterMillis(for watchId",
    ]

    /// Hintergrund bzw. ausserhalb der Merkliste — ohne Sperre: Einstellungen, Auswahl-Favoriten (eigene Ablage),
    /// Portfolio-Alarm-Zustand und -Prüfung, Probe-Alarm; `dropMissingWatchlistGroup` beachtet die Sperre selbst.
    private static let backgroundKeys: Set<String> = [
        "markFirstPairAdded()", "toggleFavorite(_ kind", "setFavorites(_ kind", "selectWatchlistGroup(_ group",
        "dropMissingWatchlistGroup()", "setPortfolioAlarmEnabled(_ id", "deletePortfolioAlarm(_ id",
        "prefetchPortfolioAlarmRates(_ widget", "evaluatePortfolioAlarms(_ widget", "sendTestAlarm()",
    ]

    private func source(_ name: String) throws -> String {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("App/Services/\(name)")
        return try String(contentsOf: file, encoding: .utf8)
    }

    /// Nicht-private Funktionen auf oberster Ebene der Erweiterung als «Name(erstes Argument».
    private func publicFunctionKeys(_ text: String) -> [String] {
        text.components(separatedBy: "\n").compactMap { line -> String? in
            guard line.hasPrefix("    "), !line.hasPrefix("     "), let range = line.range(of: "func ") else { return nil }
            let prefix = line[..<range.lowerBound]
            guard !prefix.contains("private"), !prefix.contains("/") else { return nil }
            let rest = line[range.upperBound...]
            guard let open = rest.firstIndex(of: "(") else { return nil }
            let name = rest[..<open]
            let args = rest[rest.index(after: open)...]
            let end = args.firstIndex { $0 == ":" || $0 == ")" } ?? args.endIndex
            let first = args[..<end]
            return first.isEmpty && args[end...].hasPrefix(")") ? "\(name)()" : "\(name)(\(first)"
        }
    }

    func testEveryAppDataWatchlistFunctionIsClassified() throws {
        // Jede Funktion in genau einer Liste
        XCTAssertTrue(Self.writeKeys.isDisjoint(with: Self.readKeys))
        XCTAssertTrue(Self.writeKeys.isDisjoint(with: Self.backgroundKeys))
        XCTAssertTrue(Self.readKeys.isDisjoint(with: Self.backgroundKeys))
        var found = Set<String>()
        for file in ["AppData+Watchlist.swift", "AppData+WatchEdits.swift", "AppData+Reading.swift", "AppData+Alarms.swift"] {
            for key in publicFunctionKeys(try source(file)) {
                found.insert(key)
                XCTAssertTrue(Self.writeKeys.contains(key) || Self.readKeys.contains(key) || Self.backgroundKeys.contains(key),
                              "\(file): \(key) ist keiner Liste (Schreibweg/Lesen/Hintergrund) zugeordnet")
            }
        }
        // Keine veralteten Einträge, und jeder Schreibweg steht im Verhaltenstest
        XCTAssertEqual(found, Self.writeKeys.union(Self.readKeys).union(Self.backgroundKeys))
        let market = try XCTUnwrap(MarketsConfig.market("Binance"))
        XCTAssertEqual(Set(userWrites(market).map(\.key)), Self.writeKeys)
    }
}
