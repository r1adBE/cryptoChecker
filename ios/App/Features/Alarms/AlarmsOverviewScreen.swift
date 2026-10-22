import SwiftUI

/// Übersicht aller Alarme — wie `AlarmsOverviewScreen.kt`: Welche sind scharf,
/// auf welchem Paar? Ein Tipp auf ein Paar öffnet dessen Alarme zum Bearbeiten.
struct AlarmsOverviewScreen: View {
    @EnvironmentObject private var data: AppData
    /// Leere Merkliste: «Paar hinzufügen» öffnet die Seite «Paar hinzufügen» (wie «+» der Merkliste).
    @EnvironmentObject private var router: AppRouter
    @Environment(\.appAccent) private var accent
    @ObservedObject private var lock = AppLock.shared
    /// Portfolio-Alarm, der gelöscht werden soll (Rückfrage).
    @State private var deletePortfolio: PortfolioAlarm?
    /// Leere Übersicht: «Neuer Alarm» → Paar wählen → dessen Alarm-Seite (keine Sackgasse).
    @State private var pickPair = false
    /// Seit Build 103: im Auswahlblatt gewähltes Paar — dessen Alarm-Seite öffnet erst, wenn das Blatt zu ist.
    @State private var pickedPair: Int64?
    @State private var openWatchId: Int64?
    /// Warum Portfolio-Alarme gerade nicht prüfen (letzter Portfolio-Stand, `PortfolioAlarmLogic.pause`); nil = keiner.
    @State private var portfolioPause: PortfolioAlarmPause?
    /// Merkliste gesperrt: Hinweis «watchlist_storage_locked» nach einem abgelehnten Schalter (`.watchlistLockedHint`).
    @State private var banner: WatchlistBannerMessage?
    /// Zählt abgelehnte Schalter (Merkliste gesperrt): wertet den body neu aus, damit die Schalter den echten Zustand
    /// zeigen — der gedrosselte Hinweis ändert AppData nicht bei jeder Ablehnung, ein umgelegter Schalter bliebe sonst
    /// stehen. Seit Build 104 ohne `.id` (kein Identitätswechsel, der VoiceOver-Fokus bleibt).
    @State private var refusedToggles = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Alarme «Portfolio-Wert» — nur mit eingeschaltetem Portfolio (sonst prüft sie niemand).
    private var portfolioAlarms: [PortfolioAlarm] {
        data.settings.portfolioEnabled ? data.portfolioAlarms : []
    }

    /// Beträge der Portfolio-Alarme verbergen: «Beträge verbergen» oder Portfolio gesperrt.
    private var hidePortfolio: Bool { data.settings.hidePortfolioAmounts || lock.locked }

    init() {}

    /// Nach Paar gruppiert: Gruppen mit scharfen Alarmen zuerst, dann nach Name;
    /// innerhalb scharfe zuerst, dann nach Id.
    private var groups: [[AlarmWithWatch]] {
        Dictionary(grouping: data.alarmsWithWatch, by: \.watch.id)
            .values
            .map { group in
                group.sorted {
                    if $0.alarm.enabled != $1.alarm.enabled { return $0.alarm.enabled }
                    return $0.alarm.id < $1.alarm.id
                }
            }
            .sorted { a, b in
                let aActive = a.contains { $0.alarm.enabled }
                let bActive = b.contains { $0.alarm.enabled }
                if aActive != bActive { return aActive }
                let an = a.first?.watch.displayName.lowercased() ?? ""
                let bn = b.first?.watch.displayName.lowercased() ?? ""
                if an != bn { return an < bn }
                return (a.first?.watch.id ?? 0) < (b.first?.watch.id ?? 0)
            }
    }

