import Foundation
import WidgetKit

/// Eine Position im Portfolio-Widget: Wert in der Anzeigewährung, Anteil am Gesamtwert
/// (0–100) und Kursveränderung «heute» in Prozent (nil = keine Vergleichsbasis).
struct PortfolioWidgetPosition: Codable, Equatable, Sendable {
    var symbol: String
    var value: Double
    var sharePercent: Double
    var change24hPercent: Double?
    /// Gehaltene Menge als Dezimaltext (`DecimalText.plain`) für «1.3 BTC» im Widget
    /// (`WidgetQuantityFormat`); nil = älterer Stand (dann ohne Menge).
    var quantity: String? = nil
    /// MEIN Gewinn/Verlust auf dieser Position über 24 h bzw. seit Tagesbeginn (gleiche %-Basis wie
    /// `change24hPercent`) in der Anzeigewährung (`PortfolioPeriodChange.todayByCoin`); nil = unbekannt oder
    /// älterer Stand — wie `PortfolioPosition.pnlAmount` (Android).
    var pnlAmount: Double? = nil
    /// `pnlAmount` in Prozent von Wert am Anfang + Zugängen; nil = unbekannt.
    var pnlPercent: Double? = nil
}

/// Ein Punkt des Wertverlaufs: Zeit (ms) und Gesamtwert in der Anzeigewährung.
struct PortfolioWidgetPoint: Codable, Equatable, Sendable {
    var at: Int64
    var value: Double
}

