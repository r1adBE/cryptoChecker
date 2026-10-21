import SwiftUI
import UniformTypeIdentifiers

// Ablauf des CSV-Imports ohne Ansicht: Datei lesen, Vorschau (Zuordnung, Devisenkurse), Umbuchungen
// verknüpfen und Zeilen als Transaktionen übernehmen (`CsvImportLoaded`, `PortfolioCsvImporter`).
// Eigene Datei, weil die Logik vom Vorschau-Blatt getrennt lesbar sein soll (wie `PortfolioCsvImportViewModel.kt`).

/// Geladene Vorschau eines CSV-Imports (für `.sheet(item:)`) — mit Datei, vorhandenen Schlüsseln und Tageskursen,
/// damit eine andere Zeitzone neu zuordnen kann, ohne die Datei neu zu lesen.
struct CsvImportLoaded: Identifiable {
    let id = UUID()
    /// Gelesener Text (für eine andere Zeitzone neu zuordnen, ohne die Datei neu zu lesen).
    let text: String
    let result: CsvImportResult
    let existingKeys: Set<String>
    let rates: [String: [(day: Int, rate: Double)]]
    /// Vorschau ohne Umbuchung (Grundeinstellung).
    let preview: CsvImportPreview
    /// USDT/USD-Tagesschlüsse (`CsvImport.priceInUsdt` `usdtUsd`), beim Laden geholt; leer = keine (Näherung markiert).
    var usdtUsd: [Int: Decimal] = [:]

    /// Vorschau neu zuordnen (`CsvImportMapping`, `withdrawalsAsTransfer`) — wie `mapPreview` (Android); `zone` =
    /// gewählte Zeitzone der Zeiten ohne Versatz (nil = Regel `CsvImportParser.zoneFor`): Text im Speicher neu lesen,
    /// Tageskurse bleiben (ein verschobenes Datum nimmt den Kurs des letzten Geschäftstags davor) — wie `setZone`.
    /// `dateOrder` = gewählte Reihenfolge Tag/Monat bei mehrdeutigen Daten; `decimalMark` = gewähltes Dezimalzeichen bei
    /// mehrdeutigen Zahlen; `rates` = neu geladene Tageskurse (nil = die der Datei beim Laden).
    func remapped(withdrawalsAsTransfer: Bool, zone: String? = nil, dateOrder: CsvDateOrder? = nil,
                  decimalMark: Character? = nil,
                  rates current: [String: [(day: Int, rate: Double)]]? = nil) -> CsvImportPreview {
        let r: CsvImportResult
        if zone != nil || dateOrder != nil || decimalMark != nil {
            r = CsvImportParser.parse(text, deviceZone: TimeZone.current.identifier, zone: zone, dateOrder: dateOrder,
                                      decimalMark: decimalMark)
        } else {
            r = result
        }
        return PortfolioCsvImporter.mapPreview(r, existingKeys: existingKeys, rates: current ?? rates,
                                               withdrawalsAsTransfer: withdrawalsAsTransfer, usdtUsd: usdtUsd)
    }
}

/// CSV-Import (Relai, Swissquote, Kraken, Binance, Coinbase, Bitpanda) — wie `PortfolioCsvImportViewModel` (Android): Datei
/// lesen (`CsvImportParser`), Preise mit den Devisen-Tageskursen (EZB über Frankfurter, letzter Geschäftstag ≤ Tag,
/// `PortfolioHistoryFx.rateOn`) in USDT umrechnen und die Vorschau bauen (`CsvImportMapping.preview`); Umbuchungen zwischen
/// Börsen schlägt die Vorschau vor (`CsvTransferMatch`).
enum PortfolioCsvImporter {
    /// Höchstens 16 MB, wie Android (`BackupLimits.CSV_MAX_BYTES`); die Sicherung selbst darf seit Build 92 64 MB.
    static let maxBytes = 16 * 1024 * 1024

    /// Erlaubte Dateien der Auswahl — CSV aus Dateien/Drive kommt teils als Text oder ohne Typ (`.data`);
    /// der Inhalt wird ohnehin geprüft (wie Android).
    static let contentTypes: [UTType] = [.commaSeparatedText, .delimitedText, .plainText, .text, .data]

    /// Unterstützte Quellen (Markennamen, nicht übersetzt) — Untertitel im Menü, wie Android.
    static let sources = "Relai, Swissquote, Kraken, Binance, Coinbase, Bitpanda"

