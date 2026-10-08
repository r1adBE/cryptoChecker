import Foundation
import WidgetKit

/// Eine Position im Portfolio-Widget: Wert in der Anzeigewährung, Anteil am Gesamtwert
/// (0–100) und Kursveränderung «heute» in Prozent (nil = keine Vergleichsbasis).
struct PortfolioWidgetPosition: Codable, Equatable, Sendable {
    var symbol: String
    var value: Double
    var sharePercent: Double
    var change24hPercent: Double?
}

/// Ein Punkt des Wertverlaufs: Zeit (ms) und Gesamtwert in der Anzeigewährung.
struct PortfolioWidgetPoint: Codable, Equatable, Sendable {
    var at: Int64
    var value: Double
}

/// Stand des Portfolio-Widgets: Gesamtwert und Veränderung heute in der
/// Umrechnungswährung (`portfolioCurrency`). Die App berechnet ihn, wenn sie die
/// Portfolio-Werte berechnet, und in der Hintergrund-Aktualisierung; das Widget
/// liest ihn aus der App Group (`SharedStorage.defaults`).
///
/// `totalUsdt`, `positions`, `otherPositions` und `history` kamen später dazu (grosses
/// Widget). Sie sind optional: Ein gespeicherter Stand ohne sie lädt weiter (dann nil),
/// und ältere Versionen ignorieren die zusätzlichen Felder beim Lesen.
struct PortfolioWidgetSnapshot: Codable, Equatable, Sendable {
    /// Gesamtwert in `currency`.
    var total: Double
    /// Veränderung heute in `currency`; nil = noch kein Vergleichswert.
    var changeAmount: Double?
    var changePercent: Double?
    /// Währung von `total` und `changeAmount`, z. B. «CHF»; «USD», wenn kein Devisenkurs bekannt ist.
    var currency: String
    /// Zeitpunkt der verwendeten Kurse (ms).
    var updatedAt: Int64
    /// Keine offene Position (keine Transaktion oder alles verkauft).
    var empty: Bool
    /// Gesamtwert in USDT; nil = unbekannt (älterer Stand).
    var totalUsdt: Double?
    /// Die grössten Positionen, absteigend nach Wert (höchstens `PortfolioWidgetStore.maxPositions`).
    var positions: [PortfolioWidgetPosition]?
    /// Weitere offene Positionen ausser `positions`.
    var otherPositions: Int?
    /// Wertverlauf des heutigen Bestands (Anzeigewährung), aufsteigend nach Zeit.
    var history: [PortfolioWidgetPoint]?
    /// Mit welcher %-Basis (und welchem Tagesbeginn) Veränderung und Verlauf gerechnet sind;
    /// nil = älterer Stand (rollende 24 Stunden).
    var stamp: ChangeStamp?
    /// Kursveränderung je offenem Coin in Prozent (gleiche %-Basis wie `changePercent`) — für
    /// «Grösste Bewegungen» im Portfolio-Tab (Runde 28; nil bei älterem Stand).
    var coinChanges: [String: Double]?

    init(total: Double, changeAmount: Double?, changePercent: Double?, currency: String, updatedAt: Int64, empty: Bool,
         totalUsdt: Double? = nil, positions: [PortfolioWidgetPosition]? = nil, otherPositions: Int? = nil,
         history: [PortfolioWidgetPoint]? = nil, stamp: ChangeStamp? = nil, coinChanges: [String: Double]? = nil) {
        self.total = total
        self.changeAmount = changeAmount
        self.changePercent = changePercent
        self.currency = currency
        self.updatedAt = updatedAt
        self.empty = empty
        self.totalUsdt = totalUsdt
        self.positions = positions
        self.otherPositions = otherPositions
        self.history = history
        self.stamp = stamp
        self.coinChanges = coinChanges
    }

    /// Für die Anzeige: Stand mit anderer %-Basis oder von einem früheren Tag → Veränderung «—»
    /// (bis zur nächsten Berechnung); der Verlauf bleibt.
    func shown(basis: ChangeBasis, now: Int64) -> PortfolioWidgetSnapshot {
        guard !ChangeBasisMath.isCurrent(stamp: stamp, basis: basis, now: now) else { return self }
        var copy = self
        copy.changeAmount = nil
        copy.changePercent = nil
        copy.positions = positions?.map { var p = $0; p.change24hPercent = nil; return p }
        copy.coinChanges = nil
        return copy
    }

