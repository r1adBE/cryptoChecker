import SwiftUI

// Buchungsangaben des Transaktions-Blatts (Teil von `PortfolioTxSheet`): Wallets von/nach mit Vorschlägen,
// Datum, ursprüngliches Kaufdatum, Notiz, Summe, Gebühr (auch in einem dritten Coin), Börse, Löschen.
// Eigene Datei, weil das Blatt sonst zu lang wird (Gegenstück zu `PortfolioTxFields.kt`).

extension PortfolioTxSheet {
    /// Build 46: selbst benutzte Börsen/Wallets aus den gespeicherten Transaktionen — wie Android `usedWallets`.
    private var usedWallets: [String] {
        var names: [String?] = []
        for tx in data.portfolio {
            names.append(tx.exchange)
            names.append(tx.transferFrom)
            names.append(tx.transferTo)
        }
        return PortfolioWalletNames.known(names)
    }

    /// Von/Nach einer Umbuchung: freier Text (Börse oder Wallet). Build 46: Vorschläge als Chips — im leeren,
    /// fokussierten Feld die selbst benutzten Namen, beim Tippen dazu die Börsen der App
    /// (`PortfolioWalletNames.suggestions`); bei einem fast gleichen bekannten Namen («Binanse») der Hinweis
    /// «Meintest du Binance?» zum Antippen — bei FIFO je Wallet wäre jede andere Schreibweise eine eigene Wallet.
    /// Wie Android `RouteField`.
    func routeField(_ label: String, text: Binding<String>, focus: PortfolioTxField, hint: String?) -> some View {
        let query: String = text.wrappedValue.trimmingCharacters(in: .whitespaces)
        let used: [String] = usedWallets
        let catalog: [String] = PortfolioRouteResolver.spotMarketNames
        let focusedHere: Bool = focusedField == focus
        let suggested: [String] = PortfolioWalletNames.suggestions(used: used, catalog: catalog, query: query)
        let matches: [String] = query.isEmpty && !focusedHere ? [] : suggested
        let nearName: String? = PortfolioWalletNames.nearDuplicate(query, known: PortfolioWalletNames.known(used + catalog))
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            fieldLabel(label)
            TextField("", text: Binding(
                get: { text.wrappedValue },
                set: { text.wrappedValue = String($0.prefix(PortfolioTxForm.maxRoute)) }
            ))
            .textInputAutocapitalization(.words)
            .autocorrectionDisabled()
            .focused($focusedField, equals: focus)
            .modifier(PortfolioFieldStyle(error: false, focused: focusedField == focus))
            if let nearName {
                // «Meintest du Binance?» — antippen übernimmt den bekannten Namen
                Button {
                    text.wrappedValue = nearName
                    focusedField = nil
                } label: {
                    Label(L("portfolio_wallet_did_you_mean", nearName), systemImage: "info.circle")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AppColors.warningText)
                        .multilineTextAlignment(.leading)
                        .padding(.horizontal, 12)
                        .padding(.vertical, Spacing.sm)
                        .frame(minHeight: 44)
                        .background(AppColors.containerHigh, in: Capsule())
                }
                .buttonStyle(.plain)
                // Warnfarbe trägt Bedeutung: «Warnung, Meintest du …?» (wie Android contentDescription)
                .accessibilityLabel(Text(verbatim: L("a11y_warning") + ", " + L("portfolio_wallet_did_you_mean", nearName)))
            }
            if !matches.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(matches, id: \.self) { name in
                            Button {
                                text.wrappedValue = name
                                focusedField = nil
                            } label: {
                                Text(name)
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
            if let hint {
                Text(hint)
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        }
    }

    /// Datum (nicht in der Zukunft).
    var dateRow: some View {
        DatePicker(selection: $date, in: Self.dateRange(), displayedComponents: .date) {
            Text(L("portfolio_tx_date"))
                .font(.body)
                .foregroundStyle(AppColors.onSurfaceVariant)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }

    /// «Ursprüngliches Kaufdatum (optional)» einer Einzahlung: leer ein Knopf (setzt das Datum der Einzahlung), sonst
    /// Datumswahl bis zum Datum der Einzahlung mit Leeren-Knopf; darunter «Für die Haltedauer im Steuer-Export».
    /// Wie `AcquiredDateField` (Android).
    var acquiredRow: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            HStack(spacing: Spacing.sm) {
                if let current = acquiredDate {
                    DatePicker(selection: Binding(get: { current }, set: { acquiredDate = min($0, date) }),
                               in: Self.acquiredRange(until: date), displayedComponents: .date) {
                        Text(L("portfolio_tx_acquired_date"))
                            .font(.body)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                    Button {
                        acquiredDate = nil
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L("action_clear"))
                } else {
                    Button {
                        acquiredDate = date
                    } label: {
                        HStack {
                            Text(L("portfolio_tx_acquired_date"))
                                .font(.body)
                                .foregroundStyle(AppColors.onSurfaceVariant)
                            Spacer(minLength: Spacing.sm)
                            Image(systemName: "calendar")
                                .foregroundStyle(AppColors.onSurfaceVariant)
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.leading, 16)
            .padding(.trailing, acquiredDate == nil ? 16 : 4)
            .padding(.vertical, 4)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
            Text(L("portfolio_tx_acquired_date_hint"))
                .font(.caption)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.horizontal, 16)
        }
    }

    /// Kauf: Notiz im zugeklappten Abschnitt «Gebühr, Börse, Notiz» (wie Android).
    /// Die Notiz steht seit Build 78 bei allen Arten direkt unter dem Preis, nie im zugeklappten Abschnitt.
    var noteInLedger: Bool { false }

    var noteField: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            fieldLabel(L("portfolio_tx_note"))
            TextField("", text: Binding(
                get: { note },
                set: { note = String($0.prefix(Watch.noteMax)) }
            ))
            .focused($focusedField, equals: .note)
            .submitLabel(.done)
            .modifier(PortfolioFieldStyle(error: false, focused: focusedField == .note))
        }
    }

    @ViewBuilder
    var totalLine: some View {
        if fields.price, let value = amount, let p = price {
            Text(L("portfolio_tx_total", PortfolioFormat.usdtValue(PortfolioDecimal.double(value * p))))
                .font(.subheadline.weight(.medium).monospacedDigit())
                .foregroundStyle(accent.primary)
                .contentTransition(.numericText())
        }
    }

    /// Gebühr (Betrag und Einheit USDT | Coin) und Börse, zugeklappt — wie `LedgerFields` (Android).
    /// Gebühr in USDT erhöht beim Kauf den Einstand und verringert beim Verkauf den Erlös; Gebühr im
    /// Coin verringert die Menge. Die Börse bestimmt den Kurs des Coins (`PortfolioExchangeRoute`).
    /// Gebühren und Börse je Art (`PortfolioTxForm.Fields`): Kauf/Verkauf Gebühr USDT | Coin, Auszahlung Gebühr
    /// im Coin, Umbuchung Netzwerkgebühr im Coin; dazu die Gebühr in einem dritten Coin. Belohnung/Einzahlung:
    /// nur die Börse.
    @ViewBuilder
    var ledgerSection: some View {
        let title: String = {
            if noteInLedger { return L("portfolio_tx_more_buy") }
            if fields.feeWithUnit || (fields.coinFeeOnly && fields.exchange) { return L("ledger_more_toggle") }
            return L(hasFee ? "portfolio_tx_fees_toggle" : "ledger_exchange")
        }()
        Button {
            withAnimation(.easeInOut(duration: 0.2)) { showLedger.toggle() }
        } label: {
            ExpandToggleLabel(title: title, expanded: showLedger)
        }
        .buttonStyle(.plain)
        if showLedger {
            VStack(alignment: .leading, spacing: Spacing.md) {
                if hasFee {
                    feeField
                }
                if fields.exchange {
                    exchangeField
                }
                // Gebühr in einem dritten Coin: direkt im Abschnitt (keine zweite Aufklapp-Ebene)
                if fields.thirdCoinFee {
                    Text(L("portfolio_tx_third_fee_toggle"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .padding(.top, Spacing.xs)
                    thirdFeeFields
                }
                if noteInLedger {
                    noteField
                }
            }
            .transition(.opacity)
        }
    }

    private var feeField: some View {
        let label = fields.feeWithUnit ? "ledger_fee" : (type == .TRANSFER ? "portfolio_tx_network_fee" : "portfolio_tx_coin_fee")
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            fieldLabel(L(label))
            HStack(spacing: Spacing.sm) {
                TextField("0", text: $feeText)
                    .keyboardType(.decimalPad)
                    .font(AppFont.amount(.body, weight: .semibold))
                    .focused($focusedField, equals: .fee)
                    .id(PortfolioTxField.fee)
                if fields.coinFeeOnly && coinValid {
                    Text(coin)
                        .font(.headline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
            .modifier(PortfolioFieldStyle(error: !feeValid || (feeConsumes && !isSwap), focused: focusedField == .fee))
            if fields.feeWithUnit {
                Picker(selection: $feeUnit) {
                    Text(PortfolioFormat.usdt).tag(PortfolioFeeUnit.QUOTE)
                    Text(coinValid ? coin : L("ledger_fee_coin")).tag(PortfolioFeeUnit.COIN)
                } label: {
                    EmptyView()
                }
                .pickerStyle(.segmented)
            }
            if !feeValid {
                errorText(L("portfolio_tx_price_invalid"))
            } else if feeConsumes && !isSwap {
                errorText(L("portfolio_tx_fee_consumes_amount"))
            } else if fields.feeWithUnit {
                Text(L("ledger_fee_hint"))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        }
    }

    /// Gebühr in einem dritten Coin (z. B. BNB): Coin, Menge, Preis je Gebühr-Coin (optional — leer = unvollständig).
    private var thirdFeeFields: some View {
        let state = thirdFee
        // Tausch: Gebühr im erhaltenen Coin ≥ erhaltene Menge ist ebenfalls ein Fehler
        let consumes = feeConsumes && isSwap
        let invalid = state == .invalid || consumes
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            HStack(spacing: Spacing.sm) {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    fieldLabel(L("portfolio_tx_third_fee_coin"))
                    TextField("BNB", text: Binding(
                        get: { feeCoinText },
                        set: { feeCoinText = String($0.prefix(PortfolioTx.maxFeeCoin)) }
                    ))
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .focused($focusedField, equals: .feeCoin)
                    .id(PortfolioTxField.feeCoin)
                    .modifier(PortfolioFieldStyle(error: invalid, focused: focusedField == .feeCoin))
                }
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    fieldLabel(L("portfolio_tx_third_fee_amount"))
                    TextField("0", text: $feeCoinAmountText)
                        .keyboardType(.decimalPad)
                        .focused($focusedField, equals: .feeCoinAmount)
                        .modifier(PortfolioFieldStyle(error: invalid, focused: focusedField == .feeCoinAmount))
                }
            }
            // Tausch, Gebühr im erhaltenen Coin: ohne Preis, mindert nur die erhaltene Menge
            if feeInReceived {
                if consumes {
                    errorText(L("portfolio_tx_fee_consumes_amount"))
                } else if invalid {
                    errorText(L("portfolio_invalid"))
                } else {
                    Text(L("portfolio_tx_swap_fee_received", toCoin))
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            } else {
                fieldLabel(L("portfolio_tx_third_fee_price"))
                HStack(spacing: Spacing.sm) {
                    TextField("0", text: Binding(
                        get: { feeCoinPriceText },
                        set: { feeCoinPriceText = $0; feeCoinPriceAuto = false }
                    ))
                    .keyboardType(.decimalPad)
                    .focused($focusedField, equals: .feeCoinPrice)
                    Text(PortfolioFormat.usdt)
                        .font(.headline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                .modifier(PortfolioFieldStyle(error: invalid, focused: focusedField == .feeCoinPrice))
                if invalid {
                    errorText(L("portfolio_tx_price_invalid"))
                } else if state == .incomplete {
                    Text(L("portfolio_tx_third_fee_incomplete"))
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
        }
    }

    /// Börse als Auswahl: «Keine (Binance)», ein früher frei getippter Name (falls keiner der Liste) und die
    /// Spot-Börsen der App; der Coin wird dann mit ihrem Kurs bewertet (sonst Binance) — wie Android.
    private var exchangeField: some View {
        let names = PortfolioRouteResolver.spotMarketNames
        let current = exchange.trimmingCharacters(in: .whitespaces)
        let legacy: String? = current.isEmpty || names.contains(where: { $0.caseInsensitiveCompare(current) == .orderedSame })
            ? nil : current
        let selection = Binding<String>(
            get: { names.first(where: { $0.caseInsensitiveCompare(current) == .orderedSame }) ?? current },
            set: { exchange = String($0.prefix(PortfolioTx.maxExchange)) }
        )
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            fieldLabel(L("ledger_exchange"))
            Picker(selection: selection) {
                Text(L("ledger_exchange_none")).tag("")
                if let legacy { Text(legacy).tag(legacy) }
                ForEach(names, id: \.self) { name in Text(name).tag(name) }
            } label: {
                Text(L("ledger_exchange"))
            }
            .pickerStyle(.menu)
            .tint(accent.primary)
            Text(L("ledger_exchange_hint"))
                .font(.caption)
                .foregroundStyle(AppColors.onSurfaceVariant)
        }
    }

    var deleteButton: some View {
        Button(role: .destructive) {
            askDelete = true
        } label: {
            HStack(spacing: Spacing.md) {
                Image(systemName: "trash")
                    .scaledFont(size: 17, weight: .semibold, relativeTo: .body)
                Text(L("action_delete")).font(.body.weight(.medium))
                Spacer()
            }
            .foregroundStyle(AppColors.error)
            .padding(.horizontal, 16)
            .padding(.vertical, Spacing.lg)
            .background(AppColors.error.opacity(0.10), in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.top, Spacing.xs)
    }

    func fieldLabel(_ text: String) -> some View {
        Text(text)
            .font(.caption.weight(.medium))
            .foregroundStyle(AppColors.onSurfaceVariant)
    }

    func errorText(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AppColors.error)
            .transition(.opacity)
    }
}
