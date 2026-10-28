import Foundation

// Steuer-Exporte eines Kalenderjahrs — wie `TaxExport.kt` (Android), dort beschrieben. Keine Steuerberatung.

/// Welche Datei des Steuer-Exports.
enum TaxExportKind: Sendable {
    case gains
    case income
}

/// Reine Rechnung und CSV der Steuer-Exporte — gleich wie `TaxExport.kt`: Gewinne je Veräusserung mit Verkaufsdatum im
/// Jahr (nach der Einstandsmethode; mehr verkauft als erfasst: Rest ohne Kaufpreis), Einkünfte aus Belohnungen,
/// Umrechnung mit dem EZB-Tageskurs des Datums (letzter Tag davor, höchstens `fxMaxGapDays` Tage; Ø: laufender Ø in der
/// Anzeigewährung, jeder Zugang zu seinem Kurs); «> 1 Jahr» = Verkaufsdatum nach Kaufdatum + 1 Kalenderjahr (§ 23 EStG;
/// 29.2. → 28.2.). Daten in der Zeitzone des Geräts (in der CSV genannt). Summe nur über vollständige Zeilen, dazu
/// «Erlös ohne Kaufpreis». CSV wie der Stichtag-Export (BOM, CRLF, `CutoffCsvFormat`).
enum TaxExport {

    /// Devisenkurs höchstens so viele Tage vor dem Datum (Wochenende, Feiertage).
    static let fxMaxGapDays = 7

    // Steuerjahr und Daten bewusst in der Zeitzone des Geräts (`timeZone`): massgebend ist das rechtliche (lokale)
    // Datum des Geschäfts — ein Verkauf am 31.12. um 23:30 Zürich gehört ins alte Jahr, auch wenn es in UTC schon der
    // 1.1. ist. Wertverlauf, «Heute/7 T/1 J» und Tageskerzen rechnen dagegen mit UTC-Tagen (`PortfolioHistory.utcDayEnd`)
    // — eine Transaktion um Mitternacht kann im Chart daher auf einen anderen Tag fallen als in der Steuer-CSV. Wie Android.
    static func localDay(_ millis: Int64, timeZone: TimeZone) -> LocalDay {
        LocalDay(date: Date(timeIntervalSince1970: Double(millis) / 1000), timeZone: timeZone)
    }

    /// Eigene Transaktionen samt Eröffnungsposten der Adressen, ohne Adress-Teil ohne Einstand — dieselbe Sicht wie die
    /// Veräusserungen (wie `AddressBalance.split` in `TaxEngines` und `PortfolioCalculator.disposals`). Sonst fehlte der
    /// Posten einer Adress-Wallet als Bestand: Eine Gebühr darauf stünde als «Gebühr auf Bestand ohne Kaufpreis», und ein
    /// Jahr nur mit Posten fehlte in der Jahresauswahl. Die Posten sind schon aufs beobachtete Guthaben begrenzt
    /// (`AddressCostLots`) und stecken nicht im Adress-Teil — nichts zählt doppelt. Wie Android.
    private static func real(_ trades: [PortfolioTx]) -> [PortfolioTx] {
        trades.filter { !AddressBalance.isAddressPart($0.id) && $0.amountExact > 0 }
    }

    /// Jahre mit Transaktionen (samt Eröffnungsposten), neueste zuerst.
    static func years(_ trades: [PortfolioTx], timeZone: TimeZone) -> [Int] {
        years(trades, timeZone: timeZone, template: .NEUTRAL)
    }

    /// Steuerjahre (`TaxTemplate.periodOf`, Beginnjahr) mit Transaktionen, neueste zuerst.
    static func years(_ trades: [PortfolioTx], timeZone: TimeZone, template: TaxTemplate) -> [Int] {
        Array(Set(real(trades).map { template.periodOf(localDay($0.time, timeZone: timeZone)) })).sorted(by: >)
    }

    /// Standard: letztes volles Jahr mit Transaktionen (vor `today`), sonst das neueste; nil ohne Jahre.
    static func defaultYear(_ years: [Int], today: LocalDay) -> Int? {
        defaultYear(years, today: today, template: .NEUTRAL)
    }

    /// Standard: letztes abgeschlossenes Steuerjahr mit Transaktionen, sonst das neueste; nil ohne Jahre.
    static func defaultYear(_ years: [Int], today: LocalDay, template: TaxTemplate) -> Int? {
        let current = template.periodOf(today)
        return years.filter { $0 < current }.max() ?? years.max()
    }

