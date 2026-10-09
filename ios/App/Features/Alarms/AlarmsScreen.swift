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
    @State private var alarmTestTrigger = 0
    /// «Alarm erstellt · Rückgängig» nach einem Schnell-Alarm.
    @State private var banner: WatchlistBannerMessage?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

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
                        action: watch == nil ? nil : { draft = AlarmDraft.newFor(lastPrice: watch?.lastPrice) }
                    )
                    .containerRelativeFrame(.vertical, alignment: .center)
                }
            } else {
                List {
                    NotificationsOffBanner()
                        .listRowInsets(EdgeInsets(top: 5, leading: 16, bottom: 5, trailing: 16))
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
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
                if watch != nil {
                    Button {
                        draft = AlarmDraft.newFor(lastPrice: watch?.lastPrice)
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
                },
                onTemplate: { template in
                    guard let def = AlarmTemplates.definition(template, currentPrice: watch?.lastPrice) else { return }
                    WatchlistHaptics.impact(.light)
                    let created = withAnimation { data.createAlarm(watchId: watchId, from: def) }
                    if created.firstAlarm { firstAlarmPending = true }
                    let id = created.id
                    banner = WatchlistBannerMessage(
                        text: L("alarm_template_created"), icon: "bell.badge.fill",
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
            // Ehrlich (Runde 13b): Bei geschlossener App entscheidet iOS, wann geprüft wird
            Text(L("alarm_first_text_ios", watch?.baseAsset ?? ""))
        }
        .alarmTestRunner(trigger: alarmTestTrigger)
        .overlay(alignment: .bottom) {
            // Kein gelöschtes Paar hier: «Rückgängig» läuft über die Aktion des Banners
            WatchlistBanner(message: $banner) { _ in }
                .animation(reduceMotion ? nil : .spring(duration: 0.35), value: banner)
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

            SwitchRow(isOn: Binding(get: { alarm.enabled }, set: onToggle), verticalPadding: 0, labelHidden: true) {
                Text(verbatim: "")
            }
        }
        .padding(.leading, 12)
        .padding(.trailing, Spacing.md)
        .padding(.vertical, 12)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(alarm.enabled ? accent.tint(0.35) : AppColors.outlineVariant.opacity(0.6), lineWidth: 1)
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
