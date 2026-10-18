import SwiftUI

/// Alarm anlegen/bearbeiten als Blatt von unten — wie `AlarmEditorSheet` in Android. Oben die Schnell-Alarme
/// (nur beim Anlegen), dann nur die einfachen Bedingungen (Kurs über/unter, Steigt/Fällt um) — die
/// Kursmarke als Satz «Wenn BTC über [Betrag] geht». Alle übrigen Bedingungen unter «Mehr Bedingungen»,
/// Währung, Ton und Vorlesen unter «Erweitert» (beide zugeklappt, ausser beim Bearbeiten eines Alarms,
/// der sie braucht), dazwischen «Einmal / Jedes Mal». Volle Höhe: Aufklappen scrollt im Inhalt.
@MainActor
struct AlarmEditorSheet: View {
    let initial: AlarmDraft
    let watch: Watch?
    /// Umrechnungswährung aus den Einstellungen, z. B. «CHF».
    let targetCurrency: String
    /// Gespeicherter Alarm beim Bearbeiten (nil = neu): entscheidet, ob Speichern ihn neu scharf stellt.
    let existing: Alarm?
    let onSave: (AlarmDraft) -> Void
    /// Schnell-Alarm angetippt: der Aufrufer legt ihn an, das Blatt schliesst sich.
    var onTemplate: (AlarmTemplates.Template) -> Void = { _ in }

    @Environment(\.appAccent) var accent
    @Environment(\.dismiss) var dismiss
    @State var draft: AlarmDraft
    /// Faktor Quote → Währung, soweit bekannt (zum Umrechnen des getippten Schwellwerts).
    @State var rates: [String: Double] = [:]
    @FocusState var fieldFocused: Bool
    /// «Mehr Bedingungen» aufgeklappt.
    @State var moreConditions: Bool
    /// «Erweitert» aufgeklappt.
    @State var advanced: Bool
    /// Hat das Paar Tageskerzen (30-Tage-Hoch/-Tief) bzw. Stundenvolumen? Für die Schnell-Alarme.
    @State var hasDailyRange = false
    @State var hasHourlyVolume = false
    /// Rückfrage «Alarm löst sofort aus» vor dem Speichern.
    @State private var confirmFiresNow = false
    /// Bestehender Alarm: letzte Prüfung aus dem Alarm-Protokoll («Zuletzt geprüft»).
    @State private var lastCheck: AlarmLogEntry?

    init(initial: AlarmDraft, watch: Watch?, targetCurrency: String = AppSettings.defaultCurrency(),
         existing: Alarm? = nil,
         onSave: @escaping (AlarmDraft) -> Void,
         onTemplate: @escaping (AlarmTemplates.Template) -> Void = { _ in }) {
        self.initial = initial
        self.watch = watch
        self.targetCurrency = targetCurrency
        self.existing = existing
        self.onSave = onSave
        self.onTemplate = onTemplate
        _draft = State(initialValue: initial)
        // «Mehr Bedingungen» offen beim Bearbeiten eines Alarms mit einer davon (z. B. Volumen-Spike),
        // «Erweitert» bei einer Kursmarke in eigener Währung
        _moreConditions = State(initialValue: AlarmTemplates.opensMoreConditions(condition: initial.condition))
        _advanced = State(initialValue: AlarmTemplates.opensAdvanced(condition: initial.condition, currency: initial.currency))
    }

