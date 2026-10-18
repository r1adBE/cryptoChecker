import Foundation
import SwiftUI
import UniformTypeIdentifiers

// Stichtag-Export (z. B. Wertschriftenverzeichnis per 31.12.) — wie `CutoffExport.kt`,
// `HistoricPriceSource.kt` und `PortfolioExportViewModel.kt` der Android-Fassung.

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
            usdtAssumed: L("portfolio_usdt_assumed")
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

/// Reine Rechnung und CSV: UTF-8 mit BOM (für Excel), Zeilenende CRLF; Trenn- und
/// Dezimalzeichen je Region (`CutoffCsvFormat`, Standard «;» + «.»).
enum CutoffExport {
    /// Stablecoins: die eine Liste aus `PortfolioStables` (Tagesschluss, sonst kein Wert; USDT immer 1).
    static var stables: Set<String> { PortfolioStables.coins }

    private static let bom = "\u{FEFF}"
    private static let eol = "\r\n"
    private static let posix = Locale(identifier: "en_US_POSIX")

    static func isStable(_ coin: String) -> Bool { PortfolioStables.isStable(coin) }

    static func fileName(_ day: CutoffDay) -> String { "cryptochecker-stichtag-\(day.iso).csv" }

    /// Eigener Bestand je Coin aus allen Transaktionen mit Zeit ≤ `cutoffMillis` nach der Einstandsmethode `method`
    /// (`PortfolioCalculator.holdingsAt` — dieselben Mengen wie Karte und Wertverlauf, auch FIFO je Wallet); ohne Bestand
    /// fällt weg. Alphabetisch. Adress-Guthaben: `addressRows`. Wie Android.
    static func holdingsAt(_ trades: [PortfolioTx], cutoffMillis: Int64,
                           method: CostMethod = .defaultMethod) -> [CutoffHolding] {
        PortfolioCalculator.holdingsAt(trades, time: cutoffMillis, method: method)
            .map { CutoffHolding(coin: $0.coin, amount: $0.amount) }
            .sorted { $0.coin < $1.coin }
    }

    /// Bezugskurse der Identitätsprüfung aus den aktuellen Portfolio-Kursen `current`: nur gültige,
    /// ohne ungeprüfte Kerzenkurse (`uncheckedCurrent`, kein unabhängiger Bezug). Fehlt ein Coin, wird
    /// sein Tagesschluss nicht übernommen («ungeprüft»).
    static func referencePrices(_ current: [String: Double], uncheckedCurrent: Set<String>) -> [String: Double] {
        current.filter { coin, price in !uncheckedCurrent.contains(coin) && price > 0 && price.isFinite }
    }

    /// Zeilen aus Bestand, Kursen (Coin → Tagesschluss in USDT) und Devisenkurs (nil = unbekannt), alles
    /// exakt: Wert = Menge × Kurs, Zielwert = Wert × Devisenkurs (je 34 gültige Stellen, wie Android).
    /// Stablecoins nach `PortfolioStables.price`: USDT = 1, die übrigen ihr Tagesschluss, fehlt er,
    /// «ohne Kurs» wie jeder Coin. Coins in `unchecked` (ausser USDT): kein Kurs, Hinweis «ungeprüft».
    static func rows(_ holdings: [CutoffHolding], prices: [String: Decimal], fxRate: Decimal?,
                     unchecked: Set<String> = []) -> [CutoffRow] {
        let rate = fxRate.flatMap { $0 > 0 && $0.isFinite ? $0 : nil }
        return holdings.map { h in
            let notChecked = unchecked.contains(h.coin) && PortfolioStables.needsQuote(h.coin)
            let price = notChecked ? nil : stablePrice(h.coin, market: prices[h.coin])
            let value = price.map { PortfolioDecimal.multiply(h.amount, $0) }
            var target: Decimal?
            if let value, let rate { target = PortfolioDecimal.multiply(value, rate) }
            return CutoffRow(
                coin: h.coin, amount: h.amount, priceUsdt: price, valueUsdt: value,
                rate: rate, valueTarget: target, noPrice: price == nil && !notChecked, noFx: rate == nil,
                unchecked: notChecked
            )
        }
    }

