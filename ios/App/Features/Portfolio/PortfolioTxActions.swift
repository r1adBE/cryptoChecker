import SwiftUI

// Abläufe des Transaktions-Blatts (Teil von `PortfolioTxSheet`): Kurs vorbelegen (aktuell, historischer
// Schluss, Gebühr-Coin), Datumswechsel, Umschalten USDT ↔ Anzeigewährung mit Devisenkurs des Tags, Speichern
// und die Datums-Hilfen. Eigene Datei, weil es Abläufe ohne Layout sind (Umrechnung wie `PortfolioTxInput.kt`).

extension PortfolioTxSheet {
    // MARK: Abläufe

    /// Wie der `LaunchedEffect(coin, coinValid)` in Android: nur bei neuen Einträgen,
    /// nur für einen anderen Coin; ein getippter Preis bleibt, der Kurs dient dann nur als
    /// Hinweis für eine mehrdeutige Eingabe (`priceHint`).
    func prefillPrice() async {
        let target = coin
        guard !isEdit, coinValid, target != pricedCoin else { return }
        do {
            try await Task.sleep(nanoseconds: 400_000_000)
        } catch {
            return
        }
        let current = await model.currentPrice(target).flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        guard !Task.isCancelled else { return }
        pricedCoin = target
        priceHint = current
        if !PortfolioTxForm.mayPrefill(isEdit: isEdit, isToday: isToday) {
            // Anderer Tag: kein heutiger Kurs im Feld (ein noch vorbelegter wird geleert)
            if priceAuto { priceText = ""; priceAuto = false }
        } else if !entryFiat && (priceBlank || priceAuto) {
            priceText = current.map {
                PriceFormat.exactAmountForInput(PortfolioDecimal.of($0), decimalSeparator: decimalSeparator)
            } ?? ""
            priceAuto = current != nil
        }
    }

    /// Schlüssel der Abfrage des Tagesschlusses (Art, Coin, Tag).
    struct HistoricKey: Hashable {
        let type: PortfolioTxType
        let coin: String
        let day: CutoffDay
    }

    /// Wie der `LaunchedEffect(type, coin, coinValid, time)` in Android: Belohnung/Einzahlung, neuer Eintrag,
    /// vergangener Tag, Feld leer (oder noch mit dem Tagesschluss eines anderen Coins) → geprüften Tagesschluss
    /// vorbelegen und markieren.
    func prefillHistoricClose() async {
        let target = coin
        let replaceable = priceBlank || priceHistoricDay != nil
        // Eingabe in der Anzeigewährung: kein USDT-Tagesschluss ins Feld
        guard !entryFiat, coinValid, PortfolioTxForm.mayPrefillHistoricClose(type: type, isEdit: isEdit, isToday: isToday,
                                                                  priceBlank: replaceable) else { return }
        if priceHistoricDay != nil { priceText = ""; priceHistoricDay = nil }
        do {
            try await Task.sleep(nanoseconds: 400_000_000)
        } catch {
            return
        }
        let day = CutoffDay(date)
        let current = await model.currentPrice(target)
        let result = await HistoricPriceSource.dailyCloseUsdt(target, day: day, current: current)
        guard !Task.isCancelled, let close = result.price, close.isFinite, close > 0, priceBlank,
              let exact = PortfolioDecimal.of(close) else { return }
        priceText = PriceFormat.exactAmountForInput(exact, decimalSeparator: decimalSeparator)
        priceHint = close
        priceAuto = false
        priceHistoricDay = PortfolioFormat.date(date.millis)
    }

