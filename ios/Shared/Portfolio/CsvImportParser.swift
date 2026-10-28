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
/// Kauf/Verkauf gegen einen Stablecoin (`CsvImport.stables`) → Tausch (`stableSwap`) — wie Android.
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

    /// Höchstens so viele Zellen je Zeile und Datensätze je Datei (`tokenizeOrNil`); mehr = Datei zu gross, gleich wie
    /// eine Datei über 16 MB «nicht lesbar» (wie Android). Echte Exporte haben unter 40 Spalten; ohne Grenze baute eine
    /// Zeile aus lauter Trennern Millionen leere Zellen.
    static let maxCellsPerRow = 200
    static let maxRows = 200_000

    static let satsPerBtc = Decimal(100_000_000)

    static let posix = Locale(identifier: "en_US_POSIX")

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
        var tooLarge = empty
        tooLarge.tooLarge = true
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
            // Über den Grenzen (Zellen je Zeile, Datensätze): wie eine zu grosse Datei nicht lesbar
            guard let head = tokenizeOrNil(clean, delimiter: candidates[di], maxRecords: headerSearch) else { return tooLarge }
            for h in head.indices {
                let header = Header(head[h].cells)
                let format = detect(header)
                if format != .UNKNOWN {
                    guard let records = tokenizeOrNil(clean, delimiter: candidates[di]) else { return tooLarge }
                    let override = zone ?? (header.utcDate ? "UTC" : nil)
                    var result = build(format, header, Array(records[(h + 1)...]),
                                       zone: zoneFor(format, deviceZone: deviceZone, override: override),
                                       chosenOrder: dateOrder, chosenMark: decimalMark)
                    result.columns = header.recognized()
                    result.fxZone = deviceZone
                    return result
                }
            }
        }
        return empty
    }
}
