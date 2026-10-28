import Foundation
import WidgetKit

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
/// zeigt das Widget keine Veränderung. Gleiche Regeln wie `PortfolioSnapshotMath` in `PortfolioSnapshot.kt`.
///
/// Hier Lesen, Schreiben und Aktualisieren; die reine Berechnung in `PortfolioWidgetStore+Compute.swift`, die Typen
/// des Stands in `PortfolioWidgetSnapshot.swift`.
enum PortfolioWidgetStore {
    static let kind = "PortfolioWidget"

    /// Zuletzt bekannter Devisenkurs USD → Umrechnungswährung (`PortfolioFxMemo`) — Widget und Hintergrund ohne Netz.
    private static let fxMemoKey = "portfolio_widget_fx_v1"
    /// Kerzen für alle fehlenden Coins zusammen höchstens so lange laden (Sekunden).
    private static let candleLoadTimeout: Double = 4
    /// Vergleichsstand mindestens so alt.
    static let referenceMinAgeMillis: Int64 = 20 * 3_600_000
    /// Ältere Stände werden verworfen.
    static let historyMaxAgeMillis: Int64 = 48 * 3_600_000
    /// Höchstens ein Stand je 30 Minuten.
    static let historyStepMillis: Int64 = 30 * 60_000
    /// Höchstens so viele Positionen werden gespeichert (Ansicht «Liste»).
    static let maxPositions = 12

    /// Gemerkte USDT-Kurse je Coin zu einem Zeitpunkt.
    struct PricePoint: Codable, Equatable, Sendable {
        var at: Int64
        var prices: [String: Double]
    }

    // MARK: Lesen & Schreiben

    /// Momentaufnahme (Beträge) als Datei in der App Group statt in den App-Group-Einstellungen: nur
    /// eine Datei lässt sich aus den Geräte-Backups nehmen. Wie Android (`portfolio_widget` nicht in
    /// den Backup-Regeln) nie im Backup — sie gilt nur auf diesem Gerät und entsteht neu.
    static var snapshotURL: URL { SharedStorage.directory.appendingPathComponent("portfolio_widget.json") }

    static func load() -> PortfolioWidgetSnapshot? {
        guard let data = try? ProtectedFile.read(snapshotURL) else { return nil }
        return try? JSONDecoder().decode(PortfolioWidgetSnapshot.self, from: data)
    }

    /// Gemerkter Devisenkurs (`PortfolioWidgetChart.fxRate`); nil = keiner oder nicht lesbar.
    static func loadFxMemo() -> PortfolioFxMemo? {
        guard let data = SharedStorage.defaults.data(forKey: fxMemoKey) else { return nil }
        return try? JSONDecoder().decode(PortfolioFxMemo.self, from: data)
    }

    static func saveFxMemo(_ memo: PortfolioFxMemo) {
        guard let data = try? JSONEncoder().encode(memo) else { return }
        SharedStorage.defaults.set(data, forKey: fxMemoKey)
    }

    /// Frischen Devisenkurs USD → `currency` für das Widget merken, unabhängig von einem neuen Stand — sonst blieb das
    /// Widget trotz «Umrechnung» bei USDT, solange kein Stand mit Kurs gelang. Liegt der gespeicherte Stand in einer
    /// anderen Währung, wird das Widget neu gezeichnet (es rechnet mit dem Kurs um). Wie Android `rememberFx`.
    @discardableResult
    static func rememberFx(currency: String, rate: Double?) -> Bool {
        guard let memo = PortfolioWidgetChart.fxMemoToSave(currency: currency, fresh: rate,
                                                           cachedForCurrency: FxRateSource.cached(currency),
                                                           fetchedAt: FxRateSource.fetchedAt(currency),
                                                           now: EpochTime.nowMillis) else { return false }
        saveFxMemo(memo)
        if let stored = load(), CurrencyConversion.normalize(stored.currency) != CurrencyConversion.normalize(currency) {
            WidgetCenter.shared.reloadTimelines(ofKind: kind)
        }
        return true
    }

    /// true, wenn sich der Stand geändert (und versiegelt gespeichert) hat.
    @discardableResult
    static func save(_ snapshot: PortfolioWidgetSnapshot) -> Bool {
        guard load() != snapshot, let data = try? JSONEncoder().encode(snapshot) else { return false }
        // Nicht versiegelt speicherbar (Schlüssel gerade nicht lesbar, Datei nicht schreibbar): nichts ablegen — kein
        // Ausweichen in die App-Group-Einstellungen, dort stünden die Beträge im Klartext. Das
        // Widget zeigt den letzten versiegelten Stand oder keine Werte; die nächste Aktualisierung versucht es neu.
        return writeSnapshotFile(data)
    }