    /// Adress-Zeilen am Stichtag aus `AddressBalanceHistory.balancesAt` (nur Adressen, die im Portfolio zählen — nicht
    /// «Nur anzeigen»): je Coin eine eigene Zeile «aus Adresse – ohne Kaufpreis» (`fromAddress`), bewertet wie die
    /// übrigen (`rows`). Stichtag heute: das zuletzt beobachtete (aktuelle) Guthaben; früher: das an dem Tag beobachtete.
    /// Coins, deren Adresse erst später beobachtet wurde (`unobserved`), stehen mit Hinweis und ohne fehlenden Teil da
    /// (`addressUnobserved`) — nie rückwirkend geschätzt. Wie Android.
    static func addressRows(_ at: AddressBalancesAt, prices: [String: Decimal], fxRate: Decimal?,
                            unchecked: Set<String> = []) -> [CutoffRow] {
        let holdings = at.balances.map { CutoffHolding(coin: $0.key, amount: $0.value) }.sorted { $0.coin < $1.coin }
        let priced = rows(holdings, prices: prices, fxRate: fxRate, unchecked: unchecked).map { r -> CutoffRow in
            var row = r
            row.fromAddress = true
            row.addressUnobserved = at.unobserved.contains(r.coin)
            return row
        }
        let rate = fxRate.flatMap { $0 > 0 && $0.isFinite ? $0 : nil }
        let missing = at.unobserved.filter { at.balances[$0] == nil }.sorted().map { coin in
            CutoffRow(coin: coin, amount: 0, priceUsdt: nil, valueUsdt: nil, rate: rate, valueTarget: nil,
                      noPrice: false, noFx: rate == nil, fromAddress: true, addressUnobserved: true)
        }
        return priced + missing
    }

    /// Wie `PortfolioStables.price`, exakt: USDT = 1, sonst nur ein positiver Kurs.
    private static func stablePrice(_ coin: String, market: Decimal?) -> Decimal? {
        if !PortfolioStables.needsQuote(coin) { return 1 }
        guard let market, market > 0, market.isFinite else { return nil }
        return market
    }

    /// Rechnet der Export in `currency` USDT in Fiat um (USDT zum Marktkurs: Devisenkurs × USDT/USD)? Für jede Währung
    /// ausser USDT selbst — auch USD. Wie Android `convertsUsdt`.
    static func convertsUsdt(_ currency: String) -> Bool {
        currency.trimmingCharacters(in: .whitespaces).uppercased() != PortfolioStables.valuation
    }

