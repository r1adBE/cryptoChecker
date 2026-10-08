import SwiftUI

/// «Mehr» im Aktionsblatt: auf- oder zugeklappt, für die Dauer der Sitzung gemerkt (Standard zu).
enum WatchSheetMoreState {
    nonisolated(unsafe) static var expanded = false
}

/// Aktionen zu einem Paar als Blatt von unten — wie `WatchActionsSheet`: Kopf mit Stift, Kurs
/// und Chart (`WatchSheetChartView`), gross Alarm, «Warum?» und Favorit, darunter Futures-Daten.
/// Alles Weitere — Gruppe, Notiz, «Zum Portfolio hinzufügen» (nur mit Portfolio), Aktualisieren,
/// Widget, Vorlesen, Mitteilung, Sperrbildschirm (nur iOS), Sortieren und zuletzt Löschen — im
/// aufklappbaren Abschnitt «Mehr» (zu; für die Sitzung gemerkt). Das Blatt steht immer in voller
/// Höhe, Aufklappen ändert nur den Inhalt der Liste.
@MainActor
struct WatchActionsSheet: View {
    let watchId: Int64
    /// Paar hat gerade ⚡-Signale (kleines ⚡ an «Warum bewegt sich das?»).
    var hasActivity = false
    /// Alarme öffnen (nach dem Schliessen des Blatts).
    let onOpenAlarms: (Int64) -> Void
    /// Löschen erfragen (nach dem Schliessen des Blatts).
    let onDelete: (Watch) -> Void
    /// «Warum bewegt sich das?» öffnen (nach dem Schliessen des Blatts).
    let onWhy: (Int64) -> Void
    /// Erfassen-Blatt des Portfolios öffnen (nach dem Schliessen des Blatts).
    var onAddToPortfolio: (Int64) -> Void = { _ in }

    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var futures: FuturesInfo? = nil
    /// Bitcoin-Paar: Faktor Quote → Umrechnungswährung für «1 CHF = … Sats»; nil = keine Zeile.
    @State private var satsRate: Double? = nil
    @State private var editGroup = false
    @State private var editNote = false
    /// «Paar bearbeiten» (Stift neben dem Paar): Börse, Paar, Kontrakt desselben Eintrags.
    @State private var editPair = false
    /// «Als Widget hinzufügen»: iOS lässt Apps keine Widgets anlegen → Anleitung (Runde 13b).
    @State private var showWidgetHelp = false
    /// Live-Aktivität dieses Paars läuft (Sperrbildschirm).
    @State private var liveActivityOn = false
    /// iOS-Einstellung «Live-Aktivitäten» ist aus — Hinweis zeigen.
    @State private var liveActivityDisabled = false
    /// «Mehr» aufgeklappt (Startwert: zuletzt in dieser Sitzung).
    @State private var moreExpanded = WatchSheetMoreState.expanded

    var body: some View {
        if let watch = data.watch(watchId) {
            content(watch)
        } else {
            // Paar wurde inzwischen gelöscht
            Color.clear.onAppear { dismiss() }
        }
    }

