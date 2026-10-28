import Foundation

/// Kurse direkt bei den Börsen (kein Server): Ablauf in `WatchQuoteFetch` (WatchCore; zuerst Binance mit allen Coins,
/// fehlende bzw. bei Fehler oder Sperre Bybit, dann OKX, dort je Coin eine kleine Abfrage). Hier nur die Abfrage selbst:
/// Antworten höchstens `maxBytes` (beim Lesen begrenzt, `WatchBodyLimit`), je Abfrage `timeout`, User-Agent mit der Version der Uhr-App. Wie Android
/// `WatchPriceSource`.
enum WatchPriceSource {
    private static let timeout: TimeInterval = 10
    private static let maxBytes = 4 * 1024 * 1024
    /// «CryptoChecker-Watch/16.2.2» aus dem Bundle (Uhr-App bzw. Komplikation, gleiche Version); wie Android
    /// «CryptoChecker-Wear/<versionName>».
    private static let userAgent = "CryptoChecker-Watch/"
        + ((Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "16")

    /// Ein Abruf: eine ephemere `URLSession` für alle seine Abfragen (Binance und die Ausweich-Börsen), danach
    /// aufgelöst.
    static func fetch(_ coins: [String]) async -> [String: WatchQuote] {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout * 2
        let session = URLSession(configuration: config)
        defer { session.finishTasksAndInvalidate() }
        return await WatchQuoteFetch.fetch(coins) { url in await get(url, session: session) }
    }

    /// Antwort als Strom gelesen und nach `maxBytes` abgebrochen (die Abfrage wird dann beendet, der Rest nicht mehr
    /// geladen); HTTP-Fehler, Sperre oder kein Netz = nil.
    private static func get(_ url: URL, session: URLSession) async -> Data? {
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        guard let result = try? await session.bytes(for: request) else { return nil }
        let (bytes, response) = result
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            bytes.task.cancel()
            return nil
        }
        let data = await WatchBodyLimit.collect(bytes, limit: maxBytes, expected: response.expectedContentLength)
        if data == nil { bytes.task.cancel() }
        return data
    }
}
