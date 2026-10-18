import Foundation
import SwiftUI
import UniformTypeIdentifiers

// Stichtag-Export (z. B. Wertschriftenverzeichnis per 31.12.) — wie `CutoffExport.kt`,
// `HistoricPriceSource.kt` und `PortfolioExportViewModel.kt` der Android-Fassung. Hier Typen und Ablauf; Rechnung
// und CSV in CutoffExport.swift, historische Kurse in HistoricPriceSource.swift.

/// Kalendertag ohne Uhrzeit (Jahr, Monat, Tag).
struct CutoffDay: Hashable, Sendable {
    let year: Int
    let month: Int
    let day: Int

    init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    /// Gregorianisch in der Zeitzone des Geräts — nicht der Kalender des Nutzers: Jahr, Monat und Tag
    /// werden gregorianisch weiterverwendet (`start(in:)`, «2025-12-31»); mit dem japanischen Kalender
    /// wäre das Jahr sonst z. B. 8 statt 2026.
    static var localCalendar: Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        return cal
    }

    /// Tag von `date` im Kalender `calendar` (Standard: gregorianisch, Zeitzone des Geräts).
    init(_ date: Date, calendar: Calendar = CutoffDay.localCalendar) {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        self.init(year: c.year ?? 1970, month: c.month ?? 1, day: c.day ?? 1)
    }

    /// Standardstichtag: 31. Dezember des Vorjahrs.
    static func defaultDay(today: Date, calendar: Calendar = CutoffDay.localCalendar) -> CutoffDay {
        CutoffDay(year: (calendar.dateComponents([.year], from: today).year ?? 1971) - 1, month: 12, day: 31)
    }

    /// «2025-12-31»
    var iso: String { String(format: "%04d-%02d-%02d", year, month, day) }

    /// Beginn des Tags in `timeZone`.
    func start(in timeZone: TimeZone) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        return cal.date(from: DateComponents(year: year, month: month, day: day)) ?? Date(timeIntervalSince1970: 0)
    }

    /// Letzte Millisekunde des Tags in `timeZone`.
    func endOfDayMillis(in timeZone: TimeZone) -> Int64 {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let dayStart = self.start(in: timeZone)
        let next = cal.date(byAdding: .day, value: 1, to: dayStart) ?? dayStart.addingTimeInterval(86_400)
        return Int64((next.timeIntervalSince1970 * 1000).rounded()) - 1
    }

    /// UTC-Mitternacht (Beginn der Binance-/Coinbase-Tageskerze).
    var utcStartMillis: Int64 {
        let utcStart = self.start(in: TimeZone(identifier: "UTC") ?? .gmt)
        return Int64((utcStart.timeIntervalSince1970 * 1000).rounded())
    }

    /// 12:00 Ortszeit — für die Datumswahl.
    var noonLocal: Date { start(in: .current).addingTimeInterval(12 * 3600) }
}

/// Bestand eines Coins am Stichtag, exakt aus der Buchhaltung (z. B. 0.00000001 BTC).
struct CutoffHolding: Equatable, Sendable {
    let coin: String
    let amount: Decimal
}

/// Eine Zeile des Exports, alle Zahlen exakt (gerundet wird erst im Text). nil = nicht bekannt.
struct CutoffRow: Equatable, Sendable {
    let coin: String
    let amount: Decimal
    /// Tagesschlusskurs in USDT.
    let priceUsdt: Decimal?
    /// `amount` × `priceUsdt` (34 gültige Stellen).
    let valueUsdt: Decimal?
    /// USD → Zielwährung.
    let rate: Decimal?
    /// `valueUsdt` × `rate` (34 gültige Stellen).
    let valueTarget: Decimal?
    let noPrice: Bool
    let noFx: Bool
    /// Kurs nicht als derselbe Token bestätigt (`PricePlausibility.acceptSourceClose`) — Feld leer.
    var unchecked: Bool = false
    /// Guthaben einer beobachteten Adresse (`CutoffExport.addressRows`) — eigene Zeile «aus Adresse – ohne Kaufpreis».
    var fromAddress: Bool = false
    /// Adresse am Stichtag (noch) nicht beobachtet: ihr Guthaben fehlt; ohne beobachteten Teil keine Menge.
    var addressUnobserved: Bool = false
}

/// Tagesschlüsse des Stichtags: `prices` Coin → Kurs in USDT; `unchecked` = Coins, deren Kurs mangels
/// passendem aktuellem Kurs nicht als derselbe Token bestätigt werden konnte (Hinweis «ungeprüft»).
struct CutoffCloses: Equatable, Sendable {
    /// Exakt aus der kürzesten Darstellung des Quellkurses (`PortfolioDecimal.of`).
    var prices: [String: Decimal] = [:]
    var unchecked: Set<String> = []
}

