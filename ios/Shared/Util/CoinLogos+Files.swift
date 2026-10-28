import Foundation

// Adressen und Abrufe der Bild-Dateien (Teil von `CoinLogos`) — wie `CoinLogos.urlFor` / `downloadJobs` (Android
// `CoinLogos.kt`, gemeinsame Testfälle `coin_logos.json`).

extension CoinLogos {
    /// Bild-Adresse für `symbol` aus der Zuordnung `map`: Schlüssel wie beim Zusammenführen (`pick`) — Krypto über
    /// `normalize` («1000CHEEMS» → «CHEEMS»), TradFi über `tradFiKey`. nil = keine.
    static func urlFor(_ map: [String: String], symbol: String) -> String? {
        symbol.hasPrefix(tradFiPrefix) ? map[tradFiKey(displaySymbol(symbol))] : map[normalize(symbol)]
    }

    /// Abrufe eines Bild-Durchgangs: je Dateiname (`fileName`) höchstens einer — zwei Kürzel mit derselben Datei
    /// («FLOKI», «1000FLOKI» → «FLOKI.png») schreiben nie gleichzeitig dieselbe Datei; der erste in `wanted` gilt.
    /// Ohne Dateinamen und mit `skip` (Datei schon da, Fehlschlag gesperrt) fällt ein Eintrag weg.
    /// Ergebnis: Dateiname → Adresse, in der Reihenfolge von `wanted`.
    static func downloadJobs(_ wanted: [(String, String)], skip: (String) -> Bool) -> [(name: String, url: String)] {
        var seen = Set<String>()
        var out: [(name: String, url: String)] = []
        for (symbol, url) in wanted {
            guard let name = fileName(symbol), seen.insert(name).inserted, !skip(name) else { continue }
            out.append((name, url))
        }
        return out
    }
}