    /// Preis des Gebühr-Coins mit dem aktuellen Kurs vorbelegen (nur neue Transaktion von heute, Feld leer).
    func prefillFeeCoinPrice() async {
        let symbol = feeCoinSymbol
        guard PortfolioPriceSource.isSymbol(symbol), symbol != coin else { return }
        if feeCoinPriceAuto { feeCoinPriceText = ""; feeCoinPriceAuto = false }
        guard PortfolioTxForm.mayPrefillFeeCoinPrice(isEdit: isEdit, isToday: isToday, priceBlank: feeCoinPriceBlank) else { return }
        do {
            try await Task.sleep(nanoseconds: 400_000_000)
        } catch {
            return
        }
        guard let current = await model.currentPrice(symbol), current.isFinite, current > 0,
              !Task.isCancelled, feeCoinPriceBlank else { return }
        feeCoinPriceText = PriceFormat.exactAmountForInput(PortfolioDecimal.of(current),
                                                           decimalSeparator: decimalSeparator)
        feeCoinPriceAuto = true
    }

    /// Datum gewechselt — wie `onSelect` des Datumsdialogs (Android): anderer Tag mit noch vorbelegtem
    /// Kurs → leeren; zurück auf heute mit leerem Feld → mit dem heutigen Kurs vorbelegen; sonst nichts.
    func applyDateChange() {
        // Ursprüngliches Kaufdatum höchstens das Datum der Einzahlung
        if let acquired = acquiredDate, acquired > date { acquiredDate = date }
        // Tagesschluss gehört zum alten Tag: leeren (der neue wird geladen)
        if priceHistoricDay != nil { priceText = ""; priceHistoricDay = nil }
        // Heutiger Kurs des Gebühr-Coins gilt nur für heute
        if feeCoinPriceAuto && !isToday { feeCoinPriceText = ""; feeCoinPriceAuto = false }
        switch PortfolioTxForm.onDateChange(isEdit: isEdit, isToday: isToday, priceAuto: priceAuto, priceBlank: priceBlank) {
        case .clear:
            priceText = ""
            priceAuto = false
        case .prefill:
            guard !entryFiat, coinValid, coin == pricedCoin, let current = priceHint, current.isFinite, current > 0 else { return }
            priceText = PriceFormat.exactAmountForInput(PortfolioDecimal.of(current),
                                                        decimalSeparator: decimalSeparator)
            priceAuto = true
        case .keep:
            break
        }
    }

    /// Anzeigewährung gewählt: Kurs laden, dann die Felder (noch USDT) umrechnen (`loadEntryFx`).
    func switchToFiat() {
        guard !entryFiat else { return }
        entryFiat = true
        pendingToFiat = true
    }

    /// Zurück in USDT: Felder umrechnen; ohne Kurs leeren — sonst stünde ein Fiat-Betrag als USDT da.
    func switchToUsdt() {
        guard entryFiat else { return }
        if fiatActive {
            let sep = decimalSeparator
            priceText = price.map { PriceFormat.exactAmountForInput(Self.roundForInput($0), decimalSeparator: sep) } ?? ""
            swapValueText = swapValue.map { PriceFormat.exactAmountForInput(Self.roundForInput($0), decimalSeparator: sep) } ?? ""
            priceAuto = false
            priceHistoricDay = nil
        }
        entryFiat = false
        pendingToFiat = false
    }

    /// Wie der `LaunchedEffect(currency, day, entryFiat)` (Android): Tageskurs laden (je Währung und Tag einmal), dann
    /// eine ausstehende Umrechnung der Felder; ohne Kurs bleibt es bei USDT.
    func loadEntryFx() async {
        guard entryFiat, currency != "USD" else { return }
        let key = entryKey
        if entryFxKey != key || entryFxState == nil || entryFxState == .loading {
            entryFxKey = key
            entryFxState = .loading
            entryFxRate = nil
            entryFxDay = nil
            entryFxUsdtAssumed = false
            let loaded = await Self.fetchEntryFx(currency: currency, date: date)
            guard !Task.isCancelled else { return }
            entryFxRate = loaded?.rate
            entryFxDay = loaded?.day
            entryFxUsdtAssumed = loaded?.usdtAssumed ?? false
            entryFxState = loaded == nil ? .failed : .ready
        }
        guard pendingToFiat else { return }
        pendingToFiat = false
        guard let rate = entryFxRate else {
            // Kein Kurs: bei USDT bleiben (Felder unverändert)
            entryFiat = false
            return
        }
        let sep = decimalSeparator
        priceText = Self.convertEntry(priceText, hint: priceHint, separator: sep) { PortfolioTxForm.usdtToFiat($0, usdToFiat: rate) }
        swapValueText = Self.convertEntry(swapValueText, hint: nil, separator: sep) { PortfolioTxForm.usdtToFiat($0, usdToFiat: rate) }
        priceAuto = false
        priceHistoricDay = nil
    }

