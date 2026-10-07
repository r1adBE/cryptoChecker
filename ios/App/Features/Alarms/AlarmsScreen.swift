import SwiftUI

/// Eingabezustand des Alarm-Blatts — wie `AlarmDraft`.
struct AlarmDraft: Identifiable, Equatable {
    var id: Int64 = 0
    var condition: AlarmCondition = .PRICE_ABOVE
    var thresholdText: String = ""
    var repeating = false
    var sound = true
    var vibrate = true
    var speak = false
    /// Zeitfenster für «bewegt sich um x % in y Stunden».
    var windowHours = 4
    /// Währung des Schwellwerts bei Kursalarmen; nil = Quote-Währung des Paars.
    var currency: String? = nil
    /// Aktueller Kurs in der Währung des Schwellwerts (nur Kursalarme): entscheidet bei
    /// mehrdeutiger Eingabe wie «60,000», siehe `ThresholdParser`. nil = unbekannt.
    var priceHint: Double? = nil

    static let windowChoices = [1, 4, 12, 24]
    /// Standard-Zeitfenster für «bewegt sich um x % in y Stunden».
    static let defaultWindowHours = 4
    /// Wählbare Faktoren für den Volumen-Spike.
    static let volumeFactors: [Double] = [2, 3, 5, 10]
    static let defaultVolumeFactor: Double = 3

    /// Faktor als Text ohne «.0»: 3 → "3".
    static func formatFactor(_ value: Double) -> String {
        plain.string(from: NSNumber(value: value)) ?? String(value)
    }

    /// Bedingung wechseln. Beim Volumen-Spike ist der Wert ein Faktor (×2…×10),
    /// deshalb beim Wechsel von/zu dieser Bedingung den Wert neu setzen.
    func withCondition(_ newCondition: AlarmCondition) -> AlarmDraft {
        guard newCondition != condition else { return self }
        var copy = self
        if newCondition == .VOLUME_SPIKE {
            copy.thresholdText = Self.formatFactor(Self.defaultVolumeFactor)
        } else if newCondition.isNearExtreme {
            // Nahe am Hoch/Tief: Abstand in % (Standard 2 %), Zeitraum in Tagen (Standard 30)
            if !condition.isPercent && !condition.isNearExtreme {
                copy.thresholdText = Self.formatFactor(NearExtreme.defaultDistancePercent)
            }
            if !condition.isNearExtreme { copy.windowHours = NearExtreme.defaultWindowDays }
        } else if condition.isNearExtreme {
            if !newCondition.isPercent { copy.thresholdText = "" }
            copy.windowHours = Self.defaultWindowHours
        } else if condition == .VOLUME_SPIKE {
            copy.thresholdText = ""
        }
        copy.condition = newCondition
        return copy
    }

    /// Gelesener Schwellwert (Tausendertrennung, Dezimalzeichen der Region; siehe `ThresholdParser`);
    /// nur Werte > 0.
    var threshold: Double? {
        ThresholdParser.parse(thresholdText, decimalSeparator: ThresholdParser.localeDecimalSeparator,
                              priceHint: condition.isPriceThreshold ? priceHint : nil)
    }

    var isValid: Bool { threshold != nil }

    /// Währung des Schwellwerts wechseln (nil = Quote). Ist der Faktor Quote → `other`
    /// bekannt, wird ein bereits getippter Wert mit umgerechnet — wie `withCurrency` in Android.
    func withCurrency(_ newCurrency: String?, other: String, rate: Double?) -> AlarmDraft {
        guard newCurrency != currency else { return self }
        var copy = self
        copy.currency = newCurrency
        if let value = threshold, let rate, rate > 0, rate.isFinite {
            var converted: Double?
            if currency == nil && newCurrency == other { converted = value * rate }
            if currency == other && newCurrency == nil { converted = value / rate }
            if let converted, let text = Self.significant.string(from: NSNumber(value: converted)) {
                copy.thresholdText = text
            }
        }
        return copy
    }

