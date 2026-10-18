import SwiftUI

// Kurs-Chart im Aktionsblatt — reine Regeln und Zwischenspeicher, wie `SheetChart.kt` und
// `SheetChartRepository.kt` (Android). Zeichnen: `PriceChartRenderer` (Shared, wie das Einzel-Widget).

extension PriceChartRange {
    /// Gültigkeit im Zwischenspeicher: 5 Minuten (24 h), 30 Minuten (7 und 30 Tage), 60 Minuten (1 Jahr).
    var sheetCacheMillis: Int64 {
        switch self {
        case .day: 5 * 60_000
        case .week, .month: 30 * 60_000
        case .year: 60 * 60_000
        }
    }

    /// Vorlage für Datum/Uhrzeit beim Ziehen (`setLocalizedDateFormatFromTemplate`):
    /// 24 h «Di 14:00», 7 Tage «Di 14. Okt., 16:00», 30 Tage «Di 14. Okt.», 1 Jahr «14. Okt. 2025».
    var sheetTimeTemplate: String {
        switch self {
        case .day: "EEEjm"
        case .week: "EEEdMMMjm"
        case .month: "EEEdMMM"
        case .year: "dMMMy"
        }
    }
}

/// Umrechnung der Ausweich-Reihe (USDT → Fiat-Quote des Paars) — wie `SheetChartFx` (Android).
enum SheetChartFx: Equatable, Sendable {
    /// Keine Umrechnung: Kerzen des Paars selbst.
    case none
    /// Je Kerze mit dem Devisenkurs ihres Tags («umgerechnet mit Tageskursen»).
    case daily
    /// Ohne Tageskurse: ganze Reihe mit einem Faktor (aktueller Kurs) — «umgerechnet (aktueller Kurs)».
    case current
}

/// Herkunft für die Zeile unter dem Chart: Börse der Kerzen («Binance», «Coinbase» …), abgefragte
/// Reihe («BTC/USDT»), Art der Umrechnung und Abfragezeit (Epoch-ms; 0 = unbekannt).
struct SheetChartInfo: Equatable, Sendable {
    var provider: String? = nil
    var sourcePair: String? = nil
    var fx: SheetChartFx = .none
    var fetchedAt: Int64 = 0
}

/// Ergebnis für das Blatt: gar nicht zeigen, keine Kerzen oder fertige Kerzen.
enum SheetChartResult: Equatable, Sendable {
    /// DEX-Paar oder Kürzel, die die Kerzenquelle nie liefern kann: Chart-Block ausblenden.
    case unsupported
    /// Keine Quelle lieferte passende Kerzen: «Keine Kursdaten für dieses Paar».
    case noData
    /// Kerzen in der Quote des Paars; `converted` = aus der USDT-Reihe umgerechnet.
    case ready([MarketCandle], converted: Bool, info: SheetChartInfo)
}

/// Für das Zeichnen vorbereitete Kerzen (abseits des Main-Threads): bereinigt, Achse mit
/// Mindesthöhe, Höhen der Volumen-Balken — der Canvas rechnet danach nur noch Pixel.
struct SheetChartPrepared: Equatable, Sendable {
    let candles: [MarketCandle]
    let type: PriceChartType
    let range: PriceChartRange
    let levels: WidgetChartGeometry.Levels?
    /// nil = keine Volumen-Balken.
    let volumes: [CGFloat]?
}

extension MarketCandle {
    /// Umsatz nur genähert (Menge × Schluss), weil die Quelle keinen Quote-Umsatz liefert (Coinbase):
    /// im Blatt hellere Balken und «≈» vor dem Wert — wie `WidgetCandle.volumeApproximated` (Android).
    var volumeApproximated: Bool { SheetChart.isVolumeApproximated(quoteVolume) }
}

/// Eine Kerzenabfrage: Paar oder Ausweich-Reihe gegen USDT (`convert` = umrechnen).
struct SheetCandleRequest: Equatable, Sendable {
    let base: String
    let quote: String
    let convert: Bool
}

/// Reine Regeln des Charts im Aktionsblatt — gleiche Regeln wie Android (`SheetChart`).
enum SheetChart {
    /// Markt-Schlüssel von DexScreener (wie auf der Seite «Paar hinzufügen»): keine Börsen-Kerzen.
    static let dexMarketKey = "DexScreener"

