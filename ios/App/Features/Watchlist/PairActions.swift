import SwiftUI

/// Aktionen der Paarseite: ein Hauptknopf («Alarm setzen» bzw. in der Vorschau «Zur Merkliste hinzufügen»), kleine
/// Knöpfe darunter (Favorit, «Was bewegt sich mit?», Börse wechseln, Kursbenachrichtigung), Sperrbildschirm und
/// Sortieren im Menü ⋯. Wie Android `PairActions.kt`.
extension PairScreen {
    // MARK: Hauptknopf

    /// «Alarm setzen» über die ganze Breite: öffnet den Alarm-Editor im Abschnitt «Alarme». Gesperrt bei Referenzkurs,
    /// zu dünnem DEX-Pool oder erreichter Höchstzahl — der Grund steht im Abschnitt.
    func alarmButton(_ watch: Watch) -> some View {
        let full = !WatchlistLimits.canAddAlarm(data.alarms(for: watch.id).count)
        let blocked = AlarmsScreen.lowLiquidity(watch) || AlarmsScreen.referenceRate(watch) || full
        return primaryButton(systemImage: "bell.and.waves.left.and.right", title: L("add_alarm_action"), disabled: blocked) {
            Haptics.play(.selection)
            newAlarmRequest &+= 1
        }
        .accessibilityIdentifier(UiTestTags.watchActionAlarm)
    }

    /// Hauptknopf der Seite in der Themenfarbe (mindestens 48 pt hoch, Text bricht um statt abzuschneiden).
    func primaryButton(systemImage: String, title: String, disabled: Bool = false,
                       action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: Spacing.sm) {
                Image(systemName: systemImage)
                    .scaledFont(size: 17, weight: .semibold, relativeTo: .body)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.body.weight(.semibold))
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(disabled ? AppColors.onSurfaceVariant : onAccent)
            .padding(.horizontal, Spacing.lg)
            .padding(.vertical, Spacing.md)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(disabled ? AppColors.containerHigh : accent.primary,
                        in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(disabled)
    }

    // MARK: Kleine Knöpfe

    /// Favorit und Kursbenachrichtigung als Umschalter (VoiceOver «ausgewählt»), «Was bewegt sich mit?» (nicht bei
    /// nicht mehr gehandelten bzw. deaktivierten Paaren; ⚡ bei Signalen) und «Börse wechseln» (`WatchEditSheet`).
    /// Brechen bei grosser Schrift in weitere Zeilen um.
    func secondaryActions(_ watch: Watch) -> some View {
        FlowLayout(spacing: Spacing.sm) {
            PairChip(systemImage: watch.favorite ? "star.fill" : "star", title: L("watch_action_favorite"),
                     selected: watch.favorite) {
                // Rückfrage «nicht gespeichert» vorher; «Abbrechen» = nichts, keine Haptik
                data.withWatchlistConfirm {
                    data.toggleFavorite(watch)
                    // Wie Android: gesperrt → nur der Hinweis, keine Haptik
                    if !data.watchlistWritesBlocked { Haptics.play(.lightImpact) }
                }
            }
            if !watch.isNotTraded && !watch.disabled {
                PairChip(systemImage: hasActivity(watch) ? "lightbulb.fill" : "lightbulb", title: L("watch_action_why"),
                         badge: hasActivity(watch)) {
                    Haptics.play(.selection)
                    whyFor = WatchlistSheetTarget(id: watch.id)
                }
            }
            PairChip(systemImage: "arrow.left.arrow.right", title: L("watch_action_change_exchange")) {
                Haptics.play(.selection)
                editPair = true
            }
            // Wie die Glocke in der Zeile; aus = durchgestrichen
            PairChip(systemImage: watch.notificationEnabled ? "bell" : "bell.slash", title: L("watchlist_notification"),
                     selected: watch.notificationEnabled) {
                let enabled = !watch.notificationEnabled
                data.withWatchlistConfirm {
                    // Merkliste gesperrt: nur der Hinweis, keine Erlaubnis-Anfrage
                    if enabled && !data.watchlistWritesBlocked { Task { _ = await Notifier.requestPermission() } }
                    if let w = data.watch(currentId) { data.setNotificationEnabled(w, enabled) }
                }
            }
        }
    }