    /// Umgerechneter Wert fürs Eingabefeld: sechs gültige Stellen, ohne Tausendertrennung,
    /// Dezimalzeichen der Region (liest sich so eindeutig zurück, siehe `ThresholdParser`).
    private static let significant: NumberFormatter = {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.numberStyle = .decimal
        f.decimalSeparator = String(ThresholdParser.localeDecimalSeparator)
        f.usesGroupingSeparator = false
        f.usesSignificantDigits = true
        f.maximumSignificantDigits = 6
        return f
    }()

    private static let plain: NumberFormatter = {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.numberStyle = .decimal
        f.decimalSeparator = String(ThresholdParser.localeDecimalSeparator)
        f.usesGroupingSeparator = false
        f.maximumFractionDigits = 10
        return f
    }()

    static func from(_ alarm: Alarm) -> AlarmDraft {
        AlarmDraft(
            id: alarm.id,
            condition: alarm.condition,
            thresholdText: plain.string(from: NSNumber(value: alarm.threshold)) ?? String(alarm.threshold),
            repeating: alarm.repeating,
            sound: alarm.sound,
            vibrate: alarm.vibrate,
            speak: alarm.speak,
            windowHours: alarm.condition.isNearExtreme ? NearExtreme.windowDays(alarm.windowHours) : alarm.windowHours,
            currency: alarm.currency
        )
    }
}

/// Symbol je Alarmbedingung.
enum AlarmStyle {
    static func symbol(_ condition: AlarmCondition) -> String {
        switch condition {
        case .PRICE_ABOVE: "arrow.up.to.line"
        case .PRICE_BELOW: "arrow.down.to.line"
        case .CHANGE_PERCENT_UP: "chart.line.uptrend.xyaxis"
        case .CHANGE_PERCENT_DOWN: "chart.line.downtrend.xyaxis"
        case .MOVE_PERCENT_WINDOW: "arrow.up.arrow.down"
        case .VOLUME_SPIKE: "chart.bar.fill"
        case .NEAR_HIGH: "arrowtriangle.up.circle"
        case .NEAR_LOW: "arrowtriangle.down.circle"
        }
    }

    /// Beschreibung mit Gegenwert nur bei Kursalarmen — wie in Android. Hat der Alarm
    /// eine eigene Währung, steht sie schon in der Beschreibung.
    static func title(_ alarm: Alarm, quote: String) -> String {
        let showQuote = alarm.condition.isPriceThreshold && alarm.convertCurrency == nil && !quote.isEmpty
        return AlarmTexts.describe(alarm) + (showQuote ? " \(quote)" : "")
    }
}

/// Alarme eines Paars — wie `AlarmsScreen.kt`. Antippen bearbeitet,
/// Wischen löscht (mit Rückfrage), der Schalter macht scharf.
struct AlarmsScreen: View {
    let watchId: Int64

    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
    @State private var draft: AlarmDraft?
    @State private var askDelete: Alarm?
    /// Erster Alarm überhaupt gespeichert: nach dem Schliessen des Editors bestätigen.
    @State private var firstAlarmPending = false
    @State private var showFirstAlarm = false
    @State private var alarmTestTrigger = 0

    init(watchId: Int64) {
        self.watchId = watchId
    }

