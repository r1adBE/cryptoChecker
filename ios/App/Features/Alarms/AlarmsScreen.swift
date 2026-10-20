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
    /// «Damit Alarme ankommen» als Blatt, geöffnet aus der Bestätigung des ersten Alarms.
    @State private var showDelivery = false
    /// Hinweis «Wann Alarme geprüft werden» (`AlarmTimingHint`): nach dem Schliessen des Editors zeigen.
    @State private var timingHintPending = false
    @State private var showTimingHint = false
    @State private var alarmTestTrigger = 0
    /// Paar ohne Alarm: Editor beim ersten Erscheinen gleich geöffnet, danach nicht mehr.
    @State private var autoOpenDone = false
    /// «Alarm erstellt · Rückgängig» nach einem Schnell-Alarm.
    @State private var banner: WatchlistBannerMessage?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// «Konnte nicht geprüft werden (…)» je Alarm (nur im Speicher).
    @ObservedObject private var checkStatus = AlarmCheckStatus.shared
    /// Mitteilungen zustellbar (`Notifier.isAuthorized`)? Für die Statuszeile der Karten (`AlarmCardStatus`);
    /// im Zweifel ja, damit kein falscher Hinweis erscheint. Bei jeder Rückkehr in die App neu geprüft.
    @State private var notificationsAllowed = true
    /// Uhr der Statuszeilen, jede Minute nachgeführt — ein Kurs wird auch ohne neue Daten veraltet.
    @State private var now: Int64 = TimeUtils.nowMillis
    @Environment(\.scenePhase) private var scenePhase
    /// «Anpassen» aus einer Alarm-Mitteilung (`AppRouter.pendingEditAlarmId`).
    @ObservedObject private var router = AppRouter.shared

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
                        .listRowInsets(EdgeInsets(top: Spacing.xs, leading: 16, bottom: Spacing.xs, trailing: 16))
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                    // Hintergrundaktualisierung aus / Stromsparmodus: bei geschlossener App kommen dann keine Alarme
                    BackgroundOffBanner()
                        .listRowInsets(EdgeInsets(top: Spacing.xs, leading: 16, bottom: Spacing.xs, trailing: 16))
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                    if lowLiquidity {
                        DexLowLiquidityNote()
                            .listRowInsets(EdgeInsets(top: Spacing.xs, leading: 16, bottom: Spacing.xs, trailing: 16))
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                    }
                    ForEach(alarms) { alarm in
                        AlarmCard(
                            alarm: alarm,
                            base: watch?.baseAsset ?? "",
                            quote: watch?.quoteAsset ?? "",
                            unchecked: checkStatus.unchecked(alarm.id),
                            status: AlarmCardStatus.of(
                                alarmEnabled: alarm.enabled,
                                notificationsAllowed: notificationsAllowed,
                                dexBlocksAlarms: lowLiquidity,
                                exchangeTime: watch?.exchangeTime,
                                lastUpdate: watch?.lastUpdate ?? 0,
                                now: now,
                                staleAfterMillis: data.staleAfterMillis,
                                unchecked: checkStatus.unchecked(alarm.id) != nil,
                                markFallback: watch.map { MarkFallbackStore.contains($0.id) } ?? false
                            ),
                            onToggle: { enabled in
                                WatchlistHaptics.selection()
                                data.setAlarmEnabled(alarm.id, enabled)
                            },
                            onEdit: { if !lowLiquidity { draft = AlarmDraft.from(alarm) } },
                            onDelete: { askDelete = alarm }
                        )
                        .listRowInsets(EdgeInsets(top: Spacing.xs, leading: 16, bottom: Spacing.xs, trailing: 16))
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
        .task { notificationsAllowed = await Notifier.isAuthorized() }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                now = TimeUtils.nowMillis
            }
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            now = TimeUtils.nowMillis
            Task { notificationsAllowed = await Notifier.isAuthorized() }
        }
        .onAppear {
            // Paar ohne Alarm: gleich den Editor öffnen statt der leeren Liste — wie Android
            guard !autoOpenDone else { return }
            autoOpenDone = true
            if watch != nil && alarms.isEmpty && !Self.lowLiquidity(watch) { draft = AlarmDraft.blankFor() }
        }
        // «Anpassen» aus der Alarm-Mitteilung: Editor dieses Alarms öffnen — nur der Bildschirm seines Paars;
        // bei zu wenig DEX-Liquidität bleibt die Liste mit dem Hinweis (wie Android)
        .onChange(of: router.pendingEditAlarmId, initial: true) { _, id in
            guard let id, let alarm = alarms.first(where: { $0.id == id }) else { return }
            router.pendingEditAlarmId = nil
            if !lowLiquidity { draft = AlarmDraft.from(alarm) }
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
                    if data.alarmSaveBlocked { return showSaveLocked() }
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
                    if data.alarmSaveBlocked { return showSaveLocked() }
                    guard let def = AlarmTemplates.definition(template, currentPrice: watch?.lastPrice) else { return }
                    WatchlistHaptics.impact(.light)
                    let created = withAnimation { data.createAlarm(watchId: watchId, from: def) }
                    // Nichts angelegt (Paar inzwischen gelöscht): keine Bestätigung — wie Android
                    guard created.id != 0 else { return }
                    if created.firstAlarm { firstAlarmPending = true }
                    noteTimingHint(firstAlarm: created.firstAlarm)
                    let id = created.id
                    banner = WatchlistBannerMessage(
                        // Den angelegten Alarm nennen («Alarm gespeichert: BTC Steigt um 5 % · einmalig») — wie Android
                        text: templateCreatedText(id), icon: "bell.and.waves.left.and.right.fill",
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
        // Hauptknopf «Prüfen, ob Alarme ankommen»: gleich die Prüfliste statt erst danach suchen (wie Android)
        .alert(L("alarm_first_title"), isPresented: $showFirstAlarm) {
            Button(L("alarm_first_check")) { showDelivery = true }
                .keyboardShortcut(.defaultAction)
            Button(L("alarm_test")) { alarmTestTrigger += 1 }
            Button(L("ios_action_ok"), role: .cancel) {}
        } message: {
            // Derselbe eine Satz wie unter jedem Alarm (AlarmTimingHint, höchstens 160 Zeichen)
            Text(AlarmTimingHint.text)
        }
        .sheet(isPresented: $showDelivery) {
            NavigationStack {
                AlarmDeliveryScreen()
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button(L("ios_action_ok")) { showDelivery = false }
                        }
                    }
            }
            .environmentObject(data)
            .environment(\.appAccent, accent)
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

    /// Merkliste gerade nicht lesbar (`AppData.alarmSaveBlocked`): nichts angelegt, sagen warum (der Editor schliesst sich)
    private func showSaveLocked() {
        banner = WatchlistBannerMessage(text: L("alarm_save_storage_locked"), icon: "lock.fill", long: true)
    }

    /// Bestätigung eines Schnell-Alarms: Bedingung wie nach dem Speichern (`AlarmSavedText`), ohne Mitteilungs-Hinweis.
    private func templateCreatedText(_ id: Int64) -> String {
        guard let alarm = data.alarms(for: watchId).first(where: { $0.id == id }) else { return L("alarm_saved_active") }
        let watch = data.watch(watchId)
        let title = AlarmStyle.title(alarm, quote: watch?.quoteAsset ?? "")
        let condition = AlarmSavedText.pairCondition(symbol: watch?.baseAsset ?? "", title: title)
        return AlarmSavedText.text(condition: condition, repeating: alarm.repeating, notificationsOff: false)
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
    /// «Alarm aktiv · Testen» (wie Android) — nicht beim allerersten Alarm (`firstAlarm`), dessen
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
