import Foundation

/// Fest eingebaute Paare einer Börse ohne abrufbare Paarliste: Basis → mögliche Gegenwährungen.
typealias CurrencyPairsMap = [String: [String]]

/// Grundgerüst einer Börse: WIE mit ihr gesprochen wird — Adressen, POST-Inhalte, das Lesen der Antworten und ihrer
/// Fehlertexte. Netz, Speicher, Wiederholungen und Oberfläche gehören nicht hierher (`MarketService`).
///
/// Aufbau als Schablone: Die festen Einstiege `read…`/`parse…Main` erledigen das Gemeinsame (Zeitstempel, leere
/// Paare, Zeilenfehler) und rufen die überschreibbaren Teile `fill…`/`parseBulkTickers`. Eine Unterklasse
/// überschreibt nur, was bei ihr anders ist. Die Abschnitte folgen dem Ablauf: Einzelkurs · Sammelabfrage ·
/// Paarliste · Namen. Die übliche Börse mit einer Paarliste und einer Kursadresse erbt von `SimpleMarket`.
/// Gegenstück auf Android: `Market.kt`.
///
/// Instanzen sind zustandslos und werden von gleichzeitigen Abfragen geteilt. `key` ist der Kotlin-Klassenname und
/// steht so in Sicherungen beider Plattformen — nie umbenennen.
class Market: @unchecked Sendable {
    let key: String
    let name: String
    /// Name für die Sprachausgabe.
    let ttsName: String
    /// Nur bei wenigen Börsen: fest eingebaute Paare für die Zeit vor der ersten Synchronisierung.
    let currencyPairs: CurrencyPairsMap?

    init(key: String, name: String, ttsName: String? = nil, currencyPairs: CurrencyPairsMap? = nil) {
        self.key = key
        self.name = name
        self.ttsName = ttsName ?? name
        self.currencyPairs = currencyPairs
    }

    /// true: Der gelieferte «Kurs» ist ein Mittelkurs (aus Geld/Brief oder Mark) statt des letzten Handels. Das
    /// Aktionsblatt beschriftet ihn dann als «Mittelkurs»; eine Zeit der Börse gibt es dazu nicht, «Aktualisiert»
    /// ist der Abruf. Android: `priceIsMid`.
    var priceIsMid: Bool { false }

    /// true: Die 24-h-Veränderung bezieht sich auf den Mark-Preis (Hyperliquid `markPx` gegen `prevDayPx`), nicht
    /// auf den angezeigten Kurs. Zeile und Aktionsblatt schreiben dann «Mark» dazu (`ChangeBasisMath.showsMark`).
    /// Android: `changeIsMark`.
    var changeIsMark: Bool { false }

    // MARK: Einzelkurs

    /// Wie viele Anfragen ein Kurs braucht; Nummer `call` läuft von 0 bis Anzahl − 1.
    func tickerCallCount(_ info: PairQuery) -> Int { 1 }

    /// Adresse der Anfrage `call` für dieses Paar — jede Börse muss sie liefern (in Kotlin abstrakt).
    func tickerAddress(call: Int, info: PairQuery) -> String {
        fatalError("\(key): tickerAddress(call:info:) fehlt")
    }

    /// Inhalt für POST; nil heisst: einfache GET-Anfrage.
    func tickerPostBody(call: Int, info: PairQuery) -> PostRequestInfo? { nil }