    /// Ganze CSV-Datei (mit BOM). `fxDate` = tatsächliches Kursdatum (nil → «—»).
    /// `header` = Kopfzeilen davor (Arbeitsunterlage: Vorlage, Methode, Steuerjahr, Hinweis), je ein Feld; leer = keine.
    /// `usdtApproximate`: Kurs ohne USDT/USD am Stichtag (USDT = 1 als Näherung) — Zeile `texts.usdtAssumed` nach dem Kommentar.
    static func csv(day: CutoffDay, currency: String, fxDate: String?, rows: [CutoffRow], texts: CutoffCsvTexts,
                    format: CutoffCsvFormat = .semicolonPoint, header: [String] = [],
                    usdtApproximate: Bool = false) -> String {
        // Zahlen ohne Tausendertrennung: Dezimalpunkt ggf. durch Komma ersetzen
        func n(_ text: String) -> CsvCell {
            CsvCell(text: format.decimal == "," ? text.replacingOccurrences(of: ".", with: ",") : text, numeric: true)
        }
        func t(_ text: String) -> CsvCell { CsvCell(text: text) }
        func line(_ fields: [String]) -> String { joinCells(fields.map { t($0) }, separator: format.separator) + eol }
        func cells(_ fields: [CsvCell]) -> String { joinCells(fields, separator: format.separator) + eol }
        var out = bom
        for h in header { out += line([h]) }
        let comment = String(format: texts.commentFormat, locale: posix, day.iso, currency, fxDate ?? "—")
        out += line([comment])
        // USDT zum Marktkurs, aber am Stichtag ohne USDT/USD-Kurs: Näherung 1 nie still
        if usdtApproximate && !texts.usdtAssumed.isEmpty { out += line([texts.usdtAssumed]) }
        // In CHF per 31.12.: Hinweis auf die massgebende ESTV-Kursliste des Jahrs
        if let estv = EstvKursliste.noteLine(format: texts.estvNoteFormat, day: day, currency: currency,
                                             language: texts.estvLanguage) {
            out += line([estv])
        }
        out += line([texts.coin, texts.amount, texts.priceUsdt, texts.valueUsdt, texts.rate, texts.valueTarget, texts.note])
        for r in rows {
            var notes: [String] = []
            if r.fromAddress && !texts.fromAddress.isEmpty { notes.append(texts.fromAddress) }
            if r.addressUnobserved && !texts.addressUnobserved.isEmpty { notes.append(texts.addressUnobserved) }
            if r.noPrice { notes.append(texts.noPrice) }
            if r.unchecked { notes.append(texts.unchecked) }
            if r.noFx { notes.append(texts.noFx) }
            // Adresse nicht beobachtet und kein beobachteter Teil: keine Menge
            let noAmount: Bool = r.addressUnobserved && r.amount == Decimal(0)
            let amountText: String = noAmount ? "" : amount(r.amount)
            out += cells([
                t(r.coin),
                n(amountText),
                n(r.priceUsdt.map { price($0) } ?? ""),
                n(r.valueUsdt.map { money($0) } ?? ""),
                n(r.rate.map { rate($0) } ?? ""),
                n(r.valueTarget.map { money($0) } ?? ""),
                t(notes.joined(separator: ", ")),
            ])
        }

        // Total: Summe der bekannten Werte; fehlt irgendwo ein Kurs, steht «unvollständig» dabei
        // Summen exakt; gerundet wird erst im Text
        let totalUsdt = rows.reduce(Decimal(0)) { $0 + ($1.valueUsdt ?? 0) }
        let anyValue = rows.contains { $0.valueUsdt != nil }
        let anyTarget = rows.contains { $0.valueTarget != nil }
        let totalTarget = rows.reduce(Decimal(0)) { $0 + ($1.valueTarget ?? 0) }
        var totalNotes: [String] = []
        if rows.contains(where: { $0.noPrice || $0.unchecked || $0.addressUnobserved }) { totalNotes.append(texts.incomplete) }
        if !rows.isEmpty && rows.allSatisfy({ $0.noFx }) { totalNotes.append(texts.noFx) }
        out += cells([
            t(texts.total),
            n(""),
            n(""),
            n((anyValue || rows.isEmpty) ? money(totalUsdt) : ""),
            n(rows.first?.rate.map { rate($0) } ?? ""),
            n(anyTarget ? money(totalTarget) : ""),
            t(totalNotes.joined(separator: ", ")),
        ])
        return out
    }

    /// Zeile aus Zellen ohne Zeilenende: Text über `escape`, Zahlen nur über `escapeNumber` — wie Android.
    static func joinCells(_ cells: [CsvCell], separator: Character) -> String {
        cells.map { $0.numeric ? escapeNumber($0.text, separator: separator) : escape($0.text, separator: separator) }
            .joined(separator: String(separator))
    }

    /// Erste Zeichen, mit denen Excel/LibreOffice/Sheets eine Zelle als Formel lesen (OWASP «CSV Injection»).
    private static let formulaStart: Set<Character> = ["=", "+", "-", "@", "\t", "\r", "\r\n"]

    /// TEXT-Feld: beginnt es mit = + - @ (oder Tab/CR), wird ein Apostroph vorangestellt (keine Formel beim Öffnen); dann
    /// in Anführungszeichen, wenn es das Trennzeichen, «"» oder einen Zeilenumbruch enthält. Zahlen gehen über
    /// `escapeNumber` (eine negative Zahl «-12.50» bleibt eine Zahl). Wie Android.
    static func escape(_ field: String, separator: Character = ";") -> String {
        let safe: String
        if let first = field.first, formulaStart.contains(first) { safe = "'" + field } else { safe = field }
        return quote(safe, separator: separator)
    }

