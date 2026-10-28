import SwiftUI

/// Alarme eines Paars — wie `AlarmsScreen.kt`. Antippen bearbeitet,
/// Wischen löscht (mit Rückfrage), der Schalter macht scharf.
struct AlarmsScreen: View {
    let watchId: Int64

    @EnvironmentObject var data: AppData
    @Environment(\.appAccent) private var accent
    @State var draft: AlarmDraft?
    @State private var askDelete: Alarm?
    /// Erster Alarm überhaupt gespeichert: nach dem Schliessen des Editors bestätigen.
    @State var firstAlarmPending = false
    @State private var showFirstAlarm = false
    /// «Damit Alarme ankommen» als Blatt, geöffnet aus der Bestätigung des ersten Alarms.
    @State private var showDelivery = false
    /// Hinweis «Wann Alarme geprüft werden» (`AlarmTimingHint`): nach dem Schliessen des Editors zeigen.
    @State var timingHintPending = false
    @State private var showTimingHint = false
    @State var alarmTestTrigger = 0
    /// Paar ohne Alarm: Editor beim ersten Erscheinen gleich geöffnet, danach nicht mehr.
    @State private var autoOpenDone = false
    /// «Alarm erstellt · Rückgängig» nach einem Schnell-Alarm.
    @State var banner: WatchlistBannerMessage?
    /// «Rückgängig», das auf das Schliessen der Dialoge wartet (sonst liefe es unerreichbar dahinter ab) — wie Android.
    @State var bannerAfterDialogs: WatchlistBannerMessage?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// «Konnte nicht geprüft werden (…)» je Alarm (nur im Speicher).
    @ObservedObject private var checkStatus = AlarmCheckStatus.shared
    /// Mitteilungen zustellbar (`Notifier.isAuthorized`)? Für die Statuszeile der Karten (`AlarmCardStatus`);
    /// im Zweifel ja, damit kein falscher Hinweis erscheint. Bei jeder Rückkehr in die App neu geprüft.
    @State private var notificationsAllowed = true
    /// Uhr der Statuszeilen, jede Minute nachgeführt — ein Kurs wird auch ohne neue Daten veraltet.
    @State private var now: Int64 = EpochTime.nowMillis
    /// Zählt abgelehnte Schalter (Merkliste gesperrt): wertet den body neu aus, damit sie den echten Zustand zeigen — der
    /// gedrosselte Hinweis ändert AppData nicht bei jeder Ablehnung, ein umgelegter Schalter bliebe sonst stehen.
    /// Ohne `.id` (kein Identitätswechsel, der VoiceOver-Fokus bleibt).
    @State var refusedToggles = 0
    @Environment(\.scenePhase) private var scenePhase
    /// «Anpassen» aus einer Alarm-Mitteilung (`AppRouter.pendingEditAlarmId`).
    @ObservedObject private var router = AppRouter.shared

    init(watchId: Int64) {
        self.watchId = watchId
    }

    /// DEX-Pool mit zu wenig oder unbekannter Liquidität (`DexLiquidity`)?
    private static func lowLiquidity(_ watch: Watch?) -> Bool {
        guard let watch else { return false }
        return DexLiquidityStore.blocksAlarms(exchangeKey: watch.exchangeKey, pairId: watch.pairId)
    }

    /// Quelle ohne Alarme (EZB-Referenzkurse, `Exchange.allowsAlarms`)?
    private static func referenceRate(_ watch: Watch?) -> Bool {
        guard let watch else { return false }
        return ExchangesConfig.exchange(watch.exchangeKey)?.allowsAlarms == false
    }