    /// Liest eine Kursantwort in `ticker`. Danach gilt: `timestamp` ist der Moment des Abrufs, `exchangeTime` die
    /// Zeit laut Börse (oder nil).
    ///
    /// Warum der Abruf zählt: «veraltet» (OutdatedRule, lastUpdate) fragt, ob die Börse das Paar noch führt — und
    /// die Antwort kam eben jetzt. Ein ruhiges Paar mit altem letzten Handel (Upbit `trade_timestamp`, LATOKEN
    /// `updateTimestamp`) ist darum nicht veraltet. Scheitert der Abruf, bleibt lastUpdate stehen und das Paar
    /// altert wie gewohnt; ein gestrichenes Paar fehlt in der Liste (NotTraded). Die Börsenzeit dient nur der
    /// Anzeige (PriceFreshness), damit ein stehender Kurs nicht frisch aussieht. Wie `Market.kt`.
    final func readTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        try fillTicker(call: call, body: body, ticker: &ticker, info: info)
        Market.markFetched(&ticker)
    }

    /// Standard: Antwort als JSON-Objekt an `fillTicker(call:json:…)`. Börsen mit Listen-Antwort überschreiben dies.
    func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        try fillTicker(call: call, json: JObject(string: body), ticker: &ticker, info: info)
    }

    /// Liest die Felder einer Objekt-Antwort; ohne Überschreibung bleibt der Ticker leer (→ «kein Kurs»).
    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {}

    /// Fehlertext der Börse zu einer Kursantwort, die sich nicht lesen liess. Wirft, wenn die Antwort keinen
    /// enthält; der Aufrufer meldet dann den ursprünglichen Lesefehler.
    final func readError(call: Int, body: String, info: PairQuery) throws -> String? {
        try errorMessage(call: call, body: body, info: info)
    }

    /// Standard: Antwort als JSON-Objekt an `errorMessage(call:json:info:)`.
    func errorMessage(call: Int, body: String, info: PairQuery) throws -> String? {
        try errorMessage(call: call, json: JObject(string: body), info: info)
    }

    /// Ohne eigene Regel kennt die Börse keinen Fehlertext.
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        try noErrorText()
    }

    /// Für `errorMessage`: Die Antwort enthält keinen Fehlertext der Börse.
    final func noErrorText() throws -> Never {
        throw JSONError(message: Market.noErrorTextMessage)
    }

    /// Die Leser schreiben eine allfällige Börsenzeit (s, ms, µs oder ns) nach `timestamp`. Hier wandert sie in ms
    /// nach `exchangeTime` (≤ 0 = keine), und `timestamp` wird auf jetzt gesetzt.
    private static func markFetched(_ ticker: inout Ticker) {
        let reported = ticker.timestamp
        ticker.exchangeTime = reported > 0 ? TimeUtils.parseTimeToMillis(reported) : nil
        ticker.timestamp = TimeUtils.nowMillis
    }

    // MARK: Sammelabfrage

    /// Anfragen einer Sammelabfrage; 0 = gibt es nicht, die Paare werden einzeln abgefragt.
    var bulkTickersNumOfRequests: Int { 0 }

    /// true: Die ungefilterte Sammelabfrage führt jedes handelbare Paar. Fehlt ein Paar, ist es nicht mehr im
    /// Handel; eine Einzelabfrage würde ebenso scheitern und nur Zeit kosten.
    var bulkTickersComplete: Bool { false }

    /// Ungefilterte Adresse der Teilanfrage `call`; nil = diese Teilanfrage gibt es ungefiltert nicht.
    func bulkTickersURL(call: Int) -> String? { nil }

    /// Gefilterte Sammelabfrage nur für `pairIds` — einige Kilobyte statt mehrerer Megabyte, wo die Börse eine
    /// Auswahl erlaubt. Standard: dieselbe Adresse wie ungefiltert.
    func bulkTickersURL(call: Int, pairIds: [String]) -> String? { bulkTickersURL(call: call) }

    /// Anfragen für die gefilterte Abfrage dieser Paare. Mehr als `bulkTickersNumOfRequests`, wenn die Kennungen
    /// auf mehrere Adressen verteilt werden (`BulkPairChunks`); ungefiltert ist dann jede Teilanfrage gleich.
    func bulkTickersRequestCount(pairIds: [String]) -> Int { bulkTickersNumOfRequests }

    /// Inhalt für POST; nil heisst GET.
    func bulkTickersPostRequestInfo(call: Int) -> PostRequestInfo? { nil }

    /// Gruppe einer Teilanfrage (Binance: Spot 0, Alpha 1). Scheitert eine, verliert nur ihre Gruppe
    /// (`bulkTickersPairGroup`) die Aussage «nicht mehr gehandelt»; die übrigen Paare bleiben vollständig.
    /// Standard: alles eine Gruppe.
    func bulkTickersRequestGroup(call: Int) -> Int { 0 }

    /// Gruppe eines Paars, passend zu `bulkTickersRequestGroup`.
    func bulkTickersPairGroup(pairId: String) -> Int { 0 }

    /// Für Börsen ohne ungefilterte Sammelabfrage, die eine Auswahl mit einem unbekannten Paar als Ganzes ablehnen,
    /// ohne es zu nennen (Bithumb «Code not found»): Adresse der Paarliste (`pairListAddress`, GET). Damit werden
    /// die unbekannten Paare gefunden, aus der Anfrage genommen und als nicht gehandelt gemeldet (NotTraded,
    /// BulkDelisted). nil = trifft nicht zu.
    func bulkKnownPairsURL(call: Int) -> String? { nil }

    /// Alle Kurse einer Sammelantwort, geschlüsselt nach `MarketPair.pairId` — derselben Kennung wie beim
    /// Abgleich der Paarliste, sonst liessen sie sich keinem beobachteten Paar zuordnen.
    final func parseBulkTickersMain(call: Int, body: String) throws -> [String: Ticker] {
        try parseBulkTickersDetailed(call: call, body: body).tickers
    }

    /// Wie `parseBulkTickersMain`, zusätzlich die Paare mit unlesbarer Zeile (`BulkTickerParse.failed`).
    /// Überwiegen die unlesbaren Zeilen, wirft es `BulkFormatError` (Text beginnt mit `BulkTickerRows.formatError`).
    final func parseBulkTickersDetailed(call: Int, body: String) throws -> BulkTickerParse {
        var unreadable = Set<String>()
        let good = try parseBulkTickers(call: call, body: body, failed: &unreadable)
        // Ein Paar, das doch noch gelesen wurde, gilt nicht als Fehler
        let broken = unreadable.subtracting(good.keys)

        if BulkTickerRows.isFormatFailure(parsed: good.count, failed: broken.count) {
            throw BulkFormatError(message: BulkTickerRows.formatErrorText(parsed: good.count, failed: broken.count))
        }
        let fetched = good.mapValues { row -> Ticker in
            var stamped = row
            Market.markFetched(&stamped)
            return stamped
        }
        return BulkTickerParse(tickers: fetched, failed: broken)
    }

    /// Liest eine Sammelantwort: lesbare Zeilen als Ergebnis, vorhandene, aber unlesbare nach `failed` — am
    /// einfachsten über `bulkRowIfActive` bzw. `bulkRow`. Standard: keine Sammelabfrage.
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] { [:] }

    /// Die übliche Zeile einer Sammelantwort: ohne Kennung oder mit leerem Kurs in `priceField` (ruhendes Paar,
    /// `BulkTickerRows.inactivePrice`) still übergangen, sonst über `bulkRow` gelesen.
    final func bulkRowIfActive(_ pairId: String, row: JObject, priceField: String, tickers: inout [String: Ticker],
                               failed: inout Set<String>, read: (inout Ticker) throws -> Void) {
        if pairId.isEmpty || BulkTickerRows.inactivePrice(row, priceField) { return }
        bulkRow(pairId, tickers: &tickers, failed: &failed, read: read)
    }

    /// Liest eine Zeile der Sammelantwort mit `read` in einen frischen Ticker. Wirft `read` (Feld fehlt oder heisst
    /// anders) oder fehlt ein brauchbarer letzter Kurs (`BulkTickerRows.usable`), landet `pairId` in `failed` —
    /// das Paar zeigt dann einen Fehler, statt still «unverändert» zu bleiben. Darum wird jeder Fehler hier
    /// bewusst in diesen Eintrag übersetzt und nicht weitergeworfen.
    final func bulkRow(_ pairId: String, tickers: inout [String: Ticker], failed: inout Set<String>,
                       read: (inout Ticker) throws -> Void) {
        var row = Ticker()
        let readable: Bool
        do {
            try read(&row)
            readable = BulkTickerRows.usable(row)
        } catch {
            readable = false
        }
        if readable {
            tickers[pairId] = row
        } else {
            failed.insert(pairId)
        }
    }

    // MARK: Paarliste

    var pairListCallCount: Int { 1 }

    /// true: Erst alle Antworten der Paarliste sammeln, dann gemeinsam in `fillPairListCombined` lesen — für Börsen,
    /// deren Liste nur Kennungen enthält, die eine zweite Liste auflöst (LATOKEN: Währungs-ID → Kürzel). Eine
    /// gescheiterte Anfrage lässt dann den ganzen Abgleich scheitern.
    var pairListCombined: Bool { false }

    /// Adresse der Paarliste für Anfrage `call`; nil = die Börse hat keine (feste Paare oder Suche).
    func pairListAddress(call: Int) -> String? { nil }

    /// Inhalt für POST; nil heisst GET.
    func pairListPostBody(call: Int) -> PostRequestInfo? { nil }

    /// Für Börsen, die ihre Paarliste über einen Cursor blättern (Bybit `nextPageCursor`): Adresse der Folgeseite
    /// nach `body`, nil auf der letzten Seite. Sie wird per GET geholt und mit derselben Nummer `call` gelesen,
    /// höchstens `maxPairListPages` Seiten je Anfrage. Eine unlesbare Antwort wirft (nie still aufhören).
    /// Android: `pairListNextPage(call, body)`.
    func pairListNextPage(call: Int, body: String) throws -> String? { nil }

    /// Liest eine Antwort der Paarliste und wirft Paare ohne Basis oder Gegenwährung wieder hinaus.
    final func readPairList(call: Int, body: String) throws -> [MarketPair] {
        try Market.dropIncomplete(fillPairList(call: call, body: body))
    }

    /// Wie `readPairList` für Börsen mit `pairListCombined`, über alle gesammelten Antworten.
    final func readPairListCombined(bodies: [String]) throws -> [MarketPair] {
        try Market.dropIncomplete(fillPairListCombined(bodies: bodies))
    }

    /// Standard: Antwort als JSON-Objekt an `fillPairList(call:json:)`. Börsen mit Listen-Antwort überschreiben dies.
    func fillPairList(call: Int, body: String) throws -> [MarketPair] {
        try fillPairList(call: call, json: JObject(string: body))
    }

    /// Liest die Paare einer Objekt-Antwort; ohne Überschreibung keine.
    func fillPairList(call: Int, json: JObject) throws -> [MarketPair] { [] }

    /// Nur für Börsen mit `pairListCombined`.
    func fillPairListCombined(bodies: [String]) throws -> [MarketPair] { [] }

    private static func dropIncomplete(_ pairs: [MarketPair]) -> [MarketPair] {
        pairs.filter { pair in !pair.base.isEmpty && !pair.quote.isEmpty }
    }

    // MARK: Volle Coin-Namen (Identität «gleicher Token», K-8)

    /// Kürzel → voller Name (z. B. Gate `base_name`), aus derselben Antwort wie die Paarliste, ohne weitere Anfrage.
    /// Standard: keine Namen. Android: `parseBaseNames`.
    func parseBaseNames(call: Int, body: String) throws -> [String: String] { [:] }

    /// Gegenstück zu `parseBaseNames` für Börsen mit `pairListCombined`.
    func parseBaseNamesCombined(bodies: [String]) throws -> [String: String] { [:] }

    // MARK: Konstanten

    /// Höchstzahl Seiten je Paarlisten-Anfrage, falls ein Cursor nie endet.
    static let maxPairListPages = 10

    /// Grund von `noErrorText()`; erreicht nie die Oberfläche (dort steht dann der Lesefehler).
    static let noErrorTextMessage = "No error text"
}