    /// Kurzes Ticken nur beim Wechsel auf eine andere Kerze.
    static func isNewCandle(previous: Int?, current: Int?) -> Bool {
        guard let current else { return false }
        return previous != current
    }

    /// Gleiche Regel wie die Kerzenquelle: nur Buchstaben und Ziffern.
    private static func isAsset(_ s: String) -> Bool {
        let t = s.trimmingCharacters(in: .whitespaces)
        return !t.isEmpty && t.allSatisfy { $0.isLetter || $0.isNumber }
    }

    /// Kann die Kerzenquelle das Paar überhaupt liefern? Sonst Block ganz ausblenden.
    static func isSupported(marketKey: String, base: String, quote: String) -> Bool {
        marketKey.caseInsensitiveCompare(dexMarketKey) != .orderedSame && isAsset(base) && isAsset(quote)
    }

    /// Abfragen in fester Reihenfolge — wie der 24-h-Bezug (`DayChange.select`):
    ///  1. das Paar selbst (USD-artige Quotes auf die USDT-Reihe, `DayChange.candleQuote`),
    ///  2. bei Fiat-Quote ohne eigene Kerzen die USDT-Reihe des Basis-Assets, umgerechnet.
    static func requests(base: String, quote: String, quoteIsFiat: Bool) -> [SheetCandleRequest] {
        let b = base.trimmingCharacters(in: .whitespaces).uppercased()
        let q = DayChange.candleQuote(quote)
        let pair = SheetCandleRequest(base: b, quote: q, convert: false)
        guard quoteIsFiat, q != DayChange.usdtQuote else { return [pair] }
        return [pair, SheetCandleRequest(base: b, quote: DayChange.usdtQuote, convert: true)]
    }

    /// Prüft und wandelt Kerzen einer Abfrage; nil = nicht brauchbar (nächste Abfrage).
    ///  - Paar: Weicht der Kurs mehr als `DayChange.maxPriceGap` vom letzten Schluss ab,
    ///    gehören Kerzen und Kurs wohl nicht zum selben Coin. Ohne Kurs des Paars nicht prüfbar → nil.
    ///  - Ausweich-Reihe (umrechnen): ZUERST gleicher Token — letzter Schluss × Devisenkurs (Tageskurs
    ///    der letzten Kerze, sonst `currentRate`) darf höchstens `DayChange.maxPriceGap` vom Kurs des
    ///    Paars abweichen. Ohne Kurs des Paars oder ganz ohne Devisenkurs ist das nicht prüfbar → nil
    ///    (kein Chart statt einer blind auf den Kurs gezogenen fremden Reihe).
    ///  - Umrechnung mit Tageskursen (`dailyRates`, Tag seit 1970 (UTC) → Fiat je USD): jede Kerze
    ///    × Kurs IHRES Tags (Stunden- und 4-h-Kerzen den Kurs ihres Tags; fehlender Tag → letzter Kurs
    ///    davor, vor dem ersten Kurs der erste — `PortfolioHistoryFx.rateOn`). Dazu ein gemeinsamer
    ///    Faktor, damit der letzte Schluss dem Kurs des Paars entspricht.
    ///  - Ohne Tageskurse: USDT-Reihe mit einem Faktor so skaliert, dass der letzte Schluss dem Kurs
    ///    des Paars entspricht (Verlauf in Prozent wie `DayChange.fromSeries`).
    ///  - `currentRate`: aktueller Devisenkurs Fiat je USD der Quote (nur für die Prüfung ohne Tageskurse).
    static func accept(_ candles: [MarketCandle]?, request: SheetCandleRequest, price: Double?,
                       dailyRates: [Int: Double]? = nil, currentRate: Double? = nil) -> [MarketCandle]? {
        let clean = WidgetChartGeometry.clean(candles ?? [])
        guard clean.count >= 2, let last = clean.last?.close else { return nil }
        // Gleicher Token? Ohne Kurs des Paars nicht prüfbar (auch nicht beim Paar selbst)
        guard let pairPrice = price.flatMap({ $0.isFinite && $0 > 0 ? $0 : nil }) else { return nil }
        if !request.convert {
            if abs(pairPrice / last - 1) > DayChange.maxPriceGap { return nil }
            return clean
        }
        let sorted = sortedRates(dailyRates)
        guard let firstRate = sorted.first?.rate else {
            guard let rate = currentRate, rate.isFinite, rate > 0 else { return nil }
            if abs(pairPrice / (last * rate) - 1) > DayChange.maxPriceGap { return nil }
            let factor = pairPrice / last
            // Umsatz in der Quote mit demselben Faktor (USDT → Quote des Paars, Näherung)
            return clean.map { scaled($0, factor) }
        }
        let rates = clean.map { PortfolioHistoryFx.rateOn(sorted, day: utcEpochDay($0.openTime)) ?? firstRate }
        let converted = last * (rates.last ?? firstRate)
        if abs(pairPrice / converted - 1) > DayChange.maxPriceGap { return nil }
        let anchor = pairPrice / converted
        return zip(clean, rates).map { scaled($0.0, $0.1 * anchor) }
    }

