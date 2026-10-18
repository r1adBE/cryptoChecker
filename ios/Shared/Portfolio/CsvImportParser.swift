import Foundation

/// Liest CSV-Exporte von Relai und Swissquote (und Dateien mit ähnlichen Spaltennamen) in
/// `CsvImportRow`; die Börsen-Exporte von Kraken, Binance, Coinbase und Bitpanda erkennt `detect` an ihren Spalten und liest
/// je ein eigener Leser (`CsvImportKraken`, `CsvImportBinance`, `CsvImportCoinbase`, `CsvImportBitpanda`).
/// Gleich wie Android (`CsvImportParser.kt`): BOM, CRLF, Anführungszeichen, Trenner
/// «;»/«,»/Tab, Spalten über ihre Namen (EN/DE/FR, Reihenfolge egal, Vorspann vor der Kopfzeile),
/// Zahlen mit «.»/«,» und «'»-Tausendern, Sats → BTC, Ortszeit ohne Versatz in `zoneFor` (Swissquote Europe/Zurich,
/// sonst Zeitzone des Geräts; in der Vorschau wählbar).
/// Tausch («Tausch», «Swap», «Convert», «Échange» → `.SWAP`): beide Seiten in einer Zeile («To Coin»/«To Amount»)
/// oder in zwei aufeinanderfolgenden Tausch-Zeilen mit gleicher Kennung (ohne: gleicher Zeit) — Regeln wie Android.
/// Kauf/Verkauf gegen einen Stablecoin (`CsvImport.stables`) → Tausch (`stableSwap`); Schlüssel bis Build 46 in
/// `legacyKeys` — wie Android.
enum CsvImportParser {

    /// Zeitzone ohne Angabe des Geräts (Tests) und der Swissquote-Exporte.
    static let defaultZone = "Europe/Zurich"

    /// Swissquote: Zeiten in Schweizer Ortszeit (Beispiel-Export: US-Aktie um 15:30:02 = Börsenöffnung New York in MESZ).
    static let swissZone = "Europe/Zurich"

    /// Kraken-Ledger und Binance-Exporte («UTC_Time», «Date(UTC)»): Zeiten in UTC (wie Android `KRAKEN_ZONE`).
    static let krakenZone = "UTC"

    /// Zeitzone der Zeiten ohne Versatz — wie Android `zoneFor`: gewählt vor Swissquote (Europe/Zurich), Kraken und Binance
    /// (UTC) vor Gerät. Coinbase und Bitpanda schreiben einen Versatz.
    static func zoneFor(_ format: CsvImportFormat, deviceZone: String, override: String? = nil) -> String {
        if let override { return override }
        if format == .SWISSQUOTE { return swissZone }
        if format == .KRAKEN || format == .BINANCE { return krakenZone }
        return deviceZone
    }

    /// Kopfzeile wird in so vielen ersten Datensätzen gesucht.
    private static let headerSearch = 10

    private static let satsPerBtc = Decimal(100_000_000)

    private static let posix = Locale(identifier: "en_US_POSIX")

    /// Spaltenrollen.
    enum Role: CaseIterable {
        case date, kind, coin, amount, amountSats, price, fiatAmount, quote, netCurrency, pair, fee, feeUnit, id, isin, toCoin, toAmount
    }

    /// Normalisierter Spaltenname → Rolle (exakt).
    private static let aliases: [String: Role] = {
        var map: [String: Role] = [:]
        let table: [(Role, [String])] = [
            (.date, ["date", "datum", "timestamp", "time", "datetime", "zeitpunkt", "dateheure",
                     // «Date(UTC)», «Time (UTC)», «UTC_Time»: Zeiten in UTC (`Header.utcDate`)
                     "dateutc", "timeutc", "datetimeutc", "timestamputc", "utctime", "utcdate"]),
            (.kind, ["transactiontype", "type", "transaction", "transaktion", "transaktionen", "typ", "art", "kind", "transactiontyp", "transaktionstyp"]),
            (.coin, ["symbol", "coin", "asset", "cryptocurrency", "crypto"]),
            (.amount, ["btcamount", "amountbtc", "quantity", "amount", "anzahl", "menge", "quantite", "btc"]),
            (.amountSats, ["satsamount", "amountsats", "sats", "satoshis", "satamount", "amountsat"]),
            (.price, ["btcprice", "pricebtc", "unitprice", "price", "stueckpreis", "kurs", "preis", "prixunitaire", "prix"]),
            (.fiatAmount, ["fiatamountexclfees", "fiatamount", "fiatamountexclfee"]),
            (.quote, ["fiatcurrency", "quotecurrency", "currency", "waehrung", "devise"]),
            (.netCurrency, ["netamountcurrency", "currencynetamount", "waehrungnettobetrag", "devisemontantnet"]),
            (.pair, ["currencypair", "pair", "waehrungspaar", "market"]),
            (.fee, ["fee", "fees", "costs", "kosten", "gebuehr", "gebuehren", "frais", "commission"]),
            (.feeUnit, ["feecurrency", "feeunit", "feeasset", "gebuehrenwaehrung"]),
            (.id, ["operationid", "orderid", "order", "ordernr", "auftrag", "auftragnr", "ordre", "transactionid", "id", "txid", "reference"]),
            (.isin, ["isin"]),
            // Tausch in einer Zeile: erhaltene Seite
            (.toCoin, ["tocoin", "toasset", "tocurrency", "receivedcoin", "receivedasset", "receivedcurrency", "zielcoin", "erhaltenercoin"]),
            (.toAmount, ["toamount", "toquantity", "receivedamount", "receivedquantity", "zielmenge", "erhaltenemenge"]),
        ]
        for (role, names) in table {
            for name in names { map[name] = role }
        }
        return map
    }()

    struct Record {
        let line: Int
        let cells: [String]
    }

    struct Header {
        let cells: [String]
        let names: [String]
        let index: [Role: Int]

        init(_ cells: [String]) {
            self.cells = cells
            names = cells.map { CsvImportParser.normalize($0) }
            var idx: [Role: Int] = [:]
            for (i, n) in names.enumerated() {
                if let role = CsvImportParser.aliases[n], idx[role] == nil { idx[role] = i }
            }
            index = idx
        }

        func has(_ role: Role) -> Bool { index[role] != nil }

        /// Datumsspalte nennt UTC («Date(UTC)»): ohne Wahl gilt UTC statt der Zeitzone des Geräts. Wie Android.
        var utcDate: Bool { index[.date].map { names[$0].contains("utc") } ?? false }