    private func content(_ watch: Watch) -> some View {
        let alarmCount = data.activeAlarmCounts[watch.id] ?? 0
        let refreshingThis = data.refreshingWatchIds.contains(watch.id)
        let scroll = ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header(watch)
                priceBlock(watch)

                // Kurs-Chart (24h · 7T · 30T, Kerzen/Linie); ohne Kerzenquelle (DEX) ganz ausgeblendet
                WatchSheetChartView(watch: watch)

                // Die drei häufigsten Aktionen zuerst und gleich gross: Alarm, Warum?, Favorit
                primaryActions(watch, alarmCount: alarmCount)

                if let futures {
                    WatchlistFuturesSection(info: futures)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }

                // Alles Weitere unter «Mehr» (zu, für die Sitzung gemerkt)
                moreToggle
                if moreExpanded {
                    moreContent(watch, refreshing: refreshingThis)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            .padding(.horizontal, Spacing.lg)
            .padding(.top, 24)
            .padding(.bottom, 16)
            // Kennzahlen kommen später, «Mehr» klappt auf: weich einfügen (bei reduzierter Bewegung sofort)
            .animation(reduceMotion ? nil : .spring(duration: 0.35), value: futures)
            .animation(reduceMotion ? nil : .snappy, value: moreExpanded)
        }
        .scrollIndicators(.hidden)
        .background(AppColors.background.ignoresSafeArea())
        // Futures-Kennzahlen nur für Perpetuals laden (neu, wenn das Paar bearbeitet wurde)
        .task(id: WatchEdit.Key(watch)) {
            guard watch.contractType == .perpetual else { futures = nil; return }
            futures = try? await FuturesDataSource.fetch(watch: watch)
        }
        // «1 CHF = … Sats»: Faktor zuerst aus dem Zwischenspeicher, sonst frisch (neu bei anderer
        // Quote oder Umrechnungswährung)
        .task(id: "\(watch.quoteAsset)|\(data.settings.portfolioCurrency)|\(Sats.isBitcoin(watch.baseAsset))") {
            guard Sats.isBitcoin(watch.baseAsset), !watch.isNotTraded else { satsRate = nil; return }
            let target = data.settings.portfolioCurrency
            // Andere Währung gewählt: nie kurz den alten Faktor mit der neuen Währung zeigen
            satsRate = nil
            if let cached = CurrencyConverter.cachedRate(quote: watch.quoteAsset, target: target) {
                satsRate = cached
            } else {
                satsRate = await CurrencyConverter.rate(quote: watch.quoteAsset, target: target)
            }
        }
        return subSheets(scroll, watch: watch, alarmCount: alarmCount)
    }