    /// Faktor USDT → `currency`: Devisenkurs USD → Währung × USDT/USD (`PortfolioTxForm.entryRate`, wie CSV-Import und
    /// Steuer-Export). Heute der aktuelle Kurs und der Live-Kurs USDT/USD, sonst der Tageskurs (EZB, letzter Geschäftstag
    /// ≤ Tag) mit seinem tatsächlichen Datum und der USDT/USD-Tagesschluss DES Tags (fehlt er: × 1) — wie
    /// `PortfolioViewModel.entryFx` (Android). `usdtAssumed`: mit USDT = 1 gerechnet. nil = kein Kurs.
    private static func fetchEntryFx(currency: String, date: Date) async
        -> (rate: Decimal, day: CutoffDay, usdtAssumed: Bool)? {
        let day = CutoffDay(date)
        let today = CutoffDay(Date())
        if day.iso >= today.iso {
            guard let r = await FxRateSource.usdTo(currency), r.isFinite, r > 0 else { return nil }
            let fx = PortfolioDecimal.of(r)
            let usdt = PortfolioDecimal.of(DepegAlarmCheck.liveUsdtUsd())
            guard let exact = PortfolioTxForm.entryRate(usdToFiat: fx, usdtUsd: usdt) else { return nil }
            return (exact, today, PortfolioTxForm.entryUsdtAssumed(usdToFiat: fx, usdtUsd: usdt))
        }
        guard let fx = await HistoricPriceSource.usdTo(currency, day: day), fx.rate.isFinite, fx.rate > 0 else { return nil }
        let epochDay = LocalDay(date: date).epochDay
        let days = max(1, LocalDay.today().epochDay - epochDay + 1)
        let close = await PortfolioHistorySource.usdtUsdDaily(days: days)[epochDay]
        let rate = PortfolioDecimal.of(fx.rate)
        let usdt = PortfolioDecimal.of(close)
        guard let exact = PortfolioTxForm.entryRate(usdToFiat: rate, usdtUsd: usdt) else { return nil }
        return (exact, fx.date.flatMap(cutoffDay(iso:)) ?? day,
                PortfolioTxForm.entryUsdtAssumed(usdToFiat: rate, usdtUsd: usdt))
    }

    /// «2025-03-03» → Kalendertag; unlesbar = nil.
    private static func cutoffDay(iso: String) -> CutoffDay? {
        let parts = iso.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return CutoffDay(year: parts[0], month: parts[1], day: parts[2])
    }

    /// Feldtext in die andere Währung umrechnen; leer bzw. unlesbar bleibt, wie es ist.
    private static func convertEntry(_ text: String, hint: Double?, separator sep: Character,
                                     _ convert: (Decimal) -> Decimal?) -> String {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let value = PriceFormat.parseDecimalAmount(text, decimalSeparator: sep, priceHint: hint),
              let converted = convert(value) else { return text }
        return PriceFormat.exactAmountForInput(roundForInput(converted), decimalSeparator: sep)
    }

    /// Umgerechneter Betrag fürs Feld: 8 gültige Stellen (halbe auf gerade, wie Android).
    private static func roundForInput(_ value: Decimal) -> Decimal { PortfolioDecimal.roundSignificant(value, digits: 8) }