        /// Erkannte Spalten (Name wie in der Datei, Reihenfolge der Datei) — für die Vorschau. Wie Android `recognized`.
        func recognized() -> [String] { index.values.sorted().map { cells[$0] } }
    }

    // MARK: - Einstieg

    /// Datei lesen; unbekannte Kopfzeile = `.UNKNOWN` ohne Zeilen. `deviceZone` = Zeitzone des Geräts (App:
    /// `TimeZone.current`), `zone` = in der Vorschau gewählt (nil = Regel `zoneFor`); neu zuordnen = den Text im Speicher
    /// mit anderer `zone` noch einmal lesen (die Datei wird nicht neu gelesen). `dateOrder` = in der Vorschau gewählte
    /// Reihenfolge Tag/Monat; gilt nur bei mehrdeutigen Daten (`CsvImportResult.dateOrderAmbiguous`). `decimalMark` = in der
    /// Vorschau gewähltes Dezimalzeichen («.» oder «,»); gilt nur, wenn die Datei keines verrät (`decimalAmbiguous`).
    static func parse(_ text: String, deviceZone: String = defaultZone, zone: String? = nil,
                      dateOrder: CsvDateOrder? = nil, decimalMark: Character? = nil) -> CsvImportResult {
        var scalars = Array(text.unicodeScalars)
        if scalars.first == "\u{FEFF}" { scalars.removeFirst() }
        // Zeilenenden vereinheitlichen (CRLF/CR → LF)
        var clean: [Unicode.Scalar] = []
        clean.reserveCapacity(scalars.count)
        var i = 0
        while i < scalars.count {
            let c = scalars[i]
            if c == "\r" {
                clean.append("\n")
                if i + 1 < scalars.count && scalars[i + 1] == "\n" { i += 1 }
            } else {
                clean.append(c)
            }
            i += 1
        }
        let empty = CsvImportResult(format: .UNKNOWN, rows: [], skipped: [])
        let lines = clean.split(separator: "\n", omittingEmptySubsequences: false)
        guard let firstLine = lines.first(where: { line in line.contains { !$0.properties.isWhitespace } }) else {
            return empty
        }
        // Trenner nach Häufigkeit in der ersten Zeile; bei Gleichstand «;» vor «,» vor Tab
        let candidates: [Unicode.Scalar] = [";", ",", "\t"]
        let counts = candidates.map { d in firstLine.filter { $0 == d }.count }
        let order = candidates.indices.sorted { a, b in counts[a] != counts[b] ? counts[a] > counts[b] : a < b }
        for di in order {
            // Für die Erkennung nur die ersten Zeilen zerlegen — eine grosse Datei mit falschem Trenner kostete sonst je
            // Kandidat einen ganzen Durchlauf; zerlegt wird sie erst mit dem passenden Trenner ganz (wie Android)
            let head = tokenize(clean, delimiter: candidates[di], maxRecords: headerSearch)
            for h in head.indices {
                let header = Header(head[h].cells)
                let format = detect(header)
                if format != .UNKNOWN {
                    let records = tokenize(clean, delimiter: candidates[di])
                    let override = zone ?? (header.utcDate ? "UTC" : nil)
                    var result = build(format, header, Array(records[(h + 1)...]),
                                       zone: zoneFor(format, deviceZone: deviceZone, override: override), deviceZone: deviceZone,
                                       chosenOrder: dateOrder, chosenMark: decimalMark)
                    result.columns = header.recognized()
                    result.fxZone = deviceZone
                    return result
                }
            }
        }
        return empty
    }

    // MARK: - Zeichensatz

    /// Windows-1252 0x80–0x9F (nicht belegte Bytes bleiben ihr Codepunkt, wie WHATWG); Rest = Latin-1. Wie Android.
    private static let cp1252High: [UInt32] = [
        0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
        0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178,
    ]

    /// Bytes der Datei → Text: gültiges UTF-8, sonst Windows-1252 (Excel unter Windows: «Gebühr» als 0xFC). BOM am Anfang
    /// weg. Wie Android `decode`.
    static func decode(_ data: Data) -> String {
        var text = String(data: data, encoding: .utf8) ?? decodeCp1252(data)
        if text.unicodeScalars.first == "\u{FEFF}" { text.unicodeScalars.removeFirst() }
        return text
    }

    static func decodeCp1252(_ data: Data) -> String {
        var scalars = String.UnicodeScalarView()
        for b in data {
            let v = UInt32(b)
            let code = (0x80...0x9F).contains(v) ? cp1252High[Int(v - 0x80)] : v
            if let u = Unicode.Scalar(code) { scalars.append(u) }
        }
        return String(scalars)
    }

    // MARK: - Zerlegen

    private static func isBlank(_ s: String.UnicodeScalarView) -> Bool {
        s.allSatisfy { $0.properties.isWhitespace }
    }

