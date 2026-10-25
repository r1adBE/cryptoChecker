import SwiftUI

/// «Mehr Bedingungen» und «Erweitert» im Alarm-Blatt — wie `AlarmAdvancedOptions.kt`.
extension AlarmEditorSheet {
    /// Kopf «Mehr Bedingungen» bzw. «Erweitert» zum Auf-/Zuklappen.
    func sectionHeader(_ title: String, subtitle: String? = nil, expanded: Binding<Bool>) -> some View {
        Button {
            Haptics.play(.selection)
            expanded.wrappedValue.toggle()
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.headline)
                        .foregroundStyle(AppColors.onSurface)
                    // Zugeklappt: grau, was drin steht
                    if let subtitle {
                        Text(subtitle)
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer()
                // Auf-/Zuklapp-Pfeil überall in der Themenfarbe (wie `ExpandToggleLabel`)
                Image(systemName: "chevron.down")
                    .scaledFont(size: 13, weight: .semibold, relativeTo: .subheadline)
                    .foregroundStyle(accent.primary)
                    .rotationEffect(.degrees(expanded.wrappedValue ? 180 : 0))
            }
            .padding(.vertical, Spacing.sm)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityValue(L(expanded.wrappedValue ? "a11y_expanded" : "a11y_collapsed"))
    }

    /// Einfache Bedingungen als Kacheln, zwei pro Zeile: Kurs über/unter, Steigt/Fällt um.
    var simpleConditionTiles: some View {
        LazyVGrid(columns: columns, spacing: 8) {
            ForEach(AlarmTemplates.simpleConditions, id: \.self) { condition in
                conditionTile(condition)
            }
        }
    }

    /// Inhalt von «Mehr»: Schnell-Alarme (nur beim Anlegen), weitere Bedingungen, «Erweitert» — wie Android.
    var moreContent: some View {
        VStack(alignment: .leading, spacing: Spacing.lg) {
            // Ein Antippen legt den Alarm sofort an
            if !templates.isEmpty { templateChips }
            moreSubtitle(L("alarm_more_conditions"))
            moreConditionsContent
            moreSubtitle(L("alarm_advanced"))
            advancedContent
        }
    }

    private func moreSubtitle(_ title: String) -> some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(AppColors.onSurfaceVariant)
            .accessibilityAddTraits(.isHeader)
    }

    /// «Mehr Bedingungen»: alle übrigen Bedingungen als Kacheln, dazu die Felder der gewählten
    /// (die einfachen haben ihr Feld oben).
    var moreConditionsContent: some View {
        VStack(alignment: .leading, spacing: Spacing.lg) {
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(conditions.filter { AlarmTemplates.opensMoreConditions(condition: $0) }, id: \.self) { condition in
                    conditionTile(condition)
                }
            }

            if AlarmTemplates.opensMoreConditions(condition: draft.condition) {
                if draft.condition == .VOLUME_SPIKE {
                    volumeFactorPicker
                } else if draft.condition.isFunding {
                    fundingField
                } else if !(draft.condition.isNearExtreme && draft.newExtremeOnly) {
                    thresholdField
                }
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
                    .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                nearWindowPicker
            }
        }
    }

    /// «Erweitert»: Währung der Kursmarke (Betrag steht im Satz oben), Ton, Vorlesen.
    var advancedContent: some View {
        VStack(alignment: .leading, spacing: Spacing.lg) {
            if offerCurrency {
                currencyPicker
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
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
            // iOS spricht nur bei offener App (keine Sprachausgabe im Hintergrund) — ehrlich sagen
            Text(L("alarm_speak_ios_open_only"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 4)
        }
    }

    private func conditionTile(_ condition: AlarmCondition) -> some View {
        let selected = draft.condition == condition
        return Button {
            Haptics.play(.selection)
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
                        in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
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
                    Haptics.play(.selection)
                    draft = draft.withToggledSign()
                } label: {
                    Text(verbatim: "±")
                        .font(.title2.weight(.semibold))
                        .frame(minWidth: 52, minHeight: 52)
                        .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                }
                .buttonStyle(.plain)
                .foregroundStyle(accent.primary)
                .accessibilityLabel(L("alarm_funding_sign_a11y"))
            }
            Text(L("alarm_funding_hint_8h"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// Prozent oder Abstand (Steigt/Fällt um, Zeitfenster, Hoch/Tief, Open Interest).
    var thresholdField: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L(draft.condition.isNearExtreme ? "alarm_near_field_distance"
                   : (draft.condition.isPercent || draft.condition.isDerivatives ? "alarm_field_percent" : "alarm_field_price")))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
            amountInput
        }
    }

    /// Eingabefeld für Kurs, Prozent oder Abstand (Ziffern, Trenner; gelesen von `ThresholdParser`),
    /// darunter der Fehlertext, warum «Speichern» gesperrt ist (wie Android `supportingText`).
    var amountInput: some View {
        let problem: ThresholdProblem? = draft.visibleProblem
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            amountBox(error: problem != nil)
            AlarmThresholdError(problem: problem)
        }
    }

    private func amountBox(error: Bool) -> some View {
        // Fehler: Rand in der Fehlerfarbe (wie Android isError)
        let stroke: Color = error ? AppColors.error
            : (fieldFocused ? accent.primary : AppColors.outlineVariant.opacity(0.5))
        let strokeWidth: CGFloat = fieldFocused || error ? 1.5 : 1
        return HStack(spacing: 8) {
            TextField(placeholder: placeholder, text: $draft.thresholdText)
                .keyboardType(.decimalPad)
                .focused($fieldFocused)
                .accessibilityIdentifier(UiTestTags.alarmThreshold)
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
                    // Erste Eingabe: ab jetzt gilt auch «leer» als Fehler
                    if !text.isEmpty && draft.blankStart { draft.blankStart = false }
                }
            Text(thresholdUnit)
                .font(.headline)
                .foregroundStyle(AppColors.onSurfaceVariant)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, Spacing.lg)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
                .strokeBorder(stroke, lineWidth: strokeWidth)
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
                    Haptics.play(.selection)
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
        // Aster «U» zählt als USD (`CurrencyConversion.conversionQuote`)
        guard offerCurrency, let quote = watch.map(WatchlistConversion.quote) else { return }
        let other = otherCurrency
        guard rates[other] == nil else { return }
        var rate = CurrencyConverter.cachedRate(quote: quote, target: other)
        if rate == nil { rate = await CurrencyConverter.rate(quote: quote, target: other) }
        if let rate { rates[other] = rate }
    }

    /// Kursalarm: aktueller Kurs als Vorschlag im leeren Feld.
    private var placeholder: String {
        if draft.condition.isPercent || draft.condition.isNearExtreme || draft.condition.isDerivatives { return "0" }
        guard let price = watch?.lastPrice, price > 0 else { return "0" }
        return PriceFormat.price(price)
    }
}
