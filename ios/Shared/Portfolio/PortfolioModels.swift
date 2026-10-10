import Foundation

/// Art einer Portfolio-Transaktion. Rohwert wird so in Datei und Sicherung geschrieben
/// (gleich wie `PortfolioTxType` der Android-Fassung).
enum PortfolioTxType: String, Codable, CaseIterable, Sendable {
    case BUY
    case SELL
}

/// Kauf oder Verkauf im Portfolio — unabhängig von der Merkliste.
/// Preise in USDT; `priceUsdt` nil = unbekannt (z. B. übernommener Bestand).
/// Entspricht `PortfolioTxEntity` der Android-Fassung.
struct PortfolioTx: Codable, Identifiable, Hashable, Sendable {
    /// 0 = noch nicht gespeichert.
    var id: Int64
    /// Basis-Symbol in Grossbuchstaben, z. B. «BTC».
    var coin: String
    var type: PortfolioTxType
    /// Menge, immer > 0.
    var amount: Double
    var priceUsdt: Double?
    /// Zeitpunkt des Handels (ms).
    var time: Int64
    var note: String?

    init(id: Int64 = 0, coin: String, type: PortfolioTxType, amount: Double,
         priceUsdt: Double? = nil, time: Int64, note: String? = nil) {
        self.id = id
        self.coin = coin
        self.type = type
        self.amount = amount
        self.priceUsdt = priceUsdt
        self.time = time
        self.note = note
    }

    private enum CodingKeys: String, CodingKey {
        case id, coin, type, amount, priceUsdt, time, note
    }

    // Tolerant: fehlende Nebenfelder bekommen Standardwerte; ohne Coin, Art
    // oder gültige Menge ist der Eintrag unbrauchbar (wird beim Laden übersprungen).
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(Int64.self, forKey: .id)) ?? 0
        let rawCoin = try c.decode(String.self, forKey: .coin)
        coin = PortfolioCalculator.normalizeCoin(rawCoin)
        type = try c.decode(PortfolioTxType.self, forKey: .type)
        amount = try c.decode(Double.self, forKey: .amount)
        let price = (try? c.decodeIfPresent(Double.self, forKey: .priceUsdt)) ?? nil
        priceUsdt = price.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil }
        time = (try? c.decodeIfPresent(Int64.self, forKey: .time)) ?? 0
        note = (try? c.decodeIfPresent(String.self, forKey: .note)) ?? nil
        guard !coin.isEmpty, amount.isFinite, amount > 0 else {
            throw DecodingError.dataCorruptedError(forKey: .amount, in: c, debugDescription: "Ungültige Transaktion")
        }
    }
}

/// Inhalt der Datei `portfolio.json` in der App Group.
struct PortfolioFile: Codable, Sendable {
    var transactions: [PortfolioTx] = []
    /// Nächste freie Id (Ids werden nie wiederverwendet).
    var nextId: Int64 = 1
    /// Alter Bestand der Merkliste ist als Käufe übernommen — steht in derselben Datei wie die Käufe
    /// und wird mit ihnen in einem Schreibvorgang gespeichert. So legt ein Abbruch vor dem Leeren der
    /// Merkliste die Käufe beim nächsten Start nicht doppelt an (wie Android `holdings_import_done`).
    var holdingsImportDone = false

    init(transactions: [PortfolioTx] = [], nextId: Int64 = 1, holdingsImportDone: Bool = false) {
        self.transactions = transactions
        self.nextId = nextId
        self.holdingsImportDone = holdingsImportDone
    }

    private enum CodingKeys: String, CodingKey { case transactions, nextId, holdingsImportDone }

    /// Ein einzelner kaputter Eintrag macht nicht die ganze Datei unlesbar.
    private struct Lossy: Decodable {
        let tx: PortfolioTx?
        init(from decoder: Decoder) throws { tx = try? PortfolioTx(from: decoder) }
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        transactions = ((try? c.decodeIfPresent([Lossy].self, forKey: .transactions)) ?? nil)?.compactMap(\.tx) ?? []
        nextId = (try? c.decodeIfPresent(Int64.self, forKey: .nextId)) ?? 1
        holdingsImportDone = (try? c.decodeIfPresent(Bool.self, forKey: .holdingsImportDone)) ?? false
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(transactions, forKey: .transactions)
        try c.encode(nextId, forKey: .nextId)
        if holdingsImportDone { try c.encode(true, forKey: .holdingsImportDone) }
    }
}

/// Speicher der Portfolio-Transaktionen: eigene JSON-Datei in der App Group
/// (getrennt vom Merklisten-Snapshot, den auch die Widgets lesen und schreiben).
/// Entspricht `PortfolioDao` + Teilen von `PortfolioRepository`.
enum PortfolioStore {
    static var url: URL { SharedStorage.directory.appendingPathComponent("portfolio.json") }

    /// Merker: alter Bestand der Merkliste wurde übernommen (wie Android «holdings_migrated»).
    private static let migratedKey = "holdings_migrated"

    static func load() -> PortfolioFile {
        guard let data = try? Data(contentsOf: url),
              let file = try? JSONDecoder().decode(PortfolioFile.self, from: data)
        else { return PortfolioFile() }
        return file
    }