    /// ZAHL-Feld (vom Zahlenformat): nur in Anführungszeichen, wenn nötig — nie mit Apostroph.
    static func escapeNumber(_ field: String, separator: Character = ";") -> String { quote(field, separator: separator) }

    private static func quote(_ field: String, separator: Character) -> String {
        guard field.contains(where: { $0 == separator || $0 == "\"" || $0 == "\n" || $0 == "\r" || $0 == "\r\n" }) else {
            return field
        }
        return "\"" + field.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    // MARK: Zahlen (Dezimalpunkt, ohne Tausendertrennung; Komma erst in `csv`)

    // Gerundet wird nur hier, kaufmännisch (HALF_UP, `.plain`) ab dem exakten Wert, gleich wie Android
    // (`CutoffExport.kt`): 1.005 → «1.01». Double-Fassungen rechnen mit der kürzesten Dezimaldarstellung
    // (`PortfolioDecimal.of`); nicht endlich → leeres Feld.

    /// Menge (Double): bis 10 Nachkommastellen, unter 1 bis 10 gültige Stellen (3e-11 → «0.00000000003»), ohne Nullen am Ende.
    static func amount(_ v: Double) -> String { PortfolioDecimal.of(v).map { price($0) } ?? "" }
    /// Exakte Menge: alle Nachkommastellen (höchstens 18), ohne Exponent und Nullen am Ende («0.00000001»).
    static func amount(_ v: Decimal) -> String { PortfolioDecimal.text(v) }
    /// Kurs: bis 10 Nachkommastellen, unter 1 bis 10 gültige Stellen — auch Kleinstkurse (3e-11) bleiben lesbar statt «0».
    static func price(_ v: Double) -> String { PortfolioDecimal.of(v).map { price($0) } ?? "" }
    static func price(_ v: Decimal) -> String {
        guard v.isFinite, !v.isNaN else { return "" }
        return plain(v, scale: max(10, 10 - integerDigits(v)))
    }
    /// Devisenkurs: bis 6 Nachkommastellen.
    static func rate(_ v: Double) -> String { PortfolioDecimal.of(v).map { rate($0) } ?? "" }
    static func rate(_ v: Decimal) -> String { plain(v, scale: 6) }
    /// Geldbetrag: genau 2 Nachkommastellen; «-0.00» → «0.00».
    static func money(_ v: Double) -> String { PortfolioDecimal.of(v).map { money($0) } ?? "" }
    static func money(_ v: Decimal) -> String {
        let text = plain(v, scale: 2)
        guard !text.isEmpty else { return "" }
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        let frac = parts.count > 1 ? String(parts[1]) : ""
        let zeros = String(repeating: "0", count: max(0, 2 - frac.count))
        let whole = String(parts[0])
        return whole + "." + frac + zeros
    }

    /// Auf `scale` Nachkommastellen (bei der Hälfte weg von 0), ohne Exponent und Nullen am Ende; «-0» → «0».
    private static func plain(_ v: Decimal, scale: Int) -> String {
        guard v.isFinite, !v.isNaN else { return "" }
        var value = v
        var rounded = Decimal()
        NSDecimalRound(&rounded, &value, scale, .plain)
        if rounded == 0 || rounded.isNaN { return "0" }
        NSDecimalCompact(&rounded)
        var text = NSDecimalString(&rounded, posix)
        // Sicherheit: Nullen am Ende entfernen (NSDecimalString schreibt ohne Exponent)
        if text.contains(".") {
            while text.hasSuffix("0") { text.removeLast() }
            if text.hasSuffix(".") { text.removeLast() }
        }
        return text
    }

    /// Stellen vor dem Komma (ohne führende Nullen): 123.4 → 3, 0.5 → 0, 0.000123 → −3; 0 → 0.
    private static func integerDigits(_ v: Decimal) -> Int {
        guard v != 0, v.isFinite, !v.isNaN else { return 0 }
        var compact = v
        NSDecimalCompact(&compact)
        // Ziffern der Mantisse (ohne Nullen am Ende) + Exponent
        var mantissa = compact.significand
        let count = NSDecimalString(&mantissa, posix).filter(\.isNumber).count
        return count + Int(compact.exponent)
    }
}

// MARK: Historische Kurse

/// Devisenkurs eines Stichtags mit dem tatsächlichen Kursdatum (Wochenende → Freitag).
struct HistoricFx: Sendable {
    let rate: Double
    let date: String?
}

/// Tagesschlusskurse (UTC-Tageskerze) in USDT und Devisenkurs eines Tags.
/// Kerzen-Kette wie `CandleDataSource`: data-api.binance.vision → api.binance.com →
/// fapi.binance.com → api.binance.us (USDT, sonst USD) → Coinbase (USD, sonst USDC).
/// Es zählt nur die Kerze, die genau an diesem Tag beginnt. Kurse ohne Zwischenspeicher.
/// Identitätsprüfung (`PricePlausibility.acceptSourceClose`): jede Quelle (auch Binance-Spot) zählt
/// nur, wenn sie den Coin heute als denselben Token führt wie das Portfolio (ihr jüngster Schluss
/// gegen den aktuellen Kurs, 1/3…3; Ausweich-Quellen ±25 %; eine Abfrage mehr, nur bei einem Treffer
/// dort); sonst nächste Quelle. Ohne gültigen aktuellen Kurs keine Abfrage, der Coin ist «ungeprüft»
/// (`CutoffCloses.unchecked`), ebenso wenn nur nicht passende Kurse gefunden wurden.
/// Stablecoins nach `PortfolioStables`: USDT = 1 ohne Abfrage, andere wie jeder Coin.
/// Dazu die Devisen-Tageskurse eines Zeitraums für den Wertverlauf (`usdToSeries`, Frankfurter,
/// 6 h im Speicher).
enum HistoricPriceSource {
    private static let parallel = 4

