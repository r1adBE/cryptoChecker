import SwiftUI

/// Kauf oder Verkauf erfassen bzw. bearbeiten (`txId` ≠ 0) — wie `PortfolioTxSheet.kt`.
/// Preis in USDT, wird nach der Coin-Wahl mit dem aktuellen Kurs vorbelegt
/// (nur bei neuen Einträgen und nur, solange kein Preis getippt wurde); leer = Preis unbekannt.
@MainActor
struct PortfolioTxSheet: View {
    let initial: PortfolioTxDraft

    @EnvironmentObject private var data: AppData
    @ObservedObject private var model = PortfolioModel.shared
    @Environment(\.appAccent) private var accent
    @Environment(\.dismiss) private var dismiss

    @State private var type: PortfolioTxType
    @State private var coinQuery: String
    @State private var amountText: String
    @State private var priceText: String
    /// Vorbelegter Preis darf bei einem Coin-Wechsel ersetzt werden, ein getippter nicht.
    @State private var priceAuto: Bool
    /// Coin, für den der Preis zuletzt vorbelegt wurde.
    @State private var pricedCoin: String
    @State private var date: Date
    @State private var note: String
    @State private var tried = false
    @State private var askDelete = false
    @FocusState private var focusedField: PortfolioTxField?

    private enum PortfolioTxField: Hashable { case coin, amount, price, note }

    init(initial: PortfolioTxDraft) {
        self.initial = initial
        let isEdit = initial.isEdit
        _type = State(initialValue: initial.type)
        _coinQuery = State(initialValue: initial.coin)
        _amountText = State(initialValue: PriceFormat.amountForInput(initial.amount))
        _priceText = State(initialValue: PriceFormat.amountForInput(initial.priceUsdt))
        _priceAuto = State(initialValue: !isEdit && initial.priceUsdt != nil)
        _pricedCoin = State(initialValue: (initial.priceUsdt != nil || isEdit) ? initial.coin : "")
        _date = State(initialValue: min(Date(millis: initial.time), Date()))
        _note = State(initialValue: initial.note ?? "")
    }

    // MARK: Eingaben auswerten