extension JSONError {
    /// Die Antwort ist gültig, enthält aber keinen Eintrag für das Paar (leere Liste). Android:
    /// `MarketParseException.NO_DATA`.
    static let noData = "No data"
}

/// Die übliche Börse: eine feste Adresse für die Paarliste (GET) und eine Kursadresse `tickerURL`, in die die
/// Paar-Kennung an der Stelle `pairIdSlot` eingesetzt wird. `errorField`: Feld einer Fehlerantwort mit dem Text der
/// Börse, nil = keines bekannt (dann überschreibt die Börse `errorMessage(call:json:info:)` oder hat keinen).
///
/// Wer mehrere Paarlisten, eine zusammengesetzte Liste oder eine POST-Anfrage braucht, erbt direkt von `Market`.
/// Gegenstück auf Android: `SimpleMarket.kt`.
class SimpleMarket: Market {
    private let pairListURL: String
    private let tickerURL: String
    private let errorField: String?

    init(key: String, name: String, pairListURL: String, tickerURL: String, ttsName: String? = nil,
         errorField: String? = nil, currencyPairs: CurrencyPairsMap? = nil) {
        self.pairListURL = pairListURL
        self.tickerURL = tickerURL
        self.errorField = errorField
        super.init(key: key, name: name, ttsName: ttsName, currencyPairs: currencyPairs)
    }