    /// Ohne Zeitpunkt, damit ein leeres Portfolio das Widget nicht bei jeder Runde neu zeichnet.
    static let emptyPortfolio = PortfolioWidgetSnapshot(total: 0, changeAmount: nil, changePercent: nil,
                                                        currency: "USD", updatedAt: 0, empty: true)

    /// Positionen (leer bei älterem Stand).
    var topPositions: [PortfolioWidgetPosition] { positions ?? [] }
    /// Wertverlauf (leer bei älterem Stand).
    var valueHistory: [PortfolioWidgetPoint] { history ?? [] }
}

/// Speicher und Berechnung des Portfolio-Widget-Stands.
///
/// «Heute» = Veränderung über die letzten 24 Stunden. Die Portfolio-Kurse der App
/// (`PortfolioPriceSource`: Binance `ticker/price` bzw. letzter Schlusskurs) kennen
/// keine 24-h-Veränderung je Coin. Deshalb wird bei jeder Berechnung höchstens alle
/// 30 Minuten ein Kurs-Stand je Coin (USDT) gemerkt (48 h lang); Vergleichswert ist
/// der neueste gemerkte Stand, der mindestens 20 Stunden alt ist. Veränderung =
/// Σ heutiger Bestand × (Kurs jetzt − Kurs damals) — Käufe und Verkäufe dazwischen
/// zählen so nicht als Gewinn oder Verlust; Coins ohne Kurs von damals fehlen in
/// Betrag und Prozent. Ohne passenden Stand (App/Hintergrund lief vor 20–48 h nicht)
/// zeigt das Widget keine Veränderung. Gleiche Regeln wie `PortfolioSnapshotMath.kt`.
enum PortfolioWidgetStore {
    static let kind = "PortfolioWidget"

    private static let snapshotKey = "portfolio_widget_snapshot"
    private static let historyKey = "portfolio_widget_history"
    /// Gemerkte Stundenkurse je Coin für den 24-h-Wertverlauf (`PortfolioWidgetSeries.merge`).
    private static let hourlyKey = "portfolio_widget_hourly_v1"
    /// Letzter Kerzen-Abruf je Coin (ms) — höchstens einer je Stunde, auch über App und Widget.
    private static let candleAttemptsKey = "portfolio_widget_candle_attempts_v1"
    /// Kerzen für alle fehlenden Coins zusammen höchstens so lange laden (Sekunden).
    private static let candleLoadTimeout: Double = 4
    /// Vergleichsstand mindestens so alt.
    static let referenceMinAgeMillis: Int64 = 20 * 3_600_000
    /// Ältere Stände werden verworfen.
    static let historyMaxAgeMillis: Int64 = 48 * 3_600_000
    /// Höchstens ein Stand je 30 Minuten.
    static let historyStepMillis: Int64 = 30 * 60_000
    /// Höchstens so viele Positionen werden gespeichert und gezeigt.
    static let maxPositions = 5

    /// Gemerkte USDT-Kurse je Coin zu einem Zeitpunkt.
    struct PricePoint: Codable, Equatable, Sendable {
        var at: Int64
        var prices: [String: Double]
    }

    // MARK: Lesen & Schreiben

    static func load() -> PortfolioWidgetSnapshot? {
        guard let data = SharedStorage.defaults.data(forKey: snapshotKey) else { return nil }
        return try? JSONDecoder().decode(PortfolioWidgetSnapshot.self, from: data)
    }

    /// true, wenn sich der Stand geändert hat.
    @discardableResult
    static func save(_ snapshot: PortfolioWidgetSnapshot) -> Bool {
        guard load() != snapshot, let data = try? JSONEncoder().encode(snapshot) else { return false }
        SharedStorage.defaults.set(data, forKey: snapshotKey)
        return true
    }

    static func loadHistory() -> [PricePoint] {
        guard let data = SharedStorage.defaults.data(forKey: historyKey),
              let list = try? JSONDecoder().decode([PricePoint].self, from: data)
        else { return [] }
        return list
    }