    /// Paar hat gerade ⚡-Signale (nach der gewählten Empfindlichkeit).
    func hasActivity(_ watch: Watch) -> Bool {
        !watch.isNotTraded && !watch.disabled &&
            !WatchlistActivity.active(data.activityReports[watch.id], now: EpochTime.nowMillis,
                                      sensitivity: data.settings.activitySensitivity).isEmpty
    }

    // MARK: Vorschau

    /// Vorschau (Paar nicht in der Merkliste): gross «Zur Merkliste hinzufügen», darunter «Was bewegt sich mit?».
    /// `adding`: Knopf gesperrt, «Zur Merkliste hinzugefügt.» bis die Seite zum gespeicherten Eintrag wechselt.
    func previewActions(_ watch: Watch, adding: Bool) -> some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            primaryButton(systemImage: adding ? "checkmark" : "plus",
                          title: L(adding ? "explorer_added_to_watchlist" : "explorer_add_to_watchlist"),
                          disabled: adding) { addPreview() }
            // Nicht mehr gehandelt: kein «Was bewegt sich mit?» (es gäbe nur alte Daten)
            if !watch.isNotTraded {
                PairChip(systemImage: "lightbulb", title: L("watch_action_why")) {
                    Haptics.play(.selection)
                    whyPreview = watch
                }
            }
        }
    }

    // MARK: Sperrbildschirm

    /// «Auf dem Sperrbildschirm zeigen» / «Vom Sperrbildschirm entfernen» (ein Paar gleichzeitig); Hinweis als Toast.
    func toggleLiveActivity(_ watch: Watch) {
        Haptics.play(.selection)
        let id = watch.id
        Task { @MainActor in
            if LiveActivityController.isRunning(watchId: id) {
                await LiveActivityController.stop(watchId: id)
                liveActivityOn = false
                return
            }
            switch await LiveActivityController.start(watch) {
            case .started:
                liveActivityOn = true
                // Grenze von iOS: eine Live-Aktivität endet spätestens nach 8 h
                toast = ToastMessage(L("live_activity_hint_ends"))
            case .disabled:
                liveActivityOn = false
                toast = ToastMessage(L("live_activity_disabled"), kind: .error)
            case .failed:
                liveActivityOn = false
            }
        }
    }

    // MARK: Sortieren

    /// Ganz nach oben, nach oben, nach unten, ganz nach unten — als Abschnitt im Menü ⋯.
    @ViewBuilder
    func moveSection(_ watch: Watch) -> some View {
        // Favoriten bleiben oben: Grenzen innerhalb der eigenen Abteilung. In der gefilterten Ansicht nur unter den
        // sichtbaren Paaren der Gruppe; gehört das Paar nicht mehr dazu, gilt die ganze Liste.
        let selected = data.selectedWatchlistGroup
        let filter = selected != nil && WatchFilter.matches(selected, watch) ? selected : nil
        let section = data.watches.filter { $0.favorite == watch.favorite && WatchFilter.matches(filter, $0) }
        let index = section.firstIndex { $0.id == watch.id } ?? 0
        let atTop = index == 0
        let atBottom = index >= section.count - 1
        Section {
            moveButton("arrow.up.to.line", "sort_move_top", disabled: atTop) { data.move(watch, .top, group: filter) }
            moveButton("arrow.up", "sort_move_up", disabled: atTop) { data.move(watch, .up, group: filter) }
            moveButton("arrow.down", "sort_move_down", disabled: atBottom) { data.move(watch, .down, group: filter) }
            moveButton("arrow.down.to.line", "sort_move_bottom", disabled: atBottom) { data.move(watch, .bottom, group: filter) }
        }
    }

    private func moveButton(_ symbol: String, _ key: String, disabled: Bool, action: @escaping () -> Void) -> some View {
        Button {
            // Rückfrage «nicht gespeichert» vorher (verschieben schreibt die Merkliste)
            data.withWatchlistConfirm {
                Haptics.play(.selection)
                withAnimation(.spring(duration: 0.35)) { action() }
            }
        } label: {
            Label(L(key), systemImage: symbol)
        }
        .disabled(disabled)
    }
}