    /// Kopfzeile «Mehr» mit Pfeil; VoiceOver: Knopf «Mehr» mit Zustand auf-/zugeklappt.
    private var moreToggle: some View {
        Button {
            WatchlistHaptics.selection()
            moreExpanded.toggle()
            WatchSheetMoreState.expanded = moreExpanded
        } label: {
            HStack(spacing: 8) {
                Text(L("sheet_more"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                Spacer(minLength: 8)
                Image(systemName: "chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .rotationEffect(.degrees(moreExpanded ? 180 : 0))
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("sheet_more"))
        .accessibilityValue(L(moreExpanded ? "a11y_expanded" : "a11y_collapsed"))
    }

    /// Inhalt von «Mehr»: Gruppe, Notiz, Portfolio, Aktualisieren, Widget; Vorlesen und Mitteilung;
    /// Sperrbildschirm; Sortieren; zuletzt Löschen in der Fehlerfarbe.
    private func moreContent(_ watch: Watch, refreshing refreshingThis: Bool) -> some View {
        VStack(alignment: .leading, spacing: 16) {
                groupCard(watch, refreshing: refreshingThis)

                // Vorlesen und Mitteilung
                VStack(spacing: 0) {
                    SwitchRow(title: L("watchlist_tts"), isOn: Binding(
                        get: { data.watch(watchId)?.ttsEnabled ?? false },
                        set: { enabled in
                            if let w = data.watch(watchId) { data.setTtsEnabled(w, enabled) }
                        }
                    ))
                    RowDivider()
                    SwitchRow(title: L("watchlist_notification"), isOn: Binding(
                        get: { data.watch(watchId)?.notificationEnabled ?? false },
                        set: { enabled in
                            if enabled { Task { _ = await Notifier.requestPermission() } }
                            if let w = data.watch(watchId) { data.setNotificationEnabled(w, enabled) }
                        }
                    ))
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 4)
                .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))

                liveActivityCard(watch)

                moveRow(watch)

                Button(role: .destructive) {
                    onDelete(watch)
                    dismiss()
                } label: {
                    HStack(spacing: Spacing.md) {
                        Image(systemName: "trash")
                            .scaledFont(size: 17, weight: .semibold, relativeTo: .body)
                        Text(L("action_delete")).font(.body.weight(.medium))
                        Spacer()
                    }
                    .foregroundStyle(AppColors.error)
                    .padding(.horizontal, 16)
                    .padding(.vertical, Spacing.lg)
                    .background(AppColors.error.opacity(0.10), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
        }
    }

    /// Unterblätter (Paar bearbeiten, Notiz, Widget-Hilfe, Gruppe).
    private func subSheets(_ content: some View, watch: Watch, alarmCount: Int) -> some View {
        content
        .sheet(isPresented: $editPair) {
            WatchEditSheet(watch: watch, alarmCount: alarmCount)
                .environmentObject(data)
                .environment(\.appAccent, accent)
                // Volle Höhe wie das Formular «Genau auswählen»
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(28)
                .presentationBackground(AppColors.background)
        }
        .sheet(isPresented: $editNote) {
            WatchNoteSheet(initial: watch.note) { note in
                if let w = data.watch(watchId) { data.setNote(w, note) }
            }
            .environment(\.appAccent, accent)
            // Volle Höhe: das Feld fokussiert beim Öffnen, die Tastatur schiebt das Blatt so nicht hoch
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(28)
            .presentationBackground(AppColors.background)
        }
        .sheet(isPresented: $showWidgetHelp) {
            AddWidgetHelpSheet(portfolioEnabled: data.settings.portfolioEnabled)
                .environment(\.appAccent, accent)
                // Eine feste Höhe (wie in den Einstellungen): Inhalt höher als «halb»
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $editGroup) {
            WatchGroupSheet(current: watch.groupName, groups: data.watchGroups) { group in
                if let w = data.watch(watchId) { data.setGroup(w, group) }
            }
            .environment(\.appAccent, accent)
            // Volle Höhe: «Neue Gruppe» blendet ein Feld samt Tastatur ein, das Blatt springt nicht
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(28)
            .presentationBackground(AppColors.background)
        }
    }

    // MARK: Live-Aktivität

    /// «Auf dem Sperrbildschirm zeigen» / «Vom Sperrbildschirm entfernen» (ein Paar gleichzeitig).
    private func liveActivityCard(_ watch: Watch) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Button {
                WatchlistHaptics.selection()
                toggleLiveActivity(watch)
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: liveActivityOn ? "lock.rectangle.on.rectangle.fill" : "lock.rectangle.on.rectangle")
                        .font(.body.weight(.medium))
                        .foregroundStyle(accent.primary)
                        .frame(width: 26)
                    Text(L(liveActivityOn ? "live_activity_stop" : "live_activity_start"))
                        .font(.body.weight(.medium))
                        .foregroundStyle(AppColors.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 8)
                    if liveActivityOn {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(accent.primary)
                            .accessibilityHidden(true)
                    }
                }
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Text(L(liveActivityDisabled ? "live_activity_disabled" : "live_activity_hint"))
                .font(.footnote)
                .foregroundStyle(liveActivityDisabled ? AppColors.error : AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, 12)
        }
        .padding(.horizontal, 16)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .task(id: watch.id) {
            liveActivityOn = LiveActivityController.isRunning(watchId: watch.id)
        }
    }

    private func toggleLiveActivity(_ watch: Watch) {
        let id = watch.id
        Task { @MainActor in
            if LiveActivityController.isRunning(watchId: id) {
                await LiveActivityController.stop(watchId: id)
                liveActivityOn = false
                liveActivityDisabled = false
                return
            }
            switch await LiveActivityController.start(watch) {
            case .started:
                liveActivityOn = true
                liveActivityDisabled = false
            case .disabled:
                liveActivityOn = false
                liveActivityDisabled = true
            case .failed:
                liveActivityOn = false
            }
        }
    }

    // MARK: Hauptaktionen

    /// Alarm, «Warum?» (mit ⚡ bei Signalen) und Favorit als drei gleich grosse Kacheln.
    private func primaryActions(_ watch: Watch, alarmCount: Int) -> some View {
        HStack(spacing: 8) {
            WatchActionTile(
                systemImage: alarmCount > 0 ? "alarm.fill" : "alarm",
                title: alarmCount > 0 ? L("watchlist_alarms_count", count: alarmCount) : L("watch_action_alarm")
            ) {
                WatchlistHaptics.selection()
                onOpenAlarms(watch.id)
                dismiss()
            }
            // Nicht mehr gehandelt: kein «Warum?» (es gäbe nur alte Daten)
            if !watch.isNotTraded {
                WatchActionTile(
                    systemImage: "lightbulb",
                    title: L("watch_action_why"),
                    badge: hasActivity
                ) {
                    WatchlistHaptics.selection()
                    onWhy(watch.id)
                    dismiss()
                }
            }
            WatchActionTile(
                systemImage: watch.favorite ? "star.fill" : "star",
                title: L("watch_action_favorite"),
                selected: watch.favorite
            ) {
                WatchlistHaptics.impact()
                data.toggleFavorite(watch)
            }
        }
        // Gleich hoch, auch wenn ein Text umbricht
        .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: Portfolio & Gruppe

    /// Gruppe, Notiz, «Zum Portfolio hinzufügen» (nur mit Portfolio) und Aktualisieren.
    private func groupCard(_ watch: Watch, refreshing: Bool) -> some View {
        VStack(spacing: 0) {
            WatchValueRow(
                icon: "folder",
                title: L("group_title"),
                value: watch.groupName,
                detail: nil
            ) { editGroup = true }
            RowDivider()
            noteRow(watch)
            // Nur mit eingeschaltetem Portfolio-Bereich
            if data.settings.portfolioEnabled {
                RowDivider()
                Button {
                    WatchlistHaptics.selection()
                    onAddToPortfolio(watch.id)
                    dismiss()
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "chart.pie")
                            .font(.body.weight(.medium))
                            .foregroundStyle(accent.primary)
                            .frame(width: 26)
                        Text(L("portfolio_add_from_watch"))
                            .font(.body)
                            .foregroundStyle(AppColors.onSurface)
                            .lineLimit(1)
                        Spacer(minLength: 8)
                        Image(systemName: "chevron.right")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(AppColors.outline)
                    }
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            RowDivider()
            Button {
                WatchlistHaptics.impact(.light)
                Task { await data.refreshOne(watch.id) }
            } label: {
                HStack(spacing: 12) {
                    ZStack {
                        if refreshing {
                            ProgressView().controlSize(.small).tint(accent.primary)
                        } else {
                            Image(systemName: "arrow.clockwise")
                                .font(.body.weight(.medium))
                                .foregroundStyle(accent.primary)
                        }
                    }
                    .frame(width: 26)
                    Text(L("action_refresh"))
                        .font(.body)
                        .foregroundStyle(AppColors.onSurface)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                }
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(refreshing)
            RowDivider()
            // Wie Android «Als Widget hinzufügen»; iOS kann das nicht aus der App → drei Schritte
            Button {
                WatchlistHaptics.selection()
                showWidgetHelp = true
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "square.grid.2x2")
                        .font(.body.weight(.medium))
                        .foregroundStyle(accent.primary)
                        .frame(width: 26)
                    Text(L("watch_action_add_widget"))
                        .font(.body)
                        .foregroundStyle(AppColors.onSurface)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(AppColors.outline)
                }
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 2)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    /// Eigene Notiz (#233): ganzer Text unter dem Titel, Tippen bearbeitet.
    private func noteRow(_ watch: Watch) -> some View {
        Button {
            WatchlistHaptics.selection()
            editNote = true
        } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "note.text")
                    .font(.body.weight(.medium))
                    .foregroundStyle(accent.primary)
                    .frame(width: 26)
                VStack(alignment: .leading, spacing: 3) {
                    Text(L(watch.note == nil ? "note_add" : "note_title"))
                        .font(.body)
                        .foregroundStyle(AppColors.onSurface)
                    if let note = watch.note {
                        Text(note)
                            .font(.subheadline)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.outline)
                    .padding(.top, 4)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Kopf

    private func header(_ watch: Watch) -> some View {
        HStack(spacing: Spacing.md) {
            CoinBadge(symbol: watch.baseAsset, size: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(watch.displayName)
                    .font(AppFont.headline)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text(watch.marketName)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            Spacer(minLength: 0)
            // «Paar bearbeiten»: 48 pt Tippfläche
            Button {
                WatchlistHaptics.selection()
                editPair = true
            } label: {
                Image(systemName: "pencil")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(accent.primary)
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L("watch_edit_title"))
        }
    }

    private func priceBlock(_ watch: Watch) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset))
                    .displayFont()
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .contentTransition(.numericText(value: watch.lastPrice ?? 0))
                if watch.lastPrice != nil {
                    WatchlistDayChangePill(change: watch.shownChange24h, large: true)
                }
            }
            // Bitcoin: «1 CHF = 1’234 Sats» in der Umrechnungswährung (Kurs mit dem bestehenden Faktor)
            if Sats.isBitcoin(watch.baseAsset), !watch.isNotTraded,
               let sats = Sats.perUnit(price: watch.lastPrice, rate: satsRate) {
                Text(L("sats_per_unit", LocaleNumbers.integer(1), data.settings.portfolioCurrency,
                       LocaleNumbers.decimal(sats, maxDecimals: Sats.decimals(sats), grouping: true)))
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            if let error = watch.lastError, !error.isEmpty, watch.lastPrice != nil, ConnectionErrors.isRetryable(error) {
                // Börse nicht erreichbar, älterer Kurs steht: ruhiger Satz plus «Erneut versuchen»
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(L("error_market_unreachable"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button(L("try_again")) {
                        Task { await data.refreshOne(watch.id) }
                    }
                    .font(.footnote.weight(.semibold))
                    .buttonStyle(.borderless)
                    .tint(accent.primary)
                    .disabled(data.refreshingWatchIds.contains(watch.id))
                }
            } else if let error = watch.lastError, !error.isEmpty {
                Text(ConnectionErrors.display(error))
                    .font(.footnote)
                    .foregroundStyle(ConnectionErrors.isNotTraded(error) ? AppColors.onSurfaceVariant : AppColors.error)
            } else {
                Text(L("watchlist_updated", PriceFormat.time(watch.lastUpdate)))
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(LinearGradient(
                    colors: [accent.primary.opacity(0.16), accent.primary.opacity(0.04)],
                    startPoint: .topLeading, endPoint: .bottomTrailing))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(accent.primary.opacity(0.25), lineWidth: 1)
        )
        .animation(.snappy, value: watch.lastPrice)
    }

    // MARK: Sortieren

    private func moveRow(_ watch: Watch) -> some View {
        // Favoriten bleiben oben: Grenzen innerhalb der eigenen Abteilung. In der
        // gefilterten Ansicht nur unter den sichtbaren Paaren der Gruppe; gehört das
        // Paar (nach einem Gruppenwechsel) nicht mehr dazu, gilt die ganze Liste.
        let selected = data.selectedWatchlistGroup
        let filter = selected != nil && watch.groupName == selected ? selected : nil
        let section = data.watches.filter {
            $0.favorite == watch.favorite && (filter == nil || $0.groupName == filter)
        }
        let index = section.firstIndex { $0.id == watch.id } ?? 0
        let atTop = index == 0
        let atBottom = index >= section.count - 1
        return HStack(spacing: 8) {
            moveButton("arrow.up.to.line", "sort_move_top", disabled: atTop) { data.move(watch, .top, group: filter) }
            moveButton("arrow.up", "sort_move_up", disabled: atTop) { data.move(watch, .up, group: filter) }
            moveButton("arrow.down", "sort_move_down", disabled: atBottom) { data.move(watch, .down, group: filter) }
            moveButton("arrow.down.to.line", "sort_move_bottom", disabled: atBottom) { data.move(watch, .bottom, group: filter) }
        }
    }

    private func moveButton(_ symbol: String, _ key: String, disabled: Bool, action: @escaping () -> Void) -> some View {
        Button {
            WatchlistHaptics.selection()
            withAnimation(.spring(duration: 0.35)) { action() }
        } label: {
            VStack(spacing: Spacing.xs) {
                Image(systemName: symbol).scaledFont(size: 16, weight: .semibold, relativeTo: .callout)
                Text(L(key))
                    .font(.caption2)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .minimumScaleFactor(0.8)
            }
            .foregroundStyle(disabled ? AppColors.outline : accent.primary)
            .frame(maxWidth: .infinity, minHeight: 64)
            .padding(.horizontal, 4)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .accessibilityLabel(L(key))
    }
}

/// Zeile mit Titel links und aktuellem Wert rechts (Gruppe).
private struct WatchValueRow: View {
    let icon: String
    let title: String
    /// nil = noch nicht gesetzt.
    let value: String?
    /// Zweite, kleinere Zeile unter dem Wert (optional).
    let detail: String?
    let action: () -> Void
    @Environment(\.appAccent) private var accent

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.body.weight(.medium))
                    .foregroundStyle(accent.primary)
                    .frame(width: 26)
                Text(title)
                    .font(.body)
                    .foregroundStyle(AppColors.onSurface)
                    .lineLimit(1)
                    .layoutPriority(1)
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 1) {
                    Text(value ?? "—")
                        .font(.subheadline.weight(value == nil ? .regular : .medium).monospacedDigit())
                        .foregroundStyle(value == nil ? AppColors.outline : AppColors.onSurface)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    if let detail {
                        Text(detail)
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .lineLimit(1)
                    }
                }
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.outline)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Grosse getönte Kachel im Aktionsblatt (drei nebeneinander): Symbol über kurzem Text.
/// `badge` = kleines ⚡ (Signale); `selected` = eingeschaltet (Favorit), für VoiceOver «ausgewählt».
private struct WatchActionTile: View {
    let systemImage: String
    let title: String
    var badge = false
    var selected = false
    let action: () -> Void
    @Environment(\.appAccent) private var accent

    var body: some View {
        Button(action: action) {
            VStack(spacing: Spacing.xs) {
                Image(systemName: systemImage)
                    .scaledFont(size: 19, weight: .semibold, relativeTo: .body)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .frame(height: 24)
                    .overlay(alignment: .topTrailing) {
                        if badge {
                            Image(systemName: "bolt.fill")
                                .scaledFont(size: 10, weight: .bold, relativeTo: .caption2)
                                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                                .foregroundStyle(AppColors.warning)
                                .offset(x: 10, y: -4)
                        }
                    }
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
            }
            .foregroundStyle(accent.onContainer)
            .padding(.horizontal, Spacing.xs)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 76, maxHeight: .infinity)
            .background(accent.container.opacity(selected ? 1 : 0.7), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(badge ? title + ", " + L("activity_indicator") : title)
        .accessibilityAddTraits(selected ? AccessibilityTraits([.isButton, .isSelected]) : AccessibilityTraits.isButton)
    }
}

/// Funding Rate, nächste Zahlung und Open Interest eines Perpetuals — wie `FuturesSection`.
struct WatchlistFuturesSection: View {
    let info: FuturesInfo

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            Label(L("futures_title"), systemImage: "chart.bar.xaxis")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppColors.onSurfaceVariant)

            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(L("futures_funding"))
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                    let rate = info.fundingRatePercent
                    Text(rate.map { String(format: "%+.4f %%", locale: Locale.current, $0) } ?? "—")
                        .font(.headline.monospacedDigit())
                        // Positiv: Longs zahlen an Shorts, negativ umgekehrt
                        .foregroundStyle(rate.map { PriceColors.forChange($0) } ?? AppColors.onSurface)
                    if let next = info.nextFundingTime {
                        TimelineView(.periodic(from: .now, by: 30)) { context in
                            let minutes = max(0, (next - context.date.millis) / 60_000)
                            Text(L("futures_next_funding", Int(minutes / 60), Int(minutes % 60)))
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(AppColors.onSurfaceVariant)
                        }
                    }
                    explanation(L("explain_funding"))
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                VStack(alignment: .leading, spacing: 3) {
                    Text(L("futures_open_interest"))
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                    Text(info.openInterestUsd.map { "$" + Self.compactNumber($0) } ?? "—")
                        .font(.headline.monospacedDigit())
                    explanation(L("explain_open_interest"))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Text(L("futures_source", info.source))
                .font(.caption2)
                .foregroundStyle(AppColors.onSurfaceVariant)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    /// Kurze Erklärung unter dem Wert (kleiner Nebentext, umbrechend).
    private func explanation(_ text: String) -> some View {
        Text(text)
            .font(.caption2)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 2)
    }

    /// Wie `compactNumber` in Android: 1.23 B, 4.5 M, 6.7 K.
    static func compactNumber(_ value: Double) -> String {
        let l = Locale.current
        if value >= 1e9 { return String(format: "%.2f B", locale: l, value / 1e9) }
        if value >= 1e6 { return String(format: "%.1f M", locale: l, value / 1e6) }
        if value >= 1e3 { return String(format: "%.1f K", locale: l, value / 1e3) }
        return String(format: "%.0f", locale: l, value)
    }
}