/// Übersetzte Texte für die CSV-Datei.
struct CutoffCsvTexts: Sendable {
    var coin: String
    var amount: String
    var priceUsdt: String
    var valueUsdt: String
    /// Bereits mit der Zielwährung, z. B. «Kurs USD→CHF».
    var rate: String
    /// Bereits mit der Zielwährung, z. B. «Wert CHF».
    var valueTarget: String
    var note: String
    var total: String
    var noPrice: String
    var noFx: String
    /// «ungeprüft (kein passender Kurs)».
    var unchecked: String
    var incomplete: String
    /// Kommentarzeile; 1. = Stichtag, 2. = Währung, 3. = Datum des Devisenkurses (je `%@`).
    var commentFormat: String
    /// Hinweis ESTV-Kursliste, 1. = Jahr, 2. = Link (`EstvKursliste`, je `%@`); nil = kein Hinweis.
    var estvNoteFormat: String? = nil
    /// Sprache des ESTV-Links (z. B. «de»).
    var estvLanguage: String = "en"
    /// Hinweis einer Adress-Zeile, z. B. «aus Adresse – ohne Kaufpreis».
    var fromAddress: String = ""
    /// Hinweis, wenn eine Adresse am Stichtag nicht beobachtet war (Guthaben fehlt).
    var addressUnobserved: String = ""
    /// Zeile «USDT = 1 angenommen» (kein USDT/USD-Kurs am Stichtag, `CutoffExport.csv` `usdtApproximate`); leer = keine.
    var usdtAssumed: String = ""
    /// Zeile «Adressen nicht lesbar – Guthaben fehlen» (`CutoffExport.csv` `addressesUnreadable`); leer = keine.
    var addressesUnreadable: String = ""

    /// Aus den App-Texten, Spaltentitel mit der Zielwährung.
    static func localized(currency: String) -> CutoffCsvTexts {
        CutoffCsvTexts(
            coin: L("portfolio_export_col_coin"),
            amount: L("portfolio_export_col_amount"),
            priceUsdt: L("portfolio_export_col_price"),
            valueUsdt: L("portfolio_export_col_value"),
            rate: L("portfolio_export_col_rate", currency),
            valueTarget: L("portfolio_export_col_value_cur", currency),
            note: L("portfolio_export_col_note"),
            total: L("portfolio_export_total"),
            noPrice: L("portfolio_export_no_price"),
            noFx: L("portfolio_export_no_fx"),
            unchecked: L("portfolio_export_unchecked"),
            incomplete: L("portfolio_export_incomplete"),
            commentFormat: L("portfolio_export_comment"),
            estvNoteFormat: L("portfolio_export_estv_note"),
            // Sprache der App-Texte (wie Android: Sprache der Oberfläche)
            estvLanguage: Bundle.main.preferredLocalizations.first ?? "en",
            // Beobachtete Adressen (zählen im Portfolio) als eigene Zeilen
            fromAddress: L("portfolio_export_from_address"),
            addressUnobserved: L("portfolio_export_address_unobserved"),
            usdtAssumed: L("portfolio_usdt_assumed"),
            addressesUnreadable: L("portfolio_export_addresses_unreadable")
        )
    }
}

/// Zelle einer CSV-Zeile: Text (gegen Formel-Injektion geschützt, `CutoffExport.escape`) oder Zahl (`numeric`, vom
/// Zahlenformat der App — bleibt unverändert, damit «-12.50» eine Zahl bleibt). Wie `CsvCell` (Android).
struct CsvCell: Equatable, Sendable {
    let text: String
    var numeric = false
}

/// Trenn- und Dezimalzeichen der CSV, so dass Excel der Region die Datei richtig aufteilt
/// und die Zahlen als Zahlen liest. Wie `CutoffCsvFormat` (Android).
struct CutoffCsvFormat: Equatable, Sendable {
    let separator: Character
    let decimal: Character

    /// «;» + «.» (z. B. Excel Schweiz).
    static let semicolonPoint = CutoffCsvFormat(separator: ";", decimal: ".")
    static let semicolonComma = CutoffCsvFormat(separator: ";", decimal: ",")
    static let commaPoint = CutoffCsvFormat(separator: ",", decimal: ".")

    private static let semicolonRegions: Set<String> = ["CH", "LI"]

    /// Excel-Konvention je Region (`region` = Ländercode, `decimalSeparator` = Dezimalzeichen der
    /// Zahleneingabe): Schweiz/Liechtenstein trennen in Excel mit «;», auch mit Dezimalpunkt
    /// (de-CH «;» + «.»); sonst Dezimalkomma → «;» + «,», Dezimalpunkt (und anderes) → «,» + «.».
    static func forLocale(region: String, decimalSeparator: Character) -> CutoffCsvFormat {
        let comma = decimalSeparator == ","
        if semicolonRegions.contains(region.uppercased()) { return comma ? semicolonComma : semicolonPoint }
        return comma ? semicolonComma : commaPoint
    }

    /// Format für Region und Dezimalzeichen des Geräts (`Locale.current`, wie die Eingaben).
    static var current: CutoffCsvFormat {
        forLocale(region: Locale.current.region?.identifier ?? "", decimalSeparator: ThresholdParser.localeDecimalSeparator)
    }
}

// MARK: Ablauf