    var body: some View {
        // Abgelehnte Schalter neu auswerten, ohne Identitätswechsel (sonst springt der VoiceOver-Fokus), seit Build 104
        let _ = refusedToggles
        let groups = self.groups
        let portfolio = portfolioAlarms
        let active = groups.reduce(0) { sum, g in sum + g.filter(\.alarm.enabled).count }
            + portfolio.filter(\.enabled).count
        Group {
            if groups.isEmpty && portfolio.isEmpty {
                ScrollView {
                    let watches = data.watches
                    EmptyStateView(
                        systemImage: "bell.and.waves.left.and.right",
                        title: L("alarms_overview_empty"),
                        // L-18: Beispiel im Stil der Alarm-Sätze (wie Android `AlarmsEmptyExample`)
                        example: AlarmsEmptyExample.text,
                        // Leere Merkliste: ohne Paar kein Alarm — direkt «Paar hinzufügen» statt einer Sackgasse (wie Android)
                        actionTitle: watches.isEmpty ? L("shortcut_add") : L("alarms_add"),
                        action: {
                            if watches.isEmpty {
                                router.openExplorer()
                            } else if watches.count == 1 {
                                openWatchId = watches[0].id
                            } else {
                                pickPair = true
                            }
                        }
                    )
                    .containerRelativeFrame(.vertical, alignment: .center)
                }
                // Seit Build 103: eigenes Blatt mit Suche, Favoriten oben und Lazy-Liste (`AlarmPairPicker`)
                .sheet(isPresented: $pickPair, onDismiss: {
                    if let id = pickedPair { openWatchId = id }
                    pickedPair = nil
                }) {
                    AlarmPairPicker(watches: data.watches) { id in
                        pickedPair = id
                        pickPair = false
                    }
                }
                .navigationDestination(isPresented: Binding(
                    get: { openWatchId != nil },
                    set: { if !$0 { openWatchId = nil } }
                )) {
                    if let id = openWatchId { AlarmsScreen(watchId: id) }
                }
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        NotificationsOffBanner()
                        // Hintergrundaktualisierung aus / Stromsparmodus: bei geschlossener App kommen dann keine Alarme
                        BackgroundOffBanner()
                        // Wann die Alarme zuletzt geprüft wurden
                        AlarmLastCheckedLine()
                            .padding(.horizontal, 4)
                        // Alarme «Portfolio-Wert» zuerst, als eigene Karte
                        if !portfolio.isEmpty {
                            portfolioCard(portfolio)
                                // Beim Öffnen und bei jeder Änderung der eingeschalteten Arten neu lesen
                                .task(id: portfolio.filter(\.enabled).map(\.kind)) {
                                    let kinds = portfolio.filter(\.enabled).map(\.kind)
                                    guard !kinds.isEmpty else {
                                        portfolioPause = nil
                                        return
                                    }
                                    let stored = await Task.detached { PortfolioWidgetStore.load() }.value
                                    portfolioPause = stored?.alarmPause(kinds)
                                }
                        }
                        ForEach(groups, id: \.first?.watch.id) { group in
                            if let watch = group.first?.watch {
                                groupCard(watch: watch, items: group)
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .padding(.bottom, 24)
                    // iPad/Querformat: Inhalt höchstens 640 pt breit, mittig
                    .readableContentWidth()
                }
            }
        }
        .background(AppColors.background.ignoresSafeArea())
        // Schalter abgelehnt (Merkliste gesperrt): kurzer Hinweis mit Warn-Haptik — wie die Alarme des Paars
        .watchlistLockedHint { banner = WatchlistBannerMessage(text: $0, icon: "lock.fill", long: true) }
        .overlay(alignment: .bottom) {
            WatchlistBanner(message: $banner) { _ in }
                .animation(reduceMotion ? nil : .spring(duration: 0.35), value: banner)
        }
        .confirmationDialog(L("alarm_delete_title"), isPresented: Binding(
            get: { deletePortfolio != nil },
            set: { if !$0 { deletePortfolio = nil } }
        ), titleVisibility: .visible, presenting: deletePortfolio) { alarm in
            Button(L("action_delete"), role: .destructive) {
                Haptics.play(.destructive)
                withAnimation(.snappy) { data.deletePortfolioAlarm(alarm.id) }
                deletePortfolio = nil
            }
            Button(L("action_cancel"), role: .cancel) { deletePortfolio = nil }
        } message: { alarm in
            Text(PortfolioAlarmTexts.sentence(alarm, basis: data.settings.changeBasis.storage, hidden: hidePortfolio))
        }
        .navigationTitle(L("alarms_overview_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text(L("alarms_overview_title")).font(.headline)
                    Text(L("alarms_overview_active", count: active))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(active > 0 ? accent.primary : AppColors.onSurfaceVariant)
                        .contentTransition(.numericText(value: Double(active)))
                }
                .accessibilityElement(children: .combine)
                .animation(.snappy, value: active)
            }
            // Damit Alarme ankommen: echter Zustand von Mitteilungen, Hintergrundaktualisierung und letzter Prüfung
            ToolbarItem(placement: .primaryAction) {
                NavigationLink {
                    AlarmDeliveryScreen()
                } label: {
                    Image(systemName: "bell.badge")
                }
                .accessibilityLabel(L("delivery_title"))
            }
            // Alarm-Protokoll: wann ausgelöst, wann zurückgehalten
            ToolbarItem(placement: .primaryAction) {
                NavigationLink {
                    AlarmLogScreen()
                } label: {
                    Image(systemName: "list.bullet.rectangle")
                }
                .accessibilityLabel(L("alarm_log_title"))
            }
        }
    }

