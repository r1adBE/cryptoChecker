import Foundation

/// Eine Position im Portfolio-Widget: Wert in der Anzeigewährung, Anteil am Gesamtwert
/// (0–100) und Kursveränderung «heute» in Prozent (nil = keine Vergleichsbasis).
struct PortfolioWidgetPosition: Codable, Equatable, Sendable {
    var symbol: String
    var value: Double
    var sharePercent: Double
    var change24hPercent: Double?
    /// Gehaltene Menge als Dezimaltext (`DecimalText.plain`) für «1.3 BTC» im Widget
    /// (`WidgetQuantityFormat`); nil = Menge nicht darstellbar (dann ohne Menge).
    var quantity: String? = nil
    /// MEIN Gewinn/Verlust auf dieser Position über 24 h bzw. seit Tagesbeginn (gleiche %-Basis wie
    /// `change24hPercent`) in der Anzeigewährung (`PortfolioPeriodChange.todayByCoin`); nil = unbekannt oder andere
    /// %-Basis (`shown`) — wie `PortfolioPosition.pnlAmount` (Android).
    var pnlAmount: Double? = nil
    /// `pnlAmount` in Prozent von Wert am Anfang + Zugängen; nil = unbekannt.
    var pnlPercent: Double? = nil
    /// Buchungen dieses Coins für die Liste (`PortfolioWidgetEntries`, heutiger Wert in der Anzeigewährung); nil =
    /// keine Buchungen berechnet — wie `PortfolioPosition.entries` (Android).
    var entries: [PortfolioWidgetEntry]? = nil
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
/// `totalUsdt`, `positions`, `otherPositions` und `history` (grosses Widget) und `unrealized` (Ansichten
/// «Übersicht» und «Liste») sind optional: Der Stand für ein leeres Portfolio (`emptyPortfolio`) hat sie nicht (nil).
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
    /// Gesamtwert in USDT; nil = leeres Portfolio.
    var totalUsdt: Double?
    /// Die grössten Positionen, absteigend nach Wert (höchstens `PortfolioWidgetStore.maxPositions`).
    var positions: [PortfolioWidgetPosition]?
    /// Weitere offene Positionen ausser `positions`.
    var otherPositions: Int?
    /// Wertverlauf des heutigen Bestands (Anzeigewährung), aufsteigend nach Zeit.
    var history: [PortfolioWidgetPoint]?
    /// Mit welcher %-Basis (und welchem Tagesbeginn) Veränderung und Verlauf gerechnet sind;
    /// nil = leeres Portfolio (rollende 24 Stunden).
    var stamp: ChangeStamp?
    /// Kursveränderung je offenem Coin in Prozent (gleiche %-Basis wie `changePercent`) — für
    /// «Grösste Bewegungen» im Portfolio-Tab; nil = leeres Portfolio oder andere %-Basis (`shown`).
    var coinChanges: [String: Double]?
    /// Offene Coins ohne aktuellen Kurs — zählen in `total` als 0 (wie der Rechner, App-Hinweis
    /// `portfolio_current_missing`); das Widget zeigt «ohne 1 Coin». nil = leeres Portfolio (= 0),
    /// wie `PortfolioSnapshot.missingPrices` (Android).
    var missingPrices: Int?
    /// Tagesverlauf in USDT für 7 T / 30 T / 1 J (höchstens ein Jahr bis heute, aus den schon geladenen
    /// Tageskursen, `PortfolioWidgetDailyStore`); nil/leer = keine tauglichen Kurse — wie `PortfolioSnapshot.daily`.
    var daily: [PortfolioWidgetDay]?
    /// Veränderung des heutigen Bestands je Zeitraum in USDT, Schlüssel = Tage («7», «30», «365»).
    var periodChanges: [String: PortfolioWidgetChange]?
    /// ± seit Kauf (unrealisiert) in USDT mit Prozent — «Gesamt» der Übersicht im Widget wie in der
    /// Gesamtkarte des Portfolio-Tabs; nil = unbekannt (Kaufpreis fehlt oder leeres Portfolio). Wie
    /// `PortfolioSnapshot.unrealized` (Android).
    var unrealized: PortfolioWidgetChange?
    /// Coins, die in «Heute» fehlen, weil ihnen der Vergleichskurs fehlt
    /// (`PortfolioPeriodChange.todayFromCoinChanges`); nil = leeres Portfolio — wie Android.
    var todaySkipped: [String]?
    /// MEIN Gewinn/Verlust je offenem Coin seit Beginn der %-Basis in USDT (`PortfolioPeriodChange.todayByCoin`)
    /// für die Zeilen im Portfolio-Tab — die Summe ist `changeAmount` (in USDT); nil = leeres Portfolio
    /// oder andere %-Basis (`shown`).
    var coinPnl: [String: PortfolioWidgetChange]?
    /// Bewertete Coins, deren Kurs beim Aufnehmen NICHT frisch war (`PortfolioPriceFallback.staleCoins`) — sie zählen
    /// mit ihrem letzten Kurs; das Widget zeigt «teilweise alter Kurs», Portfolio-Alarme prüfen nicht (ausser
    /// unwesentlich, `staleValueUsdt`). nil = 0.
    /// Wie Android `PortfolioSnapshot.stalePrices`.
    var stalePrices: Int?
    /// Merkzettel der App für «Seit Kauf» und «7 T» in der Anzeigewährung (`PortfolioDisplayFx`) — gespeichert, damit
    /// auch Stände aus dem Hintergrund und dem Widget damit rechnen; nil = keiner (USDT). Wie Android.
    var displayFx: PortfolioDisplayFx?
    /// «Seit Kauf» in `currency` zum Kurs der Erwerbstage wie die Gesamtkarte (`PortfolioDisplayFigures.unrealized`);
    /// `skipped` = fehlende Coins («unvollständig»). nil = unbekannt bzw. leeres Portfolio — das Widget zeigt in der
    /// Anzeigewährung dann «—» statt USDT × heutiger Kurs. Wie Android `unrealizedDisplay`.
    var unrealizedDisplay: PortfolioWidgetChange?
    /// «7 T» in `currency` mit den Tageskursen der App wie die Gesamtkarte (`PortfolioDisplayFigures.periodFx`); nil =
    /// nicht rechenbar: das Widget zeigt dann `periodChanges` in USDT mit Einheit «USDT». Wie Android `weekDisplay`.
    var weekDisplay: PortfolioWidgetChange?
    /// Coins zu `missingPrices` (Grossschreibung, sortiert) — Hinweis «Portfolio-Alarme pausiert»; nil = keine.
    var missingCoins: [String]?
    /// Coins zu `stalePrices` (sortiert) — Hinweis «Portfolio-Alarme pausiert»; nil = keine.
    var staleCoins: [String]?
    /// Wert der Coins mit altem Kurs in USDT (zum letzten Kurs) — Portfolio-Alarme prüfen trotzdem, wenn er unwesentlich
    /// ist (`PortfolioAlarmLogic.staleMaterial`); nil = keine alten Kurse. Wie Android `staleValueUsdt`.
    var staleValueUsdt: Double?
    /// Zählende Adressen ohne je geladenes Guthaben (`AddressBalance.pendingCount`) — fehlen in `total`; Portfolio-Alarme
    /// prüfen dann nicht. nil = 0. Wie Android `addressesPending`.
    var addressesPending: Int?
    /// Zählende Adressen, die seit über 24 Stunden nicht laden (`AddressAlarmState.failing`) — Alarme rechnen ohne sie;
    /// nil = keine. Wie Android `addressesFailing`.
    var addressesFailing: [String]?
    /// Adressen mit nicht bestätigten Token-Guthaben (`AddressAlarmState.staleTokenAddresses`) — wie ein alter Kurs; nil = keine.
    var staleTokenAddresses: [String]?
    /// Wert dieser Token in USDT (`AddressAlarmState.staleTokenValue`); nil = 0. Wie Android `staleTokenValueUsdt`.
    var staleTokenValueUsdt: Double?
    /// Bewertete Coins mit «Kurs ungeprüft» (Token nicht bestätigt, `PortfolioPrices.unverified`), ohne
    /// die schon in `staleCoins` — für Portfolio-Alarme wie ein alter Kurs (Wert `unverifiedValueUsdt`); das Widget zeigt
    /// deswegen NICHT «teilweise alter Kurs». nil = keine. Wie Android `unverifiedCoins`.
    var unverifiedCoins: [String]?
    /// Wert dieser Coins in USDT; nil = 0. Wie Android `unverifiedValueUsdt`.
    var unverifiedValueUsdt: Double?

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

