import SwiftUI

/// Statuszeile der Merkliste — wie `WatchlistStatus.kt`.
extension WatchlistScreen {
    /// Ehrlicher Status: wie viele Kurse sind veraltet? Der Prüfbericht des letzten Durchlaufs liegt im ⋯-Menü
    /// (wie Android, keine eigene Pille). `state`: Status der Aktualisierung — die Statuszeile
    /// wird in `RefreshStateReader` gezeigt und zeichnet bei dessen Wechsel allein neu.
    func statusRow(_ watches: [Watch], state: RefreshState) -> some View {
        let staleAfter = data.staleAfterMillis
        // Paare am Live-Strom: eigene Frische-Schwelle (2 Min.), «Live» nur mit allen (`LiveStatus`)
        let liveIds = state.liveWatchIds
        // «Nicht mehr gehandelt» ist kein Fehler: zählt weder als veraltet noch als gescheitert;
        // deaktivierte Paare zählen gar nicht (weder veraltet noch gescheitert noch «aktuell»)
        let traded = DisabledView.active(watches) { $0.disabled }.filter { !ConnectionErrors.isNotTraded($0.lastError) }
        let allDisabled = !watches.isEmpty && watches.allSatisfy(\.disabled)
        let staleCount = traded.filter {
            WatchlistTime.isStale($0, now: now, staleAfter: LiveStatus.staleAfterMillis(
                onLiveSocket: liveIds.contains($0.id), pollingAfter: staleAfter))
        }.count
        let newest = traded.map(\.lastUpdate).max() ?? 0
        let allLive = LiveStatus.allLive(tradedIds: traded.map(\.id), liveIds: liveIds, stale: staleCount)
        // «vor X Min.» bei «Kurse der gewählten Börsen»: der älteste gezeigte Kurs
        let shownTime = LiveStatus.shownTime(updateTimes: traded.map(\.lastUpdate))
        // Keine Verbindung: der letzte Durchlauf scheiterte bei ALLEN Paaren am Netz
        let offline = !traded.isEmpty && traded.allSatisfy { ConnectionErrors.isOffline($0.lastError) }
        let failed = traded.filter { $0.lastError != nil }.count
        let warn = staleCount > 0 || offline || failed > 0
        // Kein gehandeltes Paar in der Ansicht (leere Gruppe, alle nicht mehr gehandelt):
        // neutral statt grün — es gibt nichts, das «aktuell» sein könnte
        let none = traded.isEmpty
        // Gerät offline: ruhig (neutral), kein Rot — es wird dann nicht aktualisiert
        let deviceOffline = !data.online && !none
        // Aktuell: ruhig in der Textfarbe, nicht in der Steigen-Farbe (frisch ≠ gestiegen); «veraltet» in Bernstein wie
        // die Zeile, Fehler (keine Verbindung, gescheitert) in Fehlerfarbe — wie Android
        let tone: Color
        switch StatusPillTone.of(none: none, deviceOffline: deviceOffline, offline: offline, stale: staleCount,
                                 failed: failed) {
        case .neutral: tone = AppColors.onSurfaceVariant
        case .error: tone = AppColors.error
        case .warning: tone = AppColors.warningText
        case .normal: tone = AppColors.onSurface
        }
        let text: String
        // Kurzform bei Platzmangel (nur «Kurse der gewählten Börsen · …»), siehe `statusTextFit`
        var shortText: String?
        var showsLive = false
        if watches.isEmpty {
            text = L("watchlist_status_group_empty")
        } else if allDisabled {
            text = L("watchlist_status_all_disabled")
        } else if none {
            text = L("watchlist_status_none_traded")
        } else if deviceOffline {
            text = newest > 0
                ? L("offline_status_since", PriceFormat.shortTime(newest))
                : L("offline_status")
        } else if offline {
            text = newest > 0 ? L("watchlist_offline_since", WatchlistTime.ago(newest, now: now)) : L("watch_error_offline")
        } else if staleCount > 0 {
            text = L("watchlist_stale_count", count: staleCount, staleCount, traded.count)
        } else if failed > 0 {
            text = L("watchlist_failed_count", count: failed, failed, traded.count)
        } else if newest > 0 && allLive {
            // Ein Wort vorne — «Live» nur, wenn ALLE Paare frisch am Strom sind, sonst
            // «Kurse der gewählten Börsen · vor 2 Min.» (mit der Zeit des ältesten Kurses):
            // sagt wie der Store-Text, woher der Kurs kommt und wie alt er ist
            text = L("status_live")
            showsLive = true
        } else if newest > 0 {
            let age = WatchlistTime.ago(shownTime, now: now)
            text = L("watchlist_status_exchange_prices", age)
            shortText = L("watchlist_status_exchange_prices_short", age)
        } else {
            text = L("watchlist_pull_to_refresh")
        }
        // VoiceOver: derselbe Satz, Zeit ausgeschrieben («vor 2 Minuten» statt «vor 2 Min.») und
        // « · » als Pause; Live-Hinweis dahinter
        let agoTime = !warn && !deviceOffline ? shownTime : newest
        let shortAgo = agoTime > 0 ? WatchlistTime.ago(agoTime, now: now) : ""
        let longAgo = agoTime > 0 ? WatchlistTime.spokenAgo(agoTime, now: now) : ""
        let spokenText = A11ySpeech.spokenDots(shortAgo.isEmpty ? text : text.replacingOccurrences(of: shortAgo, with: longAgo))
        // «Live-Kurse aktiv» nur, wenn auch sichtbar «Live» steht
        let spokenLabel = A11ySpeech.join([spokenText, showsLive ? L("watchlist_live_a11y") : nil])
        // Höflich ansagen, aber nur beim Wechsel aktuell / veraltet / offline (nicht jede Minute) — wie Android
        let kind = A11ySpeech.statusKind(none: none, offline: deviceOffline || offline, warn: warn, newest: newest)
        // Tipp auf den Status: nur die veralteten Paare zeigen bzw. wieder alle (wie Android)
        let staleOnly = quickView == .stale
        let tappable = staleOnly || staleCount > 0
        return HStack(spacing: 8) {
            HStack(spacing: 8) {
                Circle()
                    .fill(tone)
                    .frame(width: 8, height: 8)
                    .shadow(color: tone.opacity(0.7), radius: 4)
                    .phaseAnimator(state.refreshing ? [0.35, 1.0] : [1.0]) { view, phase in
                        view.opacity(phase)
                    } animation: { _ in .easeInOut(duration: 0.6) }
                // Einzeilig: zu eng (z. B. 390 pt mit «3 deaktiviert») → Kurzform «Börsenkurse · vor 1 Min.»,
                // noch enger nur die Zeit — das Alter hat Vorrang vor dem Wort davor (VoiceOver: ganzer Satz); wie Android
                statusTextFit(text, short: shortText,
                              ageOnly: !shortAgo.isEmpty && shortAgo != text && text.contains(shortAgo) ? shortAgo : nil)
                    .contentTransition(.opacity)
                // Kurse per WebSocket: steht als Wort «Live» im Status, kein zweites Abzeichen
                if staleOnly {
                    Image(systemName: "xmark")
                        .scaledFont(size: 10, weight: .bold, relativeTo: .caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, Spacing.sm)
            .background(tone.opacity(0.12), in: Capsule())
            // Tippfläche 48 pt hoch (wie «N deaktiviert» und Android): je 8 pt über und unter der Pille;
            // nach aussen wieder die Pillen-Höhe, die Statuszeile wächst nicht
            .padding(.vertical, Self.statusTouchExtra)
            .contentShape(Rectangle())
            .padding(.vertical, -Self.statusTouchExtra)
            .onTapGesture { if tappable { toggleQuickView(.stale) } }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(tappable ? .isButton : [])
            .accessibilityHint(tappable ? L(staleOnly ? "watchlist_show_all" : "watchlist_show_only_stale") : "")
            .modifier(WatchlistStatusSpeech(label: spokenLabel, announcement: spokenText, kind: kind))
            // Nach einer Aktualisierung wechseln Farbe und Text weich (nicht hart)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: text)
            // Der Status bekommt zuerst Platz (vor «N deaktiviert»)
            .layoutPriority(1)

            // «3 deaktiviert» neben dem Status (deaktivierte Paare der Gruppen-Ansicht)
            disabledChip

            Spacer(minLength: 4)

            // Lupe und «+»: beim Sortieren und Auswählen ausgeblendet
            if !sorting && !selecting {
                Button { openSearch() } label: {
                    // Gefüllter Kreis wie «+» daneben (neutral statt Akzentfarbe)
                    Image(systemName: "magnifyingglass")
                        .scaledFont(size: WatchlistSearch.buttonSymbolSize, weight: .bold, relativeTo: .caption)
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                        .foregroundStyle(AppColors.onSurface)
                        .frame(width: WatchlistSearch.buttonSize, height: WatchlistSearch.buttonSize)
                        .background(AppColors.containerHighest, in: Circle())
                        // Tippfläche 44 pt, sichtbar bleibt der kleine Kreis
                        .contentShape(Circle().inset(by: -(44 - WatchlistSearch.buttonSize) / 2))
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L("watchlist_search_open"))

                // «+» rechts neben der Lupe: kleine Merkliste (bis 3 Paare) mit Text «Paar hinzufügen», damit der
                // Weg zum nächsten Paar klar ist; sonst nur «+» (Label «Paar hinzufügen»), so bricht der Status nicht um
                addPairButton(labelled: data.watches.count <= WatchlistSearch.addLabelMaxPairs)
            }
        }
    }

    /// Hinweis «3 deaktiviert» neben dem Status: kleine neutrale Kapsel im Stil des Status
    /// (Pause-Symbol, Zahl). Tipp zeigt nur die deaktivierten Paare (`QuickView.disabled`); ist die
    /// Ansicht an, steht ✕ am Ende und der Tipp zeigt wieder alle. Wie `DisabledChip` (Android).
    @ViewBuilder
    var disabledChip: some View {
        let count = DisabledView.count(data.visibleWatches) { $0.disabled }
        let selected = quickView == .disabled
        if count > 0 || selected {
            Button { toggleQuickView(.disabled) } label: {
                disabledChipLabel(count: count, selected: selected)
            }
            .buttonStyle(.plain)
            .accessibilityHint(L(selected ? "watchlist_show_all" : "watchlist_show_only_disabled"))
        }
    }

    /// Erste Fassung, die einzeilig passt: ganzer Satz, Kurzform, nur die Zeit; passt keine, die letzte mit «…» —
    /// wie `FirstFitText` (Android).
    private func statusTextFit(_ text: String, short: String?, ageOnly: String?) -> some View {
        ViewThatFits(in: .horizontal) {
            statusLabel(text).fixedSize()
            if let short {
                statusLabel(short).fixedSize()
            }
            statusLabel(ageOnly ?? short ?? text)
        }
    }

    private func statusLabel(_ value: String) -> some View {
        Text(value)
            .font(.caption.weight(.medium).monospacedDigit())
            .foregroundStyle(AppColors.onSurface)
            .lineLimit(1)
    }

    /// Tippfläche über und unter der Status-Pille.
    private static let statusTouchExtra: CGFloat = Spacing.sm

    /// Tippfläche über und unter der Pille «N deaktiviert».
    private static let disabledTouchExtra: CGFloat = Spacing.sm

    private func disabledChipLabel(count: Int, selected: Bool) -> some View {
        let tone = AppColors.onSurfaceVariant
        return HStack(spacing: Spacing.xs) {
            Image(systemName: "pause.circle")
                .scaledFont(size: 12, weight: .semibold, relativeTo: .caption)
                .foregroundStyle(tone)
                .accessibilityHidden(true)
            Text(L("watchlist_disabled_count", count: count))
                .font(.caption.weight(.medium).monospacedDigit())
                .foregroundStyle(AppColors.onSurface)
                .lineLimit(1)
            if selected {
                Image(systemName: "xmark")
                    .scaledFont(size: 10, weight: .bold, relativeTo: .caption)
                    .foregroundStyle(tone)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, Spacing.sm)
        .padding(.vertical, Spacing.sm)
        .background(tone.opacity(selected ? 0.22 : 0.12), in: Capsule())
        // Tippfläche 48 pt hoch (wie Android): je 8 pt über und unter der Pille; nach aussen
        // wieder die Pillen-Höhe, die Statuszeile wächst nicht
        .padding(.vertical, Self.disabledTouchExtra)
        .contentShape(Rectangle())
        .padding(.vertical, -Self.disabledTouchExtra)
    }
}

/// VoiceOver der Status-Pille: Satz wie sichtbar (Zeit ausgeschrieben) und höfliche Ansage nur beim
/// Wechsel aktuell / veraltet / offline (`A11ySpeech.shouldAnnounce`) — wie Android `AnnounceStatusChange`.
private struct WatchlistStatusSpeech: ViewModifier {
    let label: String
    let announcement: String
    let kind: A11ySpeech.StatusKind?

    func body(content: Content) -> some View {
        content
            .accessibilityLabel(label)
            .onChange(of: kind) { old, new in
                guard A11ySpeech.shouldAnnounce(previous: old, current: new) else { return }
                AccessibilityNotification.Announcement(announcement).post()
            }
    }
}

/// Zeitangaben der Merkliste.
enum WatchlistTime {
    /// Relative Zeit; je Aufruf neu (`RelativeDateTimeFormatter` ist nicht Sendable, wie `WatchText.ago` auf der Uhr).
    /// Steht meist hinter anderem Text («Börsenkurse · vor 10 Min.», «Letzte Prüfung vor 10 Min.»): klein beginnend
    /// wie «gerade eben», nach den Regeln der Sprache — wie Android `midSentence`. Am Zeilenanfang macht `lineStart`
    /// sie wieder gross.
    private static func relative(_ unitsStyle: RelativeDateTimeFormatter.UnitsStyle) -> RelativeDateTimeFormatter {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = unitsStyle
        f.dateTimeStyle = .numeric
        f.formattingContext = .middleOfSentence
        return f
    }

    /// «gerade eben» bzw. «vor 2 Min.» in der Sprache des Geräts, klein beginnend.
    static func ago(_ millis: Int64, now: Int64) -> String {
        if now - millis < 60_000 { return L("time_just_now") }
        return relative(.abbreviated).localizedString(for: Date(millis: millis), relativeTo: Date(millis: now))
    }

    /// Zeile, die mit der relativen Zeit `ago` beginnt («vor 5 Min. · Binance nicht erreichbar», Adresszeile
    /// «vor 2 Std. · Etherscan»): erstes Zeichen gross nach den Regeln der Sprache (`locale`, z. B. Türkisch
    /// «i» → «İ»). Mitten im Satz bleibt die Zeit klein; beginnt `line` nicht mit `ago` (andere Wortstellung),
    /// bleibt sie unverändert. Wie Android `lineStart`.
    static func lineStart(_ line: String, ago: String, locale: Locale = .current) -> String {
        guard !ago.isEmpty, line.hasPrefix(ago), let first = line.first else { return line }
        return String(first).uppercased(with: locale) + line.dropFirst()
    }

    /// Wie `ago`, aber ausgeschrieben für VoiceOver: «vor 2 Minuten» — wie Android `spokenAgo`.
    static func spokenAgo(_ millis: Int64, now: Int64) -> String {
        if now - millis < 60_000 { return L("time_just_now") }
        return relative(.full).localizedString(for: Date(millis: millis), relativeTo: Date(millis: now))
    }

    /// Veraltet: noch nie aktualisiert oder älter als die Schwelle — nie bei deaktivierten oder
    /// nicht mehr gehandelten Paaren. Dazu wie die Alarmsperre: letzter Handel laut Börse älter als 2 h
    /// (`PriceFreshness.isStale`).
    static func isStale(_ watch: Watch, now: Int64, staleAfter: Int64) -> Bool {
        PriceFreshness.isStale(watch, now: now, staleAfter: staleAfter)
    }
}