    var body: some View {
        // Abgelehnte Schalter neu auswerten, ohne Identitätswechsel (sonst springt der VoiceOver-Fokus)
        let _ = refusedToggles
        let watch = data.watch(watchId)
        let alarms = data.alarms(for: watchId)
        // DEX-Pool mit zu wenig Liquidität: kein neuer Alarm, kein Bearbeiten (Hinweis statt Editor) — wie Android
        let lowLiquidity = Self.lowLiquidity(watch)
        // EZB-Referenzkurs: weder neuer Alarm noch Bearbeiten, Hinweis statt Editor — wie Android
        let referenceRate = Self.referenceRate(watch)
        let blocked = lowLiquidity || referenceRate
        // Höchstzahl Alarme am Paar erreicht (`WatchlistLimits`): kein «Neuer Alarm», Hinweis über der Liste — wie Android
        let alarmsFull = !WatchlistLimits.canAddAlarm(alarms.count)
        Group {
            if alarms.isEmpty && blocked {
                ScrollView {
                    Group {
                        if referenceRate { ReferenceRateAlarmNote() } else { DexLowLiquidityNote() }
                    }
                    .padding(16)
                }
            } else if alarms.isEmpty {
                ScrollView {
                    EmptyStateView(
                        systemImage: "bell.and.waves.left.and.right",
                        title: L("alarms_empty"),
                        // Beispiel im Stil der Alarm-Sätze (wie Android `AlarmsEmptyExample`)
                        example: AlarmsEmptyExample.text,
                        actionTitle: watch == nil ? nil : L("alarms_add"),
                        action: watch == nil ? nil : { draft = AlarmDraft.blankFor() }
                    )
                    .emptyStateFrame()
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
                    if referenceRate {
                        ReferenceRateAlarmNote()
                            .listRowInsets(EdgeInsets(top: Spacing.xs, leading: 16, bottom: Spacing.xs, trailing: 16))
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                    } else if lowLiquidity {
                        DexLowLiquidityNote()
                            .listRowInsets(EdgeInsets(top: Spacing.xs, leading: 16, bottom: Spacing.xs, trailing: 16))
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                    }
                    if alarmsFull {
                        AlarmLimitNote()
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
                            onToggle: { enabled in toggleAlarm(alarm.id, enabled: enabled) },
                            onEdit: { if !blocked { draft = AlarmDraft.from(alarm) } },
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
                now = EpochTime.nowMillis
            }
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            now = EpochTime.nowMillis
            Task { notificationsAllowed = await Notifier.isAuthorized() }
        }
        .onAppear {
            // Paar ohne Alarm: gleich den Editor öffnen statt der leeren Liste — nicht bei gesperrter Merkliste (wie Android)
            guard !autoOpenDone else { return }
            autoOpenDone = true
            if watch != nil && alarms.isEmpty && !Self.lowLiquidity(watch) && !Self.referenceRate(watch)
                && !data.alarmSaveBlocked { draft = AlarmDraft.blankFor() }
        }
        // «Anpassen» aus der Alarm-Mitteilung: Editor dieses Alarms öffnen — nur der Bildschirm seines Paars;
        // bei zu wenig DEX-Liquidität bleibt die Liste mit dem Hinweis (wie Android)
        .onChange(of: router.pendingEditAlarmId, initial: true) { _, id in
            guard let id else { return }
            guard let alarm = alarms.first(where: { $0.id == id }) else {
                // Alarm gelöscht (alte Mitteilung): Wunsch verwerfen, sonst öffnete ihn ein späterer Bildschirm
                if !data.alarms.contains(where: { $0.id == id }) { router.pendingEditAlarmId = nil }
                return
            }
            router.pendingEditAlarmId = nil
            if !blocked { draft = AlarmDraft.from(alarm) }
        }
        .navigationTitle(L("alarms_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text(L("alarms_title")).font(.headline)
                    if let watch {
                        Text(verbatim: "\(BidiText.isolate(watch.displayName)) · \(BidiText.isolate(watch.exchangeName))")
                            .font(.caption)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .lineLimit(1)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            ToolbarItem(placement: .topBarTrailing) {
                if watch != nil && !blocked && !alarmsFull {
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
        .sheet(item: $draft, onDismiss: showPendingDialogs) { current in
            AlarmEditorSheet(
                initial: current,
                watch: watch,
                targetCurrency: data.settings.portfolioCurrency,
                existing: current.id == 0 ? nil : data.alarms(for: watchId).first(where: { $0.id == current.id }),
                onSave: saveFromEditor,
                onTemplate: templateFromEditor
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
                askDelete = nil
                deleteConfirmed(alarm.id)
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
        .onChange(of: dialogsOpen) { _, open in
            guard !open, let message = bannerAfterDialogs else { return }
            bannerAfterDialogs = nil
            banner = message
        }
        .alarmTestRunner(trigger: alarmTestTrigger)
        // Alarm nicht geändert bzw. gelöscht (Merkliste gesperrt): kurzer Hinweis mit Warn-Haptik
        .watchlistLockedHint { banner = WatchlistBannerMessage(text: $0, kind: .error, long: true) }
        // Einmal nach dem Speichern: ehrlich, wann geprüft wird
        .alarmTimingAlert(isPresented: $showTimingHint)
        .overlay(alignment: .bottom) {
            // Kein gelöschtes Paar hier: «Rückgängig» läuft über die Aktion des Banners
            WatchlistBanner(message: $banner) { _ in }
                .animation(reduceMotion ? nil : .spring(duration: 0.35), value: banner)
        }
    }

    /// Vorgemerkte Dialoge zeigen: beim Schliessen des Editors auch nach einer späteren Antwort der Rückfrage.
    func showPendingDialogs() {
        if firstAlarmPending {
            firstAlarmPending = false
            showFirstAlarm = true
        } else if timingHintPending {
            timingHintPending = false
            showTimingHint = true
        }
    }

    /// Editor oder ein Dialog danach offen (bzw. vorgemerkt): Die Bestätigung mit «Rückgängig» wartet (`bannerAfterDialogs`).
    private var dialogsOpen: Bool {
        draft != nil || firstAlarmPending || timingHintPending || showFirstAlarm || showTimingHint || showDelivery
    }
}