    /// Mini-Chart der Merkliste: Die Reihe ist immer die USDT-Reihe des Basis-Assets (oft von einer
    /// anderen Börse als das Paar, z. B. Binance für ein Kraken-Paar). Gezeigt nur bei gleichem Token
    /// — wie `accept`: letzter Schluss gegen Kurs des Paars `price` × `quoteUsd` (USD je Einheit der
    /// Quote, z. B. 1 für USDT, ~1.1 für EUR) höchstens `DayChange.maxPriceGap` daneben.
    /// Ohne Kurs oder ohne Umrechnungsfaktor nicht prüfbar → nil (kein Mini-Chart).
    static func sparklineFor(_ closes: [Double]?, price: Double?, quoteUsd: Double?) -> [Double]? {
        guard let closes, closes.count >= 2, let last = closes.last, last.isFinite, last > 0,
              let p = price, p.isFinite, p > 0,
              let rate = quoteUsd, rate.isFinite, rate > 0 else { return nil }
        return abs(p * rate / last - 1) <= DayChange.maxPriceGap ? closes : nil
    }

    /// Bereitet Kerzen für den Canvas vor (rein, ohne Main-Thread): bereinigen, Achse
    /// (`axisLevels`), Volumen-Balken (`volumeFractions`).
    static func prepare(_ candles: [MarketCandle], type: PriceChartType, range: PriceChartRange,
                        stable: Bool) -> SheetChartPrepared {
        let clean = WidgetChartGeometry.clean(candles)
        return SheetChartPrepared(candles: clean, type: type, range: range,
                                  levels: axisLevels(clean, type: type, stable: stable),
                                  volumes: volumeFractions(clean.map { quoteVolume($0) }))
    }

    /// Art der Umrechnung für die Zeile unter dem Chart (wie `accept` sie anwendet).
    static func fxMode(_ request: SheetCandleRequest, dailyRates: [Int: Double]?) -> SheetChartFx {
        guard request.convert else { return .none }
        return sortedRates(dailyRates).isEmpty ? .current : .daily
    }

    /// Eigene Zeile unter dem Chart, wenn die Ausweich-Reihe mit EINEM heutigen Devisenkurs umgerechnet und am letzten
    /// Punkt auf den Kurs des Paars gezogen ist (`.current`): «Verlauf in USDT, mit heutigem Kurs in CHF umgerechnet».
    /// Zurück (Währung der Reihe, Währung des Paars); nil = Kerzen des Paars selbst oder Tageskurse. Wie Android.
    static func todayRateCaption(_ fx: SheetChartFx, sourcePair: String?, quote: String) -> (source: String, target: String)? {
        guard fx == .current else { return nil }
        let target = quote.trimmingCharacters(in: .whitespaces).uppercased()
        guard !target.isEmpty else { return nil }
        var source = "USDT"
        if let pair = sourcePair, let slash = pair.lastIndex(of: "/") {
            let tail = pair[pair.index(after: slash)...].trimmingCharacters(in: .whitespaces).uppercased()
            if !tail.isEmpty { source = tail }
        }
        return (source, target)
    }

    /// Gültige Tageskurse aufsteigend nach Tag.
    private static func sortedRates(_ dailyRates: [Int: Double]?) -> [(day: Int, rate: Double)] {
        (dailyRates ?? [:])
            .filter { $0.value.isFinite && $0.value > 0 }
            .map { (day: $0.key, rate: $0.value) }
            .sorted { $0.day < $1.day }
    }