    private static func saveHistory(_ list: [PricePoint]) {
        if let data = try? JSONEncoder().encode(list) {
            SharedStorage.defaults.set(data, forKey: historyKey)
        }
    }

    static func loadHourly() -> [String: [PortfolioTimedPrice]] {
        guard let data = SharedStorage.defaults.data(forKey: hourlyKey),
              let map = try? JSONDecoder().decode([String: [PortfolioTimedPrice]].self, from: data)
        else { return [:] }
        return map
    }

    private static func saveHourly(_ map: [String: [PortfolioTimedPrice]]) {
        if let data = try? JSONEncoder().encode(map) {
            SharedStorage.defaults.set(data, forKey: hourlyKey)
        }
    }

    /// Schon geladene Stundenkerzen eines Einzel-Widgets (24 h gegen USDT, App Group,
    /// `WidgetSparkline`) als Kurse mit Zeit — ohne Netz; leer, wenn nichts oder zu alt.
    static func widgetSparkPrices(_ coin: String, now: Int64) -> [PortfolioTimedPrice] {
        let key = "widget_spark_" + coin.uppercased() + "USDT|day"
        guard let entry = SharedStorage.defaults.dictionary(forKey: key) else { return [] }
        let fetchedAt = Int64((entry["t"] as? Double) ?? 0)
        let flat = (entry["c"] as? [Double]) ?? []
        guard fetchedAt > 0, now - fetchedAt <= PortfolioWidgetSeries.keepMillis, flat.count % 5 == 0 else { return [] }
        let candles: [(open: Int64, close: Double)] = stride(from: 0, to: flat.count, by: 5).map { i in
            (open: Int64(flat[i]), close: flat[i + 4])
        }
        return PortfolioWidgetSeries.fromCandles(candles, fetchedAt: fetchedAt)
    }

    // MARK: Reine Berechnung

    /// Tages-Basis: Stand zum Tagesbeginn — der jüngste davor, höchstens 90 Minuten vorher.
    static func reference(in history: [PricePoint], dayStart: Int64) -> PricePoint? {
        history
            .filter { $0.at <= dayStart && dayStart - $0.at <= PortfolioWidgetSeries.toleranceMillis }
            .max { $0.at < $1.at }
    }

    /// Neuester Stand, der mindestens 20 h alt ist (und nicht älter als 48 h).
    static func reference(in history: [PricePoint], now: Int64) -> PricePoint? {
        history
            .filter { now - $0.at >= referenceMinAgeMillis && now - $0.at <= historyMaxAgeMillis }
            .max { $0.at < $1.at }
    }

    /// Stand anhängen, wenn der letzte mindestens 30 Minuten alt ist; Altes (und
    /// «Zukünftiges» nach Zurückstellen der Uhr) verwerfen.
    static func record(_ history: [PricePoint], prices: [String: Double], at now: Int64) -> [PricePoint] {
        var list = history.filter { now - $0.at <= historyMaxAgeMillis && $0.at <= now }
        let clean = prices.filter { $0.value > 0 && $0.value.isFinite }
        guard !clean.isEmpty else { return list }
        list.sort { $0.at < $1.at }
        if let newest = list.last, now - newest.at < historyStepMillis { return list }
        list.append(PricePoint(at: now, prices: clean))
        return list
    }

    /// Zweite Zeile «≈ … USDT» nur, wenn gewünscht und die Anzeigewährung nicht selbst
    /// USD bzw. ein USD-Stablecoin ist (ohne Devisenkurs zeigt das Widget USD).
    static func showsUsdt(enabled: Bool, currency: String) -> Bool {
        enabled && !CurrencyConversion.usdStables.contains(CurrencyConversion.normalize(currency))
    }

    /// Anteil von `value` an `total` in Prozent (0–100); ohne Gesamtwert 0.
    static func sharePercent(_ value: Double, total: Double) -> Double {
        guard total > 0, value.isFinite else { return 0 }
        return Swift.min(100, Swift.max(0, value / total * 100))
    }

    /// Kursveränderung je Coin seit `reference` in Prozent (nur Coins mit beiden Kursen).
    static func coinChanges(current: [String: Double], reference: [String: Double]?) -> [String: Double] {
        guard let reference else { return [:] }
        var out: [String: Double] = [:]
        for (coin, now) in current where now > 0 && now.isFinite {
            guard let then = reference[coin], then > 0, then.isFinite else { continue }
            out[coin] = (now / then - 1) * 100
        }
        return out
    }

