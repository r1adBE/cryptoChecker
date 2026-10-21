import SwiftUI

// Eingabefelder des Transaktions-Blatts (Teil von `PortfolioTxSheet`): Art, Coin, Menge, Preis mit Hinweisen,
// erhaltene Seite und Wert eines Tauschs, «Eingabe in: USDT | CHF».
// Eigene Datei, weil das Blatt Zustand, Prüfung und Speichern regelt und die Felder für sich lesbar sein sollen
// (wie `PortfolioTxFields.kt`).

extension PortfolioTxSheet {
    // MARK: Felder

    /// Art (Build 46): eine kompakte Wahl «Art: Kauf ▾», die übrigen Arten im Menü (gewählte mit Häkchen),
    /// darunter eine kurze Erklärung der neuen Arten — wie Android.
    var typePicker: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Menu {
                ForEach(PortfolioTxForm.types, id: \.self) { option in
                    Button {
                        type = option
                    } label: {
                        if option == type {
                            Label(PortfolioFormat.typeLabel(option), systemImage: "checkmark")
                        } else {
                            Text(PortfolioFormat.typeLabel(option))
                        }
                    }
                }
            } label: {
                ExpandToggleLabel(title: L("portfolio_tx_type_select", PortfolioFormat.typeLabel(type)), expanded: false)
            }
            .haptic(.selection, trigger: type)
            if let key = Self.explainKey(type) {
                Text(L(key))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            // Auszahlung: Verschenkt oder damit bezahlt? Je nach Land steuerlich ein Verkauf (eigene Arten)
            if type == .WITHDRAWAL {
                Text(L("portfolio_tx_withdrawal_gift_hint"))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        }
    }

    /// Kurze Erklärung unter der Wahl der Art (nur die neuen Arten) — wie `explainRes` (Android).
    static func explainKey(_ type: PortfolioTxType) -> String? {
        switch type {
        case .BUY, .SELL: return nil
        case .TRANSFER: return "portfolio_tx_explain_transfer"
        case .REWARD: return "portfolio_tx_explain_reward"
        case .DEPOSIT: return "portfolio_tx_explain_deposit"
        case .WITHDRAWAL: return "portfolio_tx_explain_withdrawal"
        case .SWAP: return "portfolio_tx_explain_swap"
        case .GIFT: return "portfolio_tx_explain_gift"
        case .PAYMENT: return "portfolio_tx_explain_payment"
        }
    }