/// Kleiner Knopf der Paarseite (Kapsel): Symbol und kurzer Text, bricht um statt abzuschneiden. `selected` =
/// eingeschaltet (Favorit, Kursbenachrichtigung), für VoiceOver «ausgewählt»; `badge` = kleines ⚡ (Signale).
private struct PairChip: View {
    let systemImage: String
    let title: String
    var selected = false
    var badge = false
    let action: () -> Void
    @Environment(\.appAccent) var accent

    var body: some View {
        Button(action: action) {
            HStack(spacing: Spacing.xs) {
                Image(systemName: systemImage)
                    .scaledFont(size: 15, weight: .semibold, relativeTo: .subheadline)
                if badge {
                    Image(systemName: "bolt.fill")
                        .scaledFont(size: 11, weight: .bold, relativeTo: .caption)
                        .foregroundStyle(AppColors.warning)
                }
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(accent.onContainer)
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.sm)
            .frame(minHeight: 44)
            .background(accent.container.opacity(selected ? 1 : 0.6), in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(badge ? title + ", " + L("activity_indicator") : title)
        .accessibilityAddTraits(selected ? AccessibilityTraits([.isButton, .isSelected]) : AccessibilityTraits.isButton)
    }
}

// MARK: - Vorschau-Paar

/// Vorschau-Paar aus «Heute auffällig» (Coin nicht in der Merkliste): Binance Spot «COIN/USDT»,
/// nur im Speicher — nichts wird gespeichert, bis der Nutzer es hinzufügt. Wie Android
/// `InfoViewModel.previewWatch` / `previewQuote`.
enum WatchPreview {
    /// Id des Vorschau-Paars (nie gespeichert): negativ, fällt mit keinem Eintrag, Alarm oder Signal zusammen.
    static let watchId: Int64 = -1
    /// Börse und Quote wie die Start-Merkliste.
    static let exchangeKey = "Binance"
    static let quoteAsset = "USDT"

    /// Vorschau-Paar zu `symbol`; nil, wenn es Binance nicht gibt oder die gespeicherte Paarliste das
    /// Spot-Paar nicht führt (dann die Suche auf der Seite «Paar hinzufügen»). Die Zeilen kommen ohnehin
    /// aus den Binance-Tickern «…USDT», die Prüfung ist nur die Absicherung.
    static func watch(symbol: String) async -> Watch? {
        let base = symbol.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !base.isEmpty, let exchange = ExchangesConfig.exchange(exchangeKey) else { return nil }
        let pairs = await PairCache.shared.pairs(for: exchange.key).pairs
        var pairId = base + quoteAsset
        // Leere Liste (noch nie geladen): den Ticker selbst entscheiden lassen
        if !pairs.isEmpty {
            guard let listed = pairs.first(where: {
                $0.contractType == .none &&
                    $0.base.caseInsensitiveCompare(base) == .orderedSame &&
                    $0.quote.caseInsensitiveCompare(quoteAsset) == .orderedSame
            }) else { return nil }
            if let id = listed.pairId { pairId = id }
        }
        return Watch(id: watchId, exchangeKey: exchange.key, exchangeName: exchange.name,
                     baseAsset: base, quoteAsset: quoteAsset, pairId: pairId)
    }

    /// Kurs und rollende 24-h-Veränderung — eine Ticker-Abfrage wie beim Aktualisieren eines Paars,
    /// aber ohne Speichern, Alarme oder Mitteilungen. Fehler stehen in `lastError`.
    static func fetchQuote(_ watch: Watch) async -> Watch {
        let fetched = await PriceRefresher.fetchSingle(watch)
        var shown = watch
        if let ticker = fetched.ticker, ticker.last.isFinite, ticker.last > 0 {
            shown.lastPrice = ticker.last
            shown.change24h = ticker.change24hPercent.flatMap { $0.isFinite ? $0 : nil }
            shown.lastUpdate = EpochTime.nowMillis
            shown.lastError = nil
        } else {
            // Älterer Kurs bleibt stehen (das Blatt bietet dann «Erneut versuchen» an)
            shown.lastError = fetched.error ?? L("exchange_unavailable_error")
        }
        return shown
    }
}