    /// Eigene Sitzung mit ~10 s Zeitgrenze je Anfrage.
    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 10
        c.timeoutIntervalForResource = 15
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = [HttpUserAgent.header: HttpUserAgent.value, "Accept": "application/json"]
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    /// Schlusskurse der Coins am Tag `day`; Coins ohne bestätigten Kurs fehlen in den Preisen.
    /// `current`: Bezugskurse in USDT für die Identitätsprüfung (`CutoffExport.referencePrices`);
    /// fehlt einer (auch: Laden der aktuellen Kurse ganz gescheitert), ist der Coin «ungeprüft».
    static func dailyClosesUsdt(_ coins: [String], day: CutoffDay, current: [String: Double]) async -> CutoffCloses {
        var symbols: [String] = []
        for c in coins.map(PortfolioCalculator.normalizeCoin) where isAsset(c) && !symbols.contains(c) {
            symbols.append(c)
        }
        var result = CutoffCloses()
        // In Gruppen zu je `parallel` Abfragen
        var index = 0
        while index < symbols.count {
            let chunk = Array(symbols[index..<min(index + parallel, symbols.count)])
            index += parallel
            let found = await withTaskGroup(of: (String, CloseResult).self, returning: CutoffCloses.self) { group in
                for coin in chunk {
                    group.addTask {
                        let r = await dailyCloseUsdt(coin, day: day, current: current[coin])
                        return (coin, r)
                    }
                }
                var partial = CutoffCloses()
                for await (coin, r) in group {
                    // Kerzenkurse kommen als Double: exakt über die kürzeste Darstellung (= Text der Quelle)
                    if let price = PortfolioDecimal.of(r.price) { partial.prices[coin] = price }
                    if r.unchecked { partial.unchecked.insert(coin) }
                }
                return partial
            }
            result.prices.merge(found.prices) { _, new in new }
            result.unchecked.formUnion(found.unchecked)
        }
        return result
    }

    /// Ergebnis je Coin: bestätigter Kurs, sonst `unchecked` = Kurs nicht als derselbe Token bestätigt.
    struct CloseResult: Sendable {
        var price: Double?
        var unchecked = false
    }