    private static func scaled(_ c: MarketCandle, _ factor: Double) -> MarketCandle {
        MarketCandle(openTime: c.openTime, open: c.open * factor, high: c.high * factor,
                     low: c.low * factor, close: c.close * factor, volume: c.volume,
                     quoteVolume: c.quoteVolume.map { $0 * factor })
    }

    /// Tage seit 1970 (UTC), für die Devisen-Abfrage passend zu `accept`.
    static func utcEpochDay(_ millis: Int64) -> Int {
        let day: Int64 = 86_400_000
        var d = millis / day
        if millis % day < 0 { d -= 1 }
        return Int(d)
    }

    /// Umsatz nur genähert (Menge × Schluss), weil die Quelle keinen Quote-Umsatz liefert.
    static func isVolumeApproximated(_ quoteVolume: Double?) -> Bool {
        guard let quoteVolume else { return true }
        return !quoteVolume.isFinite || quoteVolume < 0
    }

    /// Ab dieser Abweichung des Live-Kurses (3 %) gilt der Zwischenspeicher als überholt.
    static let priceJump = 0.03

    /// Ist der Kurs seit dem Zwischenspeichern gesprungen? `reference` = Kurs beim Speichern,
    /// ersatzweise der letzte Schluss der gespeicherten Kerzen. Ungültige Werte: nein.
    static func priceJumped(reference: Double?, price: Double?) -> Bool {
        guard let r = reference, r.isFinite, r > 0, let p = price, p.isFinite, p > 0 else { return false }
        return abs(p / r - 1) > priceJump
    }

    /// Mindesthöhe der Achse bei Stablecoins: ±0.5 % um die Mitte (1 % der Mitte).
    static let stableMinSpan = 0.01
    /// Mindesthöhe sonst: ±0.1 % — nur für extrem ruhige Reihen.
    static let minSpan = 0.002

    /// Preisstufen des Blatts: wie das Widget (`WidgetChartGeometry.levels`), aber mindestens
    /// `stableMinSpan` (Stablecoin) bzw. `minSpan` der Mitte hoch, um die Mitte gelegt.
    static func axisLevels(_ candles: [MarketCandle], type: PriceChartType, stable: Bool) -> WidgetChartGeometry.Levels? {
        guard let levels = WidgetChartGeometry.levels(WidgetChartGeometry.clean(candles), type: type) else { return nil }
        return widen(levels, minFraction: stable ? stableMinSpan : minSpan)
    }

    /// Spanne von `levels` auf mindestens `minFraction` × Mitte aufziehen (mittig).
    static func widen(_ levels: WidgetChartGeometry.Levels, minFraction: Double) -> WidgetChartGeometry.Levels {
        let mid = levels.mid
        guard mid.isFinite, mid > 0 else { return levels }
        let span = mid * minFraction
        if levels.high - levels.low >= span { return levels }
        return WidgetChartGeometry.Levels(low: mid - span / 2, high: mid + span / 2)
    }

    /// «Stand» unter dem Chart: Abfragezeit, höchstens aber das Ende der letzten Kerze.
    /// `fetchedAt` ≤ 0 = Ende der letzten Kerze.
    static func stampMillis(lastOpenTime: Int64, intervalMillis: Int64, fetchedAt: Int64) -> Int64 {
        let end = lastOpenTime + intervalMillis
        return fetchedAt > 0 ? Swift.min(fetchedAt, end) : end
    }