    /// Die `limit` wertvollsten Positionen (absteigend; gleicher Wert nach Kürzel) und die
    /// Zahl der übrigen. Positionen ohne Kurs (Wert nil) zählen nur zu den übrigen.
    /// - Parameters:
    ///   - valuesUsd: Wert je offenem Coin in USDT
    ///   - factor: USD → Anzeigewährung
    static func topPositions(valuesUsd: [String: Double?], totalUsd: Double, factor: Double,
                             changes: [String: Double], limit: Int = maxPositions)
        -> (positions: [PortfolioWidgetPosition], others: Int) {
        var priced: [(coin: String, value: Double)] = []
        for (coin, value) in valuesUsd {
            if let value, value.isFinite, value >= 0 { priced.append((coin, value)) }
        }
        priced.sort { $0.value != $1.value ? $0.value > $1.value : $0.coin < $1.coin }
        let top = priced.prefix(Swift.max(0, limit)).map { item in
            PortfolioWidgetPosition(symbol: item.coin, value: item.value * factor,
                                    sharePercent: sharePercent(item.value, total: totalUsd),
                                    change24hPercent: changes[item.coin])
        }
        return (Array(top), valuesUsd.count - top.count)
    }

    /// Wertverlauf des HEUTIGEN Bestands über die gemerkten Stände (vor `stamp`) und den
    /// aktuellen Stand — wie «heute»: Käufe und Verkäufe seither verschieben die Linie
    /// nicht. Fehlt einem Coin in einem Stand der Kurs, zählt der aktuelle.
    static func valueHistory(holdings: [String: Double], history: [PricePoint], current: [String: Double],
                             stamp: Int64, factor: Double) -> [PortfolioWidgetPoint] {
        let open = holdings.filter { $0.value > PortfolioCalculator.eps && $0.value.isFinite }
        guard !open.isEmpty else { return [] }
        func valid(_ price: Double?) -> Double? {
            guard let price, price > 0, price.isFinite else { return nil }
            return price
        }
        func value(_ prices: [String: Double]) -> Double {
            var sum = 0.0
            for (coin, amount) in open {
                sum += amount * (valid(prices[coin]) ?? valid(current[coin]) ?? 0)
            }
            return sum
        }
        let past = history
            .filter { $0.at < stamp && stamp - $0.at <= historyMaxAgeMillis }
            .sorted { $0.at < $1.at }
            .map { PortfolioWidgetPoint(at: $0.at, value: value($0.prices) * factor) }
        return past + [PortfolioWidgetPoint(at: stamp, value: value(current) * factor)]
    }

    /// Sichtbare Zeilen bei `maxLines` verfügbaren Zeilen (inklusive «+ n weitere»):
    /// Passen nicht alle, belegt «+ n weitere» die letzte Zeile; bliebe nur sie, keine Liste.
    static func rows(_ positions: [PortfolioWidgetPosition], others: Int,
                     maxLines: Int) -> (shown: [PortfolioWidgetPosition], more: Int) {
        let total = positions.count + Swift.max(0, others)
        guard maxLines > 0, !positions.isEmpty else { return ([], 0) }
        var shown = Swift.min(positions.count, maxLines)
        if shown < total && shown + 1 > maxLines { shown = maxLines - 1 }
        guard shown > 0 else { return ([], 0) }
        return (Array(positions.prefix(shown)), total - shown)
    }

    /// Veränderung in USDT (Betrag, Prozent) für die Bestände `holdings` zwischen
    /// `reference` und `prices`; nil, wenn kein Coin einen Vergleichskurs hat.
    static func change(holdings: [String: Double], prices: [String: Double],
                       reference: [String: Double]) -> (amount: Double, percent: Double?)? {
        var amount = 0.0
        var base = 0.0
        var any = false
        for (coin, held) in holdings where held > 0 {
            guard let now = prices[coin], now > 0, let then = reference[coin], then > 0 else { continue }
            amount += held * (now - then)
            base += held * then
            any = true
        }
        guard any else { return nil }
        let percent: Double? = base > 0 ? amount / base * 100 : nil
        return (amount, percent)
    }