    var body: some View {
        let watch = data.watch(watchId)
        let alarms = data.alarms(for: watchId)
        Group {
            if alarms.isEmpty {
                ScrollView {
                    EmptyStateView(
                        systemImage: "bell.badge",
                        title: L("alarms_empty"),
                        actionTitle: watch == nil ? nil : L("alarms_add"),
                        action: watch == nil ? nil : { draft = AlarmDraft() }
                    )
                    .containerRelativeFrame(.vertical, alignment: .center)
                }
            } else {
                List {
                    ForEach(alarms) { alarm in
                        AlarmCard(
                            alarm: alarm,
                            base: watch?.baseAsset ?? "",
                            quote: watch?.quoteAsset ?? "",
                            onToggle: { enabled in
                                WatchlistHaptics.selection()
                                data.setAlarmEnabled(alarm.id, enabled)
                            },
                            onEdit: { draft = AlarmDraft.from(alarm) },
                            onDelete: { askDelete = alarm }
                        )
                        .listRowInsets(EdgeInsets(top: 5, leading: 16, bottom: 5, trailing: 16))
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button {
                                askDelete = alarm
                            } label: {
                                Label(L("action_delete"), systemImage: "trash")
                            }
                            .tint(.red)
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                // iPad/Querformat: Zeilen höchstens 640 pt breit, mittig
                .readableListMargins()
                .animation(.spring(duration: 0.35), value: alarms)
            }
        }
        .background(AppColors.background.ignoresSafeArea())
        .navigationTitle(L("alarms_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text(L("alarms_title")).font(.headline)
                    if let watch {
                        Text("\(watch.displayName) · \(watch.marketName)")
                            .font(.caption)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .lineLimit(1)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            ToolbarItem(placement: .topBarTrailing) {
                if watch != nil {
                    Button {
                        draft = AlarmDraft()
                    } label: {
                        Image(systemName: "plus.circle.fill")
                            .symbolRenderingMode(.hierarchical)
                            .scaledFont(size: 20, relativeTo: .title3)
                    }
                    .tint(accent.primary)
                    .accessibilityLabel(L("alarms_add"))
                }
            }
        }
        .sheet(item: $draft, onDismiss: {
            if firstAlarmPending {
                firstAlarmPending = false
                showFirstAlarm = true
            }
        }) { current in
            AlarmEditorSheet(
                initial: current,
                watch: watch,
                targetCurrency: data.settings.portfolioCurrency,
                onSave: { saved in
                    guard let threshold = saved.threshold else { return }
                    WatchlistHaptics.impact(.light)
                    withAnimation {
                        firstAlarmPending = data.saveAlarm(
                            watchId: watchId, id: saved.id, condition: saved.condition, threshold: threshold,
                            repeating: saved.repeating, sound: saved.sound, vibrate: saved.vibrate,
                            speak: saved.speak, windowHours: saved.windowHours,
                            currency: saved.condition.isPriceThreshold ? saved.currency : nil
                        )
                    }
                }
            )
            .environmentObject(data)
            .environment(\.appAccent, accent)
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(28)
        }
        .alert(
            L("alarm_delete_title"),
            isPresented: Binding(get: { askDelete != nil }, set: { if !$0 { askDelete = nil } }),
            presenting: askDelete
        ) { alarm in
            Button(L("action_delete"), role: .destructive) {
                withAnimation { data.deleteAlarm(alarm.id) }
                askDelete = nil
            }
            Button(L("action_cancel"), role: .cancel) { askDelete = nil }
        } message: { _ in
            Text(L("alarm_delete_confirm"))
        }
        .alert(L("alarm_first_title"), isPresented: $showFirstAlarm) {
            Button(L("ios_action_ok"), role: .cancel) {}
            Button(L("alarm_test")) { alarmTestTrigger += 1 }
        } message: {
            // Ehrlich (Runde 13b): Bei geschlossener App entscheidet iOS, wann geprüft wird
            Text(L("alarm_first_text_ios", watch?.baseAsset ?? ""))
        }
        .alarmTestRunner(trigger: alarmTestTrigger)
    }
}

// MARK: Karte

/// Ein Alarm: Bedingung, Wiederholung, zuletzt ausgelöst, Schalter.
private struct AlarmCard: View {
    let alarm: Alarm
    /// Basis-Symbol des Paars für den Satz («… wenn BTC über …»); leer = kein Satz.
    var base: String = ""
    let quote: String
    let onToggle: (Bool) -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void
    @Environment(\.appAccent) private var accent

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: AlarmStyle.symbol(alarm.condition))
                .scaledFont(size: 16, weight: .semibold, relativeTo: .callout)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .foregroundStyle(alarm.enabled ? accent.primary : AppColors.outline)
                .frame(width: 40, height: 40)
                .background(
                    (alarm.enabled ? accent.primary.opacity(0.14) : AppColors.containerHigh),
                    in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                )

            VStack(alignment: .leading, spacing: 3) {
                Text(AlarmStyle.title(alarm, quote: quote))
                    .font(.headline.monospacedDigit())
                    .foregroundStyle(alarm.enabled ? AppColors.onSurface : AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                // Alarm als Satz
                if !base.isEmpty {
                    Text(AlarmTexts.sentence(alarm, base: base, quote: quote))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Text(subtitle)
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                HStack(spacing: 8) {
                    if alarm.sound { optionIcon("speaker.wave.2.fill") }
                    if alarm.speak { optionIcon("waveform") }
                    if alarm.repeating { optionIcon("repeat") }
                }
                .padding(.top, 1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Toggle("", isOn: Binding(get: { alarm.enabled }, set: onToggle))
                .labelsHidden()
                .tint(accent.primary)
        }
        .padding(.leading, 12)
        .padding(.trailing, 14)
        .padding(.vertical, 12)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(alarm.enabled ? accent.primary.opacity(0.35) : AppColors.outlineVariant.opacity(0.6), lineWidth: 1)
        )
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        // Ganze Karte antippen = bearbeiten
        .onTapGesture(perform: onEdit)
        .contextMenu {
            Button(action: onEdit) { Label(L("action_edit"), systemImage: "pencil") }
            Button(role: .destructive, action: onDelete) { Label(L("action_delete"), systemImage: "trash") }
        }
        .animation(.easeInOut(duration: 0.2), value: alarm.enabled)
    }

    private var subtitle: String {
        var s = L(alarm.repeating ? "alarm_repeating" : "alarm_once")
        if alarm.lastTriggeredAt > 0 {
            s += " · " + L("alarm_last_triggered", PriceFormat.time(alarm.lastTriggeredAt))
        }
        return s
    }

    private func optionIcon(_ name: String) -> some View {
        Image(systemName: name)
            .scaledFont(size: 10, weight: .semibold, relativeTo: .caption2)
            .foregroundStyle(AppColors.outline)
    }
}

// MARK: Bearbeiten

/// Alarm anlegen/bearbeiten als Blatt von unten — wie `AlarmDialog`.
@MainActor
struct AlarmEditorSheet: View {
    let initial: AlarmDraft
    let watch: Watch?
    /// Umrechnungswährung aus den Einstellungen, z. B. «CHF».
    let targetCurrency: String
    let onSave: (AlarmDraft) -> Void

    @Environment(\.appAccent) private var accent
    @Environment(\.dismiss) private var dismiss
    @State private var draft: AlarmDraft
    /// Faktor Quote → Währung, soweit bekannt (zum Umrechnen des getippten Schwellwerts).
    @State private var rates: [String: Double] = [:]
    @FocusState private var fieldFocused: Bool

    init(initial: AlarmDraft, watch: Watch?, targetCurrency: String = AppSettings.defaultCurrency(), onSave: @escaping (AlarmDraft) -> Void) {
        self.initial = initial
        self.watch = watch
        self.targetCurrency = targetCurrency
        self.onSave = onSave
        _draft = State(initialValue: initial)
    }

    /// Zweite Währung neben der Quote: die des Alarms (falls gesetzt), sonst die Umrechnungswährung.
    private var otherCurrency: String {
        (draft.currency ?? targetCurrency).uppercased()
    }

    /// Währungswahl nur bei Kursalarmen und wenn die Quote nicht schon die Umrechnungswährung ist.
    private var offerCurrency: Bool {
        guard draft.condition.isPriceThreshold, let quote = watch?.quoteAsset, !quote.isEmpty else { return false }
        return !CurrencyConversion.sameCurrency(quote, otherCurrency)
    }

    /// Einheit hinter dem Eingabefeld.
    private var thresholdUnit: String {
        if draft.condition.isPercent || draft.condition.isNearExtreme { return "%" }
        if offerCurrency && draft.currency != nil { return otherCurrency }
        return watch?.quoteAsset ?? ""
    }

    private let columns = [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if let watch {
                        HStack(spacing: 12) {
                            CoinBadge(symbol: watch.baseAsset, size: 36)
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

                    // Bedingung als Kacheln, zwei pro Zeile
                    LazyVGrid(columns: columns, spacing: 8) {
                        ForEach(AlarmCondition.allCases, id: \.self) { condition in
                            conditionTile(condition)
                        }
                    }

                    if draft.condition == .VOLUME_SPIKE {
                        volumeFactorPicker
                            .transition(.opacity.combined(with: .move(edge: .top)))
                    } else {
                        if offerCurrency {
                            currencyPicker
                                .transition(.opacity.combined(with: .move(edge: .top)))
                        }
                        thresholdField
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
                        .transition(.opacity.combined(with: .move(edge: .top)))
                    }

                    // «Nahe am Hoch/Tief»: Zeitraum 30 Tage / 90 Tage / 1 Jahr
                    if draft.condition.isNearExtreme {
                        nearWindowPicker
                            .transition(.opacity.combined(with: .move(edge: .top)))
                    }

                    // Vorschau: der Alarm als Satz
                    sentencePreview

                    VStack(spacing: 0) {
                        SwitchRow(title: L("alarm_option_repeating"), isOn: $draft.repeating)
                        RowDivider()
                        SwitchRow(title: L("alarm_option_sound"), isOn: $draft.sound)
                        RowDivider()
                        // Kein Vibrations-Schalter: iOS steuert Vibration nur über die Systemeinstellungen.
                        // Der Wert bleibt gespeichert, damit Sicherungen mit Android austauschbar sind.
                        SwitchRow(title: L("alarm_option_speak"), isOn: $draft.speak)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 4)
                    .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))

                    Button(action: save) {
                        Text(L("action_save"))
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                    }
                    .buttonStyle(AccentButtonStyle())
                    .disabled(!draft.isValid)
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 24)
                .animation(.spring(duration: 0.3), value: draft.condition)
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
            .onAppear {
                draft.priceHint = priceHint
                // Neuer Alarm: gleich tippen können
                if draft.thresholdText.isEmpty && draft.condition != .VOLUME_SPIKE { fieldFocused = true }
            }
            // Kurs in der Währung des Schwellwerts nachführen (Währungswechsel, Faktor geladen)
            .onChange(of: priceHint) { _, hint in
                draft.priceHint = hint
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
        return HStack(alignment: .top, spacing: 10) {
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

    private var thresholdField: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L(draft.condition.isNearExtreme ? "alarm_near_field_distance"
                   : (draft.condition.isPercent ? "alarm_field_percent" : "alarm_field_price")))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
            HStack(spacing: 8) {
                TextField(placeholder, text: $draft.thresholdText)
                    .keyboardType(.decimalPad)
                    .focused($fieldFocused)
                    .scaledFont(size: 24, weight: .semibold, design: .rounded, relativeTo: .title, monospacedDigit: true)
                    .onChange(of: draft.thresholdText) { _, text in
                        // Ziffern, Trenner (Komma, Punkt) und Tausendertrenner (’ ' Leerzeichen) behalten;
                        // Buchstaben auch, damit z. B. eingefügtes «60k» ungültig bleibt statt 60 zu werden
                        // (gelesen von `ThresholdParser`, der Satz darunter zeigt den gelesenen Wert).
                        let filtered = text.filter {
                            $0.isNumber || $0.isLetter || $0 == "," || $0 == "." || "’'‘` \u{00A0}\u{202F}\u{2009}".contains($0)
                        }
                        if filtered != text { draft.thresholdText = filtered }
                    }
                Text(thresholdUnit)
                    .font(.headline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(fieldFocused ? accent.primary : AppColors.outlineVariant.opacity(0.5),
                                  lineWidth: fieldFocused ? 1.5 : 1)
            )
            .animation(.easeInOut(duration: 0.2), value: fieldFocused)
        }
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
    private func loadRate() async {
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
                .padding(.vertical, 10)
                .background(selected ? accent.container : AppColors.container, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? accent.primary.opacity(0.6) : .clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Kursalarm: aktueller Kurs als Vorschlag im leeren Feld.
    private var placeholder: String {
        if draft.condition.isPercent || draft.condition.isNearExtreme { return "0" }
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
                .padding(.vertical, 10)
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
                .padding(.vertical, 10)
                .background(selected ? accent.container : AppColors.container, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? accent.primary.opacity(0.6) : .clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