    /// Vorlage für den «Stand»: heute nur Uhrzeit, sonst Datum und Uhrzeit (Zeitzone `timeZone`).
    static func stampTemplate(stamp: Int64, now: Int64, timeZone: TimeZone) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar.isDate(Date(millis: stamp), inSameDayAs: Date(millis: now)) ? "jm" : "dMMMjm"
    }

    /// Anteil der Volumen-Balken an der Höhe der Zeichenfläche (unten im Chart).
    static let volumeHeightFraction: CGFloat = 0.18

    /// Umsatz einer Kerze in der Quote: `quoteVolume` der Quelle (Binance), sonst genähert als
    /// Menge × Schluss (Coinbase liefert nur die Menge). nil ohne gültige Werte — wie Android.
    static func quoteVolume(base: Double, quote: Double?, close: Double) -> Double? {
        if let quote, quote.isFinite, quote >= 0 { return quote }
        guard base.isFinite, base >= 0, close.isFinite, close > 0 else { return nil }
        return base * close
    }

    /// Umsatz der Kerze `candle` in der Quote (`quoteVolume(base:quote:close:)`).
    static func quoteVolume(_ candle: MarketCandle) -> Double? {
        quoteVolume(base: candle.volume, quote: candle.quoteVolume, close: candle.close)
    }

    /// Höhe der Volumen-Balken je Kerze als Anteil (0…1) des grössten Umsatzes; Kerzen ohne gültigen
    /// Umsatz bekommen 0. nil = ausblenden: weniger als zwei Kerzen mit Umsatz > 0.
    static func volumeFractions(_ volumes: [Double?]) -> [CGFloat]? {
        let valid: [Double?] = volumes.map { v in
            guard let v, v.isFinite, v > 0 else { return nil }
            return v
        }
        guard valid.compactMap({ $0 }).count >= 2 else { return nil }
        let maxValue = valid.compactMap { $0 }.max() ?? 1
        return valid.map { v in
            guard let v else { return 0 }
            return Swift.min(Swift.max(CGFloat(v / maxValue), 0), 1)
        }
    }

    /// Kerze unter dem Finger: Kerze i belegt [plotLeft + i·slot, plotLeft + (i+1)·slot).
    static func scrubIndex(x: CGFloat, plotLeft: CGFloat, slot: CGFloat, count: Int) -> Int? {
        guard count > 0, slot > 0, !x.isNaN else { return nil }
        let raw = ((x - plotLeft) / slot).rounded(.down)
        let clamped = Swift.min(Swift.max(raw, 0), CGFloat(count - 1))
        return Int(clamped)
    }

    /// Veränderung in Prozent gegen `start`; nil ohne gültigen Start.
    static func changePercent(start: Double, value: Double) -> Double? {
        guard start.isFinite, value.isFinite, start > 0 else { return nil }
        return (value - start) / start * 100
    }

    /// Veränderung über den Zeitraum — gleiche Kennzahlen wie der VoiceOver-Satz.
    static func rangeChange(_ candles: [MarketCandle], type: PriceChartType) -> Double? {
        guard let s = WidgetChartGeometry.summary(candles, type: type) else { return nil }
        return changePercent(start: s.start, value: s.end)
    }

    /// Zahl über dem Chart: bei 24h dieselbe wie die Änderung neben dem Kurs (`dayChange` =
    /// `shownChange24h` aus dem Ticker), damit das Blatt nicht zwei verschiedene 24-h-Werte
    /// zeigt; ohne diesen Wert und bei 7T/30T/1J die Veränderung aus den Kerzen — wie `SheetChart.headerChange`.
    static func headerChange(range: PriceChartRange, _ candles: [MarketCandle], type: PriceChartType,
                             dayChange: Double?) -> Double? {
        if range == .day, let dayChange, dayChange.isFinite { return dayChange }
        return rangeChange(candles, type: type)
    }

    /// Schluss der Kerze `index` gegen den Beginn des Zeitraums (wie `rangeChange`).
    static func scrubChange(_ candles: [MarketCandle], type: PriceChartType, index: Int) -> Double? {
        guard let s = WidgetChartGeometry.summary(candles, type: type),
              candles.indices.contains(index) else { return nil }
        return changePercent(start: s.start, value: candles[index].close)
    }

    /// Linker Rand des Etiketts beim Ziehen: mittig über der Markierung, aber ganz innerhalb
    /// von `minX`…`maxX`; breiter als die Fläche → am linken Rand.
    static func labelLeft(centerX: CGFloat, width: CGFloat, minX: CGFloat, maxX: CGFloat) -> CGFloat {
        let maxLeft = Swift.max(minX, maxX - width)
        return Swift.min(Swift.max(centerX - width / 2, minX), maxLeft)
    }
}