enum PortfolioCutoffExporter {
    /// Bestand am Ende des Tags (Zeitzone des Geräts), Kurse und Devisenkurs holen, CSV bauen.
    /// Bestand nach derselben Einstandsmethode `method` wie Karte, Verlauf und Steuer-Export.
    /// `addresses`: Reihen der beobachteten Adressen, die im Portfolio zählen (`AddressBalanceHistory.of`).
    /// `addressesUnreadable`: Speicher der Adressen nicht lesbar (`WatchedAddressStore.loadChecked`, `intact` false) —
    /// in der Datei vermerkt, Total «unvollständig» (wie Android).
    /// `header`: Kopfzeilen davor (Bestand der Arbeitsunterlage), leer = keine. `zone`: Ende des Tags in dieser Zeitzone —
    /// für den Bestand der Arbeitsunterlage die der Vorlage, sonst die des Geräts (wie Android).
    static func build(transactions: [PortfolioTx], day: CutoffDay, currency: String, texts: CutoffCsvTexts,
                      method: CostMethod = .defaultMethod, addresses: [AddressBalanceHistory] = [],
                      addressesUnreadable: Bool = false,
                      header: [String] = [], zone: TimeZone = .current) async -> String {
        let cutoff = day.endOfDayMillis(in: zone)
        let holdings = CutoffExport.holdingsAt(transactions, cutoffMillis: cutoff, method: method)
        // Beobachtete Adressen, die im Portfolio zählen: heute das aktuelle, früher das an dem Tag beobachtete Guthaben
        // (eigene Zeilen «aus Adresse – ohne Kaufpreis») — wie Android
        let addressAt = AddressBalanceHistory.balancesAt(addresses, time: cutoff)
        // USDT = 1 ohne Abfrage; andere Stablecoins wie jeder Coin (fehlt der Kurs: «ohne Kurs»)
        var seenCoins: Set<String> = []
        let coins = (holdings.map(\.coin) + addressAt.balances.keys.sorted())
            .filter { seenCoins.insert($0).inserted && PortfolioStables.needsQuote($0) }
        // Aktuelle Kurse als Bezug der Identitätsprüfung (jede Quelle); ohne passenden Bezug wird kein
        // Tagesschluss übernommen («ungeprüft») — auch nicht, wenn das Laden ganz scheitert
        var current: [String: Double] = [:]
        if !coins.isEmpty {
            let live = await PortfolioPriceSource.prices(coins)
            current = CutoffExport.referencePrices(live.prices, uncheckedCurrent: live.unchecked)
        }
        let closes = await HistoricPriceSource.dailyClosesUsdt(coins, day: day, current: current)
        var fx: HistoricFx?
        if !holdings.isEmpty || !addressAt.balances.isEmpty || currency == "USD" {
            fx = await HistoricPriceSource.usdTo(currency, day: day)
        }
        // Devisenkurs exakt über die kürzeste Darstellung (= Text der Quelle, z. B. «0.79123»); USDT zum Marktkurs:
        // × USDT/USD des Stichtags (heute live, sonst Tagesschluss) — ohne Kurs 1, in der Datei vermerkt
        let usdt = await cutoffUsdtUsd(currency, day: day)
        let rate = PortfolioDecimal.of(fx?.rate).map { r in usdt.map { r * $0 } ?? r }
        let rows = CutoffExport.rows(holdings, prices: closes.prices, fxRate: rate, unchecked: closes.unchecked)
            + CutoffExport.addressRows(addressAt, prices: closes.prices, fxRate: rate, unchecked: closes.unchecked)
        // Trenn-/Dezimalzeichen wie Excel der Region (de-CH «;» + «.», de-DE «;» + «,», en «,» + «.»)
        let usdtApproximate = rate != nil && usdt == nil && CutoffExport.convertsUsdt(currency)
        return CutoffExport.csv(day: day, currency: currency, fxDate: fx?.date, rows: rows, texts: texts,
                                format: .current, header: header, usdtApproximate: usdtApproximate,
                                addressesUnreadable: addressesUnreadable)
    }

    /// USDT/USD am Stichtag `day`: heute der Live-Kurs, sonst der Tagesschluss DES Tags (plausibel, `TaxExport.usdtUsdOn`);
    /// nil = keiner (dann 1, in der Datei vermerkt) oder keine USDT-Umrechnung. Wie Android `cutoffUsdtUsd`.
    private static func cutoffUsdtUsd(_ currency: String, day: CutoffDay) async -> Decimal? {
        guard CutoffExport.convertsUsdt(currency) else { return nil }
        let date = LocalDay(day.year, day.month, day.day)
        if date >= LocalDay.today() { return DepegAlarmCheck.liveUsdtUsd().flatMap { PortfolioDecimal.of($0) } }
        let closes = await PortfolioTaxExporter.usdtUsdCloses(currency, from: date)
        return TaxExport.usdtUsdOn(closes, date: date)
    }
}

/// CSV-Datei für `.fileExporter`.
struct CutoffCsvDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.commaSeparatedText] }
    static var writableContentTypes: [UTType] { [.commaSeparatedText] }

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
