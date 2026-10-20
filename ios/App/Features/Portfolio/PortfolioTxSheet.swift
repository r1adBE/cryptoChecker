import SwiftUI

/// Transaktion erfassen bzw. bearbeiten (`txId` ≠ 0): Kauf, Verkauf, Umbuchung, Belohnung, Ein-/Auszahlung —
/// Felder und Prüfung je Art nach `PortfolioTxForm.fields` (Regeln in `PortfolioLedger`); wie `PortfolioTxSheet.kt`.
/// Preis in USDT, wird nach der Coin-Wahl mit dem aktuellen Kurs vorbelegt
/// (nur bei neuen Einträgen von heute und nur, solange kein Preis getippt wurde, `PortfolioTxForm`);
/// leer = Preis unbekannt. Belohnung/Einzahlung an einem vergangenen Tag: geprüfter Tagesschluss (markiert).
@MainActor
struct PortfolioTxSheet: View {
    let initial: PortfolioTxDraft
    /// Erste Transaktion mit ausgeschaltetem Portfolio gespeichert: Rückfrage (nur aus der Merkliste bzw. dem Markt,
    /// `PortfolioQuickAddPresenter`).
    let onAskEnablePortfolio: () -> Void

    @EnvironmentObject var data: AppData
    @ObservedObject var model = PortfolioModel.shared
    @Environment(\.appAccent) var accent
    @Environment(\.dismiss) var dismiss

    @State var type: PortfolioTxType
    @State var coinQuery: String
    @State var amountText: String
    @State var priceText: String
    /// Vorbelegter Preis darf bei einem Coin-Wechsel ersetzt werden, ein getippter nicht.
    @State var priceAuto: Bool
    /// Coin, für den der Preis zuletzt vorbelegt wurde.
    @State var pricedCoin: String
    /// Kurs, an dem ein mehrdeutiger Preis («60.000») gemessen wird: gespeicherter bzw. aktueller Kurs.
    @State var priceHint: Double?
    @State var date: Date
    /// Einzahlung: ursprüngliches Kaufdatum (optional, ≤ Datum der Einzahlung) für die Haltedauer im Steuer-Export.
    @State var acquiredDate: Date?
    @State var note: String
    // Gebühr und Börse (D1): zugeklappt, ausser beim Bearbeiten mit Angaben
    @State var feeText: String
    @State var feeUnit: PortfolioFeeUnit
    @State var exchange: String
    @State var showLedger: Bool
    /// Preis = geprüfter Tagesschluss eines vergangenen Tags (Belohnung/Einzahlung); Tag als Text.
    @State var priceHistoricDay: String?
    // Umbuchung: von / nach (freier Text, Vorschläge aus den Börsen)
    @State var transferFrom: String
    @State var transferTo: String
    // Verschenkt an Ehe-/Lebenspartner (eigenes Feld, nur bei einer Schenkung)
    @State var giftToSpouse: Bool
    // Belohnung als Zins (Lending; AT: Einkunft beim Zufluss) — eigenes Feld, nur bei einer Belohnung
    @State var interest: Bool
    // Gebühr in einem dritten Coin
    @State var feeCoinText: String
    @State var feeCoinAmountText: String
    @State var feeCoinPriceText: String
    @State var feeCoinPriceAuto = false
    // Tausch: erhaltener Coin und Menge, Wert des Tauschs (optional, gesamt)
    @State var toCoinQuery: String
    @State var toAmountText: String
    @State var swapValueText: String
    // K-2: Preis/Wert in der Anzeigewährung eingeben (gespeichert wird immer USDT, umgerechnet mit dem Tageskurs)
    @State var entryFiat = false
    /// Umschalten auf die Anzeigewährung: Felder (noch USDT) umrechnen, sobald der Kurs da ist.
    @State var pendingToFiat = false
    @State var entryFxKey: String?
    @State var entryFxRate: Decimal?
    @State var entryFxDay: CutoffDay?
    /// Faktor mit USDT = 1 (kein plausibler USDT/USD-Kurs): das Blatt nennt es (`PortfolioTxForm.entryUsdtAssumed`)
    @State var entryFxUsdtAssumed = false
    @State var entryFxState: EntryFxState?
    @State var tried = false
    @State var askDelete = false
    @State private var askDiscard = false
    /// Speichern fehlgeschlagen: Meldung, Blatt bleibt offen mit den Eingaben (wie Android).
    @State var saveFailed = false
    /// Löschen nicht geschrieben (gesperrte Datei): Blatt bleibt offen, Hinweis statt still geschlossen.
    @State private var deleteFailed = false
    @FocusState var focusedField: PortfolioTxField?