    // MARK: Einzelkurs

    /// Kennung für die Kursadresse; Standard: die aus der Paarliste. Überschreiben, wo sie sich bilden lässt.
    func pairId(_ info: PairQuery) -> String? { info.pairId }

    /// Ohne Kennung bleibt die Stelle leer (Android schreibt dort «null»); die Börse antwortet dann mit ihrem Fehler.
    override func tickerAddress(call: Int, info: PairQuery) -> String {
        tickerURL.replacingOccurrences(of: SimpleMarket.pairIdSlot, with: pairId(info) ?? "")
    }

    override func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        guard let errorField else { return try super.errorMessage(call: call, json: json, info: info) }
        // `string` wirft bei JSON null: kein Fehlertext «null» (wie Android `getText`)
        return try json.string(errorField)
    }

    // MARK: Paarliste

    final override func pairListAddress(call: Int) -> String? { pairListURL }

    /// Platzhalter der Paar-Kennung in `tickerURL` (wie Android `PAIR_ID`).
    static let pairIdSlot = "%1$s"
}

extension String {
    /// Prozent-Kodierung eines Abfragewerts, wie Javas `URLEncoder.encode(…, "UTF-8")`.
    var urlQueryEncoded: String {
        // Bewusst nur ASCII erlaubt: `CharacterSet.alphanumerics` liesse auch Umlaute oder CJK-Zeichen
        // unkodiert durch, die URLEncoder sehr wohl kodiert.
        let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._*")
        return addingPercentEncoding(withAllowedCharacters: unreserved) ?? self
    }
}