    /// Schreibt die Datei versiegelt und setzt das Backup-Merkmal neu (das atomare Schreiben verliert es). false = nicht
    /// geschrieben (ein Zwischenspeicher, den die nächste Aktualisierung neu berechnet).
    private static func writeSnapshotFile(_ data: Data) -> Bool {
        // Beträge nur verschlüsselt (`ProtectedFile`): ohne Schlüssel schriebe `ProtectedFile.write`
        // Klartext — für diesen Zwischenspeicher lieber gar nichts (wie Android `PortfolioSnapshotSeal`)
        guard case .key(_) = StorageKey.shared.lookup() else { return false }
        do {
            // `requireSealed`: wird der Schlüssel zwischen Prüfung und Schreiben unlesbar (Schlüsselbund gesperrt),
            // wirft `write` statt Klartext abzulegen — der letzte versiegelte Stand bleibt stehen
            try ProtectedFile.write(data, to: snapshotURL, requireSealed: true)
        } catch {
            return false
        }
        BackupExclusion.set(excluded: true, for: snapshotURL)
        return true
    }

    /// Gemerkte Kurs-Stände (`loadHistory`) und Stundenkurse (`loadHourly`) der gehaltenen Coins als Dateien
    /// in der App Group, wie die Momentaufnahme nie im Geräte-Backup (Android: `portfolio_widget` steht nicht
    /// in den Backup-Regeln) — die App-Group-Einstellungen lassen sich nicht je Schlüssel ausnehmen.
    static var historyURL: URL { SharedStorage.directory.appendingPathComponent("portfolio_widget_history.json") }
    static var hourlyURL: URL { SharedStorage.directory.appendingPathComponent("portfolio_widget_hourly.json") }

    static func loadHistory() -> [PricePoint] {
        guard let data = try? Data(contentsOf: historyURL),
              let list = try? JSONDecoder().decode([PricePoint].self, from: data)
        else { return [] }
        return list
    }

    static func saveHistory(_ list: [PricePoint]) {
        if let data = try? JSONEncoder().encode(list) {
            writeCache(data, to: historyURL)
        }
    }

    static func loadHourly() -> [String: [PortfolioTimedPrice]] {
        guard let data = try? Data(contentsOf: hourlyURL),
              let map = try? JSONDecoder().decode([String: [PortfolioTimedPrice]].self, from: data)
        else { return [:] }
        return map
    }

    static func saveHourly(_ map: [String: [PortfolioTimedPrice]]) {
        if let data = try? JSONEncoder().encode(map) {
            writeCache(data, to: hourlyURL)
        }
    }

    /// Schreibt die Datei und nimmt sie aus dem Backup (das atomare Schreiben verliert das Merkmal). Kein Ausweichen
    /// in die Einstellungen: Der Zwischenspeicher entsteht neu.
    private static func writeCache(_ data: Data, to url: URL) {
        do {
            try data.write(to: url, options: [.atomic])
        } catch {
            return
        }
        BackupExclusion.set(excluded: true, for: url)
    }

    /// Letzter Kerzen-Abruf je Coin (ms; nennt die gehaltenen Coins) — höchstens einer je Stunde, auch über App und
    /// Widget. Ebenfalls Datei in der App Group, nie im Geräte-Backup (Android hält sie nur im Speicher).
    static var candleAttemptsURL: URL {
        SharedStorage.directory.appendingPathComponent("portfolio_widget_candle_attempts.json")
    }

    private static func loadCandleAttempts() -> [String: Double] {
        guard let data = try? Data(contentsOf: candleAttemptsURL),
              let map = try? JSONDecoder().decode([String: Double].self, from: data)
        else { return [:] }
        return map
    }