    /// Schlusskurs eines Coins (UTC-Tageskerze). USDT = 1, andere Stablecoins werden wie jeder Coin
    /// abgefragt. `current` = aktueller Portfolio-Kurs (Bezug der Identitätsprüfung); ohne gültigen
    /// Bezug keine Abfrage, «ungeprüft».
    static func dailyCloseUsdt(_ coin: String, day: CutoffDay, current: Double?) async -> CloseResult {
        let b = PortfolioCalculator.normalizeCoin(coin)
        if !PortfolioStables.needsQuote(b) { return CloseResult(price: 1.0) }
        guard isAsset(b) else { return CloseResult(price: nil) }
        // Streng: ohne Bezug ist der gleiche Token nicht bestätigt — auch nicht über Ausweich-Quellen
        guard let reference = current, reference > 0, reference.isFinite else {
            return CloseResult(price: nil, unchecked: true)
        }
        let start = day.utcStartMillis
        // Gab es einen Kurs, der die Prüfung nicht bestand?
        var rejected = false

        // Binance-Spot: Identitätsprüfung 1/3…3; Futures und Ausweich-Quellen zusätzlich ±25 %
        let binance: [(host: String, endpoint: String, primary: Bool)] = [
            ("data-api.binance.vision", "https://data-api.binance.vision/api/v3/klines", true),
            ("api.binance.com", "https://api.binance.com/api/v3/klines", true),
            ("fapi.binance.com", "https://fapi.binance.com/fapi/v1/klines", false),
        ]
        // Gleicher Token (K-8): Nennen die Paare der Merkliste den Coin klar anders als Binance, fällt Binance aus
        let names = TokenIdentityStore.shared
        let binanceOk = names.binanceAllowed(base: b, ownName: names.watchName(b, watches: SharedStorage.loadSnapshot().watches))
        if !binanceOk { rejected = true }
        for source in binance where binanceOk {
            let r = await binanceClose(host: source.host, endpoint: source.endpoint, symbol: b + "USDT",
                                       start: start, primary: source.primary, current: reference)
            if r.price != nil { return r }
            rejected = rejected || r.unchecked
        }
        for symbol in [b + "USDT", b + "USD"] {
            let r = await binanceClose(host: "api.binance.us", endpoint: "https://api.binance.us/api/v3/klines",
                                       symbol: symbol, start: start, primary: false, current: reference)
            if r.price != nil { return r }
            rejected = rejected || r.unchecked
        }
        let r = await coinbaseClose(b, day: day, start: start, current: reference)
        return r.price != nil ? r : CloseResult(price: nil, unchecked: rejected || r.unchecked)
    }

    private static func binanceClose(host: String, endpoint: String, symbol: String, start: Int64,
                                     primary: Bool, current: Double) async -> CloseResult {
        if BlockedSources.isBlocked(host) { return CloseResult(price: nil) }
        guard let body = await get(host, "\(endpoint)?symbol=\(symbol)&interval=1d&startTime=\(start)&limit=1"),
              let candles = try? CandleDataSource.parseBinance(body),
              let close = closeOf(candles, start: start)
        else { return CloseResult(price: nil) }
        // Führt die Quelle den Coin heute als denselben Token? (jüngste Tageskerze)
        var latest: Double?
        if let recent = await get(host, "\(endpoint)?symbol=\(symbol)&interval=1d&limit=1") {
            latest = (try? CandleDataSource.parseBinance(recent))?.last?.close
        }
        return PricePlausibility.acceptSourceClose(close, primary: primary, sourceLatest: latest, current: current)
            ? CloseResult(price: close) : CloseResult(price: nil, unchecked: true)
    }

    private static func coinbaseClose(_ b: String, day: CutoffDay, start: Int64, current: Double) async -> CloseResult {
        let host = "api.exchange.coinbase.com"
        // Fenster genau über den einen Tag (ISO 8601, UTC)
        let from = "\(day.iso)T00:00:00Z"
        let to = "\(day.iso)T23:59:59Z"
        var rejected = false
        for quote in ["USD", "USDC"] {
            if BlockedSources.isBlocked(host) { break }
            let url = "https://\(host)/products/\(b)-\(quote)/candles?granularity=86400&start=\(from)&end=\(to)"
            guard let body = await get(host, url),
                  let candles = try? CandleDataSource.parseCoinbase(body),
                  let close = closeOf(candles, start: start)
            else { continue }
            // Jüngste Tageskerzen desselben Produkts (ohne Zeitfenster = die neuesten)
            var latest: Double?
            if let recent = await get(host, "https://\(host)/products/\(b)-\(quote)/candles?granularity=86400") {
                latest = (try? CandleDataSource.parseCoinbase(recent))?.max(by: { $0.openTime < $1.openTime })?.close
            }
            if PricePlausibility.acceptSourceClose(close, primary: false, sourceLatest: latest, current: current) {
                return CloseResult(price: close)
            }
            rejected = true
        }
        return CloseResult(price: nil, unchecked: rejected)
    }

