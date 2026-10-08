import SwiftUI

/// Alarm anlegen/bearbeiten als Blatt von unten — wie `AlarmEditorSheet` in Android. Oben die Schnell-Alarme
/// (nur beim Anlegen), dann der einfache Satz «Wenn BTC über [Betrag] geht» mit «Einmal /
/// Jedes Mal»; alle übrigen Bedingungen und Optionen unter «Erweitert» (zugeklappt, ausser beim
/// Bearbeiten eines Alarms, den der Satz nicht zeigt). Volle Höhe: Aufklappen scrollt im Inhalt.
@MainActor
struct AlarmEditorSheet: View {
    let initial: AlarmDraft
    let watch: Watch?
    /// Umrechnungswährung aus den Einstellungen, z. B. «CHF».
    let targetCurrency: String
    let onSave: (AlarmDraft) -> Void
    /// Schnell-Alarm angetippt: der Aufrufer legt ihn an, das Blatt schliesst sich.
    var onTemplate: (AlarmTemplates.Template) -> Void = { _ in }

    @Environment(\.appAccent) var accent
    @Environment(\.dismiss) private var dismiss
    @State var draft: AlarmDraft
    /// Faktor Quote → Währung, soweit bekannt (zum Umrechnen des getippten Schwellwerts).
    @State var rates: [String: Double] = [:]
    @FocusState var fieldFocused: Bool
    /// «Erweitert» aufgeklappt.
    @State var advanced: Bool
    /// Hat das Paar Tageskerzen (30-Tage-Hoch/-Tief) bzw. Stundenvolumen? Für die Schnell-Alarme.
    @State private var hasDailyRange = false
    @State private var hasHourlyVolume = false

    init(initial: AlarmDraft, watch: Watch?, targetCurrency: String = AppSettings.defaultCurrency(),
         onSave: @escaping (AlarmDraft) -> Void,
         onTemplate: @escaping (AlarmTemplates.Template) -> Void = { _ in }) {
        self.initial = initial
        self.watch = watch
        self.targetCurrency = targetCurrency
        self.onSave = onSave
        self.onTemplate = onTemplate
        _draft = State(initialValue: initial)
        // Offen, wenn der einfache Satz den Alarm nicht zeigen kann (z. B. Volumen-Spike)
        _advanced = State(initialValue: AlarmTemplates.opensAdvanced(condition: initial.condition, currency: initial.currency))
    }

    /// Sichtbare Schnell-Alarme (nur beim Anlegen); ohne Kurs bzw. Kerzen (z. B. DEX) ausgeblendet.
    private var templates: [AlarmTemplates.Template] {
        guard draft.id == 0 else { return [] }
        return AlarmTemplates.available(hasPrice: (watch?.lastPrice ?? 0) > 0,
                                        hasDailyRange: hasDailyRange, hasHourlyVolume: hasHourlyVolume)
    }

    /// Zweite Währung neben der Quote: die des Alarms (falls gesetzt), sonst die Umrechnungswährung.
    var otherCurrency: String {
        (draft.currency ?? targetCurrency).uppercased()
    }

    /// Währungswahl nur bei Kursalarmen und wenn die Quote nicht schon die Umrechnungswährung ist.
    var offerCurrency: Bool {
        guard draft.condition.isPriceThreshold, let quote = watch?.quoteAsset, !quote.isEmpty else { return false }
        return !CurrencyConversion.sameCurrency(quote, otherCurrency)
    }

    /// Funding- und Open-Interest-Bedingungen anbieten: nur Perpetuals an Börsen mit eigenen Daten.
    private var derivativesSupported: Bool {
        watch.map { DerivativesAlarm.supports($0) } ?? false
    }

    /// Wählbare Bedingungen; ein bestehender Funding-/OI-Alarm bleibt sichtbar (z. B. aus einer Sicherung).
    var conditions: [AlarmCondition] {
        AlarmCondition.allCases.filter { !$0.isDerivatives || derivativesSupported || $0 == draft.condition }
    }

    /// Einheit hinter dem Eingabefeld.
    var thresholdUnit: String {
        if draft.condition.isPercent || draft.condition.isNearExtreme || draft.condition.isDerivatives { return "%" }
        if offerCurrency && draft.currency != nil { return otherCurrency }
        return watch?.quoteAsset ?? ""
    }

    let columns = [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.lg) {
                    if let watch {
                        HStack(spacing: 12) {
                            CoinBadge(symbol: watch.baseAsset, size: 36, logo: CoinLogos.allowed(forMarket: watch.marketKey))
                            VStack(alignment: .leading, spacing: 1) {
                                Text(watch.displayName).font(.headline)
                                Text(watch.marketName).font(.caption).foregroundStyle(AppColors.onSurfaceVariant)
                            }
                            Spacer()
                            Text(PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset))
                                .font(.subheadline.weight(.semibold).monospacedDigit())
                                .foregroundStyle(AppColors.onSurfaceVariant)
                        }
                    }