    /// Stand aus Transaktionen, Kursen (USDT) und Devisenkurs USD → `currency`.
    /// Merkt dabei den Kurs-Stand für spätere Vergleiche (höchstens alle 30 Minuten) und die
    /// Stundenkurse je Coin. Reichen diese (`PortfolioWidgetSeries.hourly`, mindestens 6 Punkte),
    /// stammen Wertverlauf und Veränderung über 24 h daraus, sonst aus den Kursaufnahmen.
    /// `extraHourly`: weitere schon geladene Stundenkurse (z. B. Mini-Charts der Merkliste).
    /// `basis`: %-Basis — bei «seit 00:00» Verlauf und Veränderung ab Tagesbeginn (wie Android).
    static func compute(transactions: [PortfolioTx], prices: PortfolioPrices, currency: String,
                        rate: Double?, now: Int64 = TimeUtils.nowMillis,
                        extraHourly: [String: [PortfolioTimedPrice]] = [:],
                        basis: ChangeBasis = .ROLLING_24H) -> PortfolioWidgetSnapshot {
        guard !transactions.isEmpty else { return .emptyPortfolio }
        let summary = PortfolioCalculator.summarize(transactions, prices: prices.prices)
        var holdings: [String: Double] = [:]
        for p in summary.open { holdings[p.coin] = p.holdings }
        guard !holdings.isEmpty else { return .emptyPortfolio }

        var history = loadHistory()
        let changeStamp = ChangeBasisMath.stamp(basis, now: now)
        let dayStart: Int64? = basis.isDay ? changeStamp.dayStart : nil
        let ref = dayStart.map { reference(in: history, dayStart: $0) } ?? reference(in: history, now: now)
        let delta = ref.flatMap { change(holdings: holdings, prices: prices.prices, reference: $0.prices) }
        let past = history
        if prices.updatedAt > 0 {
            history = record(history, prices: prices.prices, at: now)
            saveHistory(history)
        }

        // USD = USDT (wie die Umrechnungszeile im Portfolio); ohne Devisenkurs in USD (wie Android)
        let factor: Double
        let label: String
        if let rate, rate > 0, rate.isFinite {
            factor = rate
            label = currency
        } else {
            factor = 1
            label = "USD"
        }
        // Grösste Positionen (Wert = Menge × aktueller Kurs) und Wertverlauf; Zeitpunkt des
        // letzten Punkts = Kursstand, damit unveränderte Kurse den Stand nicht ändern.
        var current: [String: Double] = [:]
        var values: [String: Double?] = [:]
        for p in summary.open {
            if let price = p.currentPrice, price > 0 { current[p.coin] = price }
            values.updateValue(p.value, forKey: p.coin)
        }
        let stamp = prices.updatedAt > 0 ? prices.updatedAt : now

        // Stundenkurse: gemerkte, dazu was ohnehin schon geladen ist — keine eigene Abfrage
        var fresh: [String: [PortfolioTimedPrice]] = [:]
        for coin in holdings.keys where !CurrencyConversion.usdStables.contains(coin.uppercased()) {
            var list = extraHourly[coin] ?? []
            list += widgetSparkPrices(coin, now: stamp)
            for sample in past where stamp - sample.at <= PortfolioWidgetSeries.keepMillis {
                if let price = sample.prices[coin] { list.append(PortfolioTimedPrice(at: sample.at, price: price)) }
            }
            if let price = current[coin] { list.append(PortfolioTimedPrice(at: stamp, price: price)) }
            if !list.isEmpty { fresh[coin] = list }
        }
        let held = Set(holdings.keys.map { $0.uppercased() })
        let hourlyPrices = PortfolioWidgetSeries.merge(stored: loadHourly(), fresh: fresh, now: stamp)
            .filter { held.contains($0.key) }
        if prices.updatedAt > 0 { saveHourly(hourlyPrices) }
        let hourly: [PortfolioWidgetPoint]
        let hourlyChange: PortfolioChange?
        var changes = coinChanges(current: current, reference: ref?.prices)
        let pastShown: [PricePoint]
        if let dayStart {
            // Seit Tagesbeginn: Verlauf ab 00:00, Veränderung gegen den Wert zum Tagesbeginn
            hourly = PortfolioWidgetSeries.hourlySince(holdings: holdings, current: current, prices: hourlyPrices,
                                                       dayStart: dayStart, now: stamp, factor: factor,
                                                       stables: CurrencyConversion.usdStables)
            hourlyChange = PortfolioWidgetSeries.changeSince(hourly, dayStart: dayStart)
            for (coin, change) in PortfolioWidgetSeries.coinChangesSince(current: current, prices: hourlyPrices,
                                                                         dayStart: dayStart) {
                changes[coin] = change
            }
            pastShown = past.filter { $0.at >= dayStart }
        } else {
            hourly = PortfolioWidgetSeries.hourly(holdings: holdings, current: current, prices: hourlyPrices,
                                                  now: stamp, factor: factor,
                                                  stables: CurrencyConversion.usdStables)
            hourlyChange = PortfolioWidgetSeries.drawable(hourly) ? PortfolioWidgetSeries.change(hourly) : nil
            // Stundenkurse vor der Vergleichsbasis (sie messen genau 24 h)
            for (coin, change) in PortfolioWidgetSeries.coinChanges(current: current, prices: hourlyPrices, now: stamp) {
                changes[coin] = change
            }
            pastShown = past
        }
        // Seit Tagesbeginn schon ab zwei Punkten (der Chart wächst über den Tag)
        let useHourly = dayStart != nil ? PortfolioWidgetSeries.drawableDay(hourly) : PortfolioWidgetSeries.drawable(hourly)
        let top = topPositions(valuesUsd: values, totalUsd: summary.totalValue, factor: factor, changes: changes)
        let points = useHourly
            ? hourly
            : valueHistory(holdings: holdings, history: pastShown, current: current, stamp: stamp, factor: factor)
        return PortfolioWidgetSnapshot(
            total: summary.totalValue * factor,
            changeAmount: hourlyChange.map { $0.amount } ?? delta.map { $0.amount * factor },
            changePercent: hourlyChange != nil ? hourlyChange?.percent : delta?.percent,
            currency: label,
            updatedAt: stamp,
            empty: false,
            totalUsdt: summary.totalValue,
            positions: top.positions,
            otherPositions: top.others,
            history: points,
            stamp: changeStamp,
            coinChanges: changes
        )
    }