    /// Mehr als ein Jahr gehalten: `sold` nach `acquired` + 1 Kalenderjahr (29.2. → 28.2. wie `plusYears`).
    static func isLongTerm(acquired: LocalDay, sold: LocalDay) -> Bool {
        let year = acquired.year + 1
        let leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        let day = acquired.month == 2 && acquired.day == 29 && !leap ? 28 : acquired.day
        return sold > LocalDay(year, acquired.month, day)
    }

    /// Stichtag des Bestands am Jahresende.
    static func yearEnd(_ year: Int) -> CutoffDay { CutoffDay(year: year, month: 12, day: 31) }

    /// Kalenderjahr als Steuerjahr (Neutral).
    static func calendar(_ year: Int) -> TaxPeriod { TaxTemplate.NEUTRAL.period(year) }

    static func gainsFileName(_ year: Int) -> String { "cryptochecker-gewinne-\(year).csv" }

    static func incomeFileName(_ year: Int) -> String { "cryptochecker-einkuenfte-\(year).csv" }

    /// Dateiname der Jahresübersicht einer Vorlage, z. B. «cryptochecker-jahresuebersicht-uk-2025.csv».
    static func summaryFileName(_ template: TaxTemplate, year: Int) -> String {
        "cryptochecker-jahresuebersicht-\(template.rawValue.lowercased())-\(year).csv"
    }

    /// Kopfzeilen der Arbeitsunterlage: Bei CH steht «Vermögenssteuer nicht gerechnet – Kursliste ESTV» (`chWealthNote`)
    /// gleich unter dem Titel (Zeile 2) — in jeder CSV der Vorlage, nicht nur als Vermerk weiter unten. Andere Vorlagen
    /// oder leerer Text: unverändert. Wie `TaxExport.withChWealthNote` (Android).
    static func withChWealthNote(_ template: TaxTemplate, header: [String], chWealthNote: String) -> [String] {
        guard template == .CH, !chWealthNote.isEmpty, !header.contains(chWealthNote) else { return header }
        return Array(header.prefix(1)) + [chWealthNote] + Array(header.dropFirst())
    }

    /// Freigrenze § 23 Abs. 3 Satz 5 EStG in EUR (steuerfrei bei WENIGER als der Grenze): 1000 € ab 2024, 600 € 2008–2023
    /// (Unternehmensteuerreformgesetz 2008, ab Veranlagungszeitraum 2008), 512 € bis 2007 — wie `privateSalesLimitEur` (Android).
    static func privateSalesLimitEur(_ year: Int) -> Decimal {
        if year >= 2024 { return 1000 }
        if year >= 2008 { return 600 }
        return 512
    }

    /// Freigrenze § 22 Nr. 3 Satz 2 EStG (Einkünfte aus Leistungen): steuerfrei, wenn weniger als 256 € im Jahr.
    static let incomeLimitEur: Decimal = 256

    /// AT Altbestand ≤ 1 Jahr (Spekulationsgeschäft, § 30 EStG a. F.): Freigrenze § 31 Abs. 3 EStG a. F. — steuerfrei, wenn
    /// höchstens 440 € im Kalenderjahr. Wie Android `AT_SPECULATION_LIMIT_EUR`.
    static let atSpeculationLimitEur: Decimal = 440

    /// USD → Zielwährung am `date`: USD = 1; sonst der Kurs des Tags oder des letzten Tags davor (höchstens
    /// `fxMaxGapDays` Tage); `rates` = Tag (epochDay) → Kurs, nil = nicht geladen.
    /// Mit `usdtUsd` (USDT/USD-Tagesschluss je Datum): USDT → Zielwährung = Kurs × USDT/USD
    /// DES Tags; ohne Schluss × 1 (`usdtApproximateOn` markiert die Zeile). nil = USDT gilt 1 USD.
    static func rateOn(_ rates: [Int: Decimal]?, date: LocalDay, currency: String,
                       usdtUsd: [Int: Decimal]? = nil) -> Decimal? {
        let fx: Decimal
        if currency.trimmingCharacters(in: .whitespaces).uppercased() == "USD" {
            fx = 1
        } else {
            guard let value = fxOnOrBefore(rates, day: date.epochDay) else { return nil }
            fx = value
        }
        guard usdtUsd != nil, let usdt = usdtUsdOn(usdtUsd, date: date) else { return fx }
        return PortfolioDecimal.multiply(fx, usdt)
    }