    func save() {
        guard !saving else { return }
        tried = true
        // Unvollständige Gebühr im dritten Coin: Abschnitt öffnen, damit der Fehler sichtbar ist
        if thirdFee == .invalid || feeConsumes {
            showLedger = true
        }
        if let first = PortfolioTxFirstInvalid.of(check, amountValid: amount != nil, isSwap: isSwap) {
            requestFocusJump(first)
        }
        // Speichern mit Fehlern: Warnung (wie Android)
        guard valid, let value = amount else {
            Haptics.play(.warning)
            return
        }
        let trimmedNote = note.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedExchange = exchange.trimmingCharacters(in: .whitespacesAndNewlines)
        let keepFee = keepStoredFee
        let validFee = keepFee ? initial.fee : fee.flatMap { $0 > 0 ? $0 : nil }
        // Nur die Felder der gewählten Art
        var tx = PortfolioTx(
            id: initial.txId,
            coin: coin,
            type: type,
            amountExact: value,
            priceExact: fields.price ? price : nil,
            time: timeForSelectedDate(),
            note: trimmedNote.isEmpty ? nil : trimmedNote,
            feeExact: validFee,
            feeUnit: validFee == nil ? nil : (keepFee ? initial.feeUnit : effectiveFeeUnit),
            exchange: fields.exchange && !trimmedExchange.isEmpty ? trimmedExchange : nil
        )
        if fields.route {
            let from = transferFrom.trimmingCharacters(in: .whitespacesAndNewlines)
            let to = transferTo.trimmingCharacters(in: .whitespacesAndNewlines)
            tx.transferFrom = from.isEmpty ? nil : from
            tx.transferTo = to.isEmpty ? nil : to
        }
        tx.giftToSpouse = type == .GIFT && giftToSpouse
        tx.interest = type == .REWARD && interest
        // Gebühr-Zeile bleibt eine, solange die Art «Verkauf» bleibt
        tx.feeRow = type == .SELL && initial.feeRow
        // Staking-Umbuchung bleibt eine, solange die Art «Umbuchung» bleibt; neu erfasst nie (dann eine Auszahlung)
        tx.stakingMove = type == .TRANSFER && initial.stakingMove
        // Gebühr im dritten Coin, die diese Art im Blatt nicht zeigt (CSV-Import): bei gleicher Art behalten
        let keepThird = !fields.thirdCoinFee && isEdit && type == initial.type && initial.feeCoin != nil
        if keepThird {
            tx.feeCoin = initial.feeCoin
            tx.feeCoinAmountExact = initial.feeCoinAmount
            tx.feeCoinPriceExact = initial.feeCoinPriceUsdt
        } else if thirdFee == .complete || thirdFee == .incomplete {
            tx.feeCoin = feeCoinSymbol
            tx.feeCoinAmountExact = feeCoinAmount
            // Gebühr im erhaltenen Coin: ohne Preis
            tx.feeCoinPriceExact = feeInReceived ? nil : feeCoinPrice
        }
        // Tausch: erhaltener Coin, Menge und Wert (USDT; leer = unbekannt)
        if isSwap {
            tx.toCoin = toCoin
            tx.toAmountExact = toAmount
            tx.swapValueExact = swapValue.flatMap { $0 >= 0 ? $0 : nil }
        }
        // Herkunft (CSV-Import), Importlauf und Verknüpfung (zweiter Schlüssel, Zustand für «Rückgängig») bleiben erhalten
        tx.importKey = initial.importKey
        tx.importBatch = initial.importBatch
        tx.linkedImportKey = initial.linkedImportKey
        tx.importRestoreState = initial.importRestoreState
        // Ursprüngliches Kaufdatum: nur bei einer Einzahlung, nie nach ihrem Datum
        tx.acquiredTime = type == .DEPOSIT ? acquiredTimeForSave(until: tx.time) : nil
        // Vor dem Schreiben — gab es schon Transaktionen? (erste mit Portfolio aus: Rückfrage statt still ein)
        let askEnable: Bool = PortfolioOff.askEnableOnSave(isNew: !initial.isEdit,
                                                           portfolioEnabled: data.settings.portfolioEnabled,
                                                           hadTransactions: !data.portfolio.isEmpty)
        // Geschrieben wird abseits des Haupt-Threads; solange nicht doppelt speichern (neue Transaktion zweimal)
        saving = true
        Task { @MainActor in
            let saved = await data.savePortfolioTx(tx)
            saving = false
            // Nicht geschrieben: nie still verwerfen — Meldung, Eingaben bleiben
            guard saved != nil else {
                Haptics.play(.error)
                saveFailed = true
                return
            }
            Haptics.play(.success)
            if askEnable {
                onAskEnablePortfolio()
            }
            dismiss()
        }
    }