/// Stand des Portfolio-Widgets: Gesamtwert und Veränderung heute in der
/// Umrechnungswährung (`portfolioCurrency`). Die App berechnet ihn, wenn sie die
/// Portfolio-Werte berechnet, und in der Hintergrund-Aktualisierung; das Widget
/// liest ihn aus der App Group (Datei `portfolio_widget.json`, `PortfolioWidgetStore.snapshotURL`).
///
/// `totalUsdt`, `positions`, `otherPositions` und `history` kamen später dazu (grosses
/// Widget), `unrealized` mit den Ansichten «Übersicht» und «Liste». Sie sind optional: Ein gespeicherter Stand ohne sie lädt weiter (dann nil),
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
    /// Offene Coins ohne aktuellen Kurs — zählen in `total` als 0 (wie der Rechner, App-Hinweis
    /// `portfolio_current_missing`); das Widget zeigt «ohne 1 Coin». nil bei älterem Stand (= 0),
    /// wie `PortfolioSnapshot.missingPrices` (Android).
    var missingPrices: Int?
    /// Tagesverlauf in USDT für 7 T / 30 T / 1 J (höchstens ein Jahr bis heute, aus den schon geladenen
    /// Tageskursen, `PortfolioWidgetDailyStore`); nil/leer = keine tauglichen Kurse — wie `PortfolioSnapshot.daily`.
    var daily: [PortfolioWidgetDay]?
    /// Veränderung des heutigen Bestands je Zeitraum in USDT, Schlüssel = Tage («7», «30», «365»).
    var periodChanges: [String: PortfolioWidgetChange]?
    /// ± seit Kauf (unrealisiert) in USDT mit Prozent — «Gesamt» der Übersicht im Widget wie in der
    /// Gesamtkarte des Portfolio-Tabs; nil = unbekannt (Kaufpreis fehlt oder älterer Stand). Wie
    /// `PortfolioSnapshot.unrealized` (Android).
    var unrealized: PortfolioWidgetChange?
    /// Coins, die in «Heute» fehlen, weil ihnen der Vergleichskurs fehlt
    /// (`PortfolioPeriodChange.todayFromCoinChanges`); nil bei älterem Stand — wie Android.
    var todaySkipped: [String]?
    /// MEIN Gewinn/Verlust je offenem Coin seit Beginn der %-Basis in USDT (`PortfolioPeriodChange.todayByCoin`)
    /// für die Zeilen im Portfolio-Tab — die Summe ist `changeAmount` (in USDT); nil bei älterem Stand.
    var coinPnl: [String: PortfolioWidgetChange]?
    /// Bewertete Coins, deren Kurs beim Aufnehmen NICHT frisch war (`PortfolioPriceFallback.staleCoins`) — sie zählen
    /// mit ihrem letzten Kurs; das Widget zeigt «teilweise alter Kurs», Portfolio-Alarme prüfen nicht. nil = 0.
    /// Wie Android `PortfolioSnapshot.stalePrices`.
    var stalePrices: Int?

    init(total: Double, changeAmount: Double?, changePercent: Double?, currency: String, updatedAt: Int64, empty: Bool,
         totalUsdt: Double? = nil, positions: [PortfolioWidgetPosition]? = nil, otherPositions: Int? = nil,
         history: [PortfolioWidgetPoint]? = nil, stamp: ChangeStamp? = nil, coinChanges: [String: Double]? = nil,
         missingPrices: Int? = nil, daily: [PortfolioWidgetDay]? = nil,
         periodChanges: [String: PortfolioWidgetChange]? = nil, unrealized: PortfolioWidgetChange? = nil) {
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
        self.missingPrices = missingPrices
        self.daily = daily
        self.periodChanges = periodChanges
        self.unrealized = unrealized
    }

    /// Für die Anzeige: Stand mit anderer %-Basis oder von einem früheren Tag → Veränderung «—»
    /// (bis zur nächsten Berechnung); der Verlauf bleibt.
    func shown(basis: ChangeBasis, now: Int64) -> PortfolioWidgetSnapshot {
        guard !ChangeBasisMath.isCurrent(stamp: stamp, basis: basis, now: now) else { return self }
        var copy = self
        copy.changeAmount = nil
        copy.changePercent = nil
        copy.positions = positions?.map {
            var p = $0
            p.change24hPercent = nil
            p.pnlAmount = nil
            p.pnlPercent = nil
            return p
        }
        copy.coinChanges = nil
        copy.coinPnl = nil
        return copy
    }

    /// Ohne Zeitpunkt, damit ein leeres Portfolio das Widget nicht bei jeder Runde neu zeichnet.
    static let emptyPortfolio = PortfolioWidgetSnapshot(total: 0, changeAmount: nil, changePercent: nil,
                                                        currency: "USD", updatedAt: 0, empty: true)

    /// Positionen (leer bei älterem Stand).
    var topPositions: [PortfolioWidgetPosition] { positions ?? [] }
    /// Wertverlauf (leer bei älterem Stand).
    var valueHistory: [PortfolioWidgetPoint] { history ?? [] }
    /// Coins ohne Kurs (0 bei älterem Stand).
    var missingCount: Int { Swift.max(0, missingPrices ?? 0) }
    /// Coins mit altem Kurs (0 bei älterem Stand).
    var staleCount: Int { Swift.max(0, stalePrices ?? 0) }
    /// Tagesverlauf (leer bei älterem Stand).
    var dailyPoints: [PortfolioHistoryPoint] { (daily ?? []).map(\.point) }

    /// Mit Tagesverlauf und Veränderung je Zeitraum (`PortfolioWidgetChart.dailyData`).
    func with(_ data: PortfolioWidgetDailyData) -> PortfolioWidgetSnapshot {
        var copy = self
        copy.daily = data.points.isEmpty ? nil : data.points.map(PortfolioWidgetDay.init)
        var changes: [String: PortfolioWidgetChange] = [:]
        for (days, change) in data.changes {
            changes[String(days)] = PortfolioWidgetChange(amount: change.amount, percent: change.percent,
                                                          skipped: change.skipped.isEmpty ? nil : change.skipped)
        }
        copy.periodChanges = changes.isEmpty ? nil : changes
        return copy
    }
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
    /// Höchstens so viele Positionen werden gespeichert (Ansicht «Liste»; ältere Stände: 5).
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
        // Ein Eintrag unter `snapshotKey` ist neuer als die Datei: alter Stand vor der Umstellung
        // (`migrateSnapshotToFile`) oder Ausweichweg bis Build 59, als die Datei nicht schreibbar war. Seit Build 60
        // entsteht er nie mehr; der nächste Start der App zieht ihn um oder entfernt ihn.
        guard let data = SharedStorage.defaults.data(forKey: snapshotKey) ?? (try? ProtectedFile.read(snapshotURL))
        else { return nil }
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
                                                           now: TimeUtils.nowMillis) else { return false }
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
        // Ausweichen in die App-Group-Einstellungen mehr (Build 60), dort stünden die Beträge im Klartext. Das
        // Widget zeigt den letzten versiegelten Stand oder keine Werte; die nächste Aktualisierung versucht es neu.
        return writeSnapshotFile(data)
    }

    /// Schreibt die Datei versiegelt und setzt das Backup-Merkmal neu (das atomare Schreiben verliert es). Der alte
    /// Klartext-Eintrag in den Einstellungen (bis Build 59) fällt in jedem Fall weg — auch wenn nicht geschrieben
    /// werden konnte: ein Zwischenspeicher, den die nächste Aktualisierung neu berechnet. false = nicht geschrieben.
    private static func writeSnapshotFile(_ data: Data) -> Bool {
        defer { SharedStorage.defaults.removeObject(forKey: snapshotKey) }
        // Beträge nur verschlüsselt (`ProtectedFile`, Build 59): ohne Schlüssel schriebe `ProtectedFile.write`
        // Klartext — für diesen Zwischenspeicher lieber gar nichts (wie Android `PortfolioSnapshotSeal`)
        guard case .key(_) = StorageKey.shared.lookup() else { return false }
        do {
            try ProtectedFile.write(data, to: snapshotURL)
        } catch {
            return false
        }
        BackupExclusion.set(excluded: true, for: snapshotURL)
        return true
    }

    /// Beim Start (`PortfolioStore.applyBackupPolicy`): Stand aus den Einstellungen (bis Build 59) versiegelt in die
    /// Datei übernehmen (der Eintrag ist immer der neuere, siehe `load`) oder, wenn das nicht geht, nur entfernen.
    static func migrateSnapshotToFile() {
        guard let pending = SharedStorage.defaults.data(forKey: snapshotKey) else { return }
        _ = writeSnapshotFile(pending)
    }

    /// Gemerkte Kurs-Stände (`loadHistory`) und Stundenkurse (`loadHourly`) der gehaltenen Coins als Dateien
    /// in der App Group, wie die Momentaufnahme nie im Geräte-Backup (Android: `portfolio_widget` steht nicht
    /// in den Backup-Regeln) — die App-Group-Einstellungen lassen sich nicht je Schlüssel ausnehmen. Ältere
    /// Einträge unter `historyKey`/`hourlyKey` zieht `migrateCachesToFiles` (bzw. das nächste Speichern) um.
    static var historyURL: URL { SharedStorage.directory.appendingPathComponent("portfolio_widget_history.json") }
    static var hourlyURL: URL { SharedStorage.directory.appendingPathComponent("portfolio_widget_hourly.json") }

    static func loadHistory() -> [PricePoint] {
        guard let data = readCache(historyURL, legacyKey: historyKey),
              let list = try? JSONDecoder().decode([PricePoint].self, from: data)
        else { return [] }
        return list
    }

    private static func saveHistory(_ list: [PricePoint]) {
        if let data = try? JSONEncoder().encode(list) {
            writeCache(data, to: historyURL, legacyKey: historyKey)
        }
    }

    static func loadHourly() -> [String: [PortfolioTimedPrice]] {
        guard let data = readCache(hourlyURL, legacyKey: hourlyKey),
              let map = try? JSONDecoder().decode([String: [PortfolioTimedPrice]].self, from: data)
        else { return [:] }
        return map
    }

    private static func saveHourly(_ map: [String: [PortfolioTimedPrice]]) {
        if let data = try? JSONEncoder().encode(map) {
            writeCache(data, to: hourlyURL, legacyKey: hourlyKey)
        }
    }

    /// Ein Eintrag unter `legacyKey` ist neuer als die Datei (Stand vor der Umstellung).
    private static func readCache(_ url: URL, legacyKey: String) -> Data? {
        SharedStorage.defaults.data(forKey: legacyKey) ?? (try? Data(contentsOf: url))
    }

    /// Schreibt die Datei, nimmt sie aus dem Backup (das atomare Schreiben verliert das Merkmal) und entfernt
    /// den alten Eintrag. Kein Ausweichen in die Einstellungen: Der Zwischenspeicher entsteht neu.
    private static func writeCache(_ data: Data, to url: URL, legacyKey: String) {
        do {
            try data.write(to: url, options: [.atomic])
        } catch {
            return
        }
        BackupExclusion.set(excluded: true, for: url)
        SharedStorage.defaults.removeObject(forKey: legacyKey)
    }

    /// Beim Start (`PortfolioStore.applyBackupPolicy`): Kurs-Stände und Stundenkurse aus den Einstellungen
    /// einmalig in ihre Dateien übernehmen, danach die Schlüssel entfernen. Wiederholbar; schlägt das
    /// Schreiben fehl, bleibt der Eintrag stehen.
    static func migrateCachesToFiles() {
        for (url, key) in [(historyURL, historyKey), (hourlyURL, hourlyKey)] {
            guard let pending = SharedStorage.defaults.data(forKey: key) else { continue }
            writeCache(pending, to: url, legacyKey: key)
        }
        // Kerzen-Abrufe lagen als Wörterbuch (nicht als Data) in den Einstellungen
        if let pending = legacyCandleAttempts() {
            saveCandleAttempts(pending)
        }
    }

    /// Letzte Kerzen-Abrufe je Coin (nennen die gehaltenen Coins): ebenfalls Datei in der App Group,
    /// nie im Geräte-Backup (Android hält sie nur im Speicher). Ältere Einträge unter
    /// `candleAttemptsKey` zieht `migrateCachesToFiles` (bzw. das nächste Speichern) um.
    static var candleAttemptsURL: URL {
        SharedStorage.directory.appendingPathComponent("portfolio_widget_candle_attempts.json")
    }

    /// Alter Eintrag in den Einstellungen (Stand vor der Umstellung), sonst nil.
    private static func legacyCandleAttempts() -> [String: Double]? {
        SharedStorage.defaults.dictionary(forKey: candleAttemptsKey) as? [String: Double]
    }

    private static func loadCandleAttempts() -> [String: Double] {
        // Ein Eintrag in den Einstellungen ist neuer als die Datei (wie `readCache`)
        if let legacy = legacyCandleAttempts() { return legacy }
        guard let data = try? Data(contentsOf: candleAttemptsURL),
              let map = try? JSONDecoder().decode([String: Double].self, from: data)
        else { return [:] }
        return map
    }

    private static func saveCandleAttempts(_ attempts: [String: Double]) {
        if let data = try? JSONEncoder().encode(attempts) {
            writeCache(data, to: candleAttemptsURL, legacyKey: candleAttemptsKey)
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

    /// Zweite Zeile «≈ … USDT» nur, wenn gewünscht und die Anzeigewährung nicht selbst ein USD-Stablecoin ist (ohne
    /// Devisenkurs zeigt das Widget USDT). USD zeigt sie wie jede Fiat-Währung (USDT zum Marktkurs USDT/USD). Wie Android.
    static func showsUsdt(enabled: Bool, currency: String) -> Bool {
        enabled && !PortfolioWidgetChart.collapsesToUsdt(currency)
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
    ///   - amounts: gehaltene Menge je Coin (inkl. beobachteter Adressen) für `quantity`
    static func topPositions(valuesUsd: [String: Double?], totalUsd: Double, factor: Double,
                             changes: [String: Double], limit: Int = maxPositions,
                             amounts: [String: Double] = [:])
        -> (positions: [PortfolioWidgetPosition], others: Int) {
        var priced: [(coin: String, value: Double)] = []
        for (coin, value) in valuesUsd {
            if let value, value.isFinite, value >= 0 { priced.append((coin, value)) }
        }
        priced.sort { $0.value != $1.value ? $0.value > $1.value : $0.coin < $1.coin }
        let top = priced.prefix(Swift.max(0, limit)).map { item in
            PortfolioWidgetPosition(symbol: item.coin, value: item.value * factor,
                                    sharePercent: sharePercent(item.value, total: totalUsd),
                                    change24hPercent: changes[item.coin],
                                    quantity: amounts[item.coin].map { DecimalText.plain($0) }.flatMap { $0.isEmpty ? nil : $0 })
        }
        return (Array(top), valuesUsd.count - top.count)
    }

    /// Wertverlauf des HEUTIGEN Bestands über die gemerkten Stände (vor `stamp`) und den
    /// aktuellen Stand — wie «heute»: Käufe und Verkäufe seither verschieben die Linie
    /// nicht. Fehlt einem Coin in einem Stand der Kurs, zählt der aktuelle.
    /// `addressSteps`: Adress-Guthaben je Coin als Stufen (`PortfolioWidgetSeries.amountAt`) — nie rückwirkend.
    static func valueHistory(holdings: [String: Double], history: [PricePoint], current: [String: Double],
                             stamp: Int64, factor: Double,
                             addressSteps: [String: [AddressObservation]] = [:]) -> [PortfolioWidgetPoint] {
        let open = holdings.filter { $0.value > PortfolioCalculator.eps && $0.value.isFinite }
        guard !open.isEmpty else { return [] }
        func valid(_ price: Double?) -> Double? {
            guard let price, price > 0, price.isFinite else { return nil }
            return price
        }
        func value(_ prices: [String: Double], at time: Int64) -> Double {
            var sum = 0.0
            for (coin, amount) in open {
                let held = PortfolioWidgetSeries.amountAt(coin, amount: amount, time: time, addressSteps: addressSteps)
                sum += held * (valid(prices[coin]) ?? valid(current[coin]) ?? 0)
            }
            return sum
        }
        let past = history
            .filter { $0.at < stamp && stamp - $0.at <= historyMaxAgeMillis }
            .sorted { $0.at < $1.at }
            .map { PortfolioWidgetPoint(at: $0.at, value: value($0.prices, at: $0.at) * factor) }
        return past + [PortfolioWidgetPoint(at: stamp, value: value(current, at: stamp) * factor)]
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

    /// Kurse für Kurs-Stand und «heute»-Vergleich: die abgefragten Kurse, für die offenen Coins
    /// überschrieben mit dem Kurs nach der Stablecoin-Regel (`PortfolioStables`, z. B. USDC ohne
    /// USDCUSDT = 1) — wie `current` in `PortfolioSnapshotUpdater.kt`. Sonst fehlte ein solcher
    /// Stablecoin in den Ständen und «heute» rechnete ohne ihn (falsche Prozente).
    static func samplePrices(raw: [String: Double], open: [CoinPosition]) -> [String: Double] {
        var out = raw
        for p in open {
            if let price = p.currentPrice, price > 0, price.isFinite { out[p.coin] = price }
        }
        return out
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
                        basis: ChangeBasis = .ROLLING_24H,
                        dailyPrices: [String: PortfolioWidgetDailyCoin]? = nil,
                        routed: Set<String>? = nil,
                        method: CostMethod = .defaultMethod,
                        addresses: [AddressBalanceHistory] = [],
                        staleAfterMillis: Int64? = nil) -> PortfolioWidgetSnapshot {
        guard !transactions.isEmpty else { return .emptyPortfolio }
        let summary = PortfolioCalculator.summarize(transactions, prices: prices.prices, method: method)
        var holdings: [String: Double] = [:]
        for p in summary.open { holdings[p.coin] = p.holdings }
        guard !holdings.isEmpty else { return .emptyPortfolio }

        var history = loadHistory()
        let changeStamp = ChangeBasisMath.stamp(basis, now: now)
        let dayStart: Int64? = basis.isDay ? changeStamp.dayStart : nil
        let ref = dayStart.map { reference(in: history, dayStart: $0) } ?? reference(in: history, now: now)
        // Stablecoin-Regel schon angewandt (wie Android), auch im gemerkten Stand
        let sampled = samplePrices(raw: prices.prices, open: summary.open)
        // Bewertete Coins ohne frischen Kurs (je Coin, `PortfolioPriceFallback.staleCoins`; nil = nicht prüfen):
        // zählen mit dem letzten Kurs, der Stand ist dann als «teilweise alter Kurs» markiert, Portfolio-Alarme
        // prüfen nicht, und ihr alter Kurs wird nie als Kurs «jetzt» gemerkt — wie Android `record`
        var pricedValues: [String: Double] = [:]
        for p in summary.open {
            if let v = p.value { pricedValues[PortfolioCalculator.normalizeCoin(p.coin)] = v }
        }
        let staleCoins: Set<String> = staleAfterMillis.map {
            Set(PortfolioPriceFallback.staleCoins(values: pricedValues, times: prices.times, now: now, staleAfterMillis: $0))
        } ?? []
        let delta = ref.flatMap { change(holdings: holdings, prices: sampled, reference: $0.prices) }
        let past = history
        if prices.updatedAt > 0 {
            let freshSampled = sampled.filter { !staleCoins.contains(PortfolioCalculator.normalizeCoin($0.key)) }
            history = record(history, prices: freshSampled, at: now)
            saveHistory(history)
        }

        // Faktor USDT → Währung (Devisenkurs × USDT/USD, auch für USD); ohne Devisenkurs in USDT (1:1 gerechnet —
        // nicht «USD», das in USD nun USDT zum Marktkurs meint; wie Android).
        // Der aktuelle Kurs gilt auch für die Punkte des Verlaufs: er reicht nur 24 h zurück,
        // EZB-Tageskurse (`PortfolioHistoryFx`) brächten dort nur einen künstlichen Sprung.
        let factor: Double
        let label: String
        if let rate, rate > 0, rate.isFinite {
            factor = rate
            label = currency
        } else {
            factor = 1
            label = PortfolioStables.valuation
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
        // (auch USDC, DAI …: ihr Verlauf zeigt eine Abkopplung; nur USDT gilt immer 1)
        var fresh: [String: [PortfolioTimedPrice]] = [:]
        for coin in holdings.keys where PortfolioStables.needsQuote(coin) {
            var list = extraHourly[coin] ?? []
            list += widgetSparkPrices(coin, now: stamp)
            for sample in past where stamp - sample.at <= PortfolioWidgetSeries.keepMillis {
                if let price = sample.prices[coin] { list.append(PortfolioTimedPrice(at: sample.at, price: price)) }
            }
            if let price = current[coin], !staleCoins.contains(PortfolioCalculator.normalizeCoin(coin)) {
                list.append(PortfolioTimedPrice(at: stamp, price: price))
            }
            if !list.isEmpty { fresh[coin] = list }
        }
        let held = Set(holdings.keys.map { $0.uppercased() })
        let hourlyPrices = PortfolioWidgetSeries.merge(stored: loadHourly(), fresh: fresh, now: stamp)
            .filter { held.contains($0.key) }
        if prices.updatedAt > 0 { saveHourly(hourlyPrices) }
        // Adress-Anteil im 24-h-Verlauf nur mit dem damals beobachteten Guthaben (nie rückwirkend) — wie Android
        let addressSteps = AddressBalanceHistory.stepsByCoin(addresses)
        let hourly: [PortfolioWidgetPoint]
        let hourlyChange: PortfolioChange?
        var changes = coinChanges(current: current, reference: ref?.prices)
        let pastShown: [PricePoint]
        if let dayStart {
            // Seit Tagesbeginn: Verlauf ab 00:00, Veränderung gegen den Wert zum Tagesbeginn
            hourly = PortfolioWidgetSeries.hourlySince(holdings: holdings, current: current, prices: hourlyPrices,
                                                       dayStart: dayStart, now: stamp, factor: factor,
                                                       stables: PortfolioStables.coins, addressSteps: addressSteps)
            hourlyChange = PortfolioWidgetSeries.changeSince(hourly, dayStart: dayStart)
            for (coin, change) in PortfolioWidgetSeries.coinChangesSince(current: current, prices: hourlyPrices,
                                                                         dayStart: dayStart) {
                changes[coin] = change
            }
            pastShown = past.filter { $0.at >= dayStart }
        } else {
            hourly = PortfolioWidgetSeries.hourly(holdings: holdings, current: current, prices: hourlyPrices,
                                                  now: stamp, factor: factor,
                                                  stables: PortfolioStables.coins, addressSteps: addressSteps)
            hourlyChange = PortfolioWidgetSeries.drawable(hourly) ? PortfolioWidgetSeries.change(hourly) : nil
            // Stundenkurse vor der Vergleichsbasis (sie messen genau 24 h)
            for (coin, change) in PortfolioWidgetSeries.coinChanges(current: current, prices: hourlyPrices, now: stamp) {
                changes[coin] = change
            }
            pastShown = past
        }
        // Seit Tagesbeginn schon ab zwei Punkten (der Chart wächst über den Tag)
        let useHourly = dayStart != nil ? PortfolioWidgetSeries.drawableDay(hourly) : PortfolioWidgetSeries.drawable(hourly)
        let top = topPositions(valuesUsd: values, totalUsd: summary.totalValue, factor: factor, changes: changes,
                               amounts: holdings)
        let points = useHourly
            ? hourly
            : valueHistory(holdings: holdings, history: pastShown, current: current, stamp: stamp, factor: factor,
                           addressSteps: addressSteps)
        var snapshot = PortfolioWidgetSnapshot(
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
            coinChanges: changes,
            missingPrices: summary.missingCurrentPrices.count,
            // «Gesamt» der Übersicht: ± seit Kauf in USDT wie in der Gesamtkarte des Portfolio-Tabs
            unrealized: summary.unrealized.flatMap { $0.isFinite
                ? PortfolioWidgetChange(amount: $0, percent: summary.unrealizedPercent) : nil }
        )
        // «Heute» für Karte, Widget und Portfolio-Alarm aus EINER Rechnung: Kursänderung je Coin mit
        // Vergleichskurs (Coins ohne fehlen statt flach mitzuzählen), um Käufe/Verkäufe seit Beginn der
        // %-Basis berichtigt — wie Android `withHonestToday`
        let todayStart = ChangeBasisMath.dayStart(basis, now: now) ?? (now - 86_400_000)
        // «Heute»: eigener Bestand + zuletzt beobachtetes Adress-Guthaben; die Reihe rechnet Änderungen seit Beginn
        // der %-Basis als Zu-/Abfluss heraus (nie rückwirkend) — wie Android `todayHoldings`
        let observed = AddressBalanceHistory.latestByCoin(addresses)
        var ledgerHoldings: [String: Double] = [:]
        for p in summary.open {
            ledgerHoldings[p.coin] = PortfolioDecimal.double(
                p.ledgerHoldingsExact + (observed[PortfolioCalculator.normalizeCoin(p.coin)] ?? 0))
        }
        let adjusted = PortfolioPeriodChange.todayFromCoinChanges(holdings: ledgerHoldings, current: current,
                                                                  coinChanges: changes)
            .flatMap { PortfolioPeriodChange.todayAdjusted($0, trades: transactions, current: current,
                                                           coinChanges: changes, windowStart: todayStart,
                                                           addresses: addresses, method: method) }
        snapshot.changeAmount = adjusted.flatMap { $0.amount.isFinite ? $0.amount * factor : nil }
        snapshot.changePercent = adjusted?.percent.flatMap { $0.isFinite ? $0 : nil }
        snapshot.todaySkipped = adjusted?.skipped ?? []
        // Dieselbe Rechnung je Coin für die Zeilen (Summe = «Heute»): USDT für den Portfolio-Tab, in der
        // Anzeigewährung an den Positionen des Widgets — wie Android `withHonestToday`
        let byCoin = PortfolioPeriodChange.todayByCoin(holdings: ledgerHoldings, trades: transactions, current: current,
                                                       coinChanges: changes, windowStart: todayStart,
                                                       addresses: addresses, method: method)
        var coinPnl: [String: PortfolioWidgetChange] = [:]
        for (coin, c) in byCoin {
            coinPnl[coin] = PortfolioWidgetChange(amount: c.amount, percent: c.percent.flatMap { $0.isFinite ? $0 : nil })
        }
        snapshot.coinPnl = coinPnl
        snapshot.stalePrices = staleCoins.isEmpty ? nil : staleCoins.count
        snapshot.positions = snapshot.positions?.map { p in
            var p = p
            let pnl = coinPnl[PortfolioCalculator.normalizeCoin(p.symbol)]
            p.pnlAmount = pnl.map { $0.amount * factor }
            p.pnlPercent = pnl?.percent
            return p
        }
        // 7 T / 30 T / 1 J: aus den Tageskursen, die der Wertverlauf der App schon geladen hat (ohne Netz)
        let dailyMap = dailyPrices ?? PortfolioWidgetDailyStore.load()
        let cached = PortfolioWidgetDailyStore.prices(dailyMap)
        // Dieselben Reihen wie Verlauf, «7 T» und «1 J» der App: eigene Börse, sonst Binance skaliert —
        // ohne Binance-Bezugskurs jetzt fehlt der Coin (`PortfolioExchangeRoute.historyCloses`)
        let routes = routed ?? Set(PortfolioExchangeRouteStore.load().routes.keys)
        let routedHistory = PortfolioExchangeRoute.historyCloses(
            cached.closes, ranges: cached.ranges, own: PortfolioWidgetDailyStore.own(dailyMap),
            routed: routes.union(prices.sources.keys), failed: Set(prices.exchangeFailed.keys),
            prices: current, binance: prices.binance)
        let daily = PortfolioWidgetChart.dailyData(trades: transactions,
                                                   closes: routedHistory.closes,
                                                   ranges: routedHistory.ranges,
                                                   holdings: holdings, current: current,
                                                   todayEpochDay: LocalDay.today().epochDay,
                                                   todayUtcDay: PortfolioHistory.epochDay(utcMillis: now),
                                                   dayEndMillis: { PortfolioHistory.dayEndMillis($0) },
                                                   addresses: addresses, method: method)
        return snapshot.with(daily)
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
        let settings = SharedStorage.loadSettings()
        // Frischen Kurs merken; ohne (kein Netz, frischer Prozess) der gemerkte — sonst fiele der Stand auf USD
        // zurück und das Widget zeigte trotz «Umrechnung» USDT (wie Android `record`)
        let now = TimeUtils.nowMillis
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
        let snapshot = compute(transactions: transactions, prices: prices, currency: currency, rate: resolvedRate,
                               extraHourly: extraHourly, basis: settings.changeBasis.storage,
                               method: settings.costMethod,
                               addresses: AddressBalanceHistory.of(WatchedAddressStore.load().addresses),
                               staleAfterMillis: PortfolioPriceFallback.staleAfterMillis(
                                   backgroundIntervalMinutes: settings.backgroundIntervalMinutes))
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
                                               now: TimeUtils.nowMillis, staleAfterMillis: staleAfter) else { return nil }
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
