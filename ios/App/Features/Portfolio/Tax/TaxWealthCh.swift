import Foundation

// CH: Vermögenswert per 31.12. als Orientierung (`TaxExport.chWealth`) — wie `TaxWealthCh.kt` (Android).

/// Ein Coin im Vermögenswert per 31.12. (`TaxWealth`): eigener Bestand und Guthaben beobachteter Adressen zusammen (das
/// Vermögen zählt je Person, nicht je Wallet). Menge und Kurs exakt; die Werte sind die Summe der auf 2 Stellen gerundeten
/// Zeilen der Bestandsdatei (`CutoffExport.rows` + `addressRows`) — so stimmen beide Dateien je Coin überein. Wie Android.
struct TaxWealthRow: Sendable, Equatable {
    let coin: String
    let amount: Decimal
    /// Tagesschluss 31.12. in USDT; nil = keiner.
    let priceUsdt: Decimal?
    /// Summe der gerundeten Teilwerte (Menge × `priceUsdt`); nil = nicht gerechnet.
    let valueUsdt: Decimal?
    /// Summe der gerundeten Teilwerte in der Steuerwährung; nil = nicht gerechnet bzw. ohne Devisenkurs.
    let valueTarget: Decimal?
    /// Kein Tagesschluss 31.12. (`CutoffRow.noPrice`).
    var noPrice = false
    /// Kurs nicht als derselbe Token bestätigt (`CutoffRow.unchecked`).
    var unchecked = false
    /// Eine Adresse war am 31.12. nicht beobachtet (`CutoffRow.addressUnobserved`) — ihr Guthaben fehlt.
    var addressUnobserved = false
    /// Kein Devisenkurs für den 31.12.
    var noFx = false

    /// Wert des Coins nicht gerechnet (Kurs fehlt, ungeprüft oder Guthaben unbekannt) — nie ein anderer Kurs.
    var missing: Bool { valueUsdt == nil }
}

/// CH: Vermögenswert per `date` (31.12. des Steuerjahrs) — ORIENTIERUNG (Börsen-Tagesschluss, nicht die Steuerwerte der
/// ESTV-Kursliste; Vermögenssteuer nicht gerechnet). Werte je Coin = Summe der GERUNDETEN Teilzeilen (eigener Bestand, je
/// Adresse; `CutoffExport.roundedMoney`), genau wie in der Bestandsdatei; die Summen aus diesen Werten (3 × 0.335 → 1.02,
/// nicht 1.01), und beide Dateien weichen je Coin nicht um 0.01 ab. Wie `TaxWealth` (Android).
struct TaxWealth: Sendable, Equatable {
    let date: LocalDay
    let rows: [TaxWealthRow]
    /// Summe der bekannten, je Coin auf 2 Stellen gerundeten Werte in USDT.
    let totalUsdt: Decimal
    /// Summe der bekannten, je Coin gerundeten Werte in der Steuerwährung; nil = ohne Devisenkurs.
    let totalTarget: Decimal?

    /// Mindestens ein Coin nicht gerechnet — die Summe ist «unvollständig».
    var incomplete: Bool { rows.contains { $0.missing } }

    /// Kein Devisenkurs für den Stichtag (bei Bestand).
    var noFx: Bool { totalTarget == nil && !rows.isEmpty }
}

extension TaxExport {