    /// Zeitpunkt für das gewählte Datum: unverändertes Datum behält die Uhrzeit,
    /// heute = jetzt, sonst 12:00 Ortszeit (Reihenfolge am selben Tag nach Erfassung).
    private func timeForSelectedDate() -> Int64 {
        let calendar = Self.gregorian
        if calendar.isDate(date, inSameDayAs: Date(millis: initial.time)) { return initial.time }
        if calendar.isDateInToday(date) { return EpochTime.nowMillis }
        let noon = calendar.date(bySettingHour: 12, minute: 0, second: 0, of: date) ?? date
        return noon.millis
    }

    /// Zeitpunkt des ursprünglichen Kaufdatums: unverändertes Datum behält die Uhrzeit, sonst 12:00 Ortszeit; höchstens
    /// `until` (Zeitpunkt der Einzahlung). nil = keins.
    private func acquiredTimeForSave(until: Int64) -> Int64? {
        guard let acquiredDate else { return nil }
        let calendar = Self.gregorian
        let millis: Int64
        if let stored = initial.acquiredTime, calendar.isDate(acquiredDate, inSameDayAs: Date(millis: stored)) {
            millis = stored
        } else {
            millis = (calendar.date(bySettingHour: 12, minute: 0, second: 0, of: acquiredDate) ?? acquiredDate).millis
        }
        return min(millis, until)
    }

    /// 1. Januar 2009 bis zum Datum der Einzahlung `until` (ursprüngliches Kaufdatum).
    static func acquiredRange(until: Date) -> ClosedRange<Date> {
        let start = gregorian.date(from: DateComponents(year: 2009, month: 1, day: 1)) ?? Date(timeIntervalSince1970: 0)
        return start...max(start, until)
    }

    /// Gregorianischer Kalender in der Zeitzone des Geräts — nicht der Kalender des Nutzers: Mit dem
    /// japanischen (oder buddhistischen) Kalender wäre «Jahr 2009» sonst weit in der Zukunft.
    static var gregorian: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        return calendar
    }

    /// 1. Januar 2009 bis heute (wie `yearRange = 2009..heute`, keine Zukunft).
    static func dateRange() -> ClosedRange<Date> {
        let start = gregorian.date(from: DateComponents(year: 2009, month: 1, day: 1)) ?? Date(timeIntervalSince1970: 0)
        return start...max(start, Date())
    }

    /// Treffer für die Suche: erst «beginnt mit», dann «enthält»; ein exakter
    /// Treffer allein blendet die Liste aus. Höchstens 12 — wie `matchCoins`.
    static func matchCoins(_ coins: [String], _ query: String) -> [String] {
        guard !query.isEmpty, !coins.isEmpty else { return [] }
        let byLength: (String, String) -> Bool = { $0.count != $1.count ? $0.count < $1.count : $0 < $1 }
        let starts = coins.filter { $0.hasPrefix(query) }.sorted(by: byLength)
        if starts.count == 1 && starts[0] == query { return [] }
        let contains = coins.filter { !$0.hasPrefix(query) && $0.contains(query) }.sorted(by: byLength)
        return Array((starts + contains).prefix(12))
    }
}
