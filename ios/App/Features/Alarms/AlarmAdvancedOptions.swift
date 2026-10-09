import SwiftUI

/// «Erweitert» im Alarm-Blatt: alle Bedingungen und Optionen — wie `AlarmAdvancedOptions.kt`.
extension AlarmEditorSheet {
    /// Kopf «Erweitert» zum Auf-/Zuklappen.
    var advancedHeader: some View {
        Button {
            WatchlistHaptics.selection()
            advanced.toggle()
        } label: {
            HStack {
                Text(L("alarm_advanced"))
                    .font(.headline)
                    .foregroundStyle(AppColors.onSurface)
                Spacer()
                Image(systemName: "chevron.down")
                    .scaledFont(size: 13, weight: .semibold, relativeTo: .subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .rotationEffect(.degrees(advanced ? 180 : 0))
            }
            .padding(.vertical, Spacing.sm)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityValue(L(advanced ? "a11y_expanded" : "a11y_collapsed"))
    }

    /// Alle Bedingungen und Optionen wie bisher — Kursmarke ohne eigenes Feld (steht im Satz oben).
    var advancedContent: some View {
        VStack(alignment: .leading, spacing: Spacing.lg) {
            // Bedingung als Kacheln, zwei pro Zeile
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(conditions, id: \.self) { condition in
                    conditionTile(condition)
                }
            }

            if draft.condition == .VOLUME_SPIKE {
                volumeFactorPicker
            } else if draft.condition.isPriceThreshold {
                if offerCurrency {
                    currencyPicker
                }
            } else if draft.condition.isFunding {
                fundingField
            } else if !(draft.condition.isNearExtreme && draft.newExtremeOnly) {
                thresholdField
            }

            // Open Interest: Vergleich mit der Messung von vor 1, 4 oder 24 Stunden
            if draft.condition.isOpenInterest {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("alarm_window_label"))
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                    HStack(spacing: 8) {
                        ForEach(DerivativesAlarm.oiWindows, id: \.self) { hours in
                            windowChip(hours)
                        }
                    }
                    Text(L("alarm_oi_hint"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            // Zeitfenster nur für «bewegt sich um x % in y Stunden»
            if draft.condition == .MOVE_PERCENT_WINDOW {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("alarm_window_label"))
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                    HStack(spacing: 8) {
                        ForEach(AlarmDraft.windowChoices, id: \.self) { hours in
                            windowChip(hours)
                        }
                    }
                }
            }

            // «Nahe am Hoch/Tief»: nur neue Hochs/Tiefs, Zeitraum 30 Tage / 90 Tage / 1 Jahr
            if draft.condition.isNearExtreme {
                SwitchRow(title: L("alarm_near_new_only"), isOn: $draft.newExtremeOnly)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 4)
                    .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                nearWindowPicker
            }

            VStack(spacing: 0) {
                SwitchRow(title: L("alarm_option_sound"), isOn: $draft.sound)
                RowDivider()
                // Kein Vibrations-Schalter: iOS steuert Vibration nur über die Systemeinstellungen.
                // Der Wert bleibt gespeichert, damit Sicherungen mit Android austauschbar sind.
                SwitchRow(title: L("alarm_option_speak"), isOn: $draft.speak)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
    }

    private func conditionTile(_ condition: AlarmCondition) -> some View {
        let selected = draft.condition == condition
        return Button {
            WatchlistHaptics.selection()
            let wasVolume = draft.condition == .VOLUME_SPIKE
            draft = draft.withCondition(condition)
            // Zurück zu einem Eingabefeld: gleich tippen können
            if wasVolume && condition != .VOLUME_SPIKE { fieldFocused = true }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: AlarmStyle.symbol(condition))
                    .scaledFont(size: 14, weight: .semibold, relativeTo: .subheadline)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .frame(width: 18)
                Text(AlarmTexts.conditionName(condition))
                    .font(.subheadline.weight(selected ? .semibold : .regular))
                    .lineLimit(2)
                    .minimumScaleFactor(0.85)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
            }
            .foregroundStyle(selected ? accent.onContainer : AppColors.onSurface)
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
            .background(selected ? accent.container : AppColors.container,
                        in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(selected ? accent.primary.opacity(0.6) : AppColors.outlineVariant.opacity(0.5), lineWidth: 1)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Funding: Prozent mit Vorzeichen; «±» wechselt es (die Zifferntastatur hat kein Minus).
    private var fundingField: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("alarm_field_percent"))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
            HStack(spacing: 8) {
                amountInput
                Button {
                    WatchlistHaptics.selection()
                    draft = draft.withToggledSign()
                } label: {
                    Text(verbatim: "±")
                        .font(.title2.weight(.semibold))
                        .frame(minWidth: 52, minHeight: 52)
                        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain)
                .foregroundStyle(accent.primary)
                .accessibilityLabel(L("alarm_funding_sign_a11y"))
            }
            Text(L("alarm_funding_hint"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var thresholdField: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L(draft.condition.isNearExtreme ? "alarm_near_field_distance"
                   : (draft.condition.isPercent || draft.condition.isDerivatives ? "alarm_field_percent" : "alarm_field_price")))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
            amountInput
        }
    }

    /// Eingabefeld für Kurs, Prozent oder Abstand (Ziffern, Trenner; gelesen von `ThresholdParser`).
    var amountInput: some View {
        HStack(spacing: 8) {
            TextField(placeholder, text: $draft.thresholdText)
                .keyboardType(.decimalPad)
                .focused($fieldFocused)
                .accessibilityLabel(L(draft.condition.isNearExtreme ? "alarm_near_field_distance"
                                      : (draft.condition.isPercent || draft.condition.isDerivatives
                                         ? "alarm_field_percent" : "alarm_field_price")))
                .scaledFont(size: 24, weight: .semibold, design: .rounded, relativeTo: .title, monospacedDigit: true)
                .onChange(of: draft.thresholdText) { _, text in
                    // Ziffern, Trenner (Komma, Punkt), Tausendertrenner (’ ' Leerzeichen) und Vorzeichen
                    // (Funding; «±» bzw. eingefügt) behalten;
                    // Buchstaben auch, damit z. B. eingefügtes «60k» ungültig bleibt statt 60 zu werden
                    // (gelesen von `ThresholdParser`, der Satz darunter zeigt den gelesenen Wert).
                    let filtered = text.filter {
                        $0.isNumber || $0.isLetter || $0 == "," || $0 == "."
                            || "’'‘` \u{00A0}\u{202F}\u{2009}-+\u{2212}".contains($0)
                    }
                    if filtered != text { draft.thresholdText = filtered }
                }
            Text(thresholdUnit)
                .font(.headline)
                .foregroundStyle(AppColors.onSurfaceVariant)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, Spacing.lg)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(fieldFocused ? accent.primary : AppColors.outlineVariant.opacity(0.5),
                              lineWidth: fieldFocused ? 1.5 : 1)
        )
        .animation(.easeInOut(duration: 0.2), value: fieldFocused)
    }