    enum PortfolioTxField: Hashable {
        case coin, amount, price, note, fee, exchange, transferFrom, transferTo, feeCoin, feeCoinAmount, feeCoinPrice
        case toCoin, toAmount, swapValue
    }

    /// Laden des Devisenkurses für die Eingabe in der Anzeigewährung (K-2).
    enum EntryFxState { case loading, ready, failed }

    init(initial: PortfolioTxDraft, onAskEnablePortfolio: @escaping () -> Void = {}) {
        self.initial = initial
        self.onAskEnablePortfolio = onAskEnablePortfolio
        let isEdit = initial.isEdit
        _type = State(initialValue: initial.type)
        _coinQuery = State(initialValue: initial.coin)
        // Dezimalzeichen der Region — wie bei den Alarmen (`ThresholdParser.localeDecimalSeparator`)
        let separator = ThresholdParser.localeDecimalSeparator
        _amountText = State(initialValue: PriceFormat.exactAmountForInput(initial.amount, decimalSeparator: separator))
        _priceText = State(initialValue: PriceFormat.exactAmountForInput(initial.priceUsdt, decimalSeparator: separator))
        _priceHint = State(initialValue: initial.priceUsdt.flatMap { $0 > 0 ? PortfolioDecimal.double($0) : nil })
        _priceAuto = State(initialValue: !isEdit && initial.priceUsdt != nil)
        _pricedCoin = State(initialValue: (initial.priceUsdt != nil || isEdit) ? initial.coin : "")
        _date = State(initialValue: min(Date(millis: initial.time), Date()))
        _acquiredDate = State(initialValue: initial.acquiredTime.map { min(Date(millis: $0), Date()) })
        // Höchstens 120 Zeichen wie die Notiz in der Merkliste (eine längere alte Notiz wird beim Speichern gekürzt)
        _note = State(initialValue: String((initial.note ?? "").prefix(Watch.noteMax)))
        _feeText = State(initialValue: PriceFormat.exactAmountForInput(initial.fee, decimalSeparator: separator))
        _feeUnit = State(initialValue: initial.feeUnit)
        _exchange = State(initialValue: initial.exchange ?? "")
        // Kauf: auch die Notiz steht im zugeklappten Abschnitt «Gebühr, Börse, Notiz»
        let buyNote: Bool = initial.type == .BUY && !(initial.note ?? "").isEmpty
        _showLedger = State(initialValue: initial.fee != nil || !(initial.exchange ?? "").isEmpty || initial.feeCoin != nil || buyNote)
        _transferFrom = State(initialValue: initial.transferFrom ?? "")
        _transferTo = State(initialValue: initial.transferTo ?? "")
        _giftToSpouse = State(initialValue: initial.type == .GIFT && initial.giftToSpouse)
        _interest = State(initialValue: initial.type == .REWARD && initial.interest)
        _feeCoinText = State(initialValue: initial.feeCoin ?? "")
        _feeCoinAmountText = State(initialValue: PriceFormat.exactAmountForInput(initial.feeCoinAmount, decimalSeparator: separator))
        _feeCoinPriceText = State(initialValue: PriceFormat.exactAmountForInput(initial.feeCoinPriceUsdt, decimalSeparator: separator))
        _toCoinQuery = State(initialValue: initial.toCoin ?? "")
        _toAmountText = State(initialValue: PriceFormat.exactAmountForInput(initial.toAmount, decimalSeparator: separator))
        _swapValueText = State(initialValue: PriceFormat.exactAmountForInput(initial.swapValueUsdt, decimalSeparator: separator))
    }