    /// Kurs des Tags `day` oder des letzten Tags davor mit Kurs > 0, höchstens `fxMaxGapDays` Tage zurück: höchstens acht
    /// direkte Abfragen statt die ganze Kursreihe je Zeile zu durchsuchen — wie Android `fxOnOrBefore`.
    static func fxOnOrBefore(_ rates: [Int: Decimal]?, day: Int) -> Decimal? {
        guard let rates, !rates.isEmpty else { return nil }
        for d in stride(from: day, through: day - fxMaxGapDays, by: -1) {
            if let value = rates[d], value > 0 { return value }
        }
        return nil
    }

    /// USDT/USD-Tagesschluss am `date` aus `usdtUsd` (Tag → Schluss), nur plausibel (`PortfolioStables.usdtUsd`); nil =
    /// keiner (kein Schluss eines anderen Tags). Wie Android.
    static func usdtUsdOn(_ usdtUsd: [Int: Decimal]?, date: LocalDay) -> Decimal? {
        guard let close = usdtUsd?[date.epochDay],
              !PortfolioStables.usdtUsd(PortfolioDecimal.double(close)).approximate else { return nil }
        return close
    }

    /// Rechnet `rateOn` am `date` mit USDT = 1 als Näherung (USDT/USD-Kurse da, aber kein Schluss DES Tags)? Wie Android.
    static func usdtApproximateOn(_ usdtUsd: [Int: Decimal]?, date: LocalDay) -> Bool {
        usdtUsd != nil && usdtUsdOn(usdtUsd, date: date) == nil
    }

    /// Veräusserungen mit Verkaufsdatum im Jahr `year`, wie `PortfolioCalculator.disposals` sortiert.
    static func disposals(in trades: [PortfolioTx], method: CostMethod, year: Int,
                          timeZone: TimeZone) -> [PortfolioDisposal] {
        disposals(in: trades, method: TaxMethod.of(method), period: calendar(year), timeZone: timeZone)
    }

    /// Veräusserungen nach `method` mit Verkaufsdatum im Steuerjahr `period` (`TaxEngines.disposals`; `fx` = USDT →
    /// Steuerwährung zu einem Zeitpunkt, CA: Superficial Loss in CAD).
    static func disposals(in trades: [PortfolioTx], method: TaxMethod, period: TaxPeriod, timeZone: TimeZone,
                          priceAt: ((String, Int64) -> Decimal?)? = nil,
                          fx: ((Int64) -> Decimal?)? = nil,
                          rules: TaxEventRules? = nil,
                          walletFrom: LocalDay? = nil) -> [PortfolioDisposal] {
        // Adress-Einträge bleiben drin: keine Veräusserungen (die Rechnungen trennen sie ab), FR braucht sie für den
        // Gesamtwert des Portfolios — wie Android
        TaxEngines.disposals(trades.filter { $0.amountExact > 0 }, method: method, timeZone: timeZone, priceAt: priceAt, fx: fx,
                             rules: rules, walletFrom: walletFrom)
            .filter { period.contains(localDay($0.sellTime, timeZone: timeZone)) }
    }

    /// Einstand von Netzwerkgebühren ohne bezahlten Posten mit Datum im Jahr `year`, zeitlich — wie
    /// `lostFeeCostsIn` (Android); für den Vermerk im Gewinne-Export. Kein realisierter Gewinn/Verlust.
    static func lostFeeCosts(in trades: [PortfolioTx], method: CostMethod, year: Int,
                             timeZone: TimeZone) -> [PortfolioLostFeeCost] {
        lostFeeCosts(in: trades, method: TaxMethod.of(method), period: calendar(year), timeZone: timeZone)
    }