    private static func closeOf(_ candles: [MarketCandle], start: Int64) -> Double? {
        guard let close = candles.first(where: { $0.openTime == start })?.close, close > 0, close.isFinite else { return nil }
        return close
    }

    /// Devisenkurs USD → `currency` am Tag `day`; USD = 1 (ohne Datum). Frankfurter liefert
    /// an Wochenenden/Feiertagen den letzten Geschäftstag — dessen Datum steht in `date`.
    static func usdTo(_ currency: String, day: CutoffDay) async -> HistoricFx? {
        let target = currency.trimmingCharacters(in: .whitespaces).uppercased()
        if target == "USD" { return HistoricFx(rate: 1, date: nil) }
        guard FxRateSource.isCurrencyCode(target) else { return nil }
        if let fx = await loadUsdTo(target, day: day) { return fx }
        // Lew ab 2026: aus dem EUR-Kurs desselben Tags (fester Kurs 1.95583)
        guard target == "BGN", day.iso >= FxRateSource.bgnEuroDay,
              let eur = await loadUsdTo("EUR", day: day),
              let bgn = FxRateSource.derivedBgn(currency: target, usdToEur: eur.rate, dayIso: day.iso)
        else { return nil }
        return HistoricFx(rate: bgn, date: eur.date)
    }

    private static func loadUsdTo(_ target: String, day: CutoffDay) async -> HistoricFx? {
        let urls = [
            "https://api.frankfurter.app/\(day.iso)?from=USD&to=\(target)",
            "https://api.frankfurter.dev/v1/\(day.iso)?from=USD&to=\(target)",
        ]
        for url in urls {
            guard let body = try? await MarketHTTP.call(url, session: session),
                  let data = body.data(using: .utf8),
                  let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let rates = root["rates"] as? [String: Any],
                  let rate = (rates[target] as? NSNumber)?.doubleValue,
                  rate > 0, rate.isFinite
            else { continue }
            return HistoricFx(rate: rate, date: root["date"] as? String)
        }
        return nil
    }

    // MARK: Devisen-Tageskurse für den Wertverlauf

    private static let fxSeriesTtlMillis: Int64 = 6 * 60 * 60_000
    private static let fxSeriesLock = NSLock()
    /// «Währung|von|bis» → (Tageskurse, Abfragezeit); nur im Speicher.
    nonisolated(unsafe) private static var fxSeriesCache: [String: (rates: [Int: Double], at: Int64)] = [:]