    /// Datei lesen, höchstens `maxBytes` — auch ohne bekannte Grösse (`fileSizeKey` fehlt bei manchen Anbietern) nie
    /// eine beliebig grosse Datei in den Speicher (wie `BackupManager.readFile`). nil = nicht lesbar oder zu gross.
    static func readFile(_ url: URL, maxBytes: Int = maxBytes) -> Data? {
        if let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize, size > maxBytes { return nil }
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        guard let data = try? handle.read(upToCount: maxBytes + 1) ?? Data(), data.count <= maxBytes else { return nil }
        return data
    }

    /// nil = nicht lesbar oder keine unterstützte Zeile.
    static func load(_ url: URL, existingKeys: Set<String>) async -> CsvImportLoaded? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = readFile(url) else { return nil }
        // UTF-8, sonst Windows-1252 (Excel), BOM weg — wie Android
        let text = CsvImportParser.decode(data)
        let result = CsvImportParser.parse(text, deviceZone: TimeZone.current.identifier)
        let rates = await fetchRates(result)
        let usdtUsd = await fetchUsdtUsd(result)
        let preview = mapPreview(result, existingKeys: existingKeys, rates: rates, withdrawalsAsTransfer: false,
                                 usdtUsd: usdtUsd)
        guard preview.format != .UNKNOWN,
              !preview.transactions.isEmpty || preview.duplicates > 0 || !preview.skipped.isEmpty else { return nil }
        return CsvImportLoaded(text: text, result: result, existingKeys: existingKeys, rates: rates, preview: preview,
                               usdtUsd: usdtUsd)
    }

    /// USDT/USD-Tagesschlüsse (Kraken, `PortfolioHistorySource.usdtUsdDaily`) vom frühesten Tag der Datei bis heute — wie
    /// der Steuer-Export (`usdtUsdCloses`), damit Import und Export denselben Schluss DES Tags nehmen. Nicht erreichbar →
    /// leer (USD/Fiat dann mit USDT = 1, Zeilen als Näherung markiert). Wie Android `usdtUsdCloses`.
    static func fetchUsdtUsd(_ result: CsvImportResult) async -> [Int: Decimal] {
        guard let first = result.rows.map({ CsvImport.epochDay($0.timeMillis, zone: result.fxZone) }).min() else { return [:] }
        let days = max(1, LocalDay.today().epochDay - Int(first) + 1 + PortfolioHistoryFx.leadDays)
        let closes = await PortfolioHistorySource.usdtUsdDaily(days: days)
        var exact: [Int: Decimal] = [:]
        for (day, close) in closes { if let c = PortfolioDecimal.of(close) { exact[day] = c } }
        return exact
    }

    /// Tageskurse je Währung: eine Abfrage über den ganzen Zeitraum (mit Vorlauf für Wochenenden/Feiertage).
    static func fetchRates(_ result: CsvImportResult) async -> [String: [(day: Int, rate: Double)]] {
        var rates: [String: [(day: Int, rate: Double)]] = [:]
        for (currency, days) in CsvImportMapping.fxNeeds(result.rows, zone: result.fxZone) {
            let from = LocalDay(epochDay: Int(days.lowerBound) - PortfolioHistoryFx.leadDays)
            let to = LocalDay(epochDay: Int(days.upperBound))
            if let series = await HistoricPriceSource.usdToSeries(currency, from: from, to: to) {
                rates[currency] = series.map { (day: $0.key, rate: $0.value) }.sorted { $0.day < $1.day }
            }
        }
        return rates
    }

    /// Abgleich, Umrechnung und Zuordnung (`withdrawalsAsTransfer`: Auszahlung → Umbuchung).
    static func mapPreview(_ result: CsvImportResult, existingKeys: Set<String>, rates: [String: [(day: Int, rate: Double)]],
                           withdrawalsAsTransfer: Bool, usdtUsd: [Int: Decimal] = [:]) -> CsvImportPreview {
        CsvImportMapping.preview(result, existingKeys: existingKeys, withdrawalsAsTransfer: withdrawalsAsTransfer,
                                 usdtUsd: usdtUsd) { currency, day in
            guard let list = rates[currency], let r = PortfolioHistoryFx.rateOn(list, day: Int(day)) else { return nil }
            return PortfolioDecimal.of(r)
        }
    }

    static func tx(_ t: CsvImportTx) -> PortfolioTx {
        var tx = PortfolioTx(coin: t.coin, type: t.type, amountExact: t.amount, priceExact: t.priceUsdt, time: t.time,
                             note: t.note, feeExact: t.fee, feeUnit: t.feeUnit)
        tx.feeCoin = t.feeCoin
        tx.feeCoinAmountExact = t.feeCoinAmount
        tx.transferFrom = t.transferFrom
        tx.transferTo = t.transferTo
        tx.importKey = t.importKey
        // Verknüpfte Umbuchung zweier neuer Zeilen: Herkunft der Einzahlung
        tx.linkedImportKey = t.linkedImportKey
        // Tausch aus der Datei (erhaltener Coin, Menge, Wert) — wie `toEntity` (Android)
        tx.toCoin = t.toCoin
        tx.toAmountExact = t.toAmount
        tx.swapValueExact = t.swapValueUsdt
        // Gebühr-Zeile und Zins (eigene Merker)
        tx.feeRow = t.feeRow && t.type == .SELL
        tx.interest = t.interest && t.type == .REWARD
        return tx
    }

    static func formatName(_ format: CsvImportFormat) -> String { format.exchangeName ?? "CSV" }

    /// Importierte Aus-/Einzahlung bzw. Umbuchung als mögliches Gegenstück (`CsvTransferMatch`); sonst nil — wie Android
    /// `toStoredMove`.
    static func storedMove(_ tx: PortfolioTx) -> CsvStoredMove? {
        guard let key = tx.importKey, tx.type == .WITHDRAWAL || tx.type == .DEPOSIT || tx.type == .TRANSFER else { return nil }
        return CsvStoredMove(id: tx.id, coin: tx.coin, type: tx.type, amount: tx.amountExact, fee: tx.feeExact,
                             feeUnit: tx.feeExact == nil ? nil : (tx.feeUnit ?? .QUOTE), feeCoin: tx.feeCoin,
                             feeCoinAmount: tx.feeCoinAmountExact, time: tx.time, importKey: key,
                             transferFrom: tx.transferFrom, transferTo: tx.transferTo, priceUsdt: tx.priceExact,
                             feeCoinPrice: tx.feeCoinPriceExact, acquiredTime: tx.acquiredTime, exchange: tx.exchange,
                             importBatch: tx.importBatch, linkedImportKey: tx.linkedImportKey)
    }

    /// Gespeicherte Transaktion als verknüpfte Umbuchung (`CsvStoredTransfer`) im Importlauf `batch` des verknüpfenden
    /// Imports (ihr Zustand davor samt früherem Lauf steht in `importRestoreState`) — wie Android `linkStored`.
    static func linked(_ tx: PortfolioTx, _ u: CsvStoredTransfer, batch: String) -> PortfolioTx {
        var out = tx
        out.type = .TRANSFER
        out.amountExact = u.amount
        out.priceExact = nil
        out.time = u.time
        out.feeExact = u.fee
        out.feeUnit = u.feeUnit
        out.exchange = nil
        out.transferFrom = u.transferFrom
        out.transferTo = u.transferTo
        if u.feeCoin != tx.feeCoin { out.feeCoinPriceExact = nil }
        out.feeCoin = u.feeCoin
        out.feeCoinAmountExact = u.feeCoinAmount
        out.importKey = u.importKey
        out.linkedImportKey = u.linkedImportKey
        out.importRestoreState = u.restoreState
        out.acquiredTime = nil
        out.importBatch = batch
        return out
    }

    static func skipKey(_ reason: CsvImportSkipReason) -> String {
        switch reason {
        case .UNSUPPORTED_KIND: return "portfolio_csv_skip_unsupported"
        case .NOT_CRYPTO: return "portfolio_csv_skip_not_crypto"
        case .BAD_NUMBER: return "portfolio_csv_skip_bad_number"
        case .BAD_DATE: return "portfolio_csv_skip_bad_date"
        case .MISSING_FIELD: return "portfolio_csv_skip_missing"
        case .FEE_EXCEEDS_AMOUNT: return "portfolio_csv_skip_fee_exceeds"
        case .TWO_FEES: return "portfolio_csv_skip_two_fees"
        case .INTERNAL_MOVE: return "portfolio_csv_skip_internal_move"
        case .POSSIBLE_DUPLICATE: return "portfolio_csv_skip_possible_duplicate"
        case .AMBIGUOUS_GROUP: return "portfolio_csv_skip_ambiguous"
        case .DUPLICATE_TXID_DIFFERENT: return "portfolio_csv_skip_duplicate_txid_different"
        case .STAKING_UNPAIRED: return "portfolio_csv_skip_staking_unpaired"
        }
    }
}