/// Ausgewertete Sammelantwort: lesbare Kurse je Paar-Kennung und die Kennungen, deren Zeile zwar da, aber
/// unlesbar war (`failed`: Feld fehlt oder umbenannt, kein letzter Kurs). Wie `BulkTickerParse` auf Android.
struct BulkTickerParse: Sendable {
    let tickers: [String: Ticker]
    let failed: Set<String>
}

/// Sammelantwort überwiegend unlesbar — vermutlich hat die Börse ihr Format geändert. Der Text beginnt mit
/// `BulkTickerRows.formatError`, damit der Abruf den Fall erkennt (angezeigt via `ConnectionErrors.display`).
struct BulkFormatError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Umgang mit einzelnen Zeilen einer Sammelantwort (wie `BulkTickerRows` auf Android): Eine unlesbare Zeile
/// lässt den alten Kurs nicht stillschweigend stehen, sondern macht das Paar zum Fehler (`rowError`); sind die
/// meisten Zeilen unlesbar, ist die ganze Antwort ein Formatfehler (`formatError`).
enum BulkTickerRows {
    /// Beginn des Fehlertexts, wenn die Mehrheit der Zeilen unlesbar ist.
    static let formatError = "Bulk format error"
    /// Fehlertext für ein Paar mit unlesbarer Zeile in einer sonst guten Antwort.
    static let rowError = "Bulk row unreadable"

    /// Letzter Kurs ist eine endliche Zahl und nicht «keine Daten» — dieselbe Hürde wie bei der Einzelabfrage.
    static func usable(_ ticker: Ticker) -> Bool {
        let last = ticker.last
        return last.isFinite && last > Ticker.missing
    }