    /// Wie oben für ein Steuerjahr und jede Vorlage — wie Android: Einstandsmethoden über `PortfolioCalculator.ledger`,
    /// eigene Rechnungen über `TaxEngines.lostFeeCosts`. Mit `networkFeeIsDisposal` (UK, US, CA, AU, JP) ist die
    /// Netzwerkgebühr eine Veräusserung — kein Einstand bleibt liegen; FR: keiner. `rules` nil = nach der Rechnung.
    static func lostFeeCosts(in trades: [PortfolioTx], method: TaxMethod, period: TaxPeriod,
                             timeZone: TimeZone, rules: TaxEventRules? = nil) -> [PortfolioLostFeeCost] {
        let r = real(trades)
        let all: [PortfolioLostFeeCost]
        if let cm = method.costMethod {
            let applied = TaxEngines.applyRules(r, rules: rules ?? .of(method), priceAt: nil)
            // Ein Buch je Coin, Schritte einmal gesammelt; Herkunftsteile ändern den vermerkten Einstand nicht
            all = PortfolioCalculator.ledgers(applied, method: cm, withParts: false).flatMap(\.lostFeeCosts)
        } else {
            all = TaxEngines.lostFeeCosts(r, method: method, timeZone: timeZone, rules: rules)
        }
        return all
            .filter { period.contains(localDay($0.time, timeZone: timeZone)) }
            .sorted { ($0.time, $0.txId, $0.coin) < ($1.time, $1.txId, $1.coin) }
    }

    /// Umbuchungsgebühren auf Bestand ohne Kaufpreis (`PortfolioUnassignedFee`) mit Datum im Jahr `year`, zeitlich — wie
    /// `unassignedFeesIn` (Android); für die Info-Zeile im Gewinne-Export. Kein realisierter Gewinn/Verlust.
    static func unassignedFees(in trades: [PortfolioTx], method: CostMethod, year: Int,
                               timeZone: TimeZone) -> [PortfolioUnassignedFee] {
        unassignedFees(in: trades, method: TaxMethod.of(method), period: calendar(year), timeZone: timeZone)
    }

    /// Wie oben für ein Steuerjahr und jede Vorlage — wie Android: Einstandsmethoden über `PortfolioCalculator.ledger`
    /// (nach `applyRules`: mit `quoteFeeIsInfo` zählt die Gebühr USDT einer Umbuchung ohnehin nicht zum Einstand), eigene
    /// Rechnungen über `TaxEngines.unassignedFees`. `rules` nil = nach der Rechnung.
    static func unassignedFees(in trades: [PortfolioTx], method: TaxMethod, period: TaxPeriod,
                               timeZone: TimeZone, rules: TaxEventRules? = nil) -> [PortfolioUnassignedFee] {
        let r = real(trades)
        let all: [PortfolioUnassignedFee]
        if let cm = method.costMethod {
            let applied = TaxEngines.applyRules(r, rules: rules ?? .of(method), priceAt: nil)
            all = PortfolioCalculator.ledgers(applied, method: cm, withParts: false).flatMap(\.unassignedFees)
        } else {
            all = TaxEngines.unassignedFees(r, method: method, timeZone: timeZone, rules: rules)
        }
        return all
            .filter { period.contains(localDay($0.time, timeZone: timeZone)) }
            .sorted { ($0.time, $0.txId, $0.coin) < ($1.time, $1.txId, $1.coin) }
    }

    /// Belohnungen im Steuerjahr `period`, zeitlich (dann Id).
    static func rewards(in trades: [PortfolioTx], period: TaxPeriod, timeZone: TimeZone) -> [PortfolioTx] {
        real(trades)
            .filter { $0.type == .REWARD && period.contains(localDay($0.time, timeZone: timeZone)) }
            // Reihenfolge wie das Buch (`PortfolioLedger.precedes`)
            .enumerated().sorted { a, b in
                PortfolioLedger.precedes(PortfolioLedgerEvent(trade: a.element, feeLeg: false),
                                         PortfolioLedgerEvent(trade: b.element, feeLeg: false)) ?? (a.offset < b.offset)
            }.map(\.element)
    }

    /// Erster Tag, für den Devisenkurse gebraucht werden (frühestes Kaufdatum bzw. 1.1.), ohne Vorlauf.
    static func fxFrom(_ trades: [PortfolioTx], method: CostMethod, year: Int, timeZone: TimeZone) -> LocalDay {
        fxFrom(trades, method: TaxMethod.of(method), period: calendar(year), timeZone: timeZone)
    }

    /// Wie oben für `period`: frühestes Kauf-/Herkunftsdatum der Veräusserungen bzw. Beginn des Steuerjahrs. CA: auch
    /// aller früheren Veräusserungen (nicht abziehbarer Verlust eines Vorjahrs wird in CAD entschieden) — wie Android.
    static func fxFrom(_ trades: [PortfolioTx], method: TaxMethod, period: TaxPeriod, timeZone: TimeZone,
                       priceAt: ((String, Int64) -> Decimal?)? = nil, rules: TaxEventRules? = nil,
                       walletFrom: LocalDay? = nil) -> LocalDay {
        fxSpan(trades, method: method, period: period, timeZone: timeZone, priceAt: priceAt, rules: rules,
               walletFrom: walletFrom).from
    }