    private var isEdit: Bool { initial.isEdit }
    private var coin: String { PortfolioCalculator.normalizeCoin(coinQuery) }
    private var coinValid: Bool { PortfolioPriceSource.isSymbol(coin) }
    private var amount: Double? { PriceFormat.parseAmount(amountText).flatMap { $0 > 0 ? $0 : nil } }
    private var priceBlank: Bool { priceText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var price: Double? { priceBlank ? nil : PriceFormat.parseAmount(priceText) }
    private var priceValid: Bool { priceBlank || price != nil }
    private var valid: Bool { coinValid && amount != nil && priceValid }

    /// Bestand ohne diese Transaktion — nur beim Verkauf (Warnung bei Überverkauf).
    private var sellHoldings: Double? {
        guard type == .SELL, coinValid else { return nil }
        return data.portfolioHoldings(of: coin, excludingId: initial.txId)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    typePicker
                    coinField
                    amountField
                    priceField
                    dateRow
                    noteField
                    totalLine
                    if isEdit {
                        deleteButton
                    }
                }
                .padding(.horizontal, 20)
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
                    Button(L("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_save"), action: save)
                        .fontWeight(.semibold)
                }
            }
        }
        .tint(accent.primary)
        .task { await model.loadCoins() }
        // Kurs vorbelegen, sobald ein (anderer) Coin feststeht — erst nach kurzer Tipp-Pause
        .task(id: coinValid ? coin : "") { await prefillPrice() }
        .alert(L("portfolio_tx_delete_title"), isPresented: $askDelete) {
            Button(L("action_delete"), role: .destructive) {
                data.deletePortfolioTx(initial.txId)
                dismiss()
            }
            Button(L("action_cancel"), role: .cancel) {}
        } message: {
            Text(L("portfolio_tx_delete_confirm"))
        }
    }

    // MARK: Felder

    /// Kauf | Verkauf
    private var typePicker: some View {
        Picker(selection: $type) {
            ForEach(PortfolioTxType.allCases, id: \.self) { option in
                Text(PortfolioFormat.typeLabel(option)).tag(option)
            }
        } label: {
            EmptyView()
        }
        .pickerStyle(.segmented)
        .sensoryFeedback(.selection, trigger: type)
    }

    /// Coin mit Suche: Treffer als Chips darunter.
    private var coinField: some View {
        let error = tried && !coinValid
        let matches = Self.matchCoins(model.coins, coin)
        return VStack(alignment: .leading, spacing: 6) {
            fieldLabel(L("portfolio_coin"))
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(AppColors.onSurfaceVariant)
                TextField(L("portfolio_coin_search"), text: Binding(
                    get: { coinQuery },
                    set: { coinQuery = String($0.prefix(15)) }
                ))
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .font(.body.weight(.semibold))
                .focused($focusedField, equals: .coin)
                .submitLabel(.next)
                .onSubmit { focusedField = .amount }
            }
            .modifier(PortfolioFieldStyle(error: error, focused: focusedField == .coin))
            if error {
                errorText(L("portfolio_coin_invalid"))
            }
            if !matches.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(matches, id: \.self) { symbol in
                            Button {
                                coinQuery = symbol
                                focusedField = nil
                            } label: {
                                Text(symbol)
                                    .font(.subheadline.weight(.medium))
                                    .lineLimit(1)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 7)
                                    .foregroundStyle(accent.onContainer)
                                    .background(accent.container, in: Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 2)
                }
                .scrollClipDisabled()
                .transition(.opacity)
            }
        }
    }

    private var amountField: some View {
        let holdings = sellHoldings
        let value = amount
        let exceeds: Bool = {
            guard let holdings, let value else { return false }
            return value > holdings * (1 + 1e-9) + PortfolioCalculator.eps
        }()
        let amountError = (tried || !amountText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) && value == nil
        let message: String?
        if amountError {
            message = L("portfolio_invalid")
        } else if exceeds, let holdings {
            message = L("portfolio_sell_exceeds", PortfolioFormat.amount(holdings, coin))
        } else {
            message = nil
        }
        return VStack(alignment: .leading, spacing: 6) {
            fieldLabel(L("portfolio_tx_amount"))
            HStack(spacing: 10) {
                TextField("0", text: $amountText)
                    .keyboardType(.decimalPad)
                    .font(.system(.title3, design: .rounded).weight(.semibold).monospacedDigit())
                    .focused($focusedField, equals: .amount)
                if coinValid {
                    Text(coin)
                        .font(.headline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
            .modifier(PortfolioFieldStyle(error: amountError || exceeds, focused: focusedField == .amount))
            if let message {
                errorText(message)
            }
        }
    }

    private var priceField: some View {
        VStack(alignment: .leading, spacing: 6) {
            fieldLabel(L("portfolio_tx_price"))
            HStack(spacing: 10) {
                // Getippt = nicht mehr automatisch ersetzen
                TextField("0", text: Binding(
                    get: { priceText },
                    set: { priceText = $0; priceAuto = false }
                ))
                .keyboardType(.decimalPad)
                .font(.system(.title3, design: .rounded).weight(.semibold).monospacedDigit())
                .focused($focusedField, equals: .price)
                Text(PortfolioFormat.usdt)
                    .font(.headline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            .modifier(PortfolioFieldStyle(error: !priceValid, focused: focusedField == .price))
            if priceValid {
                Text(L("portfolio_tx_price_hint"))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            } else {
                errorText(L("portfolio_tx_price_invalid"))
            }
        }
    }

    /// Datum (nicht in der Zukunft).
    private var dateRow: some View {
        DatePicker(selection: $date, in: Self.dateRange(), displayedComponents: .date) {
            Text(L("portfolio_tx_date"))
                .font(.body)
                .foregroundStyle(AppColors.onSurfaceVariant)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var noteField: some View {
        VStack(alignment: .leading, spacing: 6) {
            fieldLabel(L("portfolio_tx_note"))
            TextField("", text: Binding(
                get: { note },
                set: { note = String($0.prefix(200)) }
            ))
            .focused($focusedField, equals: .note)
            .submitLabel(.done)
            .modifier(PortfolioFieldStyle(error: false, focused: focusedField == .note))
        }
    }

    @ViewBuilder
    private var totalLine: some View {
        if let value = amount, let p = price {
            Text(L("portfolio_tx_total", PortfolioFormat.usdtValue(value * p)))
                .font(.subheadline.weight(.medium).monospacedDigit())
                .foregroundStyle(accent.primary)
                .contentTransition(.numericText())
        }
    }

    private var deleteButton: some View {
        Button(role: .destructive) {
            askDelete = true
        } label: {
            HStack(spacing: 14) {
                Image(systemName: "trash")
                    .scaledFont(size: 17, weight: .semibold, relativeTo: .body)
                Text(L("action_delete")).font(.body.weight(.medium))
                Spacer()
            }
            .foregroundStyle(AppColors.error)
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(AppColors.error.opacity(0.10), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.top, 6)
    }

    private func fieldLabel(_ text: String) -> some View {
        Text(text)
            .font(.caption.weight(.medium))
            .foregroundStyle(AppColors.onSurfaceVariant)
    }

    private func errorText(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AppColors.error)
            .transition(.opacity)
    }

    // MARK: Abläufe

    /// Wie der `LaunchedEffect(coin, coinValid)` in Android: nur bei neuen Einträgen,
    /// nur für einen anderen Coin und nur, solange kein Preis getippt wurde.
    private func prefillPrice() async {
        let target = coin
        guard !isEdit, coinValid, target != pricedCoin else { return }
        if !priceBlank && !priceAuto { return }
        do {
            try await Task.sleep(nanoseconds: 400_000_000)
        } catch {
            return
        }
        let current = await model.currentPrice(target)
        guard !Task.isCancelled else { return }
        pricedCoin = target
        if priceBlank || priceAuto {
            priceText = current.map { PriceFormat.amountForInput($0) } ?? ""
            priceAuto = current != nil
        }
    }

    private func save() {
        tried = true
        guard valid, let value = amount else {
            WatchlistHaptics.impact(.light)
            return
        }
        let trimmedNote = note.trimmingCharacters(in: .whitespacesAndNewlines)
        data.savePortfolioTx(PortfolioTx(
            id: initial.txId,
            coin: coin,
            type: type,
            amount: value,
            priceUsdt: price,
            time: timeForSelectedDate(),
            note: trimmedNote.isEmpty ? nil : trimmedNote
        ))
        dismiss()
    }

    /// Zeitpunkt für das gewählte Datum: unverändertes Datum behält die Uhrzeit,
    /// heute = jetzt, sonst 12:00 Ortszeit (Reihenfolge am selben Tag nach Erfassung).
    private func timeForSelectedDate() -> Int64 {
        let calendar = Calendar.current
        if calendar.isDate(date, inSameDayAs: Date(millis: initial.time)) { return initial.time }
        if calendar.isDateInToday(date) { return TimeUtils.nowMillis }
        let noon = calendar.date(bySettingHour: 12, minute: 0, second: 0, of: date) ?? date
        return noon.millis
    }

    /// 1. Januar 2009 bis heute (wie `yearRange = 2009..heute`, keine Zukunft).
    private static func dateRange() -> ClosedRange<Date> {
        let start = Calendar.current.date(from: DateComponents(year: 2009, month: 1, day: 1)) ?? Date(timeIntervalSince1970: 0)
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

/// Eingabefeld-Fläche mit Fokus- und Fehlerrand.
private struct PortfolioFieldStyle: ViewModifier {
    let error: Bool
    let focused: Bool
    @Environment(\.appAccent) private var accent

    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 16)
            .padding(.vertical, 13)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(error ? AppColors.error : (focused ? accent.primary.opacity(0.6) : .clear), lineWidth: 1.2)
            )
    }
}