    /// Tageskurse USD → `currency` von `from` bis `to` (EZB über Frankfurter, in Stücken von höchstens
    /// `PortfolioHistoryFx.fxChunkDays` Tagen): Tag (epochDay) → Kurs, nur Geschäftstage — Wochenenden und Feiertage
    /// füllt `PortfolioHistoryFx.rateOn` mit dem Vortag. USD: leer (kein Bedarf). 6 h im Speicher.
    /// nil, wenn kein Kurs zu haben ist oder ein Stück bei beiden Anbietern scheitert.
    static func usdToSeries(_ currency: String, from: LocalDay, to: LocalDay) async -> [Int: Double]? {
        let target = currency.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if target == "USD" { return [:] }
        guard FxRateSource.isCurrencyCode(target), from <= to else { return nil }
        let key = "\(target)|\(from)|\(to)"
        let now = TimeUtils.nowMillis
        let cached: [Int: Double]? = fxSeriesLock.withLock {
            guard let entry = fxSeriesCache[key], now - entry.at >= 0, now - entry.at < fxSeriesTtlMillis else { return nil }
            return entry.rates
        }
        if let cached { return cached }
        let symbols = PortfolioHistoryFx.requestCurrencies(target).joined(separator: ",")
        // In Stücken von höchstens einem Jahr: längere Zeiträume liefert Frankfurter nur wöchentlich. Scheitert ein Stück
        // bei beiden Anbietern, keine Reihe (lieber keine als eine mit einer Lücke von einem Jahr). Wie Android.
        // Ein Stück ohne Werktag (nur Wochenende bzw. nur heute) darf scheitern: dort zählt es als leer
        var merged: [Int: Double] = [:]
        let today = LocalDay.todayUTC()
        for chunk in PortfolioHistoryFx.fxChunks(from: from, to: to) {
            var fetched = await fxSeriesChunk(target, symbols: symbols, from: chunk.from, to: chunk.to)
            if fetched == nil && !PortfolioHistoryFx.chunkMayHaveRates(from: chunk.from, to: chunk.to, today: today) {
                fetched = [:]
            }
            guard let part = fetched else { return nil }
            merged.merge(part) { _, new in new }
        }
        guard !merged.isEmpty else { return nil }
        fxSeriesLock.withLock { fxSeriesCache[key] = (rates: merged, at: TimeUtils.nowMillis) }
        return merged
    }

    /// Ein Stück der Zeitreihe (`usdToSeries`) von beiden Frankfurter-Adressen: die erste Antwort mit Kursen, sonst eine
    /// gültige leere (z. B. Zeitraum ohne Kurse dieser Währung); nil, wenn keine Adresse eine gültige Antwort gibt.
    private static func fxSeriesChunk(_ target: String, symbols: String, from: LocalDay, to: LocalDay) async -> [Int: Double]? {
        let urls = [
            "https://api.frankfurter.app/\(from)..\(to)?from=USD&to=\(symbols)",
            "https://api.frankfurter.dev/v1/\(from)..\(to)?from=USD&to=\(symbols)",
        ]
        var answered = false
        for url in urls {
            guard let body = try? await MarketHTTP.call(url, session: session),
                  let data = body.data(using: .utf8),
                  let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let rates = root["rates"] as? [String: Any]
            else { continue }
            var byDate: [String: [String: Double]] = [:]
            for (date, value) in rates {
                guard let day = value as? [String: Any] else { continue }
                var parsed: [String: Double] = [:]
                for (code, rate) in day {
                    if let number = rate as? NSNumber { parsed[code] = number.doubleValue }
                }
                byDate[date] = parsed
            }
            answered = true
            let series = PortfolioHistoryFx.ratesByDay(target, byDate: byDate)
            if !series.isEmpty { return series }
        }
        return answered ? [:] : nil
    }

    /// GET; nil bei Fehler. 451/403 sperrt die Quelle vorübergehend (`BlockedSources`).
    private static func get(_ host: String, _ url: String) async -> String? {
        do {
            return try await MarketHTTP.call(url, session: session)
        } catch {
            BlockedSources.noteFailure(host, error)
            return nil
        }
    }

    private static func isAsset(_ s: String) -> Bool {
        !s.isEmpty && s.count <= 15 && s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }
}

// MARK: Ablauf

enum PortfolioCutoffExporter {
    /// Bestand am Ende des Tags (Zeitzone des Geräts), Kurse und Devisenkurs holen, CSV bauen.
    /// Bestand nach derselben Einstandsmethode `method` wie Karte, Verlauf und Steuer-Export.
    /// `addresses`: Reihen der beobachteten Adressen, die im Portfolio zählen (`AddressBalanceHistory.of`).
    /// `header`: Kopfzeilen davor (Bestand der Arbeitsunterlage), leer = keine. `zone`: Ende des Tags in dieser Zeitzone —
    /// für den Bestand der Arbeitsunterlage die der Vorlage, sonst die des Geräts (wie Android).
    static func build(transactions: [PortfolioTx], day: CutoffDay, currency: String, texts: CutoffCsvTexts,
                      method: CostMethod = .defaultMethod, addresses: [AddressBalanceHistory] = [],
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
                                format: .current, header: header, usdtApproximate: usdtApproximate)
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