    /// Erster und letzter Tag mit Devisenbedarf in einem Durchgang (die Veräusserungen nur einmal gerechnet). Erster wie
    /// `fxFrom`. Letzter: `until` (`priceUntil`) oder das späteste Datum eines Zugangs, den eine Veräusserung des Jahres
    /// nutzt (Kaufdatum, Zuordnung, Herkunftsteil), höchstens heute (`today`, nie vor dem Ende des Steuerjahrs) — UK: ein
    /// Kauf am 20. April deckt einen Verkauf vom 2. April und braucht den Kurs SEINES Datums. FR: dazu die Herkunft der
    /// Kürzungen durch Abgänge ohne Verkauf des Jahres (`frOutflowTimes`). Wie Android.
    static func fxSpan(_ trades: [PortfolioTx], method: TaxMethod, period: TaxPeriod, timeZone: TimeZone,
                       priceAt: ((String, Int64) -> Decimal?)? = nil, rules: TaxEventRules? = nil,
                       walletFrom: LocalDay? = nil, until: LocalDay? = nil,
                       today: LocalDay? = nil) -> (from: LocalDay, until: LocalDay) {
        let upTo = spanPeriod(method, period: period)
        let used = disposals(in: trades, method: method, period: upTo, timeZone: timeZone, priceAt: priceAt,
                             rules: rules, walletFrom: walletFrom)
        return span(of: used, period: period, timeZone: timeZone, until: until, today: today,
                    extra: frOutflowTimes(trades, method: method, period: period, timeZone: timeZone, priceAt: priceAt))
    }

    /// Steuerjahr, dessen Veräusserungen `fxSpan` auswertet: `period`; CA alle bis zu dessen Ende.
    static func spanPeriod(_ method: TaxMethod, period: TaxPeriod) -> TaxPeriod {
        method == .CA_ACB ? TaxPeriod(startYear: period.startYear, start: LocalDay(1, 1, 1), end: period.end) : period
    }

    /// `fxSpan` aus den schon gerechneten Veräusserungen `used` (Verkaufsdatum in `spanPeriod`) und weiteren Zeitpunkten
    /// `extra` (FR: `frOutflowTimes`).
    static func span(of used: [PortfolioDisposal], period: TaxPeriod, timeZone: TimeZone, until: LocalDay?,
                     today: LocalDay?, extra: [Int64] = []) -> (from: LocalDay, until: LocalDay) {
        // FIFO: Kaufdatum der Posten; Durchschnitt/Pool: Datum jedes Herkunftsteils des Einstands
        let times = used
            .flatMap { d -> [Int64] in
                var out: [Int64] = []
                if let a = d.acquiredTime { out.append(a) }
                if let m = d.matchedTime { out.append(m) }
                return out + d.costParts.timeBounds
            } + extra
        var from = period.start
        if let first = times.min().map({ localDay($0, timeZone: timeZone) }), first < period.start { from = first }
        var wanted = until ?? period.end
        if let last = times.max().map({ localDay($0, timeZone: timeZone) }), wanted < last { wanted = last }
        let now = today ?? localDay(Int64(Date().timeIntervalSince1970 * 1000), timeZone: timeZone)
        return (from, min(wanted, max(now, period.end)))
    }

    /// Letzter Tag, bis zu dem Tagesschlüsse (Gebühr-Zeilen, Belohnungen und Tausch ohne Wert, Netzwerkgebühren) und
    /// Devisenkurse mindestens geladen werden: das Ende des Steuerjahrs; UK und CA 30 Tage danach — UK ordnet einem Verkauf
    /// Zugänge der 30 Tage DANACH zu (Bed-and-Breakfast, CRYPTO22200), CA prüft den Superficial Loss bis 30 Tage danach;
    /// diese Zugänge liegen beim Verkauf kurz vor dem Jahresende im nächsten Steuerjahr. Höchstens `today`, nie vor dem
    /// Ende des Steuerjahrs. Wie Android.
    static func priceUntil(_ template: TaxTemplate, period: TaxPeriod, today: LocalDay) -> LocalDay {
        let window: Int
        switch template {
        case .UK: window = TaxTemplate.ukBbDays
        case .CA: window = TaxTemplate.caWindowDays
        default: return period.end
        }
        return min(period.end.plusDays(window), max(today, period.end))
    }
}