/// Kerzen des Blatts: gleiche Intervalle wie das Einzel-Widget (`PriceChartRange`). Zuerst die Kerzen
/// der EIGENEN Börse des Paars (`OwnCandleDataSource`, z. B. Kraken-Paar → Kraken); sonst die
/// Ausweich-Kette (`CandleDataSource`, Futures-Paare zuerst USDⓈ-M); bei Fiat-Quotes ersatzweise die
/// USDT-Reihe, je Kerze mit dem Devisenkurs ihres Tags umgerechnet (dieselben EZB-Tageskurse wie der
/// Wertverlauf, `HistoricPriceSource.usdToSeries`). Jede Reihe gilt nur bei gleichem Token
/// (`SheetChart.accept`); passt die Reihe einer Quelle nicht, ist die nächste der Kette dran.
/// TradFi-Paare und DEX-Pools nie aus der Kette (`OwnCandlePolicy.allowsFallback`).
/// Im Speicher je Paar (mit Börse) und Zeitraum 5, 30 bzw. 60 Minuten (nur Erfolge); verworfen beim
/// Aktualisieren von Hand (`invalidate`, `invalidateAll`) oder bei einem Kurssprung über 3 %.
/// Netz und Umrechnung laufen abseits des Main-Threads. Wie `SheetChartRepository.kt`.
final class SheetChartStore: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    static let shared = SheetChartStore()

    private struct Entry {
        let time: Int64
        let candles: [MarketCandle]
        let converted: Bool
        let info: SheetChartInfo
        /// Kurs beim Speichern (Bezug für den Sprung); nil = letzter Schluss.
        let price: Double?
    }

    private let lock = NSLock()
    private var entries: [String: Entry] = [:]

    /// Längster Zeitraum des Blatts (1 Jahr): alle Zeiträume aus EINER Devisen-Abfrage.
    private static let fxDays = 366

    /// Börse (eigene Kerzen je Börse) und Kürzel des Paars.
    private static func prefix(_ watch: Watch) -> String {
        let b = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        let q = watch.quoteAsset.trimmingCharacters(in: .whitespaces).uppercased()
        return "\(CandlePair(watch: watch, tradFi: false).cacheKey)#\(b)|\(q)|"
    }

    private static func key(_ watch: Watch, _ range: PriceChartRange) -> String {
        prefix(watch) + range.rawValue
    }

    /// Ohne Netz: ausgeblendet (DEX), gültiger Zwischenspeicher oder nil (laden).
    func cached(_ watch: Watch, range: PriceChartRange, now: Int64 = TimeUtils.nowMillis) -> SheetChartResult? {
        guard SheetChart.isSupported(marketKey: watch.marketKey, base: watch.baseAsset, quote: watch.quoteAsset) else {
            return .unsupported
        }
        let k = Self.key(watch, range)
        lock.lock()
        defer { lock.unlock() }
        guard let entry = entries[k] else { return nil }
        let age = now - entry.time
        // Bezug: Kurs beim Speichern (Kerzen und Kurs dürfen je nach Börse leicht auseinanderliegen)
        let reference = entry.price ?? entry.candles.last?.close
        guard age >= 0, age < range.sheetCacheMillis,
              !SheetChart.priceJumped(reference: reference, price: watch.lastPrice) else {
            entries[k] = nil
            return nil
        }
        return .ready(entry.candles, converted: entry.converted, info: entry.info)
    }

    /// Aktualisieren im Blatt: Kerzen dieses Paars (alle Zeiträume) neu laden.
    func invalidate(_ watch: Watch) {
        let p = Self.prefix(watch)
        lock.withLock { entries = entries.filter { !$0.key.hasPrefix(p) } }
        let pair = CandlePair(watch: watch, tradFi: false)
        Task { await OwnCandleDataSource.shared.invalidate(pair) }
    }

    /// Nach unten ziehen in der Merkliste: alle Kerzen neu laden.
    func invalidateAll() {
        lock.withLock { entries.removeAll() }
        Task { await OwnCandleDataSource.shared.invalidateAll() }
    }

    private func store(_ watch: Watch, range: PriceChartRange, candles: [MarketCandle], converted: Bool,
                       info: SheetChartInfo) {
        let k = Self.key(watch, range)
        let price = watch.lastPrice.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        lock.withLock {
            entries[k] = Entry(time: TimeUtils.nowMillis, candles: candles, converted: converted, info: info, price: price)
        }
    }

    /// Prüfung einer Reihe der Ausweich-Kette (gleicher Token, Umrechnung); Tageskurse einmal je Abfrage.
    private actor Attempt {
        private(set) var accepted: [MarketCandle]?
        private(set) var rates: [Int: Double]?
        private var ratesLoaded = false

        func check(_ raw: [MarketCandle], request: SheetCandleRequest, quote: String, price: Double?) async -> Bool {
            // Tageskurse nur für die Ausweich-Reihe
            if request.convert && !ratesLoaded {
                let today = LocalDay(epochMillisUTC: TimeUtils.nowMillis)
                let span = PortfolioHistoryFx.requestRange(days: SheetChartStore.fxDays, today: today)
                rates = await HistoricPriceSource.usdToSeries(quote, from: span.from, to: span.to)
                ratesLoaded = true
            }
            // Ohne Tageskurse: aktueller Devisenkurs für die Prüfung auf gleichen Token
            var current: Double? = nil
            if request.convert, rates?.isEmpty ?? true { current = await FxRateSource.usdTo(quote) }
            accepted = SheetChart.accept(raw, request: request, price: price, dailyRates: rates, currentRate: current)
            return accepted != nil
        }
    }

    func load(_ watch: Watch, range: PriceChartRange) async -> SheetChartResult {
        if let hit = cached(watch, range: range) { return hit }
        let quote = watch.quoteAsset.trimmingCharacters(in: .whitespaces).uppercased()
        let base = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        let requests = SheetChart.requests(base: watch.baseAsset, quote: watch.quoteAsset,
                                           quoteIsFiat: DayChange.isFiat(watch.quoteAsset))
        let price = watch.lastPrice
        let pair = CandlePair.of(watch)
        // Name des Coins an der Börse des Paars (K-8); gelesen erst in der Aufgabe (Datei, nicht auf dem Main-Thread)
        let marketKey = watch.marketKey
        let rawBase = watch.baseAsset
        // Eigene Aufgabe abseits des Main-Threads (eigene Börse, Ausweich-Kette, Tageskurse, Umrechnung)
        let found = await Task.detached(priority: .userInitiated) { () -> (candles: [MarketCandle], converted: Bool, info: SheetChartInfo)? in
            // 1. Eigene Börse des Paars (kein Hinweis auf eine fremde Quelle nötig)
            if OwnCandleDataSource.supports(pair, interval: range.candleInterval) {
                let own = await OwnCandleDataSource.shared.candles(pair, interval: range.candleInterval, limit: range.limit)
                let request = SheetCandleRequest(base: base, quote: quote, convert: false)
                if let own, let candles = SheetChart.accept(own.value, request: request, price: price) {
                    let info = SheetChartInfo(provider: own.provider, sourcePair: "\(base)/\(quote)", fx: .none,
                                              fetchedAt: TimeUtils.nowMillis)
                    return (candles: candles, converted: false, info: info)
                }
            }
            // TradFi und DEX: nie Kerzen eines gleichnamigen Krypto-Tokens
            guard OwnCandlePolicy.allowsFallback(pair) else { return nil }
            // 2. Ausweich-Kette (Binance … Coinbase), Futures-Paare zuerst USDⓈ-M; Binance nur, wenn der Name
            // des Coins an der Börse des Paars dort nicht klar anders lautet (gleicher Token, K-8)
            let identityName = TokenIdentityStore.shared.pairName(marketKey: marketKey, base: rawBase)
            for request in requests {
                if Task.isCancelled { return nil }
                let attempt = Attempt()
                let sourced = await CandleDataSource.candlesSourced(
                    base: request.base, quote: request.quote, interval: range.candleInterval, limit: range.limit,
                    preferFutures: pair.isFutures, identityName: identityName
                ) { raw in
                    // Fremde Reihe gleichen Kürzels: nächste Quelle der Kette
                    await attempt.check(raw, request: request, quote: quote, price: price)
                }
                let accepted = await attempt.accepted
                let rates = await attempt.rates
                if let sourced, let candles = accepted {
                    let info = SheetChartInfo(provider: sourced.provider, sourcePair: "\(request.base)/\(request.quote)",
                                              fx: SheetChart.fxMode(request, dailyRates: rates),
                                              fetchedAt: TimeUtils.nowMillis)
                    return (candles: candles, converted: request.convert, info: info)
                }
            }
            return nil
        }.value
        guard let found else { return .noData }
        store(watch, range: range, candles: found.candles, converted: found.converted, info: found.info)
        return .ready(found.candles, converted: found.converted, info: found.info)
    }
}
