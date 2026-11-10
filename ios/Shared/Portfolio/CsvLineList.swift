import Foundation

/// Zeilennummern eines Vorschau-Hinweises (CSV-Import: ohne Tageskurs, ohne Preis, USDT = 1, Gebühr im Preis): Anzahl,
/// die ersten `shownDefault` Nummern und der Rest als «+ N weitere» — wie Android `CsvLineList.kt`
/// (`testdata/parity/csv_line_list.json`).
struct CsvLineList: Equatable {
    let count: Int
    let shown: [Int]
    let more: Int

    /// So viele Zeilennummern je Hinweis.
    static let shownDefault = 5

    static func of(_ lines: [Int], shown: Int = shownDefault) -> CsvLineList {
        let first = Array(lines.prefix(max(shown, 0)))
        return CsvLineList(count: lines.count, shown: first, more: lines.count - first.count)
    }

    /// Übersprungene Zeilen: ab so vielen steht «+ N weitere» darunter.
    static func moreAfter(total: Int, shown: Int) -> Int { max(total - max(shown, 0), 0) }
}