    // MARK: Eingaben auswerten

    var isEdit: Bool { initial.isEdit }
    var coin: String { PortfolioCalculator.normalizeCoin(coinQuery) }
    var coinValid: Bool { PortfolioPriceSource.isSymbol(coin) }
    // Menge ohne Kurs-Hinweis (mehrdeutig → Dezimalzeichen der Region), Preis am Kurs gemessen.
    // Exakt gelesen (`Decimal`): 0.00000001 BTC = 1 Satoshi, ohne Gleitkomma-Rundung
    var amount: Decimal? {
        PriceFormat.parseDecimalAmount(amountText, decimalSeparator: ThresholdParser.localeDecimalSeparator)
            .flatMap { $0 > 0 ? $0 : nil }
    }
    var priceBlank: Bool { priceText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var priceEntered: Decimal? {
        // In der Anzeigewährung am umgerechneten Kurs gemessen
        let hint = fiatActive ? fxRate.flatMap { r in priceHint.map { $0 * PortfolioDecimal.double(r) } } : priceHint
        return priceBlank ? nil
            : PriceFormat.parseDecimalAmount(priceText, decimalSeparator: ThresholdParser.localeDecimalSeparator, priceHint: hint)
    }
    /// Gespeichert wird USDT: Eingabe in der Anzeigewährung ÷ Tageskurs.
    var price: Decimal? {
        fiatActive ? PortfolioTxForm.fiatToUsdt(priceEntered, usdToFiat: fxRate) : priceEntered
    }
    var fields: PortfolioTxForm.Fields { PortfolioTxForm.fields(type) }
    var isSwap: Bool { type == .SWAP }
    // K-2: Eingabe in der Anzeigewährung — nur mit Preis-/Wertfeld und nicht für USD (USDT gilt als USD)
    var currency: String { data.settings.portfolioCurrency }
    var entryKey: String { "\(currency)|\(CutoffDay(date).iso)" }
    private var fiatPossible: Bool { currency != "USD" && (fields.price || isSwap) }
    var fxRate: Decimal? { entryFxState == .ready && entryFxKey == entryKey ? entryFxRate : nil }
    var fxFailed: Bool { entryFxState == .failed && entryFxKey == entryKey }
    var fiatActive: Bool { entryFiat && fiatPossible && !pendingToFiat }
    var entryUnit: String { fiatActive ? currency : PortfolioFormat.usdt }
    // Tausch: erhaltener Coin ≠ abgegebener, Menge > 0; Wert optional (≥ 0)
    var toCoin: String { PortfolioCalculator.normalizeCoin(toCoinQuery) }
    var toCoinValid: Bool { check.toCoinValid }
    var toAmount: Decimal? {
        PriceFormat.parseDecimalAmount(toAmountText, decimalSeparator: ThresholdParser.localeDecimalSeparator)
            .flatMap { $0 > 0 ? $0 : nil }
    }
    private var toAmountValid: Bool { check.toAmountValid }
    private var swapValueBlank: Bool { swapValueText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    var swapValue: Decimal? {
        let entered = swapValueBlank ? nil
            : PriceFormat.parseDecimalAmount(swapValueText, decimalSeparator: ThresholdParser.localeDecimalSeparator)
        return fiatActive ? PortfolioTxForm.fiatToUsdt(entered, usdToFiat: fxRate) : entered
    }
    var swapValueValid: Bool { check.swapValueValid }
    /// Tausch: Gebühr im erhaltenen Coin braucht keinen Preis (mindert nur die erhaltene Menge).
    var feeInReceived: Bool { check.feeInReceived }
    /// Preis 0 ausdrücklich eingegeben: abgelehnt (0 = intern «ohne Preis», würde zum Marktkurs bewertet) — wie Android.
    /// Preis 0: nur bei einer importierten Transaktion abgelehnt (Kennzeichen der Gebühr-Zeile); von Hand ein echter Preis.
    var priceZero: Bool { check.priceZero }
    var priceValid: Bool { check.priceValid }
    var hasFee: Bool { check.hasFee }
    private var feeBlank: Bool { feeText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    /// Gebühr: leer = keine; 0 gilt als keine (Belohnung/Einzahlung haben keine).
    var fee: Decimal? { check.fee }
    var feeValid: Bool { check.feeValid }
    var effectiveFeeUnit: PortfolioFeeUnit { check.feeUnit }
    /// Gespeicherte Gebühr, die das Blatt für diese Art nicht zeigen kann (z. B. CSV-Import: Auszahlung mit
    /// USDT-Gebühr, Einzahlung mit Gebühr): beim Bearbeiten ohne Änderung von Art und Gebühr unverändert
    /// behalten — sonst würde sie still gelöscht oder als Gebühr im Coin umgedeutet. Wie `keepStoredFee` (Android).
    var keepStoredFee: Bool {
        isEdit && type == initial.type && initial.fee != nil
            && feeText == PriceFormat.exactAmountForInput(initial.fee, decimalSeparator: ThresholdParser.localeDecimalSeparator)
            && !(fields.feeWithUnit || (fields.coinFeeOnly && initial.feeUnit == .COIN))
    }
    var feeCoinSymbol: String { PortfolioCalculator.normalizeCoin(feeCoinText) }
    private var feeCoinAmountBlank: Bool { feeCoinAmountText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    var feeCoinPriceBlank: Bool { feeCoinPriceText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    var feeCoinAmount: Decimal? {
        PriceFormat.parseDecimalAmount(feeCoinAmountText, decimalSeparator: ThresholdParser.localeDecimalSeparator)
    }
    var feeCoinPrice: Decimal? {
        feeCoinPriceBlank ? nil
            : PriceFormat.parseDecimalAmount(feeCoinPriceText, decimalSeparator: ThresholdParser.localeDecimalSeparator)
    }
    var thirdFee: PortfolioTxForm.ThirdFee { check.thirdFee }
    /// Gebühr verbraucht die ganze erhaltene Menge: nicht speicherbar (Kauf: Gebühr im Coin, Tausch: im erhaltenen Coin).
    var feeConsumes: Bool { check.feeConsumes }
    var valid: Bool { check.valid }

    /// Gelesene Eingaben (Dezimalzeichen der Region, Anzeigewährung schon in USDT) für `PortfolioTxForm.check`.
    private var entry: PortfolioTxForm.Entry {
        PortfolioTxForm.Entry(
            type: type, coinQuery: coinQuery, amount: amount, priceBlank: priceBlank, priceEntered: priceEntered,
            price: price, imported: !(initial.importKey?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true),
            feeBlank: feeBlank,
            fee: feeBlank ? nil : PriceFormat.parseDecimalAmount(feeText, decimalSeparator: ThresholdParser.localeDecimalSeparator),
            feeUnit: feeUnit, feeCoin: feeCoinText, feeCoinAmountBlank: feeCoinAmountBlank, feeCoinAmount: feeCoinAmount,
            feeCoinPriceBlank: feeCoinPriceBlank, feeCoinPrice: feeCoinPrice, toCoinQuery: toCoinQuery, toAmount: toAmount,
            swapValueBlank: swapValueBlank, swapValue: swapValue)
    }
    /// Prüfung der Eingaben — Regeln in `PortfolioTxForm.check` (gleich wie Android).
    var check: PortfolioTxForm.Check { PortfolioTxForm.check(entry) }

    /// Vom Nutzer geändert? Vorbelegte Preise (heutiger Kurs, Tagesschluss) zählen nicht — wie `dirty` (Android).
    private var dirty: Bool {
        let sep = ThresholdParser.localeDecimalSeparator
        let initialDate = min(Date(millis: initial.time), Date())
        if type != initial.type || coinQuery != initial.coin { return true }
        if amountText != PriceFormat.exactAmountForInput(initial.amount, decimalSeparator: sep) { return true }
        if !priceAuto && priceHistoricDay == nil
            && priceText != PriceFormat.exactAmountForInput(initial.priceUsdt, decimalSeparator: sep) { return true }
        if !Self.gregorian.isDate(date, inSameDayAs: initialDate) { return true }
        switch (acquiredDate, initial.acquiredTime) {
        case (nil, nil): break
        case let (current?, stored?):
            if !Self.gregorian.isDate(current, inSameDayAs: Date(millis: stored)) { return true }
        default: return true
        }
        if note != String((initial.note ?? "").prefix(Watch.noteMax)) { return true }
        if feeText != PriceFormat.exactAmountForInput(initial.fee, decimalSeparator: sep) || feeUnit != initial.feeUnit { return true }
        let initialSpouse = initial.type == .GIFT && initial.giftToSpouse
        if exchange != (initial.exchange ?? "") || transferFrom != (initial.transferFrom ?? "")
            || transferTo != (initial.transferTo ?? "") || giftToSpouse != initialSpouse
            || interest != (initial.type == .REWARD && initial.interest)
            || feeCoinText != (initial.feeCoin ?? "") { return true }
        if feeCoinAmountText != PriceFormat.exactAmountForInput(initial.feeCoinAmount, decimalSeparator: sep) { return true }
        if toCoinQuery != (initial.toCoin ?? "")
            || toAmountText != PriceFormat.exactAmountForInput(initial.toAmount, decimalSeparator: sep)
            || swapValueText != PriceFormat.exactAmountForInput(initial.swapValueUsdt, decimalSeparator: sep) { return true }
        return !feeCoinPriceAuto
            && feeCoinPriceText != PriceFormat.exactAmountForInput(initial.feeCoinPriceUsdt, decimalSeparator: sep)
    }

    /// Der vorbelegte Kurs ist der heutige — gilt nur für eine Transaktion von heute (Ortszeit).
    var isToday: Bool { Self.gregorian.isDateInToday(date) }

    /// Bestand ohne diese Transaktion — nur bei Abgängen (Verkauf, Auszahlung, Umbuchung: Warnung).
    var sellHoldings: Decimal? {
        guard fields.outflow, coinValid else { return nil }
        return data.portfolioHoldings(of: coin, excludingId: initial.txId)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.md) {
                    typePicker
                    coinField
                    // Datum vor Menge und Preis: ein Datumswechsel kann den Preis leeren — nicht unbemerkt darüber
                    dateRow
                    // Einzahlung: ursprüngliches Kaufdatum (optional) — Haltedauer im Steuer-Export
                    if type == .DEPOSIT {
                        acquiredRow
                    }
                    amountField
                    // Tausch: erhaltener Coin und Menge; K-2: Preis/Wert in USDT oder in der Anzeigewährung
                    Group {
                        if isSwap {
                            swapReceivedFields
                        }
                        if fiatPossible {
                            entryCurrencyRow
                        }
                        if isSwap {
                            swapValueField
                        }
                    }
                    if fields.price {
                        priceField
                        // Gespeicherter Zugang ohne Preis: Vorschlag Tagesschluss bzw. «Wert bei Zufluss» (Build 84)
                        PortfolioTxCostSuggestion(
                            isEdit: isEdit, txId: initial.txId, storedPrice: initial.priceUsdt, type: type, coin: coin,
                            coinValid: coinValid, amount: amount, millis: date.millis, interest: interest, entryFiat: entryFiat,
                            template: data.settings.taxTemplate, priceText: $priceText, priceHint: $priceHint,
                            priceAuto: $priceAuto, priceHistoricDay: $priceHistoricDay)
                    }
                    // Notiz direkt unter dem Preis, bei allen Arten (Entscheid 18. Oktober 2026) — wie Android
                    noteField
                    // Verschenkt an Ehe-/Lebenspartner: UK/CA ohne Gewinn und Verlust (`TaxTemplate.spouseGiftNeutral`)
                    if type == .GIFT {
                        SwitchRow(title: L("portfolio_tx_gift_spouse"), subtitle: L("portfolio_tx_gift_spouse_hint"),
                                  isOn: $giftToSpouse)
                    }
                    // Belohnung als Zins (Lending): AT zählt ihn beim Zufluss (Anschaffungskosten = Wert), Staking nicht
                    if type == .REWARD {
                        SwitchRow(title: L("portfolio_tx_interest"), subtitle: L("portfolio_tx_interest_hint"),
                                  isOn: $interest)
                    }
                    if fields.route {
                        routeField(L("portfolio_tx_transfer_from"), text: $transferFrom, focus: .transferFrom, hint: nil)
                        routeField(L("portfolio_tx_transfer_to"), text: $transferTo, focus: .transferTo,
                                   hint: L("portfolio_tx_transfer_to_hint"))
                    }
                    ledgerSection
                    totalLine
                    if isEdit {
                        deleteButton
                    }
                }
                .padding(.horizontal, Spacing.lg)
                .padding(.top, 8)
                .padding(.bottom, 24)
                .animation(.easeInOut(duration: 0.2), value: tried)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(AppColors.background.ignoresSafeArea())
            .navigationTitle(L(isEdit ? "portfolio_edit_tx" : "portfolio_add_tx"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    // Mit Änderungen erst nachfragen (Wischen ist dann gesperrt)
                    Button(L("action_cancel")) {
                        if dirty { askDiscard = true } else { dismiss() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_save"), action: save)
                        .fontWeight(.semibold)
                }
            }
        }
        .tint(accent.primary)
        // Ungespeicherte Änderungen: nicht wegwischen, Abbrechen fragt nach (wie Android)
        .interactiveDismissDisabled(dirty)
        .confirmationDialog(L("portfolio_discard_title"), isPresented: $askDiscard, titleVisibility: .visible) {
            Button(L("portfolio_discard_confirm"), role: .destructive) { dismiss() }
            Button(L("portfolio_discard_keep"), role: .cancel) {}
        }
        .task { await model.loadCoins() }
        // Kurs vorbelegen, sobald ein (anderer) Coin feststeht — erst nach kurzer Tipp-Pause
        .task(id: coinValid ? coin : "") { await prefillPrice() }
        // Belohnung/Einzahlung an einem vergangenen Tag: geprüften Tagesschluss vorbelegen (markiert)
        .task(id: HistoricKey(type: type, coin: coinValid ? coin : "", day: CutoffDay(date))) { await prefillHistoricClose() }
        // Gebühr-Coin: Preis mit dem aktuellen Kurs vorbelegen — nur bei einer Transaktion von heute
        .task(id: feeCoinSymbol) { await prefillFeeCoinPrice() }
        // Vorbelegter heutiger Kurs gilt nur für heute: anderer Tag → leeren, zurück auf heute → wieder vorbelegen
        .onChange(of: date) { _, _ in applyDateChange() }
        // K-2: Tageskurs USD → Anzeigewährung für das Datum (heute: aktueller Kurs), nur in der Anzeigewährung
        .task(id: "\(entryKey)|\(entryFiat)") { await loadEntryFx() }
        .alert(L("portfolio_tx_save_failed"), isPresented: $saveFailed) {
            Button(L("action_close"), role: .cancel) {}
        }
        .alert(L("portfolio_tx_delete_title"), isPresented: $askDelete) {
            Button(L("action_delete"), role: .destructive) {
                if data.deletePortfolioTx(initial.txId) { dismiss() } else { deleteFailed = true }
            }
            Button(L("action_cancel"), role: .cancel) {}
        } message: {
            Text(L("portfolio_tx_delete_confirm"))
        }
        .alert(L("portfolio_storage_locked"), isPresented: $deleteFailed) {
            Button(L("action_close"), role: .cancel) {}
        }
    }

}

/// Eingabefeld-Fläche mit Fokus- und Fehlerrand.
struct PortfolioFieldStyle: ViewModifier {
    let error: Bool
    let focused: Bool
    @Environment(\.appAccent) var accent

    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 16)
            .padding(.vertical, Spacing.md)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
                    .strokeBorder(error ? AppColors.error : (focused ? accent.primary.opacity(0.6) : .clear), lineWidth: 1.2)
            )
    }
}