    /// Coin mit Suche: Treffer als Chips darunter.
    var coinField: some View {
        let error = tried && !coinValid
        let matches = Self.matchCoins(model.coins, coin)
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            fieldLabel(L(isSwap ? "portfolio_tx_swap_given_coin" : "portfolio_coin"))
            HStack(spacing: Spacing.sm) {
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
                .id(PortfolioTxField.coin)
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
                                    .padding(.vertical, Spacing.sm)
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

    var amountField: some View {
        let holdings = sellHoldings
        let value = amount
        // Menge + Gebühr im Coin über dem Bestand: Warnung (Speichern bleibt möglich)
        let exceeds = PortfolioTxForm.outflowExceeds(type: type, amount: value, fee: fee, unit: feeUnit, holdings: holdings)
        // FIFO je Wallet: auch der Bestand DIESER Wallet (Börse bzw. «Von» der Umbuchung) muss reichen
        let walletName = (type == .TRANSFER ? transferFrom : exchange).trimmingCharacters(in: .whitespacesAndNewlines)
        let walletHoldings: Decimal? = fields.outflow && coinValid && !exceeds
            ? data.portfolioWalletHoldings(of: coin, wallet: walletName, excludingId: initial.txId) : nil
        let walletExceeds = walletHoldings.map {
            PortfolioTxForm.outflowExceeds(type: type, amount: value, fee: fee, unit: feeUnit, holdings: $0)
        } ?? false
        let amountError = (tried || !amountText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) && value == nil
        let message: String?
        if amountError {
            message = L("portfolio_invalid")
        } else if walletExceeds, let walletHoldings {
            message = L("portfolio_sell_exceeds_wallet", PortfolioFormat.exactAmount(walletHoldings, coin),
                        walletName.isEmpty ? L("tax_export_wallet_unspecified") : walletName)
        } else if exceeds, let holdings {
            let key = PortfolioTxForm.sellExceedsIncludesFee(fee, unit: effectiveFeeUnit) ? "portfolio_sell_exceeds_fee" : "portfolio_sell_exceeds"
            message = L(key, PortfolioFormat.exactAmount(holdings, coin))
        } else {
            message = nil
        }
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            fieldLabel(L(isSwap ? "portfolio_tx_swap_given_amount" : "portfolio_tx_amount"))
            HStack(spacing: Spacing.sm) {
                TextField("0", text: $amountText)
                    .keyboardType(.decimalPad)
                    .font(AppFont.amount(.title3, weight: .semibold))
                    .focused($focusedField, equals: .amount)
                    .id(PortfolioTxField.amount)
                if coinValid {
                    Text(coin)
                        .font(.headline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
            .modifier(PortfolioFieldStyle(error: amountError || exceeds || walletExceeds, focused: focusedField == .amount))
            if let message {
                errorText(message)
            }
        }
    }

    var priceField: some View {
        let receipt = type == .REWARD || type == .DEPOSIT
        // Verschenkt, Damit bezahlt: Marktwert je Coin (Damit bezahlt: Erlös = Menge × Wert, kein Geld erhalten)
        let label = receipt ? "portfolio_tx_value_received" : (type == .GIFT || type == .PAYMENT ? "portfolio_tx_market_value" : "portfolio_tx_price")
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            fieldLabel(L(label))
            HStack(spacing: Spacing.sm) {
                // Getippt = nicht mehr automatisch ersetzen
                TextField("0", text: Binding(
                    get: { priceText },
                    set: { priceText = $0; priceAuto = false; priceHistoricDay = nil }
                ))
                .keyboardType(.decimalPad)
                .font(AppFont.amount(.title3, weight: .semibold))
                .focused($focusedField, equals: .price)
                .id(PortfolioTxField.price)
                Text(entryUnit)
                    .font(.headline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            .modifier(PortfolioFieldStyle(error: !priceValid, focused: focusedField == .price))
            if priceValid {
                Text(convertedText(price, perCoin: true) ?? priceHintText(receipt: receipt))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            } else if priceZero {
                errorText(L("portfolio_tx_price_zero"))
            } else if fiatActive && fxRate == nil {
                // Anzeigewährung ohne (geladenen) Kurs
                errorText(L("portfolio_tx_fiat_no_fx"))
            } else {
                errorText(L("portfolio_tx_price_invalid"))
            }
        }
    }

    private func priceHintText(receipt: Bool) -> String {
        // Geprüfter Tagesschluss vorbelegt: so markiert
        if let day = priceHistoricDay { return L("portfolio_tx_price_historic", day) }
        if receipt { return L("portfolio_tx_price_receipt_hint") }
        // Ohne Marktwert: zum Tagesschluss geschätzt (`PortfolioLedger.valueAtDayClose`) — wie Android
        if type == .GIFT || type == .PAYMENT { return L("portfolio_tx_gift_price_hint") }
        // Wert nur für die Verlaufsrechnung (`PortfolioLedger.flow`); der Abgang zählt immer zum Einstand — wie Android
        if type == .WITHDRAWAL { return L("portfolio_tx_withdrawal_price_hint") }
        // Anderer Tag: der heutige Kurs passt nicht — Kurs vom Kauftag eintragen
        return L(PortfolioTxForm.showPastPriceHint(isEdit: isEdit, isToday: isToday)
                 ? "portfolio_tx_price_past_hint" : "portfolio_tx_price_hint")
    }

    /// «≈ 12’345.67 USDT zum Kurs vom 03.03.2025» unter einem Feld in der Anzeigewährung; nil sonst. Ohne plausiblen
    /// USDT/USD-Kurs (USDT = 1) mit dem Vermerk der Summen und des Steuer-Exports «· USDT = 1 angenommen» — wie Android.
    private func convertedText(_ usdt: Decimal?, perCoin: Bool) -> String? {
        guard fiatActive, let usdt, let day = entryFxDay else { return nil }
        let value = PortfolioDecimal.double(usdt)
        let amount = perCoin ? PortfolioFormat.price(value) : PortfolioFormat.usdtValue(value)
        let text = L("portfolio_tx_fiat_converted", amount, PortfolioFormat.date(day.noonLocal.millis))
        return entryFxUsdtAssumed ? text + " · " + L("portfolio_usdt_assumed") : text
    }

    /// Tausch: erhaltener Coin (mit Suche) und erhaltene Menge.
    var swapReceivedFields: some View {
        let showCoinError = (tried || !toCoinQuery.isEmpty) && !toCoinValid
        let coinError = coinValid && toCoin == coin ? L("portfolio_tx_swap_same_coin") : L("portfolio_coin_invalid")
        let matches = Self.matchCoins(model.coins, toCoin)
        let amountError = (tried || !toAmountText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) && toAmount == nil
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            fieldLabel(L("portfolio_tx_swap_received_coin"))
            HStack(spacing: Spacing.sm) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(AppColors.onSurfaceVariant)
                TextField(L("portfolio_coin_search"), text: Binding(
                    get: { toCoinQuery },
                    set: { toCoinQuery = String($0.prefix(15)) }
                ))
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .font(.body.weight(.semibold))
                .focused($focusedField, equals: .toCoin)
                .id(PortfolioTxField.toCoin)
            }
            .modifier(PortfolioFieldStyle(error: showCoinError, focused: focusedField == .toCoin))
            if showCoinError {
                errorText(coinError)
            }
            if !matches.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(matches, id: \.self) { symbol in
                            Button {
                                toCoinQuery = symbol
                                focusedField = nil
                            } label: {
                                Text(symbol)
                                    .font(.subheadline.weight(.medium))
                                    .lineLimit(1)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, Spacing.sm)
                                    .foregroundStyle(accent.onContainer)
                                    .background(accent.container, in: Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 2)
                }
                .scrollClipDisabled()
            }
            fieldLabel(L("portfolio_tx_swap_received_amount"))
                .padding(.top, Spacing.xs)
            HStack(spacing: Spacing.sm) {
                TextField("0", text: $toAmountText)
                    .keyboardType(.decimalPad)
                    .font(AppFont.amount(.title3, weight: .semibold))
                    .focused($focusedField, equals: .toAmount)
                    .id(PortfolioTxField.toAmount)
                if PortfolioPriceSource.isSymbol(toCoin) {
                    Text(toCoin)
                        .font(.headline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
            .modifier(PortfolioFieldStyle(error: amountError, focused: focusedField == .toAmount))
            if amountError {
                errorText(L("portfolio_invalid"))
            }
        }
    }

    /// «Eingabe in: USDT | CHF» — Preis/Wert in USDT oder in der Anzeigewährung (gespeichert wird USDT).
    var entryCurrencyRow: some View {
        let selection = Binding<Bool>(
            get: { entryFiat },
            set: { fiat in
                if fiat { switchToFiat() } else { switchToUsdt() }
            }
        )
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            HStack(spacing: Spacing.sm) {
                Text(L("portfolio_tx_entry_currency"))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                Spacer(minLength: 8)
                Picker(selection: selection) {
                    Text(PortfolioFormat.usdt).tag(false)
                    Text(currency).tag(true)
                } label: {
                    EmptyView()
                }
                .pickerStyle(.segmented)
                .fixedSize()
                // Kein Kurs für diesen Tag: Anzeigewährung gesperrt
                .disabled(fxFailed && !entryFiat)
            }
            if fxFailed {
                errorText(L("portfolio_tx_fiat_no_fx"))
            } else if entryFiat && (pendingToFiat || fxRate == nil) {
                Text(L("portfolio_tx_fiat_loading"))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        }
    }

    /// Tausch: Wert des Tauschs (optional, gesamt) in USDT bzw. in der Anzeigewährung.
    var swapValueField: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            fieldLabel(L("portfolio_tx_swap_value"))
            HStack(spacing: Spacing.sm) {
                TextField("0", text: $swapValueText)
                    .keyboardType(.decimalPad)
                    .font(AppFont.amount(.title3, weight: .semibold))
                    .focused($focusedField, equals: .swapValue)
                    .id(PortfolioTxField.swapValue)
                Text(entryUnit)
                    .font(.headline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            .modifier(PortfolioFieldStyle(error: !swapValueValid, focused: focusedField == .swapValue))
            if swapValueValid {
                Text(convertedText(swapValue, perCoin: false) ?? L("portfolio_tx_swap_value_hint"))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            } else {
                errorText(L("portfolio_tx_price_invalid"))
            }
        }
    }
}