                    // Schnell-Alarme: ein Antippen legt den Alarm sofort an
                    if !templates.isEmpty {
                        templateChips
                    }

                    // Einfacher Modus: «Wenn BTC [über ▾] [Betrag] geht»
                    if draft.condition.isPriceThreshold {
                        simpleSentence
                    }

                    // Einmal / Jedes Mal
                    Picker(L("alarm_option_repeating"), selection: $draft.repeating) {
                        Text(L("alarm_repeat_once")).tag(false)
                        Text(L("alarm_repeat_each")).tag(true)
                    }
                    .pickerStyle(.segmented)

                    advancedHeader

                    if advanced {
                        advancedContent
                            .transition(.opacity)
                    }

                    // Vorschau: der Alarm als Satz (zeigt auch, wie der Betrag gelesen wurde)
                    sentencePreview

                    Button(action: save) {
                        Text(L(draft.id == 0 ? "alarm_create" : "action_save"))
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, Spacing.lg)
                    }
                    .buttonStyle(AccentButtonStyle())
                    .disabled(!draft.isValid)
                }
                .padding(.horizontal, Spacing.lg)
                .padding(.top, 8)
                .padding(.bottom, 24)
                .animation(.spring(duration: 0.3), value: draft.condition)
                .animation(.easeInOut(duration: 0.2), value: advanced)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(AppColors.background.ignoresSafeArea())
            .navigationTitle(L(draft.id == 0 ? "alarms_add" : "alarms_edit"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_save"), action: save)
                        .fontWeight(.semibold)
                        .disabled(!draft.isValid)
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button {
                        fieldFocused = false
                    } label: {
                        Image(systemName: "keyboard.chevron.compact.down")
                    }
                    .accessibilityLabel(L("a11y_hide_keyboard"))
                }
            }
            .task(id: offerCurrency ? otherCurrency : "") {
                await loadRate()
            }
            .task {
                await loadTemplateData()
            }
            .onAppear {
                draft.priceHint = priceHint
                // Neuer Alarm ohne Vorschlag: gleich tippen können
                if draft.thresholdText.isEmpty && draft.condition != .VOLUME_SPIKE && !draft.newExtremeOnly { fieldFocused = true }
            }
            // Kurs in der Währung des Schwellwerts nachführen (Währungswechsel, Faktor geladen)
            .onChange(of: priceHint) { _, hint in
                draft.priceHint = hint
            }
        }
        .tint(accent.primary)
    }

    // MARK: Einfacher Modus und Schnell-Alarme

    /// Tages-/Stundenkerzen prüfen (zwischengespeichert, gleiche Quellen wie die Alarmprüfung).
    /// DEX-Paare ohne Kerzenquelle fragen gar nicht.
    private func loadTemplateData() async {
        guard initial.id == 0, let watch,
              SheetChart.isSupported(marketKey: watch.marketKey, base: watch.baseAsset, quote: watch.quoteAsset) else { return }
        let ranges = await NearExtremeDataSource.ranges(base: watch.baseAsset, quote: watch.quoteAsset)
        hasDailyRange = ranges?.ranges[AlarmTemplates.newExtremeWindowDays] != nil
        let spike = await VolumeDataSource.hourlySpike(base: watch.baseAsset, quote: watch.quoteAsset)
        hasHourlyVolume = spike != nil
    }

    /// «Schnell-Alarme»: «+1 %», «−5 %», «Neues 30-Tage-Hoch», «Volumen ×3» …
    private var templateChips: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("alarm_templates_title"))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(templates, id: \.self) { template in
                        templateChip(template)
                    }
                }
            }
            .scrollClipDisabled()
        }
    }

    private func templateChip(_ template: AlarmTemplates.Template) -> some View {
        let label = Self.templateLabel(template)
        return Button {
            onTemplate(template)
            dismiss()
        } label: {
            Text(label)
                .font(.subheadline.weight(.medium).monospacedDigit())
                .lineLimit(1)
                .foregroundStyle(accent.onContainer)
                .padding(.horizontal, Spacing.lg)
                .padding(.vertical, Spacing.md)
                .background(accent.container, in: Capsule())
                .overlay(Capsule().strokeBorder(accent.primary.opacity(0.4), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Self.templateA11y(template, label: label))
    }

    /// Sichtbare Beschriftung: «+1 %», «−5 %», «Neues 30-Tage-Hoch», «Volumen ×3».
    private static func templateLabel(_ template: AlarmTemplates.Template) -> String {
        if let percent = template.percent {
            return BidiText.ltr((percent > 0 ? "+" : "\u{2212}") + AlarmTexts.percent(abs(percent)))
        }
        switch template {
        case .NEW_HIGH_30: return AlarmTexts.newExtremeLabel(high: true, days: AlarmTemplates.newExtremeWindowDays)
        case .NEW_LOW_30: return AlarmTexts.newExtremeLabel(high: false, days: AlarmTemplates.newExtremeWindowDays)
        default: return L("alarm_template_volume", AlarmTexts.factor(AlarmTemplates.volumeFactor))
        }
    }

    /// Vorgelesen: «Alarm bei plus 1 Prozent erstellen», «Alarm erstellen: Neues 30-Tage-Hoch».
    private static func templateA11y(_ template: AlarmTemplates.Template, label: String) -> String {
        guard let percent = template.percent else { return L("alarm_template_a11y", label) }
        let amount = LocaleNumbers.decimal(abs(percent), maxDecimals: 2, minDecimals: 0)
        return L(percent > 0 ? "alarm_template_a11y_up" : "alarm_template_a11y_down", amount)
    }

    /// «Wenn BTC [über ▾]» / «[Betrag] geht» — Kursmarke als Satz; der Betrag läuft über den ThresholdParser.
    private var simpleSentence: some View {
        let above = draft.condition == .PRICE_ABOVE
        let direction = L(above ? "alarm_simple_above" : "alarm_simple_below")
        // Satzende («geht»); in vielen Sprachen leer — ein leerer Katalogwert darf nicht als Schlüssel erscheinen
        let endText = L("alarm_simple_end")
        let end = endText == "alarm_simple_end" ? "" : endText
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text(L("alarm_simple_when", BidiText.isolate(watch?.baseAsset ?? "")))
                    .font(.title3.weight(.semibold))
                Menu {
                    Picker(L("alarm_simple_direction_a11y", direction), selection: Binding<AlarmCondition>(
                        get: { draft.condition },
                        set: { value in
                            WatchlistHaptics.selection()
                            draft = draft.withCondition(value)
                        }
                    )) {
                        Text(L("alarm_simple_above")).tag(AlarmCondition.PRICE_ABOVE)
                        Text(L("alarm_simple_below")).tag(AlarmCondition.PRICE_BELOW)
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text(direction)
                            .font(.title3.weight(.semibold))
                        Image(systemName: "chevron.down")
                            .scaledFont(size: 12, weight: .bold, relativeTo: .caption)
                    }
                    .foregroundStyle(accent.primary)
                    .padding(.horizontal, Spacing.md)
                    .padding(.vertical, Spacing.sm)
                    .background(accent.container.opacity(0.6), in: Capsule())
                }
                .accessibilityLabel(L("alarm_simple_direction_a11y", direction))
                Spacer(minLength: 0)
            }
            HStack(spacing: 8) {
                amountInput
                if !end.isEmpty {
                    Text(end)
                        .font(.title3.weight(.semibold))
                }
            }
        }
    }

    /// Aktueller Kurs in der Währung des Schwellwerts — entscheidet bei mehrdeutiger Eingabe
    /// wie «60,000» (Tausender oder Dezimalkomma?), siehe `ThresholdParser`.
    private var priceHint: Double? {
        guard let price = watch?.lastPrice, price.isFinite, price > 0 else { return nil }
        guard let currency = draft.currency else { return price }
        guard let rate = rates[currency.uppercased()], rate.isFinite, rate > 0 else { return nil }
        return price * rate
    }

    /// Währung des Schwellwerts für den Satz: gewählte Alarmwährung oder Quote.
    private var sentenceCurrency: String {
        offerCurrency && draft.currency != nil ? otherCurrency : (watch?.quoteAsset ?? "")
    }

    private var sentencePreview: some View {
        let text = AlarmTexts.sentence(condition: draft.condition, base: watch?.baseAsset ?? "",
                                       threshold: draft.threshold, currency: sentenceCurrency,
                                       windowHours: draft.windowHours)
        return HStack(alignment: .top, spacing: Spacing.sm) {
            Image(systemName: draft.isValid ? "text.bubble.fill" : "text.bubble")
                .scaledFont(size: 15, weight: .semibold, relativeTo: .subheadline)
                .foregroundStyle(draft.isValid ? accent.primary : AppColors.outline)
                .accessibilityHidden(true)
            Text(text)
                .font(.subheadline.weight(draft.isValid ? .medium : .regular))
                .foregroundStyle(draft.isValid ? AppColors.onSurface : AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentTransition(.opacity)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(accent.container.opacity(draft.isValid ? 0.45 : 0.2),
                    in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .combine)
        .animation(.easeInOut(duration: 0.2), value: text)
    }

    private func save() {
        guard draft.isValid else { return }
        onSave(draft)
        dismiss()
    }
}
