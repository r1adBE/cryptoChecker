import SwiftUI

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
    /// Hinweis «Wann Alarme geprüft werden» (`AlarmTimingHint`): nach dem Schliessen des Editors zeigen.
    @State private var timingHintPending = false
    @State private var showTimingHint = false
    @State private var alarmTestTrigger = 0
    /// Paar ohne Alarm: Editor beim ersten Erscheinen gleich geöffnet (Review 13), danach nicht mehr.
    @State private var autoOpenDone = false
    /// «Alarm erstellt · Rückgängig» nach einem Schnell-Alarm.
    @State private var banner: WatchlistBannerMessage?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// «Konnte nicht geprüft werden (…)» je Alarm (nur im Speicher).
    @ObservedObject private var checkStatus = AlarmCheckStatus.shared

    init(watchId: Int64) {
        self.watchId = watchId
    }

    /// DEX-Pool mit zu wenig oder unbekannter Liquidität (`DexLiquidity`)?
    private static func lowLiquidity(_ watch: Watch?) -> Bool {
        guard let watch else { return false }
        return DexLiquidityStore.blocksAlarms(marketKey: watch.marketKey, pairId: watch.pairId)
    }

    var body: some View {
        let watch = data.watch(watchId)
        let alarms = data.alarms(for: watchId)
        // DEX-Pool mit zu wenig Liquidität: kein neuer Alarm, kein Bearbeiten (Hinweis statt Editor) — wie Android
        let lowLiquidity = Self.lowLiquidity(watch)
        Group {
            if alarms.isEmpty && lowLiquidity {
                ScrollView {
                    DexLowLiquidityNote()
                        .padding(16)
                }
            } else if alarms.isEmpty {
                ScrollView {
                    EmptyStateView(
                        systemImage: "bell.and.waves.left.and.right",
                        title: L("alarms_empty"),
                        // L-18: Beispiel im Stil der Alarm-Sätze (wie Android `AlarmsEmptyExample`)
                        example: AlarmsEmptyExample.text,
                        actionTitle: watch == nil ? nil : L("alarms_add"),
                        action: watch == nil ? nil : { draft = AlarmDraft.blankFor() }
                    )
                    .containerRelativeFrame(.vertical, alignment: .center)
                }
            } else {
                List {
                    NotificationsOffBanner()
                        .listRowInsets(EdgeInsets(top: 5, leading: 16, bottom: 5, trailing: 16))
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                    // Hintergrundaktualisierung aus / Stromsparmodus: bei geschlossener App kommen dann keine Alarme
                    BackgroundOffBanner()
                        .listRowInsets(EdgeInsets(top: 5, leading: 16, bottom: 5, trailing: 16))
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                    if lowLiquidity {
                        DexLowLiquidityNote()
                            .listRowInsets(EdgeInsets(top: 5, leading: 16, bottom: 5, trailing: 16))
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                    }
                    ForEach(alarms) { alarm in
                        AlarmCard(
                            alarm: alarm,
                            base: watch?.baseAsset ?? "",
                            quote: watch?.quoteAsset ?? "",
                            unchecked: checkStatus.unchecked(alarm.id),
                            onToggle: { enabled in
                                WatchlistHaptics.selection()
                                data.setAlarmEnabled(alarm.id, enabled)
                            },
                            onEdit: { if !lowLiquidity { draft = AlarmDraft.from(alarm) } },
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
                            .tint(AppColors.destructive)
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
        .onAppear {
            // Paar ohne Alarm: gleich den Editor öffnen statt der leeren Liste — wie Android
            guard !autoOpenDone else { return }
            autoOpenDone = true
            if watch != nil && alarms.isEmpty && !Self.lowLiquidity(watch) { draft = AlarmDraft.blankFor() }
        }
        .navigationTitle(L("alarms_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text(L("alarms_title")).font(.headline)
                    if let watch {
                        Text(verbatim: "\(BidiText.isolate(watch.displayName)) · \(BidiText.isolate(watch.marketName))")
                            .font(.caption)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .lineLimit(1)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            ToolbarItem(placement: .topBarTrailing) {
                if watch != nil && !lowLiquidity {
                    Button {
                        draft = AlarmDraft.blankFor()
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
            } else if timingHintPending {
                timingHintPending = false
                showTimingHint = true
            }
        }) { current in
            AlarmEditorSheet(
                initial: current,
                watch: watch,
                targetCurrency: data.settings.portfolioCurrency,
                existing: current.id == 0 ? nil : data.alarms(for: watchId).first(where: { $0.id == current.id }),
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
                    noteTimingHint(firstAlarm: firstAlarmPending)
                    showSaved(id: saved.id, firstAlarm: firstAlarmPending)
                },
                onTemplate: { template in
                    guard let def = AlarmTemplates.definition(template, currentPrice: watch?.lastPrice) else { return }
                    WatchlistHaptics.impact(.light)
                    let created = withAnimation { data.createAlarm(watchId: watchId, from: def) }
                    if created.firstAlarm { firstAlarmPending = true }
                    noteTimingHint(firstAlarm: created.firstAlarm)
                    let id = created.id
                    banner = WatchlistBannerMessage(
                        text: L("alarm_template_created"), icon: "bell.and.waves.left.and.right.fill",
                        action: WatchlistBannerAction(title: L("action_undo")) {
                            withAnimation { data.deleteAlarm(id) }
                        }
                    )
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
            // Derselbe eine Satz wie unter jedem Alarm (AlarmTimingHint, höchstens 160 Zeichen)
            Text(AlarmTimingHint.text)
        }
        .alarmTestRunner(trigger: alarmTestTrigger)
        // Einmal nach dem Speichern: ehrlich, wann geprüft wird
        .alarmTimingAlert(isPresented: $showTimingHint)
        .overlay(alignment: .bottom) {
            // Kein gelöschtes Paar hier: «Rückgängig» läuft über die Aktion des Banners
            WatchlistBanner(message: $banner) { _ in }
                .animation(reduceMotion ? nil : .spring(duration: 0.35), value: banner)
        }
    }

    /// Hinweis «Wann Alarme geprüft werden» prüfen und merken (`AlarmTimingHint`): einmal, nicht
    /// zusätzlich zur Bestätigung des allerersten Alarms (sie zeigt denselben Satz) — wie Android.
    /// Nach jedem Speichern gilt er als gezeigt.
    private func noteTimingHint(firstAlarm: Bool) {
        let shown = data.settings.alarmTimingHintShown
        if AlarmTimingHint.shouldShow(alreadyShown: shown, firstAlarmDialog: firstAlarm) {
            timingHintPending = true
        }
        if !shown { data.settings.alarmTimingHintShown = true }
    }

    /// «Alarm gespeichert: BTC Kurs über 70’000 USDT · einmalig»; Mitteilungen aus → « · Mitteilungen
    /// aus» mit «Einstellungen». `id` 0 = neu angelegt (dann der Alarm mit der höchsten Id): eine Zeile
    /// «Alarm aktiv · Testen» (Review 13, wie Android) — nicht beim allerersten Alarm (`firstAlarm`), dessen
    /// Bestätigung das Testen schon anbietet.
    private func showSaved(id: Int64, firstAlarm: Bool = false) {
        let list = data.alarms(for: watchId)
        let found = id != 0 ? list.first(where: { $0.id == id }) : list.max(by: { $0.id < $1.id })
        guard let alarm = found else { return }
        let watch = data.watch(watchId)
        let title = AlarmStyle.title(alarm, quote: watch?.quoteAsset ?? "")
        let condition = AlarmSavedText.pairCondition(symbol: watch?.baseAsset ?? "", title: title)
        let repeating = alarm.repeating
        Task { @MainActor in
            let off = await Notifier.isDenied()
            if id == 0 && !off {
                if firstAlarm { return }
                banner = WatchlistBannerMessage(
                    text: L("alarm_saved_active"), icon: "bell.badge.fill",
                    action: WatchlistBannerAction(title: L("alarm_test_action"), perform: { alarmTestTrigger += 1 })
                )
                return
            }
            banner = WatchlistBannerMessage(
                text: AlarmSavedText.text(condition: condition, repeating: repeating, notificationsOff: off),
                icon: off ? "bell.slash.fill" : "bell.badge.fill",
                action: off ? WatchlistBannerAction(title: L("settings_title"), perform: { NotificationsOffBanner.openSettings() }) : nil
            )
        }
    }
}

// MARK: Karte

/// Ein Alarm: Bedingung, Wiederholung, zuletzt ausgelöst, Schalter.
private struct AlarmCard: View {
    let alarm: Alarm
    /// Basis-Symbol des Paars für den Satz («… wenn BTC über …»); leer = kein Satz.
    var base: String = ""
    let quote: String
    /// Zuletzt nicht geprüft (kein Devisenkurs/keine Kerzen); nil = geprüft.
    var unchecked: AlarmCheckStatus.Unchecked? = nil
    let onToggle: (Bool) -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void
    @Environment(\.appAccent) private var accent

    var body: some View {
        HStack(spacing: 0) {
            // Nur der Inhalt öffnet den Editor; der Schalter daneben schaltet nur (eigene Tippfläche)
            content
                .padding(.leading, 12)
                .padding(.trailing, Spacing.sm)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
                .onTapGesture(perform: onEdit)
                // VoiceOver: ein Element mit allen Angaben (inkl. Ton, Ansage, Wiederholung)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(a11yLabel)
                .accessibilityAddTraits(.isButton)
                .accessibilityIdentifier(UiTestTags.alarmCard)
                .accessibilityAction(named: Text(L("action_delete")), onDelete)

            // Beschriftung nur für VoiceOver: welcher Alarm geschaltet wird
            SwitchRow(isOn: Binding(get: { alarm.enabled }, set: onToggle), verticalPadding: 0, labelHidden: true) {
                Text(AlarmStyle.title(alarm, quote: quote))
            }
            .padding(.trailing, Spacing.md)
        }
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.large, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: Radius.large, style: .continuous))
        .contextMenu {
            Button(action: onEdit) { Label(L("action_edit"), systemImage: "pencil") }
            Button(role: .destructive, action: onDelete) { Label(L("action_delete"), systemImage: "trash") }
        }
        .animation(.easeInOut(duration: 0.2), value: alarm.enabled)
    }

    /// Symbol, Titel, Satz, Wiederholung und die kleinen Symbole für Ton/Ansage/Wiederholung.
    private var content: some View {
        HStack(spacing: 12) {
            Image(systemName: AlarmStyle.symbol(alarm.condition))
                .scaledFont(size: 16, weight: .semibold, relativeTo: .callout)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .foregroundStyle(alarm.enabled ? accent.primary : AppColors.outline)
                .frame(width: 40, height: 40)
                .background(
                    (alarm.enabled ? accent.tint(0.14) : AppColors.containerHigh),
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
                // «Konnte nicht geprüft werden (kein Devisenkurs)» — bis die nächste Prüfung gelingt
                if let uncheckedText {
                    Text(uncheckedText)
                        .font(.footnote)
                        .foregroundStyle(AppColors.destructive)
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
        }
    }

    private var a11yLabel: String {
        let sentence = base.isEmpty ? nil : AlarmTexts.sentence(alarm, base: base, quote: quote)
        let text = AlarmA11y.description(alarm, title: AlarmStyle.title(alarm, quote: quote), sentence: sentence)
        return uncheckedText.map { text + ". " + $0 } ?? text
    }

    /// Nur bei eingeschalteten Alarmen: ausgeschaltete werden ohnehin nicht geprüft.
    private var uncheckedText: String? {
        guard alarm.enabled, let unchecked else { return nil }
        switch unchecked.reason {
        case .noRate: return L("alarm_unchecked_no_rate")
        case .noCandles: return L("alarm_unchecked_no_candles")
        }
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

/// Vorgelesene Beschreibung eines Paar-Alarms — wie `alarmA11yDescription` (Android): Titel,
/// Satz, «Einmalig»/«Wiederholend», zuletzt ausgelöst, «Ton an», «Ansage an».
enum AlarmA11y {
    static func description(_ alarm: Alarm, title: String, sentence: String?) -> String {
        var parts = [title]
        if let sentence, !sentence.isEmpty { parts.append(sentence) }
        parts.append(L(alarm.repeating ? "alarm_repeating" : "alarm_once"))
        if alarm.lastTriggeredAt > 0 {
            parts.append(L("alarm_last_triggered", PriceFormat.time(alarm.lastTriggeredAt)))
        }
        if alarm.sound { parts.append(L("alarm_a11y_sound_on")) }
        if alarm.speak { parts.append(L("alarm_a11y_speak_on")) }
        return parts.joined(separator: ". ")
    }
}

// MARK: Bearbeiten

/// Beispielzeile unter dem leeren Alarm-Text (Übersicht und je Paar, L-18) im Stil der Alarm-Sätze:
/// «Zum Beispiel: Sag mir Bescheid, wenn BTC über 90’000 steigt.» — Zahl im Format der Sprache.
enum AlarmsEmptyExample {
    static var text: String { L("alarms_empty_example", LocaleNumbers.integer(90_000, grouping: true)) }
}

/// Hinweis für DEX-Pools mit zu wenig Liquidität: kein Alarm, bestehende Alarme ruhen (`DexLiquidity`). Wie Android.
private struct DexLowLiquidityNote: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L("dex_low_liquidity_no_alarm"))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppColors.error)
            Text(L("dex_low_liquidity_alarm_hint"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}