    /// CH: Vermögenswert per 31.12. (`date`) — dieselbe Rechnung wie der Stichtag-Export der Arbeitsunterlage
    /// (`CutoffExport`): Bestand am Ende des Tags in der Zeitzone der Vorlage (31.12. 23:59:59.999 zählt, 1.1. 00:00 nicht)
    /// nach `method` (Gebühren im Coin mindern den Bestand, Umbuchungen zwischen eigenen Wallets ändern die Summe nicht),
    /// dazu beobachtete Adressen (`addresses`), je Coin zusammengezählt; Kurs = Tagesschluss (UTC-Tageskerze) des 31.12. aus
    /// `closes` (Stablecoins nach `PortfolioStables`); `rate` = USD → Steuerwährung samt USDT/USD (`rateOn` am 31.12.).
    /// Fehlt der Schluss eines Coins, ist er ungeprüft oder fehlt ein Teil seines Guthabens, bleibt SEIN Wert leer («nicht
    /// gerechnet», nie der heutige Kurs) und die Summe ist unvollständig. Alphabetisch nach Coin. Wie Android `chWealth`.
    static func chWealth(_ trades: [PortfolioTx], method: CostMethod, date: LocalDay, timeZone: TimeZone,
                         closes: CutoffCloses, rate: Decimal?, addresses: AddressBalancesAt? = nil) -> TaxWealth {
        let cutoff = CutoffDay(year: date.year, month: date.month, day: date.day).endOfDayMillis(in: timeZone)
        let holdings = CutoffExport.holdingsAt(trades.filter { $0.amountExact > 0 }, cutoffMillis: cutoff, method: method)
        var parts = CutoffExport.rows(holdings, prices: closes.prices, fxRate: rate, unchecked: closes.unchecked)
        if let addresses {
            parts += CutoffExport.addressRows(addresses, prices: closes.prices, fxRate: rate, unchecked: closes.unchecked)
        }
        let byCoin = Dictionary(grouping: parts, by: \.coin)
        let rows: [TaxWealthRow] = byCoin.keys.sorted().map { coin in
            let list = byCoin[coin] ?? []
            let noPrice = list.contains { $0.noPrice }
            let unchecked = list.contains { $0.unchecked }
            let unobserved = list.contains { $0.addressUnobserved }
            // Ein Teil ohne Wert (oder ein unbeobachtetes Guthaben) → der ganze Coin ist nicht gerechnet
            let missing = noPrice || unchecked || unobserved || list.contains { $0.valueUsdt == nil }
            // Jeder Teil gerundet wie seine Zeile in der Bestandsdatei, erst dann zusammengezählt
            let valueUsdt: Decimal? = missing ? nil
                : list.reduce(Decimal(0)) { $0 + CutoffExport.roundedMoney($1.valueUsdt ?? 0) }
            let valueTarget: Decimal? = (missing || list.contains { $0.valueTarget == nil }) ? nil
                : list.reduce(Decimal(0)) { $0 + CutoffExport.roundedMoney($1.valueTarget ?? 0) }
            return TaxWealthRow(coin: coin, amount: list.reduce(Decimal(0)) { $0 + $1.amount },
                                priceUsdt: list.lazy.compactMap(\.priceUsdt).first, valueUsdt: valueUsdt,
                                valueTarget: valueTarget, noPrice: noPrice, unchecked: unchecked,
                                addressUnobserved: unobserved, noFx: list.contains { $0.noFx })
        }
        // Summen aus den gerundeten Zeilenwerten (wie sie in der Datei stehen), wie `CutoffExport.csv` — wie Android
        let totalUsdt = rows.reduce(Decimal(0)) { $0 + CutoffExport.roundedMoney($1.valueUsdt ?? 0) }
        let fx = rate.flatMap { $0 > 0 && $0.isFinite ? $0 : nil }
        let totalTarget: Decimal? = (fx == nil && !rows.isEmpty) ? nil
            : rows.reduce(Decimal(0)) { $0 + CutoffExport.roundedMoney($1.valueTarget ?? 0) }
        return TaxWealth(date: date, rows: rows, totalUsdt: totalUsdt, totalTarget: totalTarget)
    }

    /// Posten «Vermögenswert 31.12.<Jahr> (Orientierung)» (`.CH_WEALTH_VALUE`): Summe in USDT und Steuerwährung,
    /// «unvollständig» / «kein Devisenkurs» wie jeder Posten; ohne `wealth` «nicht gerechnet». Vermerk immer
    /// `.CH_WEALTH_ORIENTATION`. Wie Android `chWealthLine`.
    static func chWealthLine(_ wealth: TaxWealth?, fx: Bool) -> TaxSummaryLine {
        guard let wealth else {
            return TaxSummaryLine(line: .CH_WEALTH_VALUE, notCalculated: true, note: .CH_WEALTH_ORIENTATION)
        }
        return TaxSummaryLine(line: .CH_WEALTH_VALUE, usdt: wealth.totalUsdt, target: fx ? wealth.totalTarget : nil,
                              incomplete: wealth.incomplete, noFx: fx && wealth.noFx, note: .CH_WEALTH_ORIENTATION)
    }

    /// Platzhalter für Coin und Menge in `TaxSummaryTexts.wealthCoin` — wie `TAX_WEALTH_COIN`/`TAX_WEALTH_AMOUNT` (Android).
    static let wealthCoinToken = "{coin}"
    static let wealthAmountToken = "{amount}"
}

extension TaxSummaryTexts {
    /// Bezeichnung der Zeile eines Coins im Vermögenswert; `amount` schon als Text. Wie Android `wealthCoinLabel`.
    func wealthCoinLabel(_ coin: String, amount: String) -> String {
        guard !wealthCoin.isEmpty else { return "\(coin) \(amount)" }
        return wealthCoin.replacingOccurrences(of: TaxExport.wealthCoinToken, with: coin)
            .replacingOccurrences(of: TaxExport.wealthAmountToken, with: amount)
    }

    /// Hinweis eines Coins im Vermögenswert: «nicht gerechnet – kein Kurs» usw.; ohne Devisenkurs `noFx`; leer = gerechnet.
    /// Wie Android `wealthCoinNote`.
    func wealthCoinNote(_ r: TaxWealthRow) -> String {
        var reasons: [String] = []
        if r.noPrice && !wealthNoPrice.isEmpty { reasons.append(wealthNoPrice) }
        if r.unchecked && !wealthUnchecked.isEmpty { reasons.append(wealthUnchecked) }
        if r.addressUnobserved && !wealthAddressUnobserved.isEmpty { reasons.append(wealthAddressUnobserved) }
        let missing = r.missing ? ((wealthMissing.isEmpty ? [] : [wealthMissing]) + reasons).joined(separator: " – ") : ""
        let fxNote = r.noFx && !r.missing ? noFx : ""
        return [missing, fxNote].filter { !$0.isEmpty }.joined(separator: ", ")
    }
}
