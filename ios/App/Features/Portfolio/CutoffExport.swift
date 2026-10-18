import Foundation

// Stichtag-Export: reine Rechnung und CSV — wie `CutoffExport.kt` (Android).

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
    /// `addressesUnreadable`: beobachtete Adressen nicht lesbar, ihre Guthaben fehlen, ohne dass bekannt ist welche Coins —
    /// Zeile `texts.addressesUnreadable` nach dem Kommentar und Total «unvollständig» (wie bei nicht beobachteten
    /// Adressen), statt still eine zu tiefe Summe. Wie Android.
    static func csv(day: CutoffDay, currency: String, fxDate: String?, rows: [CutoffRow], texts: CutoffCsvTexts,
                    format: CutoffCsvFormat = .semicolonPoint, header: [String] = [],
                    usdtApproximate: Bool = false, addressesUnreadable: Bool = false) -> String {
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
        // Adressen nicht lesbar: fehlende Guthaben nie still
        if addressesUnreadable && !texts.addressesUnreadable.isEmpty { out += line([texts.addressesUnreadable]) }
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
        if addressesUnreadable || rows.contains(where: { $0.noPrice || $0.unchecked || $0.addressUnobserved }) {
            totalNotes.append(texts.incomplete)
        }
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