    private func groupCard(watch: Watch, items: [AlarmWithWatch]) -> some View {
        VStack(spacing: 0) {
            NavigationLink {
                AlarmsScreen(watchId: watch.id)
            } label: {
                HStack(spacing: 12) {
                    CoinBadge(symbol: watch.baseAsset, size: 38, logo: CoinLogos.allowed(forMarket: watch.marketKey),
                              pair: watch.logoPairKey)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(watch.displayName)
                            .font(.headline)
                            .foregroundStyle(AppColors.onSurface)
                            .lineLimit(1)
                        Text(watch.marketName)
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                    Spacer(minLength: 8)
                    // Gegenwährung klein und grau wie der Börsenname der Merkliste
                    PriceWithQuote(price: PriceFormat.price(watch.lastPrice), quote: watch.quoteAsset,
                                   font: .subheadline.weight(.semibold).monospacedDigit(),
                                   color: AppColors.onSurfaceVariant)
                        .minimumScaleFactor(0.7)
                    // Spiegelt sich in Rechts-nach-links-Sprachen automatisch
                    Image(systemName: "chevron.forward")
                        .scaledFont(size: 13, weight: .semibold, relativeTo: .footnote)
                        .foregroundStyle(AppColors.outline)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            RowDivider().padding(.horizontal, 16)

            VStack(spacing: 0) {
                ForEach(items) { item in
                    let alarm = item.alarm
                    // Ganze Zeile schaltet; seit Build 105 Rückfrage F-08 vorher und «Schwelle ungültig» nicht einschalten (FUN3-02)
                    SwitchRow(isOn: Binding(
                        get: { alarm.enabled },
                        set: { enabled in
                            data.withWatchlistConfirm {
                                // Gesperrt: nur die Warn-Haptik des Hinweises (wie Android), seit Build 104
                                if !data.watchlistWritesBlocked { Haptics.play(.selection) }
                                if !withAnimation(.snappy, { data.setAlarmEnabled(alarm.id, enabled) }) { refusedToggles &+= 1 }
                            } cancelled: { refusedToggles &+= 1 }
                        }
                    ), enabled: BackupManager.toggleAllowed(alarm.condition, alarm.threshold, enable: !alarm.enabled),
                    verticalPadding: 8) {
                        HStack(spacing: Spacing.sm) {
                            Image(systemName: AlarmStyle.symbol(alarm.condition))
                                .scaledFont(size: 13, weight: .semibold, relativeTo: .footnote)
                                .foregroundStyle(alarm.enabled ? accent.primary : AppColors.outline)
                                .frame(width: 20)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(AlarmStyle.title(alarm, quote: watch.quoteAsset))
                                    .font(.subheadline.monospacedDigit())
                                    .foregroundStyle(alarm.enabled ? AppColors.onSurface : AppColors.onSurfaceVariant)
                                // Alarm als Satz (zweite Zeile)
                                Text(AlarmTexts.sentence(alarm, base: watch.baseAsset, quote: watch.quoteAsset))
                                    .font(.caption)
                                    .foregroundStyle(AppColors.onSurfaceVariant)
                                    .lineLimit(2)
                                    .fixedSize(horizontal: false, vertical: true)
                                // Seit Build 104: «fällt um ≥ 100 %» (aus Build 102) löst nie aus — kennzeichnen
                                if BackupManager.exceedsFall(alarm.condition, alarm.threshold) {
                                    Text(L("alarm_threshold_invalid_fix"))
                                        .font(.caption)
                                        .foregroundStyle(AppColors.error)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        }
                        // VoiceOver: vollständig, inkl. Wiederholung, Ton und Ansage (Symbol bleibt stumm)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(Self.a11yLabel(alarm, watch: watch))
                    }
                    .padding(.horizontal, 16)
                }
            }
            .padding(.vertical, 4)
        }
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.large, style: .continuous))
    }

    /// Vorgelesen je Paar-Alarm: Titel, Satz, Wiederholung, Ton, Ansage (`AlarmA11y`).
    private static func a11yLabel(_ alarm: Alarm, watch: Watch) -> String {
        let sentence = AlarmTexts.sentence(alarm, base: watch.baseAsset, quote: watch.quoteAsset)
        let text = AlarmA11y.description(alarm, title: AlarmStyle.title(alarm, quote: watch.quoteAsset), sentence: sentence)
        // Seit Build 104: ungültige Schwelle mit vorlesen (wie Android)
        return BackupManager.exceedsFall(alarm.condition, alarm.threshold) ? text + ". " + L("alarm_threshold_invalid_fix") : text
    }

    /// Karte «Portfolio»: je Alarm Satz und Schalter (ganze Zeile); Löschen über das Kontextmenü.
    private func portfolioCard(_ alarms: [PortfolioAlarm]) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                AppSymbol.view(AppSymbol.portfolio, size: 20)
                    .scaledFont(size: 17, weight: .semibold, relativeTo: .headline)
                    .foregroundStyle(accent.primary)
                    .frame(width: 38, height: 38)
                    .background(accent.tint(0.14), in: Circle())
                    .accessibilityHidden(true)
                Text(L("portfolio_title"))
                    .font(.headline)
                    .foregroundStyle(AppColors.onSurface)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)