    /// Schreibt die Datei und setzt danach das Backup-Merkmal neu (das atomare Schreiben ersetzt die
    /// Datei und verliert es). `includeInBackup` = Einstellung «Portfolio in Systemsicherung».
    static func save(_ file: PortfolioFile, includeInBackup: Bool) {
        guard let data = try? JSONEncoder().encode(file) else { return }
        try? data.write(to: url, options: [.atomic])
        BackupExclusion.set(excluded: !includeInBackup, for: url)
    }

    /// Dateien mit Portfolio-Daten, die dem Schalter folgen: Transaktionen, Portfolio-Alarme
    /// (`SharedStorage`) und die gesicherte Kopie einer unlesbaren Alarm-Datei.
    static var backupPolicyURLs: [URL] {
        let alarms = SharedStorage.portfolioAlarmsURL
        return [url, alarms, SharedStorage.unreadableCopyURL(of: alarms)]
    }

    /// «Portfolio in Systemsicherung» (wie Android, Standard aus): aus = Portfolio-Daten nicht in
    /// iCloud-/Geräte-Backups (`backupPolicyURLs`). Beim Start und beim Umschalten aufrufen; die
    /// Schreibstellen setzen das Merkmal nach jedem Schreiben selbst neu.
    /// Die Momentaufnahme des Portfolio-Widgets ist nie im Backup (wie Android `portfolio_widget`); beim
    /// Start zieht sie zudem aus den App-Group-Einstellungen (die sich nicht ausnehmen lassen) in ihre Datei.
    static func applyBackupPolicy(includeInBackup: Bool) {
        PortfolioWidgetStore.migrateSnapshotToFile()
        BackupExclusion.set(excluded: true, for: PortfolioWidgetStore.snapshotURL)
        for file in backupPolicyURLs {
            BackupExclusion.set(excluded: !includeInBackup, for: file)
        }
    }

    static var holdingsMigrated: Bool {
        get { SharedStorage.defaults.bool(forKey: migratedKey) }
        set { SharedStorage.defaults.set(newValue, forKey: migratedKey) }
    }

    /// Neueste zuerst (Zeit, dann Id absteigend) — wie `ORDER BY time DESC, id DESC`.
    static func newestFirst(_ list: [PortfolioTx]) -> [PortfolioTx] {
        list.sorted { $0.time != $1.time ? $0.time > $1.time : $0.id > $1.id }
    }

    /// Einfügen wie `INSERT … REPLACE`: Id 0 bekommt eine neue Id, eine
    /// vorhandene Id ersetzt den Eintrag. Liefert die Id.
    @discardableResult
    static func insert(_ tx: PortfolioTx, into file: inout PortfolioFile) -> Int64 {
        var item = tx
        let maxId = file.transactions.map(\.id).max() ?? 0
        if item.id <= 0 {
            item.id = max(file.nextId, maxId + 1)
        }
        if let i = file.transactions.firstIndex(where: { $0.id == item.id }) {
            file.transactions[i] = item
        } else {
            file.transactions.append(item)
        }
        file.nextId = max(file.nextId, item.id + 1, maxId + 1)
        return item.id
    }

    /// Ändern wie `@Update`: nur wenn die Id existiert.
    @discardableResult
    static func update(_ tx: PortfolioTx, in file: inout PortfolioFile) -> Bool {
        guard let i = file.transactions.firstIndex(where: { $0.id == tx.id }) else { return false }
        file.transactions[i] = tx
        return true
    }

    /// Wandelt Bestände der Merkliste in Käufe ohne Preis um (Mengen gleicher
    /// Coins addiert) und leert sie — wie `PortfolioRepository.importHoldings`.
    /// Mit `onlyNewCoins` nur für Coins, die noch keine Transaktion haben
    /// (Wiederherstellen einer alten Sicherung — sonst doppelt).
    /// Ohne gültigen Bestand bleibt alles unverändert.
    /// - Returns: Anzahl angelegter Käufe.
    @discardableResult
    static func importHoldings(watches: inout [Watch], into file: inout PortfolioFile,
                               onlyNewCoins: Bool, now: Int64) -> Int {
        var order: [String] = []
        var amounts: [String: Double] = [:]
        for w in watches {
            guard let amount = w.holdings, amount > 0, !amount.isInfinite else { continue }
            let coin = PortfolioCalculator.normalizeCoin(w.baseAsset)
            guard !coin.isEmpty else { continue }
            if amounts[coin] == nil { order.append(coin) }
            amounts[coin, default: 0] += amount
        }
        guard !order.isEmpty else { return 0 }

        let existing: Set<String> = onlyNewCoins
            ? Set(file.transactions.map { PortfolioCalculator.normalizeCoin($0.coin) })
            : []
        var created = 0
        for coin in order where !existing.contains(coin) {
            guard let amount = amounts[coin] else { continue }
            insert(PortfolioTx(coin: coin, type: .BUY, amount: amount, priceUsdt: nil, time: now), into: &file)
            created += 1
        }
        for i in watches.indices { watches[i].holdings = nil }
        return created
    }
}