    // MARK: Aktualisieren

    /// Berechnen und speichern; bei Änderung das Widget neu zeichnen (`reload`).
    /// Ohne einen einzigen bekannten Kurs bleibt der bisherige Stand stehen (dann nil).
    /// Gibt den neuen Stand zurück (Portfolio-Tab: «Grösste Bewegungen»; App: Portfolio-Alarme).
    @discardableResult
    static func update(transactions: [PortfolioTx], prices: PortfolioPrices, currency: String,
                       rate: Double?, reload: Bool = true,
                       extraHourly: [String: [PortfolioTimedPrice]] = [:]) -> PortfolioWidgetSnapshot? {
        if !transactions.isEmpty && prices.updatedAt <= 0 { return nil }
        let snapshot = compute(transactions: transactions, prices: prices, currency: currency, rate: rate,
                               extraHourly: extraHourly, basis: SharedStorage.loadSettings().changeBasis)
        if save(snapshot) && reload {
            WidgetCenter.shared.reloadTimelines(ofKind: kind)
        }
        return snapshot
    }

    /// Nur aus den Zwischenspeichern (ohne Netz), z. B. nach einer neuen Transaktion.
    static func updateFromCache(transactions: [PortfolioTx], currency: String, reload: Bool = true) {
        let coins = transactions.map(\.coin)
        update(transactions: transactions, prices: PortfolioPriceSource.cached(coins), currency: currency,
               rate: FxRateSource.cached(currency), reload: reload)
    }