            // Warum die Alarme gerade nicht prüfen (kein aktueller Kurs, Adressen nicht geladen) — sonst schwiegen sie still;
            // gesperrtes Portfolio bzw. «Beträge verbergen»: ohne Coin-Namen
            if let portfolioPause {
                Text(PortfolioPauseText.text(portfolioPause, hideCoins: hidePortfolio))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 12)
            }

            RowDivider().padding(.horizontal, 16)

            VStack(spacing: 0) {
                ForEach(alarms) { alarm in
                    // Seit Build 105 (FUN3-01): «fällt um ≥ 100 %» löst nie aus — kennzeichnen, nicht einschalten
                    let invalid = PortfolioAlarmLogic.exceedsFall(alarm.kind, alarm.threshold)
                    SwitchRow(isOn: Binding(
                        get: { alarm.enabled },
                        set: { enabled in
                            Haptics.play(.selection)
                            withAnimation(.snappy) { data.setPortfolioAlarmEnabled(alarm.id, enabled) }
                        }
                    ), verticalPadding: 8) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(PortfolioAlarmTexts.sentence(alarm, basis: data.settings.changeBasis.storage, hidden: hidePortfolio))
                                .font(.subheadline.monospacedDigit())
                                .foregroundStyle(alarm.enabled ? AppColors.onSurface : AppColors.onSurfaceVariant)
                            Text(L(invalid ? "alarm_threshold_invalid_fix" : (alarm.repeating ? "alarm_repeating" : "alarm_once")))
                                .font(.caption)
                                .foregroundStyle(invalid ? AppColors.error : AppColors.onSurfaceVariant)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    // Ausschalten geht immer, einschalten erst nach dem Anpassen
                    .disabled(invalid && !alarm.enabled)
                    .padding(.horizontal, 16)
                    .contextMenu {
                        Button(role: .destructive) {
                            deletePortfolio = alarm
                        } label: {
                            Label(L("action_delete"), systemImage: "trash")
                        }
                    }
                    .accessibilityAction(named: Text(L("action_delete"))) { deletePortfolio = alarm }
                }
            }
            .padding(.vertical, 4)
        }
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.large, style: .continuous))
    }
}