    private static func trim(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// RFC-4180-Zerlegung («""» = «"», Umbruch in Anführungszeichen erlaubt); Zellen getrimmt, leere Zeilen weg.
    static func tokenize(_ text: [Unicode.Scalar], delimiter: Unicode.Scalar, maxRecords: Int = .max) -> [Record] {
        var out: [Record] = []
        var cells: [String] = []
        var cell = String.UnicodeScalarView()
        var inQuotes = false
        var line = 1
        var startLine = 1
        func endCell() {
            cells.append(trim(String(cell)))
            cell = String.UnicodeScalarView()
        }
        func endRecord() {
            endCell()
            if cells.contains(where: { !$0.isEmpty }) { out.append(Record(line: startLine, cells: cells)) }
            cells = []
        }
        var i = 0
        let n = text.count
        // Höchstens `maxRecords` Zeilen (Erkennung der Kopfzeile): danach nicht weiterlesen
        while i < n && out.count < maxRecords {
            let c = text[i]
            if inQuotes && c == "\"" && i + 1 < n && text[i + 1] == "\"" {
                cell.append("\"")
                i += 1
            } else if inQuotes && c == "\"" {
                inQuotes = false
            } else if inQuotes {
                if c == "\n" { line += 1 }
                cell.append(c)
            } else if c == "\"" && isBlank(cell) {
                cell = String.UnicodeScalarView()
                inQuotes = true
            } else if c == delimiter {
                endCell()
            } else if c == "\n" {
                endRecord()
                line += 1
                startLine = line
            } else {
                cell.append(c)
            }
            i += 1
        }
        if out.count < maxRecords && (!cell.isEmpty || !cells.isEmpty) { endRecord() }
        return out
    }

    /// Spaltenname/Art vergleichbar machen: klein, Umlaute ausgeschrieben, nur a–z und 0–9.
    static func normalize(_ text: String) -> String {
        var out = ""
        for ch in text.lowercased() {
            switch ch {
            case "ä": out += "ae"
            case "ö": out += "oe"
            case "ü": out += "ue"
            case "ß": out += "ss"
            case "é", "è", "ê", "ë": out += "e"
            case "à", "â": out += "a"
            case "ô": out += "o"
            case "î", "ï": out += "i"
            case "ç": out += "c"
            default:
                if ch.isASCII, let a = ch.asciiValue, (a >= 97 && a <= 122) || (a >= 48 && a <= 57) {
                    out.append(ch)
                }
            }
        }
        return out
    }

    // MARK: - Format

    static func detect(_ h: Header) -> CsvImportFormat {
        let names = Set(h.names)
        // Kraken «Ledgers»-Export: txid, refid, time, type, subtype, aclass, asset, (wallet,) amount, fee, balance
        let krakenCore: Set<String> = ["refid", "txid", "asset", "amount"]
        if krakenCore.isSubset(of: names) && (names.contains("aclass") || names.contains("balance")) { return .KRAKEN }
        // Börsen-Exporte mit eigenem Aufbau vor der allgemeinen Erkennung (Coinbase hätte auch Datum/Art/Coin)
        if CsvImportBinance.matches(names) { return .BINANCE }
        if CsvImportCoinbase.matches(names) { return .COINBASE }
        if CsvImportBitpanda.matches(names) { return .BITPANDA }
        let hasAmount = h.has(.amount) || h.has(.amountSats)
        guard h.has(.date), h.has(.kind), hasAmount else { return .UNKNOWN }
        let relai = !names.isDisjoint(with: ["btcamount", "btcprice", "satsamount", "amountsats"])
            || (names.contains("operationid") && names.contains("counterparty"))
        if relai { return .RELAI }
        let order = !names.isDisjoint(with: ["order", "auftrag", "ordre"])
        if h.has(.isin) && order && h.has(.price) { return .SWISSQUOTE }
        if h.has(.coin) { return .GENERIC }
        return .UNKNOWN
    }

    /// Wörter einer Art — wie Android `words`: wie `normalize`, aber an allem ausser a–z/0–9 getrennt.
    static func words(_ text: String) -> [String] {
        var joined = ""
        for ch in text {
            let n = normalize(String(ch))
            joined += n.isEmpty ? " " : n
        }
        return joined.split(separator: " ").map(String.init)
    }

    /// Muster gegen `words` — wie Android `matches`: «wort» genau, «wort*» Anfang, «*wort» Ende, «*wort*» enthalten,
    /// «zwei wörter» nacheinander oder zusammengeschrieben.
    private static func matches(_ words: [String], _ pattern: String) -> Bool {
        if pattern.contains(" ") {
            let parts = pattern.split(separator: " ").map(String.init)
            let glued = parts.joined()
            if words.contains(glued) { return true }
            guard words.count >= parts.count else { return false }
            for i in 0...(words.count - parts.count) where parts.indices.allSatisfy({ words[i + $0] == parts[$0] }) {
                return true
            }
            return false
        }
        let head = pattern.hasPrefix("*")
        let tail = pattern.hasSuffix("*")
        let core = pattern.trimmingCharacters(in: CharacterSet(charactersIn: "*"))
        return words.contains { w in
            if head && tail { return w.contains(core) }
            if tail { return w.hasPrefix(core) }
            if head { return w.hasSuffix(core) }
            return w == core
        }
    }

    private static let unstakeWords = ["unstak*", "redeem*", "redemption*", "entstak*", "stake", "staked"]
    private static let stakingWords = ["staking*", "stake", "staked"]
    private static let moveWords = ["withdraw*", "*auszahlung*", "retrait*", "deposit*", "*einzahlung*", "depot", "transfer*",
                                    "send*", "receive*", "unlock*", "lock*"]
    private static let swapWords = ["*tausch*", "swap*", "convert*", "conversion*", "konvert*", "echange*"]
    private static let sellWords = ["sell*", "sold", "*verkauf*", "vente*", "vendu", "vendita"]
    private static let buyWords = ["buy*", "bought", "*kauf*", "achat*", "purchase*", "savingsplan*", "savings plan", "sparplan*",
                                   "dca", "recurring*", "acquisto"]
    private static let rewardWords = ["reward*", "*belohnung*", "praemie*", "bonus*", "referral*", "cashback*", "airdrop*",
                                      "interest*", "interet*", "recompense*"]
    private static let feeWords = ["fee", "fees", "*gebuehr*", "frais"]
    private static let withdrawalWords = ["withdraw*", "send*", "sent", "*auszahlung*", "transfer out", "retrait*", "outgoing",
                                          "*ausgang*", "envoi*"]
    private static let depositWords = ["deposit*", "receive*", "*einzahlung*", "transfer in", "depot", "incoming", "*eingang*",
                                       "reception*"]

    /// Art aus dem Text der Datei — wie Android `classify` (Wörter statt Teiltext, feste Reihenfolge): Unstaking/Redeem/
    /// Staking-Auszahlung → `.TRANSFER` (ohne Belohnungswort), dann Tausch, Verkauf, Kauf, Belohnung, Gebühr, Auszahlung,
    /// Einzahlung; nil = nicht unterstützt (Dividende, Forex …).
    static func classify(_ text: String) -> CsvImportKind? {
        let w = words(text)
        if w.isEmpty { return nil }
        func any(_ patterns: [String]) -> Bool { patterns.contains { matches(w, $0) } }
        let reward = any(rewardWords)
        if !reward && (any(unstakeWords) || (any(stakingWords) && any(moveWords))) { return .TRANSFER }
        if any(swapWords) { return .SWAP }
        if any(sellWords) { return .SELL }
        if any(buyWords) { return .BUY }
        if reward || any(stakingWords) { return .REWARD }
        if any(feeWords) { return .FEE }
        if any(withdrawalWords) { return .WITHDRAWAL }
        if any(depositWords) { return .DEPOSIT }
        return nil
    }

    // MARK: - Zeilen

    private struct Skip: Error {
        let reason: CsvImportSkipReason
    }

    private static func build(_ format: CsvImportFormat, _ h: Header, _ data: [Record], zone: String, deviceZone: String,
                              chosenOrder: CsvDateOrder?, chosenMark: Character?) -> CsvImportResult {
        switch format {
        case .KRAKEN: return CsvImportKraken.build(names: h.names, data: data, zone: zone)
        case .BINANCE: return CsvImportBinance.build(names: h.names, data: data, zone: zone)
        case .COINBASE: return CsvImportCoinbase.build(names: h.names, data: data, zone: zone)
        case .BITPANDA: return CsvImportBitpanda.build(names: h.names, data: data, zone: zone)
        case .RELAI, .SWISSQUOTE, .GENERIC, .UNKNOWN: break
        }
        let dateIndex: Int? = h.index[.date]
        // Tag/Monat je Datei: Swissquote immer Tag zuerst; sonst aus den Daten, nur mehrdeutige → Auswahl der Vorschau
        var detected: CsvDateOrder? = .DAY_FIRST
        if format != .SWISSQUOTE, let di = dateIndex {
            detected = detectDateOrder(data.map { di < $0.cells.count ? $0.cells[di] : "" })
        }
        let ambiguous = detected == nil
        let order: CsvDateOrder = detected ?? chosenOrder ?? .DAY_FIRST
        let monthFirst = order == .MONTH_FIRST
        let numericRoles: [Role] = [.amount, .amountSats, .price, .fiatAmount, .fee]
        var numericCells: [String] = []
        for r in data {
            for role in numericRoles {
                if let i = h.index[role], i < r.cells.count { numericCells.append(r.cells[i]) }
            }
        }
        let detectedMark = decimalHint(numericCells)
        // Datei verrät kein Dezimalzeichen und hat «42,000»: Vorschau fragt; ohne Wahl das Zeichen der ersten mehrdeutigen
        // Zahl = Dezimalzeichen (gilt dann für die ganze Datei) — wie Android
        let decimalSample: String? = detectedMark == nil ? ambiguousNumber(numericCells) : nil
        var hint: Character? = detectedMark
        if hint == nil, let sample = decimalSample {
            if let m = chosenMark, m == "." || m == "," { hint = m } else { hint = defaultMark(sample) }
        }
        var parsed: [(row: CsvImportRow, sign: Int)] = []
        var skipped: [CsvImportSkip] = []
        // Zeile → ursprünglicher Kauf/Verkauf (vor dem Tausch gegen Stablecoin), für die Schlüssel bis Build 46
        var origins: [Int: CsvImportRow] = [:]
        for rec in data {
            do {
                parsed.append(try row(format, h, rec, hint: hint, zone: zone, monthFirst: monthFirst, origins: &origins))
            } catch let s as Skip {
                skipped.append(CsvImportSkip(line: rec.line, reason: s.reason))
            } catch {
                skipped.append(CsvImportSkip(line: rec.line, reason: .MISSING_FIELD))
            }
        }
        // Gebühr ≥ erhaltene Menge: übersprungen mit Grund (wie das Blatt nicht speicherbar)
        var drafts: [CsvImportRow] = pairSwapLegs(format, parsed, &skipped).filter { r -> Bool in
            let consumes = CsvImport.feeConsumesAmount(r)
            if consumes { skipped.append(CsvImportSkip(line: r.line, reason: .FEE_EXCEEDS_AMOUNT)) }
            return !consumes
        }
        // stabil nach Zeile (wie Android `sortBy`)
        skipped = skipped.enumerated().sorted { ($0.element.line, $0.offset) < ($1.element.line, $1.offset) }.map(\.element)
        let keys = CsvImport.withOccurrence(drafts.map(\.key))
        // Frühere Schlüssel: Zeit in UTC je Zeitzone der Auswahl (bis Build 45) und Kauf/Verkauf gegen Stablecoin noch
        // als Kauf/Verkauf (bis Build 46) — ältere Importe weiter als Duplikat erkennen
        let old: [CsvImportRow] = drafts.map { r in r.kind == .SWAP ? (origins[r.line] ?? r) : r }
        let oldKeys = CsvImport.withOccurrence(old.map(\.key))
        var legacyZones: [String] = []
        for z in [zone, deviceZone, swissZone, "UTC"] where !legacyZones.contains(z) { legacyZones.append(z) }
        var seen: [String: Int] = [:]
        for i in drafts.indices {
            let r = drafts[i]
            let o = old[i]
            let n = (seen[o.key] ?? 0) + 1
            seen[o.key] = n
            var legacy: [String] = []
            if oldKeys[i] != keys[i] { legacy.append(oldKeys[i]) }
            if let local = r.localTime {
                for z in legacyZones {
                    let k = CsvImport.key(format: format, kind: o.kind, coin: o.coin, amount: o.amount, price: o.price,
                                          timeMillis: toUtc(local, zone: z), externalId: o.externalId)
                    let suffixed = n == 1 ? k : "\(k)#\(n)"
                    if suffixed != keys[i] && !legacy.contains(suffixed) { legacy.append(suffixed) }
                }
            }
            drafts[i].key = keys[i]
            drafts[i].legacyKeys = legacy
        }
        // Zählt die Zeitzone? Nur wenn eine lesbare Zeit ohne Versatz vorkommt
        var localTimes = false
        if let di = dateIndex {
            localTimes = data.contains { r in
                let t: String = di < r.cells.count ? r.cells[di] : ""
                return !hasOffset(t) && parseTime(t, zone: zone, monthFirst: monthFirst) != nil
            }
        }
        return CsvImportResult(format: format, rows: drafts, skipped: skipped, zone: zone, localTimes: localTimes,
                               dateOrder: order, dateOrderAmbiguous: ambiguous, decimalSample: decimalSample,
                               decimalMark: decimalSample == nil ? nil : hint)
    }

    /// Ohne Wahl gilt das einzige Zeichen einer Zahl als Dezimalzeichen (`parseNumber` ohne Hinweis).
    private static func defaultMark(_ sample: String) -> Character { sample.contains(",") ? "," : "." }

    /// Wert von `sample` mit Dezimalzeichen `mark` («.» oder «,»), für die Auswahl der Vorschau; nil = nicht lesbar.
    static func sampleValue(_ sample: String, mark: Character) -> Decimal? { parseNumber(sample, hint: mark) }

    /// Kauf/Verkauf gegen einen Stablecoin → Tausch — wie Android `stableSwap`: Verkauf X gegen S = Tausch X → S, Kauf X
    /// mit S = Tausch S → X; S-Menge = `stableAmount` (Fiat-/Quote-Betrag der Datei), sonst Preis × Menge; Wert nur bei
    /// USDT. nil = bleibt wie `r` (kein Stablecoin, ohne Preis, Coin = Stablecoin der Preiswährung).
    /// Ebenso gegen einen Krypto-Coin (`CsvImport.isCryptoQuote`, Paar «ETH/BTC»): Wert unbekannt, ohne Stablecoin-Hinweis.
    static func stableSwap(_ format: CsvImportFormat, _ r: CsvImportRow, stableAmount: Decimal?) -> CsvImportRow? {
        guard r.kind == .BUY || r.kind == .SELL, let price = r.price, let stable = r.quote,
              CsvImport.isStable(stable) || CsvImport.isCryptoQuote(stable), stable != r.coin else { return nil }
        let sAmount = stableAmount ?? price * r.amount
        let sell = r.kind == .SELL
        let coin = sell ? r.coin : stable
        let amount = sell ? r.amount : sAmount
        let toCoin = sell ? stable : r.coin
        let toAmount = sell ? sAmount : r.amount
        let value = CsvImport.stableSwapValue(coin: coin, amount: amount, toCoin: toCoin, toAmount: toAmount)
        return CsvImportRow(
            line: r.line,
            kind: .SWAP,
            rawKind: r.rawKind,
            coin: coin,
            amount: amount,
            price: nil,
            quote: value.quote,
            fee: r.fee,
            feeUnit: r.feeUnit,
            timeMillis: r.timeMillis,
            externalId: r.externalId,
            key: CsvImport.key(format: format, kind: .SWAP, coin: coin, amount: amount, price: nil,
                               timeMillis: r.timeMillis, externalId: r.externalId, localTime: r.localTime),
            toCoin: toCoin,
            toAmount: toAmount,
            swapValue: value.value,
            localTime: r.localTime,
            legacyKeys: r.legacyKeys,
            stableSwap: CsvImport.isStable(stable)
        )
    }

    /// Tausch-Seiten (Tausch-Zeile ohne erhaltene Seite) paarweise zu einer Zeile — wie Android `pairSwapLegs`:
    /// zwei aufeinanderfolgende Seiten mit gleicher Kennung (fehlt eine: gleicher Zeit) und verschiedenen Coins;
    /// abgegeben = negative Menge, sonst Verkaufs-/Ausgangswort in der Art; sonst übersprungen.
    private static func pairSwapLegs(_ format: CsvImportFormat, _ parsed: [(row: CsvImportRow, sign: Int)],
                                     _ skipped: inout [CsvImportSkip]) -> [CsvImportRow] {
        func isLeg(_ r: CsvImportRow) -> Bool { r.kind == .SWAP && r.toCoin == nil }
        func outWord(_ r: CsvImportRow) -> Bool {
            let t = normalize(r.rawKind)
            return ["sell", "verkauf", "vente", "out", "from", "ausgang", "debit", "belastung", "abgang"].contains { t.contains($0) }
        }
        var out: [CsvImportRow] = []
        var i = 0
        while i < parsed.count {
            let a = parsed[i].row
            let signA = parsed[i].sign
            if !isLeg(a) { out.append(a); i += 1; continue }
            guard i + 1 < parsed.count else {
                skipped.append(CsvImportSkip(line: a.line, reason: .MISSING_FIELD))
                i += 1
                continue
            }
            let b = parsed[i + 1].row
            let signB = parsed[i + 1].sign
            let sameId: Bool
            if let ia = a.externalId, let ib = b.externalId { sameId = ia == ib } else { sameId = a.timeMillis == b.timeMillis }
            if !(isLeg(b) && a.coin != b.coin && sameId) {
                skipped.append(CsvImportSkip(line: a.line, reason: .MISSING_FIELD))
                i += 1
                continue
            }
            let aGiven: Bool?
            if signA < 0 && signB > 0 { aGiven = true }
            else if signB < 0 && signA > 0 { aGiven = false }
            else if outWord(a) && !outWord(b) { aGiven = true }
            else if outWord(b) && !outWord(a) { aGiven = false }
            else { aGiven = nil }
            guard let isAGiven = aGiven else {
                skipped.append(CsvImportSkip(line: a.line, reason: .MISSING_FIELD))
                skipped.append(CsvImportSkip(line: b.line, reason: .MISSING_FIELD))
                i += 2
                continue
            }
            let given = isAGiven ? a : b
            let received = isAGiven ? b : a
            // Wert: abgegebene Seite zuerst; Gebühr: abgegebene Seite zuerst
            let valueRow = given.swapValue != nil ? given : received
            // Beide Seiten mit Gebühr: gleiche Einheit → zusammenzählen; verschiedene → nicht still eine verwerfen,
            // beide Zeilen überspringen (Tausch von Hand erfassen)
            guard let fees = CsvImport.swapFee(givenFee: given.fee, givenUnit: given.feeUnit,
                                               receivedFee: received.fee, receivedUnit: received.feeUnit) else {
                skipped.append(CsvImportSkip(line: a.line, reason: .TWO_FEES))
                skipped.append(CsvImportSkip(line: b.line, reason: .TWO_FEES))
                i += 2
                continue
            }
            let externalId = given.externalId ?? received.externalId
            out.append(CsvImportRow(
                line: min(a.line, b.line),
                kind: .SWAP,
                rawKind: given.rawKind,
                coin: given.coin,
                amount: given.amount,
                price: nil,
                quote: valueRow.swapValue != nil ? valueRow.quote : nil,
                fee: fees.fee,
                feeUnit: fees.unit,
                timeMillis: given.timeMillis,
                externalId: externalId,
                key: CsvImport.key(format: format, kind: .SWAP, coin: given.coin, amount: given.amount, price: nil,
                                   timeMillis: given.timeMillis, externalId: externalId, localTime: given.localTime),
                toCoin: received.coin,
                toAmount: received.amount,
                swapValue: valueRow.swapValue,
                localTime: given.localTime
            ))
            i += 2
        }
        return out
    }

    /// Eine Zeile samt Vorzeichen der Menge in der Datei (−1 negativ, sonst 1; für Tausch-Seiten).
    private static func row(_ format: CsvImportFormat, _ h: Header, _ rec: Record, hint: Character?, zone: String,
                            monthFirst: Bool, origins: inout [Int: CsvImportRow]) throws -> (row: CsvImportRow, sign: Int) {
        func cell(_ role: Role) -> String {
            guard let i = h.index[role], i < rec.cells.count else { return "" }
            return rec.cells[i]
        }
        func number(_ role: Role) throws -> Decimal? {
            let t = cell(role)
            // «-» / «–» = leer (manche Exporte für fehlende Gebühr)
            if t.isEmpty || t == "-" || t == "–" { return nil }
            guard let v = parseNumber(t, hint: hint) else { throw Skip(reason: .BAD_NUMBER) }
            return v
        }
        func nonEmpty(_ s: String) -> String? { s.isEmpty ? nil : s }

        let rawKind = cell(.kind)
        guard let kind = classify(rawKind) else { throw Skip(reason: .UNSUPPORTED_KIND) }

        // Coin: Relai nur Bitcoin; Swissquote nur Zeilen ohne ISIN (Aktien/ETF haben eine)
        let coinText: String
        switch format {
        case .RELAI:
            coinText = nonEmpty(cell(.coin)) ?? "BTC"
        case .SWISSQUOTE:
            if !cell(.isin).isEmpty || cell(.coin).isEmpty { throw Skip(reason: .NOT_CRYPTO) }
            coinText = cell(.coin)
        case .KRAKEN, .BINANCE, .COINBASE, .BITPANDA, .GENERIC, .UNKNOWN:
            coinText = cell(.coin)
        }
        let coin = coinText.uppercased()
        if coin.isEmpty { throw Skip(reason: .MISSING_FIELD) }
        // Nur Geld («EUR»-Einzahlung u. ä.): kein Krypto
        if CsvImport.isFiat(coin) { throw Skip(reason: .NOT_CRYPTO) }

        guard let time = parseTime(cell(.date), zone: zone, monthFirst: monthFirst) else { throw Skip(reason: .BAD_DATE) }
        // Ohne Versatz: Kalenderzeit für den Schlüssel (unabhängig von der Zeitzone)
        let local: Int64? = localTime(cell(.date), monthFirst: monthFirst)

        var amountValue = try number(.amount)
        if amountValue == nil, let sats = try number(.amountSats) { amountValue = sats / satsPerBtc }
        guard var amount = amountValue else { throw Skip(reason: .MISSING_FIELD) }
        let sign = amount < 0 ? -1 : 1
        if amount < 0 { amount = -amount }
        if amount == 0 { throw Skip(reason: .BAD_NUMBER) }

        let pairParts = cell(.pair).split(omittingEmptySubsequences: false, whereSeparator: { $0 == "/" || $0 == "-" })
        let pairQuote = pairParts.count == 2 ? trim(String(pairParts[1])) : ""
        let quote = nonEmpty((nonEmpty(cell(.netCurrency)) ?? nonEmpty(cell(.quote)) ?? pairQuote).uppercased())

        var price = try number(.price).map { $0 < 0 ? -$0 : $0 }
        if let p = price, p == 0 { price = nil }
        if price == nil && (kind == .BUY || kind == .SELL), let fiat = try number(.fiatAmount) {
            let f = fiat < 0 ? -fiat : fiat
            if f > 0 { price = CsvImport.divide(f, amount) }
        }
        // Tausch: kein Preis, sondern Wert (Fiat-Betrag, sonst Preis × Menge) — nur in Geld, sonst unbekannt
        var swapValue: Decimal?
        var toCoin: String?
        var toAmount: Decimal?
        if kind == .SWAP {
            var value: Decimal?
            if let fiat = try number(.fiatAmount) {
                let f = fiat < 0 ? -fiat : fiat
                if f > 0 { value = f }
            }
            if value == nil, let p = price { value = p * amount }
            if let q = quote, CsvImport.money.contains(q) { swapValue = value }
            price = nil
            toCoin = nonEmpty(cell(.toCoin).uppercased())
            toAmount = try number(.toAmount).map { $0 < 0 ? -$0 : $0 }
            if let t = toAmount, t == 0 { toAmount = nil }
            if toCoin == nil || toAmount == nil || toCoin == coin { toCoin = nil; toAmount = nil }
        }
        if price != nil && quote == nil { throw Skip(reason: .MISSING_FIELD) }

        var fee = try number(.fee).map { $0 < 0 ? -$0 : $0 }
        if let f = fee, f == 0 { fee = nil }
        var feeUnit = nonEmpty(cell(.feeUnit).uppercased())
        if var f = fee {
            if let u = feeUnit, ["SATS", "SAT", "SATOSHI", "SATOSHIS"].contains(u) {
                f = f / satsPerBtc
                fee = f
                feeUnit = "BTC"
            }
            if feeUnit == nil {
                guard let q = quote else { throw Skip(reason: .MISSING_FIELD) }
                feeUnit = q
            }
        } else {
            feeUnit = nil
        }

        // Swissquote: «00000000» = ohne Auftrag (Gebühren, Zahlungen)
        let idText = cell(.id)
        let externalId: String? = (!idText.isEmpty && idText.contains { $0 != "0" }) ? idText : nil

        let draft = CsvImportRow(
            line: rec.line,
            kind: kind,
            rawKind: rawKind,
            coin: coin,
            amount: amount,
            price: price,
            quote: quote,
            fee: fee,
            feeUnit: feeUnit,
            timeMillis: time,
            externalId: externalId,
            key: CsvImport.key(format: format, kind: kind, coin: coin, amount: amount, price: price,
                               timeMillis: time, externalId: externalId, localTime: local),
            toCoin: toCoin,
            toAmount: toAmount,
            swapValue: swapValue,
            localTime: local
        )
        // Gegen Stablecoin bzw. Krypto-Coin: Tausch; der ursprüngliche Kauf/Verkauf bleibt für die früheren Schlüssel
        var stableAmount: Decimal?
        if kind == .BUY || kind == .SELL, CsvImport.isStable(quote) || CsvImport.isCryptoQuote(quote),
           let fiat = try number(.fiatAmount) {
            let f = fiat < 0 ? -fiat : fiat
            if f > 0 { stableAmount = f }
        }
        if let swapped = stableSwap(format, draft, stableAmount: stableAmount) {
            origins[rec.line] = draft
            return (swapped, sign)
        }
        return (draft, sign)
    }

    // MARK: - Zahlen

    /// Ziffern nach dem ersten `sep` (wie Android `length - indexOf - 1`).
    private static func digitsAfter(_ chars: [Character], _ sep: Character) -> Int {
        guard let i = chars.firstIndex(of: sep) else { return -1 }
        return chars.count - i - 1
    }

    /// Ganzzahlteil vor dem einzigen `sep` ist «0» oder «-0» (nie eine Tausendergruppe) — wie Android `zeroWhole`.
    private static func zeroWhole(_ chars: [Character], _ sep: Character) -> Bool {
        guard let i = chars.firstIndex(of: sep) else { return false }
        var whole = chars[..<i]
        if whole.first == "-" { whole = whole.dropFirst() }
        return whole.count == 1 && whole.first == "0"
    }

    /// Erste mehrdeutige Zahl, wenn die Datei kein Dezimalzeichen verrät — wie Android `ambiguousNumber`: genau ein «.»
    /// oder «,», genau 3 Ziffern dahinter, gültig als Tausender («42,000» = 42000 oder 42?). nil = keine.
    static func ambiguousNumber(_ cells: [String]) -> String? {
        for raw in cells {
            let t = stripGrouping(raw)
            let dots = t.filter { $0 == "." }.count
            let commas = t.filter { $0 == "," }.count
            let sep: Character
            if dots == 1 && commas == 0 { sep = "." } else if commas == 1 && dots == 0 { sep = "," } else { continue }
            if grouped(t, sep) && isPlain(t.replacingOccurrences(of: String(sep), with: "")) { return trim(raw) }
        }
        return nil
    }

    /// Dezimalzeichen der Datei aus eindeutigen Zahlen (wie Android `decimalHint`; «0.125» = Punkt).
    static func decimalHint(_ cells: [String]) -> Character? {
        var dot = false
        var comma = false
        for raw in cells {
            let t = Array(stripGrouping(raw))
            let dots = t.filter { $0 == "." }.count
            let commas = t.filter { $0 == "," }.count
            if dots == 1 && commas == 0 && (digitsAfter(t, ".") != 3 || zeroWhole(t, ".")) { dot = true }
            if commas == 1 && dots == 0 && (digitsAfter(t, ",") != 3 || zeroWhole(t, ",")) { comma = true }
            if dots >= 1 && commas >= 1 {
                if (t.lastIndex(of: ".") ?? 0) > (t.lastIndex(of: ",") ?? 0) { dot = true } else { comma = true }
            }
        }
        if dot && !comma { return "." }
        if comma && !dot { return "," }
        return nil
    }

    private static func stripGrouping(_ text: String) -> String {
        var out = ""
        for c in trim(text) {
            switch c {
            case " ", "\u{00A0}", "\u{202F}", "'", "’", "ʼ", "+": continue
            case "−": out.append("-")
            default: out.append(c)
            }
        }
        return out
    }

    /// Nur «-?Ziffern(.Ziffern)?» (wie Android `PLAIN`).
    private static func isPlain(_ s: String) -> Bool {
        var chars = Array(s)
        if chars.first == "-" { chars.removeFirst() }
        let parts = chars.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 1 || parts.count == 2 else { return false }
        return parts.allSatisfy { part in !part.isEmpty && part.allSatisfy { $0.isASCII && $0.isNumber } }
    }

    /// `sep` als Tausender gültig: erste Gruppe 1–3 Ziffern ohne führende 0 (eine einzelne «0» nie), alle weiteren genau
    /// 3 Ziffern, mindestens eine Trennung — «0.001» bleibt 0.001 auch mit Dezimalkomma. Wie Android `grouped`.
    private static func grouped(_ text: String, _ sep: Character) -> Bool {
        let body: Substring = text.hasPrefix("-") ? text.dropFirst() : Substring(text)
        let groups = body.split(separator: sep, omittingEmptySubsequences: false)
        guard groups.count >= 2, let first = groups.first, (1...3).contains(first.count), first.first != "0",
              first.allSatisfy({ $0.isASCII && $0.isNumber }) else { return false }
        return groups.dropFirst().allSatisfy { g in g.count == 3 && g.allSatisfy { $0.isASCII && $0.isNumber } }
    }

    /// Zahl aus der Datei → exakte Dezimalzahl; ungültig = nil (Regeln wie Android `parseNumber`).
    static func parseNumber(_ text: String, hint: Character? = nil) -> Decimal? {
        let t = stripGrouping(text)
        if t.isEmpty { return nil }
        let chars = Array(t)
        let dots = chars.filter { $0 == "." }.count
        let commas = chars.filter { $0 == "," }.count
        let plain: String
        if dots > 0 && commas > 0 {
            let dotLast = (chars.lastIndex(of: ".") ?? 0) > (chars.lastIndex(of: ",") ?? 0)
            let cut: Int = (dotLast ? chars.lastIndex(of: ".") : chars.lastIndex(of: ",")) ?? 0
            let whole = String(chars[0..<cut])
            let sep: Character = dotLast ? "," : "."
            guard grouped(whole, sep) else { return nil }
            plain = whole.replacingOccurrences(of: String(sep), with: "") + "." + String(chars[(cut + 1)...])
        } else if commas > 1 {
            guard grouped(t, ",") else { return nil }
            plain = t.replacingOccurrences(of: ",", with: "")
        } else if dots > 1 {
            guard grouped(t, ".") else { return nil }
            plain = t.replacingOccurrences(of: ".", with: "")
        } else if commas == 1 {
            plain = (hint == "." && grouped(t, ","))
                ? t.replacingOccurrences(of: ",", with: "")
                : t.replacingOccurrences(of: ",", with: ".")
        } else if dots == 1 {
            plain = (hint == "," && grouped(t, ".")) ? t.replacingOccurrences(of: ".", with: "") : t
        } else {
            plain = t
        }
        guard isPlain(plain), plain.count <= 60 else { return nil }
        return Decimal(string: plain, locale: posix)
    }

    // MARK: - Zeit

    private static let iso = try? NSRegularExpression(
        pattern: "^([0-9]{4})-([0-9]{2})-([0-9]{2})(?:[T ]([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:[.,]([0-9]{1,9}))?)?)?\\s*(Z|UTC|[+-][0-9]{2}:?[0-9]{2})?$")
    private static let dayFirst = try? NSRegularExpression(
        pattern: "^([0-9]{1,2})[./-]([0-9]{1,2})[./-]([0-9]{4})(?:[ T,]+([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?)?$")

    /// Gruppen eines vollständigen Treffers ("" für nicht beteiligte Gruppen); nil = kein Treffer.
    private static func groups(_ regex: NSRegularExpression?, _ text: String) -> [String]? {
        guard let regex else { return nil }
        let ns = text as NSString
        guard let m = regex.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)),
              m.range.location == 0, m.range.length == ns.length else { return nil }
        return (0..<m.numberOfRanges).map { i in
            let r = m.range(at: i)
            return r.location == NSNotFound ? "" : ns.substring(with: r)
        }
    }

    private static func int(_ s: String) -> Int { Int(s) ?? 0 }

    /// Zeit mit Versatz oder «Z»/«UTC» (dann zählt keine Zeitzone) — wie Android `hasOffset`.
    static func hasOffset(_ text: String) -> Bool {
        guard let g = groups(iso, trim(text)) else { return false }
        return !g[8].isEmpty
    }

    static func timeZone(_ zone: String) -> TimeZone {
        TimeZone(identifier: zone) ?? TimeZone(secondsFromGMT: 0) ?? .current
    }

    /// Zeit aus der Datei → UTC-Millisekunden; nil = unlesbar (Formate wie Android `parseTime`). `monthFirst`:
    /// «03/04/2024» bzw. «03-04-2024» = 4. März (`.MONTH_FIRST`); mit «.» immer Tag zuerst.
    static func parseTime(_ text: String, zone: String = defaultZone, monthFirst: Bool = false) -> Int64? {
        guard let clock = parseClock(text, monthFirst: monthFirst) else { return nil }
        guard let offsetMinutes = clock.offsetMinutes else { return toUtc(clock.local, zone: zone) }
        return clock.local - Int64(offsetMinutes) * 60_000
    }

    /// Kalenderzeit (als wäre sie UTC) einer Zeit OHNE Versatz; nil = mit Versatz/«Z» oder unlesbar.
    static func localTime(_ text: String, monthFirst: Bool = false) -> Int64? {
        guard let clock = parseClock(text, monthFirst: monthFirst), clock.offsetMinutes == nil else { return nil }
        return clock.local
    }

    /// Erstes Trennzeichen eines Datums «03/04/2024» ist ein Punkt (dann immer Tag zuerst)?
    private static func dotted(_ t: String, firstPart: String) -> Bool {
        let chars = Array(t)
        return firstPart.count < chars.count && chars[firstPart.count] == "."
    }

    /// Kalenderzeit (als wäre sie UTC) und Versatz in Minuten (nil = ohne Versatz); nil = unlesbar.
    private static func parseClock(_ text: String, monthFirst: Bool) -> (local: Int64, offsetMinutes: Int?)? {
        let t = trim(text)
        if let g = groups(iso, t) {
            let frac = String((g[7] + "000").prefix(3))
            guard let local = localMillis(int(g[1]), int(g[2]), int(g[3]), int(g[4]), int(g[5]), int(g[6]), int(frac)) else {
                return nil
            }
            let off = g[8]
            if off.isEmpty { return (local, nil) }
            if off == "Z" || off == "UTC" { return (local, 0) }
            let digits = Array(off.dropFirst().replacingOccurrences(of: ":", with: ""))
            let minutes = int(String(digits[0..<2])) * 60 + int(String(digits[2..<4]))
            return (local, off.hasPrefix("-") ? -minutes : minutes)
        }
        if let g = groups(dayFirst, t) {
            // «.» ist immer Tag zuerst; «/» und «-» je nach Reihenfolge der Datei
            let swap = monthFirst && !dotted(t, firstPart: g[1])
            let day = swap ? int(g[2]) : int(g[1])
            let month = swap ? int(g[1]) : int(g[2])
            guard let local = localMillis(int(g[3]), month, day, int(g[4]), int(g[5]), int(g[6]), 0) else {
                return nil
            }
            return (local, nil)
        }
        return nil
    }

    /// Reihenfolge Tag/Monat der Datumsspalte (`texts`) — wie Android `detectDateOrder`: Zahl > 12 vorne → `.DAY_FIRST`,
    /// an zweiter Stelle → `.MONTH_FIRST` (vorne gewinnt bei Widerspruch); nur mehrdeutige Daten (Tag ≠ Monat) → nil
    /// (Vorschau fragt). Nur Trenner «/» und «-»; ISO und «03.04.2024» sind eindeutig → `.DAY_FIRST`.
    static func detectDateOrder(_ texts: [String]) -> CsvDateOrder? {
        var dayFirstSeen = false
        var monthFirstSeen = false
        var ambiguous = false
        for raw in texts {
            let t = trim(raw)
            guard let g = groups(dayFirst, t), !dotted(t, firstPart: g[1]) else { continue }
            let a = int(g[1])
            let b = int(g[2])
            if a > 12 {
                dayFirstSeen = true
            } else if b > 12 {
                monthFirstSeen = true
            } else if a != b {
                ambiguous = true
            }
        }
        if dayFirstSeen { return .DAY_FIRST }
        if monthFirstSeen { return .MONTH_FIRST }
        return ambiguous ? nil : .DAY_FIRST
    }

    /// Kalenderzeit als wäre sie UTC → ms; ungültiges Datum = nil.
    static func localMillis(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int, _ s: Int, _ ms: Int) -> Int64? {
        guard (1...12).contains(mo), d >= 1, d <= daysInMonth(y, mo), h <= 23, mi <= 59, s <= 59 else { return nil }
        // In Schritten mit festen Typen (lange Int64-Kette mit Umwandlungen ist für den Type-Checker teuer)
        let hours: Int64 = daysFromCivil(y, mo, d) * 24 + Int64(h)
        let minutes: Int64 = hours * 60 + Int64(mi)
        let seconds: Int64 = Int64(s) * 1000 + Int64(ms)
        return minutes * 60_000 + seconds
    }

    private static func daysInMonth(_ y: Int, _ m: Int) -> Int {
        switch m {
        case 2: return ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }

    /// Tage seit 1970-01-01 (proleptisch gregorianisch, H. Hinnant).
    static func daysFromCivil(_ y0: Int, _ m: Int, _ d: Int) -> Int64 {
        let y = Int64(m <= 2 ? y0 - 1 : y0)
        let era = CsvImport.floorDiv(y, 400)
        let yoe = y - era * 400
        let mp = Int64((m + 9) % 12)
        // In Schritten mit festen Typen (für den Type-Checker)
        let monthDays: Int64 = (153 * mp + 2) / 5
        let doy: Int64 = monthDays + Int64(d) - 1
        let yearDays: Int64 = yoe * 365 + yoe / 4
        let doe: Int64 = yearDays - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /// Ortszeit in `zone` → UTC, zwei Schritte über den Versatz (gleich wie Android): Herbst-Doppelstunde
    /// = spätere (Winterzeit), Frühlingslücke = eine Stunde vorgerückt.
    static func toUtc(_ local: Int64, zone: String) -> Int64 {
        let tz = timeZone(zone)
        func offset(_ utc: Int64) -> Int64 {
            Int64(tz.secondsFromGMT(for: Date(timeIntervalSince1970: Double(utc) / 1000))) * 1000
        }
        let guess = local - offset(local)
        return local - offset(guess)
    }
}