    /// Sichtbare Schnell-Alarme (nur beim Anlegen); ohne Kurs bzw. Kerzen (z. B. DEX) ausgeblendet.
    var templates: [AlarmTemplates.Template] {
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
                            CoinBadge(symbol: watch.baseAsset, size: 36, logo: CoinLogos.allowed(forMarket: watch.marketKey),
                                      pair: watch.logoPairKey)
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

                    // «Zuletzt geprüft: 14:02 · Kraken 99’800 CHF · Marke nicht erreicht» — nur beim Bearbeiten
                    if draft.id != 0, let lastCheck {
                        Text(AlarmLogTexts.lastCheckLine(lastCheck))
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    // Schnell-Alarme: ein Antippen legt den Alarm sofort an
                    if !templates.isEmpty {
                        templateChips
                    }

                    // Einfache Bedingungen: Kurs über/unter, Steigt/Fällt um
                    simpleConditionTiles

                    if draft.condition.isPriceThreshold {
                        // Kursmarke als Satz: «Wenn BTC über [Betrag] geht»
                        simpleSentence
                    } else if draft.condition.isPercent && !AlarmTemplates.opensMoreConditions(condition: draft.condition) {
                        thresholdField
                    }

                    // Alle übrigen Bedingungen (Zeitfenster, Volumen, Hoch/Tief, Funding, Open Interest)
                    sectionHeader(L("alarm_more_conditions"), expanded: $moreConditions)

                    if moreConditions {
                        moreConditionsContent
                            .transition(.opacity)
                    }

                    // «Melden: Einmal / Jedes Mal» — sichtbare Beschriftung (die des Pickers zeigt .segmented nicht), wie Android
                    VStack(alignment: .leading, spacing: Spacing.xs) {
                        Text(L("alarm_option_repeating"))
                            .font(.subheadline)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .accessibilityHidden(true)
                        Picker(L("alarm_option_repeating"), selection: $draft.repeating) {
                            Text(L("alarm_repeat_once")).tag(false)
                            Text(L("alarm_repeat_each")).tag(true)
                        }
                        .pickerStyle(.segmented)
                    }

                    sectionHeader(L("alarm_advanced"), expanded: $advanced)

                    if advanced {
                        advancedContent
                            .transition(.opacity)
                    }

                    // Vorschau: der Alarm als Satz (zeigt auch, wie der Betrag gelesen wurde),
                    // direkt darunter ehrlich, wann bei geschlossener App gemeldet wird
                    VStack(alignment: .leading, spacing: Spacing.sm) {
                        sentencePreview
                        Self.closedAppHint
                    }

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
                .animation(.easeInOut(duration: 0.2), value: moreConditions)
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
                    // UI-Tests: der Knopf oben bleibt über der Zifferntastatur sichtbar (der grosse unten nicht)
                    Button(L("action_save"), action: save)
                        .accessibilityIdentifier(UiTestTags.alarmSave)
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
            .task(id: initial.id) {
                guard initial.id != 0 else { return }
                let id = initial.id
                // Verschlüsselte Datei: abseits des Hauptfadens lesen
                lastCheck = await Task.detached(priority: .utility) { AlarmLogStore.load().lastChecks[id] }.value
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
            // Kursmarke schon erreicht: erst nachfragen (sonst meldet der Alarm sofort)
            .alert(L("alarm_fires_now_title"), isPresented: $confirmFiresNow) {
                Button(L("alarm_fires_now_save")) { commit() }
                // Zurück in den Editor, Betrag anpassen
                Button(L("alarm_fires_now_change"), role: .cancel) {}
            } message: {
                Text(firesNowMessage)
            }
        }
        .tint(accent.primary)
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
                    in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        .accessibilityElement(children: .combine)
        .animation(.easeInOut(duration: 0.2), value: text)
    }

    /// Kleine graue Zeile unter dem Satz: Bei geschlossener App entscheidet iOS, wann geprüft
    /// wird — oft erst nach 15 Min. oder später. Auch im Portfolio-Alarm (`PortfolioAlarmSheet`).
    /// Das (i) daneben zeigt dauerhaft den ganzen Hinweis «Wann Alarme geprüft werden».
    static var closedAppHint: some View { ClosedAppHintRow() }

    private func save() {
        guard draft.isValid else { return }
        if firesOnSave {
            confirmFiresNow = true
            return
        }
        commit()
    }

    private func commit() {
        onSave(draft)
        dismiss()
    }

    /// Würde der Alarm gleich nach dem Speichern melden? Kursmarke schon erreicht
    /// (`AlarmEdit.firesNow`, Kurs in der Währung des Schwellwerts = `priceHint`) — und nur, wenn
    /// er dabei (neu) scharf wird (`AlarmEdit.rearms`). Wie `AlarmsViewModel.firesOnSave`.
    private var firesOnSave: Bool {
        guard let threshold = draft.threshold,
              AlarmEdit.firesNow(condition: draft.condition, threshold: threshold, price: draft.priceHint)
        else { return false }
        guard let existing, existing.id == draft.id else { return true }
        var edited = existing
        edited.condition = draft.condition
        edited.threshold = threshold
        edited.repeating = draft.repeating
        edited.windowHours = draft.windowHours
        // Eigene Währung nur bei Kursalarmen und nur, wenn sie von der Quote abweicht (wie `saveAlarm`)
        let code = draft.condition.isPriceThreshold ? Alarm.validCurrency(draft.currency) : nil
        edited.currency = CurrencyConversion.sameCurrency(code, watch?.quoteAsset) ? nil : code
        return AlarmEdit.rearms(existing: existing, edited: edited)
    }

    /// «Der Kurs liegt schon über 66’600 USDT. Trotzdem speichern?»
    private var firesNowMessage: String {
        let amount = PriceFormat.thresholdWithCurrency(draft.threshold, sentenceCurrency)
        return L(draft.condition == .PRICE_BELOW ? "alarm_fires_now_below" : "alarm_fires_now_above", amount)
    }
}