    /// Das Kursfeld ist da, aber leer (null, "" oder 0): ein ruhendes oder nie gehandeltes Paar. Solche Zeilen
    /// werden übersprungen statt als unlesbar gezählt. Fehlt das Feld ganz (umbenannt) oder ist der Wert Unsinn,
    /// bleibt es ein Fehler. Wie Android `BulkTickerRows.inactivePrice`.
    static func inactivePrice(_ json: JObject, _ key: String) -> Bool {
        json.raw[key].map(holdsNoPrice) ?? false
    }

    /// Dasselbe für Zeilen in Listenform; eine Zeile, die zu kurz ist, gilt nicht als leer, sondern als unlesbar.
    static func inactivePrice(_ array: JArray, _ index: Int) -> Bool {
        guard array.raw.indices.contains(index) else { return false }
        return holdsNoPrice(array.raw[index])
    }

    /// null, leerer Text, Text «0» oder die Zahl 0 (Wahrheitswerte zählen nicht als Zahl).
    private static func holdsNoPrice(_ value: Any) -> Bool {
        switch value {
        case is NSNull:
            return true
        case let text as String:
            let cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return cleaned.isEmpty || Double(cleaned) == 0
        case let number as NSNumber:
            let isBool = CFGetTypeID(number) == CFBooleanGetTypeID()
            return !isBool && number.doubleValue == 0
        default:
            return false
        }
    }

    /// Formatfehler, sobald es unlesbare Zeilen gibt und sie die lesbaren übertreffen.
    static func isFormatFailure(parsed: Int, failed: Int) -> Bool { failed > max(parsed, 0) }

    static func formatErrorText(parsed: Int, failed: Int) -> String {
        let total = parsed + failed
        return "\(formatError): \(failed) of \(total) rows unreadable"
    }

    /// Ist dieser Fehlertext einer Sammelabfrage ein Formatfehler?
    static func isFormatError(_ error: String?) -> Bool {
        guard let error else { return false }
        return error.contains(formatError)
    }
}

/// Für welche Paare eine Sammelabfrage aus mehreren Teilen «vollständig» Auskunft gibt (Gruppen über
/// `Market.bulkTickersRequestGroup` / `bulkTickersPairGroup`; Binance: Spot 0, Alpha 1) — fällt Alpha aus,
/// werden fehlende Spot-Paare trotzdem erkannt. Ausserdem: unbekannte Paare einer abgelehnten gefilterten
/// Anfrage (Bithumb). Wie `BulkCompleteness.kt`.
enum BulkCompleteness {
    /// Angefragte Paare, deren Gruppe in `failedGroups` steckt (Teilabfrage gescheitert oder ausgelassen).
    static func incomplete(_ pairIds: [String], failedGroups: Set<Int>, groupOf: (String) -> Int) -> Set<String> {
        guard !failedGroups.isEmpty else { return [] }
        var hit = Set<String>()
        for id in pairIds where failedGroups.contains(groupOf(id)) { hit.insert(id) }
        return hit
    }

    /// Gibt die Liste über wenigstens ein angefragtes Paar verlässlich Auskunft? Ohne gescheiterte Teilabfrage
    /// immer; sonst nur, wenn ein Paar in einer fehlerfreien Gruppe liegt (ganz ohne Paare: nein).
    static func anyComplete(_ pairIds: [String], failedGroups: Set<Int>, incomplete: Set<String>) -> Bool {
        if failedGroups.isEmpty { return true }
        return Set(pairIds).count > incomplete.count
    }

    /// Angefragte Paare, die in der Paarliste der Börse (`known`) fehlen. nil, wenn keines fehlt oder die Liste
    /// leer ist — eine leere Liste beweist nichts, dann gilt der ursprüngliche Fehler.
    static func unknownPairs(_ pairIds: [String], known: Set<String>) -> Set<String>? {
        guard !known.isEmpty else { return nil }
        let missing = Set(pairIds).subtracting(known)
        return missing.isEmpty ? nil : missing
    }
}