    /// Kursalarm: Schwellwert in der Quote (z. B. USDT) oder in der Umrechnungswährung (z. B. CHF).
    private var currencyPicker: some View {
        let quote = (watch?.quoteAsset ?? "").uppercased()
        let other = otherCurrency
        return VStack(alignment: .leading, spacing: 8) {
            Text(L("alarm_currency_label"))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
            Picker(L("alarm_currency_label"), selection: Binding<String>(
                get: { draft.currency == nil ? "" : other },
                set: { value in
                    WatchlistHaptics.selection()
                    draft = draft.withCurrency(value.isEmpty ? nil : other, other: other, rate: rates[other])
                }
            )) {
                Text(quote).tag("")
                Text(other).tag(other)
            }
            .pickerStyle(.segmented)
        }
    }

    /// Faktor Quote → Umrechnungswährung holen (zuerst aus dem Zwischenspeicher).
    func loadRate() async {
        guard offerCurrency, let quote = watch?.quoteAsset else { return }
        let other = otherCurrency
        guard rates[other] == nil else { return }
        var rate = CurrencyConverter.cachedRate(quote: quote, target: other)
        if rate == nil { rate = await CurrencyConverter.rate(quote: quote, target: other) }
        if let rate { rates[other] = rate }
    }

    /// Volumen-Spike: Faktor als Chips statt Eingabefeld, darunter der Hinweis zur Quelle.
    private var volumeFactorPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("alarm_volume_factor_label"))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
            HStack(spacing: 8) {
                ForEach(AlarmDraft.volumeFactors, id: \.self) { factor in
                    factorChip(factor)
                }
            }
            Text(L("alarm_volume_hint"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func factorChip(_ factor: Double) -> some View {
        let selected = draft.threshold == factor
        return Button {
            WatchlistHaptics.selection()
            draft.thresholdText = AlarmDraft.formatFactor(factor)
        } label: {
            Text("×" + AlarmDraft.formatFactor(factor))
                .font(.subheadline.weight(selected ? .semibold : .regular).monospacedDigit())
                .lineLimit(1)
                .foregroundStyle(selected ? accent.onContainer : AppColors.onSurface)
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.md)
                .background(selected ? accent.container : AppColors.container, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? accent.primary.opacity(0.6) : .clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Kursalarm: aktueller Kurs als Vorschlag im leeren Feld.
    private var placeholder: String {
        if draft.condition.isPercent || draft.condition.isNearExtreme || draft.condition.isDerivatives { return "0" }
        guard let price = watch?.lastPrice, price > 0 else { return "0" }
        return PriceFormat.price(price)
    }

    /// «Nahe am Hoch/Tief»: Zeitraum als Chips, darunter der Hinweis zu neuen Hochs/Tiefs.
    private var nearWindowPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("alarm_near_window_label"))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
            HStack(spacing: 8) {
                ForEach(NearExtreme.windows, id: \.self) { days in
                    nearWindowChip(days)
                }
            }
            Text(L("alarm_near_hint"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func nearWindowChip(_ days: Int) -> some View {
        let selected = NearExtreme.windowDays(draft.windowHours) == days
        let weight: Font.Weight = selected ? Font.Weight.semibold : Font.Weight.regular
        return Button {
            WatchlistHaptics.selection()
            draft.windowHours = days
        } label: {
            Text(AlarmTexts.windowLabel(days))
                .font(.subheadline.weight(weight).monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .foregroundStyle(selected ? accent.onContainer : AppColors.onSurface)
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.md)
                .background(selected ? accent.container : AppColors.container, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? accent.primary.opacity(0.6) : .clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func windowChip(_ hours: Int) -> some View {
        let selected = draft.windowHours == hours
        return Button {
            WatchlistHaptics.selection()
            draft.windowHours = hours
        } label: {
            Text(L("alarm_window_hours", count: hours))
                .font(.subheadline.weight(selected ? .semibold : .regular).monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .foregroundStyle(selected ? accent.onContainer : AppColors.onSurface)
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.md)
                .background(selected ? accent.container : AppColors.container, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? accent.primary.opacity(0.6) : .clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