    /// Positionen (leer bei leerem Portfolio).
    var topPositions: [PortfolioWidgetPosition] { positions ?? [] }
    /// Wertverlauf (leer bei leerem Portfolio).
    var valueHistory: [PortfolioWidgetPoint] { history ?? [] }
    /// Coins ohne Kurs (0, wenn nil).
    var missingCount: Int { Swift.max(0, missingPrices ?? 0) }
    /// Coins mit altem Kurs (0, wenn nil).
    var staleCount: Int { Swift.max(0, stalePrices ?? 0) }
    /// Nie geladene zählende Adressen (0, wenn nil).
    var pendingAddressCount: Int { Swift.max(0, addressesPending ?? 0) }
    /// Tagesverlauf (leer ohne taugliche Tageskurse).
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

extension PortfolioWidgetSnapshot {
    /// Stand für die Portfolio-Alarme — Prüfung (`AppData.evaluatePortfolioAlarms`) und Hinweis «pausiert» der
    /// Alarm-Übersicht (`PortfolioAlarmLogic.pause`) lesen ihn gleich. Wie Android `PortfolioSnapshot.toAlarmReading`.
    func alarmReading(usdTo: [String: Double] = [:], usdtUsdLive: Double? = nil) -> PortfolioReading {
        // «Kurs ungeprüft» zählt wie ein alter Kurs (wesentlich → Pause, unwesentlich nicht) — wie Android
        PortfolioReading(total: total, currency: currency, totalUsdt: totalUsdt, changePercent: changePercent, empty: empty,
                         usdTo: usdTo, missingPrices: missingCount, todaySkipped: todaySkipped?.count ?? 0,
                         stalePrices: staleCount, usdtUsdLive: usdtUsdLive, staleValueUsdt: staleValueUsdt,
                         addressesPending: pendingAddressCount, addressesFailing: addressesFailing ?? [],
                         staleTokenAddresses: staleTokenAddresses ?? [],
                         staleTokenValueUsdt: Swift.max(0, staleTokenValueUsdt ?? 0),
                         unverifiedCoins: unverifiedCoins ?? [],
                         unverifiedValueUsdt: Swift.max(0, unverifiedValueUsdt ?? 0))
    }

    /// Hinweis «Portfolio-Alarme pausiert» für die eingeschalteten Alarme `kinds`; nil = keiner.
    func alarmPause(_ kinds: [PortfolioAlarmKind]) -> PortfolioAlarmPause? {
        PortfolioAlarmLogic.pause(kinds, alarmReading(), missingCoins: missingCoins ?? [], staleCoins: staleCoins ?? [])
    }
}