    /// Kurse und Devisenkurs holen (60 s Zwischenspeicher) — Hintergrund-Aktualisierung
    /// und Widget, damit das Widget nicht vom Öffnen der App abhängt.
    @discardableResult
    static func refresh(reload: Bool = true) async -> PortfolioWidgetSnapshot? {
        let transactions = PortfolioStore.load().transactions
        let currency = SharedStorage.loadSettings().portfolioCurrency
        guard !transactions.isEmpty else {
            return update(transactions: [], prices: PortfolioPrices(), currency: currency, rate: nil, reload: reload)
        }
        let prices = await PortfolioPriceSource.prices(transactions.map(\.coin))
        let rate = await FxRateSource.usdTo(currency)
        var extra = await cachedHourly(Set(transactions.map { $0.coin.uppercased() }))
        for (coin, list) in await loadMissingCandles(transactions: transactions, prices: prices, known: extra) {
            extra[coin, default: []] += list
        }
        return update(transactions: transactions, prices: prices, currency: currency, rate: rate, reload: reload,
                      extraHourly: extra)
    }

    /// Stundenkerzen für offene Coins, denen Stundenkurse fehlen — z. B. Coins, die nur im Portfolio
    /// und nicht in der Merkliste sind. Die grössten Positionen zuerst, je Coin höchstens ein Abruf
    /// pro Stunde (`PortfolioWidgetSeries.candleCoins`), zusammen höchstens `candleLoadTimeout`.
    /// Wie `loadMissingCandles` in Android. Fehler bleiben still.
    private static func loadMissingCandles(transactions: [PortfolioTx], prices: PortfolioPrices,
                                           known: [String: [PortfolioTimedPrice]]) async -> [String: [PortfolioTimedPrice]] {
        guard prices.updatedAt > 0 else { return [:] }
        let open = PortfolioCalculator.summarize(transactions, prices: prices.prices).open.map(\.coin)
        guard !open.isEmpty else { return [:] }
        let now = TimeUtils.nowMillis
        let merged = PortfolioWidgetSeries.merge(stored: loadHourly(), fresh: known, now: now)
        var attempts = (SharedStorage.defaults.dictionary(forKey: candleAttemptsKey) as? [String: Double]) ?? [:]
        let due = PortfolioWidgetSeries.candleCoins(open, prices: merged,
                                                    lastAttempt: attempts.mapValues { Int64($0) }, now: now,
                                                    stables: CurrencyConversion.usdStables)
        guard !due.isEmpty else { return [:] }
        for coin in due { attempts[coin] = Double(now) }
        // Alte Einträge nicht ewig mitschleppen
        attempts = attempts.filter { Double(now) - $0.value < Double(PortfolioWidgetSeries.keepMillis) }
        SharedStorage.defaults.set(attempts, forKey: candleAttemptsKey)

        return await withTaskGroup(of: CandleLoad.self) { group in
            for coin in due {
                group.addTask {
                    guard let candles = await CandleDataSource.candles(base: coin, quote: "USDT", interval: .h1, limit: 24),
                          candles.count >= 2 else { return .empty }
                    let list = PortfolioWidgetSeries.fromCandles(candles.map { (open: $0.openTime, close: $0.close) },
                                                                 fetchedAt: TimeUtils.nowMillis)
                    return list.isEmpty ? .empty : .candles(coin, list)
                }
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(candleLoadTimeout * 1_000_000_000))
                return .timeout
            }
            var out: [String: [PortfolioTimedPrice]] = [:]
            var remaining = due.count
            while remaining > 0, let next = await group.next() {
                switch next {
                case .candles(let coin, let list):
                    out[coin] = list
                    remaining -= 1
                case .empty:
                    remaining -= 1
                case .timeout:
                    remaining = 0
                }
            }
            group.cancelAll()
            return out
        }
    }

    /// Mini-Chart-Kurse der Merkliste, die schon im Speicher liegen (`DayReferenceStore`) — ohne Netz.
    private static func cachedHourly(_ coins: Set<String>) async -> [String: [PortfolioTimedPrice]] {
        var out: [String: [PortfolioTimedPrice]] = [:]
        for coin in coins {
            guard let cached = await DayReferenceStore.shared.cachedCloses(base: coin) else { continue }
            let prices = PortfolioWidgetSeries.fromHourlyCloses(cached.closes, fetchedAt: cached.time)
            if !prices.isEmpty { out[coin] = prices }
        }
        return out
    }
}

/// Ergebnis eines Kerzen-Abrufs für das Portfolio-Widget (`loadMissingCandles`).
private enum CandleLoad: Sendable {
    case candles(String, [PortfolioTimedPrice])
    case empty
    case timeout
}