    private static func saveCandleAttempts(_ attempts: [String: Double]) {
        if let data = try? JSONEncoder().encode(attempts) {
            writeCache(data, to: candleAttemptsURL)
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
        let candles: [(open: Int64, close: Double)] = stride(from: 0, to: flat.count, by: 5).map { (i: Int) -> (open: Int64, close: Double) in
            // Feste Typen (für den Type-Checker)
            let close: Double = flat[i + 4]
            return (open: Int64(flat[i]), close: close)
        }
        return PortfolioWidgetSeries.fromCandles(candles, fetchedAt: fetchedAt)
    }


    // MARK: Aktualisieren

    /// Berechnen und speichern; bei Änderung das Widget neu zeichnen (`reload`). Nur über `update` bzw.
    /// `updateInBackground` (nacheinander, `PortfolioWidgetStore+Background.swift`).
    /// Ohne einen einzigen bekannten Kurs bleibt der bisherige Stand stehen (dann nil).
    static func updateNow(transactions: [PortfolioTx], prices: PortfolioPrices, currency: String,
                       rate: Double?, reload: Bool = true,
                       extraHourly: [String: [PortfolioTimedPrice]] = [:],
                       displayFx: PortfolioDisplayFx? = nil) -> PortfolioWidgetSnapshot? {
        if !transactions.isEmpty && prices.updatedAt <= 0 { return nil }
        let settings = SharedStorage.loadSettings()
        // Frischen Kurs merken; ohne (kein Netz, frischer Prozess) der gemerkte — sonst fiele der Stand auf USD
        // zurück und das Widget zeigte trotz «Umrechnung» USDT (wie Android `record`)
        let now = EpochTime.nowMillis
        if let memo = PortfolioWidgetChart.fxMemoToSave(currency: currency, fresh: rate,
                                                        cachedForCurrency: FxRateSource.cached(currency),
                                                        fetchedAt: FxRateSource.fetchedAt(currency), now: now) {
            saveFxMemo(memo)
        }
        let fxRate = PortfolioWidgetChart.fxRate(for: currency, fresh: rate, memo: loadFxMemo(), now: now)
        // USDT zum Marktkurs: Devisenkurs × USDT/USD (Widget, «Heute» und Portfolio-Alarme in der Währung)
        let resolvedRate = fxRate.map {
            PortfolioWidgetChart.usdtFactor(currency: currency, fx: $0, usdtLive: DepegAlarmCheck.liveUsdtUsd(now: now))
        }
        let watched = WatchedAddressStore.load().addresses
        let snapshot = compute(transactions: transactions, prices: prices, currency: currency, rate: resolvedRate,
                               extraHourly: extraHourly, basis: settings.changeBasis.storage,
                               method: settings.costMethod,
                               addresses: AddressBalanceHistory.of(watched),
                               staleAfterMillis: PortfolioPriceFallback.staleAfterMillis(
                                   backgroundIntervalMinutes: settings.backgroundIntervalMinutes),
                               displayFx: displayFx,
                               // Zählende Adressen ohne je geladenes Guthaben: Portfolio-Alarme prüfen dann nicht (seit
                               // über 24 h scheiternde rechnen sie ohne); nicht bestätigte Token wie ein alter Kurs
                               addressState: AddressAlarmState.of(watched, now: now))
        if save(snapshot) && reload {
            WidgetCenter.shared.reloadTimelines(ofKind: kind)
        }
        return snapshot
    }

    /// Nur aus den Zwischenspeichern (ohne Netz), z. B. nach einer neuen Transaktion.
    static func updateFromCache(transactions: [PortfolioTx], currency: String, reload: Bool = true) {
        // Adress-Guthaben mitrechnen wie der Portfolio-Tab (schon zusammengeführt: unverändert)
        let transactions = AddressBalance.withAddresses(transactions, addresses: WatchedAddressStore.load().addresses)
        let coins = transactions.flatMap(\.coins)
        update(transactions: transactions, prices: PortfolioPriceSource.cached(coins), currency: currency,
               rate: FxRateSource.cached(currency), reload: reload)
    }

    /// Kurse und Devisenkurs holen (60 s Zwischenspeicher) — Hintergrund-Aktualisierung
    /// und Widget, damit das Widget nicht vom Öffnen der App abhängt.
    @discardableResult
    static func refresh(reload: Bool = true) async -> PortfolioWidgetSnapshot? {
        // Mit Adress-Guthaben (zuletzt geladener Stand, Einzahlung ohne Kaufpreis) — wie der Portfolio-Tab
        let transactions = AddressBalance.withAddresses(PortfolioStore.load().transactions,
                                                        addresses: WatchedAddressStore.load().addresses)
        let currency = SharedStorage.loadSettings().portfolioCurrency
        guard !transactions.isEmpty else {
            return update(transactions: [], prices: PortfolioPrices(), currency: currency, rate: nil, reload: reload)
        }
        let prices = await PortfolioPriceSource.prices(transactions.flatMap(\.coins))
        // Wie im Portfolio-Tab: nur aufnehmen, wenn mindestens 80 % des Werts einen frischen Kurs haben — je
        // Coin geprüft; Alarme prüfen nur, wenn ALLE bewerteten Coins frisch sind (`stalePrices`)
        let staleAfter = PortfolioPriceFallback.staleAfterMillis(
            backgroundIntervalMinutes: SharedStorage.loadSettings().backgroundIntervalMinutes)
        guard PortfolioPriceFallback.mayRecord(transactions: transactions, prices: prices,
                                               now: EpochTime.nowMillis, staleAfterMillis: staleAfter) else { return nil }
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
        let open = PortfolioCalculator.summarize(transactions, prices: prices.prices, withParts: false).open.map(\.coin)
        guard !open.isEmpty else { return [:] }
        let now = EpochTime.nowMillis
        let merged = PortfolioWidgetSeries.merge(stored: loadHourly(), fresh: known, now: now)
        var attempts = loadCandleAttempts()
        let due = PortfolioWidgetSeries.candleCoins(open, prices: merged,
                                                    lastAttempt: attempts.mapValues { Int64($0) }, now: now,
                                                    stables: PortfolioStables.coins)
        guard !due.isEmpty else { return [:] }
        for coin in due { attempts[coin] = Double(now) }
        // Alte Einträge nicht ewig mitschleppen
        attempts = attempts.filter { Double(now) - $0.value < Double(PortfolioWidgetSeries.keepMillis) }
        saveCandleAttempts(attempts)

        return await withTaskGroup(of: CandleLoad.self) { group in
            for coin in due {
                group.addTask {
                    guard let candles = await CandleDataSource.candles(base: coin, quote: "USDT", interval: .h1, limit: 24),
                          candles.count >= 2 else { return .empty }
                    let list = PortfolioWidgetSeries.fromCandles(candles.map { (open: $0.openTime, close: $0.close) },
                                                                 fetchedAt: EpochTime.nowMillis)
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
