import Accessibility
import SwiftUI

/// Leere Merkliste (Start-Auswahl) und der «Erst-Hinzufügen»-Moment.
extension WatchlistScreen {
    /// Holt den ausstehenden Moment ab, sobald die Merkliste sichtbar ist (aktiver Tab, Seite
    /// «Paar hinzufügen» geschlossen). Start-Tipp: Banner, eine Haptik und die VoiceOver-Ansage
    /// hier; beim Hinzufügen über die Seite hat sie das schon gemacht — hier nur die Zeilen.
    func takeCelebration() {
        guard router.tab == .watchlist, !router.showExplorer, let celebration = data.takeAddCelebration() else { return }
        var positions: [Int64: Int] = [:]
        for (index, id) in celebration.watchIds.enumerated() { positions[id] = index }
        celebrating = positions
        if celebration.announceInWatchlist {
            firstAddHaptic += 1
            // Noch kein Alarm auf den neuen Paaren: «Alarm setzen» öffnet die Alarme des
            // ersten (wie im Aktionsblatt). Kein weiterer Banner danach.
            let counts = data.activeAlarmCounts
            let noAlarms = celebration.watchIds.allSatisfy { (counts[$0] ?? 0) == 0 }
            let action: WatchlistBannerAction? = noAlarms ? celebration.watchIds.first.map { id in
                WatchlistBannerAction(title: L("add_alarm_action")) { alarmsFor = id }
            } : nil
            banner = WatchlistBannerMessage(text: celebration.message, long: true, action: action)
            let text = celebration.message
            Task { @MainActor in
                // Erst nach dem Wechsel zur Liste ansagen, sonst geht es im Fokuswechsel unter
                try? await Task.sleep(nanoseconds: 400_000_000)
                AccessibilityNotification.Announcement(text).post()
            }
        }
        // Moment vorbei: Zeilen wieder im Normalzustand (Versatz + Einblenden + Häkchen)
        let ids = Set(celebration.watchIds)
        let nanos = UInt64(max(celebration.watchIds.count - 1, 0)) * 90_000_000 + 2_600_000_000
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: nanos)
            if Set(celebrating.keys) == ids { celebrating = [:] }
        }
    }

    /// Leere Merkliste: gibt es trotzdem Alarme (Portfolio)? Gleiche Zählung wie `openBell`.
    private var starterHasAlarms: Bool {
        data.alarmsWithWatch.count + (data.settings.portfolioEnabled ? data.portfolioAlarms.count : 0) > 0
    }

    /// Start-Tipp: frische Liste, sonst Zwischenspeicher (auch älter), sonst Ersatzliste —
    /// so steht sofort etwas da.
    private var shownStarterCoins: [StarterCoin] {
        starterCoins ?? StarterCoins.initial(marketKey: StarterCoins.marketKey())
    }

    /// «Kurse von Binance in USDT. Abwählen, …»: Börse (bei Standort-Sperre Binance.US) und Quote halbfett in
    /// der Textfarbe, der Rest wie bisher.
    private func starterSourceText(region: String?) -> AttributedString {
        let market = StarterPairs.marketName(region)
        let quote = StarterPairs.startQuote(region)
        var text = AttributedString(L("starter_source_text", BidiText.isolate(market), BidiText.isolate(quote)))
        for part in [market, quote] where !part.isEmpty {
            if let range = text.range(of: part) {
                text[range].font = Font.subheadline.weight(.semibold)
                text[range].foregroundColor = AppColors.onSurface
            }
        }
        return text
    }

    /// Top 5 nach Marktkapitalisierung höchstens alle 24 h neu holen; kommt die Liste,
    /// bevor jemand tippt, wird still getauscht.
    private func refreshStarterCoins() async {
        // Binance am Standort gesperrt (451/403)? Dann Startpaare auf Binance.US (`StarterGeoBlock`) — zuerst prüfen,
        // damit Liste, Kurse und Hinzufügen dieselbe Börse nutzen
        if await StarterGeoBlock.probe(), !Task.isCancelled, data.watches.isEmpty {
            starterCoins = StarterCoins.initial(marketKey: StarterCoins.marketKey())
        }
        let marketKey = StarterCoins.marketKey()
        guard !StarterCoins.isFresh(marketKey: marketKey) else { return }
        guard let fresh = await StarterCoins.load(marketKey: marketKey), !Task.isCancelled else { return }
        if data.watches.isEmpty { starterCoins = fresh }
    }

    /// Leere Merkliste: die fünf grössten Coins zur Auswahl (alle vorgewählt) und in
    /// einem Schritt hinzufügen; darunter der Weg zu jedem anderen Coin über die Coin-Suche von
    /// «Paar hinzufügen» (auch «+» oben rechts).
    var emptyState: some View {
        let coins = shownStarterCoins
        return GeometryReader { geo in
            ScrollView {
                VStack(spacing: Spacing.md) {
                    WatchlistLogo(size: 64)
                        .padding(16)
                        .background(accent.container.opacity(0.5), in: Circle())
                        .accessibilityHidden(true)
                    Text(L("starter_title"))
                        .font(.title3.weight(.semibold))
                        .multilineTextAlignment(.center)
                        .accessibilityAddTraits(.isHeader)
                    // Unterzeile «Ohne Konto. Ohne Werbung. Ohne Tracking.»: letzte Zeile von welcome_text (wie Android)
                    Text(L("welcome_text").components(separatedBy: "\n").last ?? "")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(accent.primary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                    // Börse und Quote der Startpaare hervorgehoben: man soll sehen, woher die Kurse kommen (wie Android)
                    Text(starterSourceText(region: StarterPairs.region()))
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)

                    WatchlistStarterPicker(coins: coins, region: StarterPairs.region()) { symbols in
                        addStarter(symbols)
                    }

                    // Der Weg zu jedem anderen Coin: «Paar hinzufügen» mit der Coin-Suche oben. Keine Werbung für das
                    // Portfolio hier — es ist ab Werk aus und gehört nicht zum ersten Schritt eines Preis-Checkers.
                    Button {
                        router.openExplorer()
                    } label: {
                        Label(L("starter_search_coin"), systemImage: "magnifyingglass")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(accent.primary)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            // Echter Rahmen wie Android `OutlinedButton` (Farbe `outline`, ≥ 3:1 – der
                            // halbdurchsichtige Akzentstrich lag dunkel unter 3:1)
                            .background(Capsule().strokeBorder(AppColors.outline, lineWidth: 1))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, Spacing.lg)
                .padding(.vertical, Spacing.xxl)
                .frame(maxWidth: 480)
                // Mittig, solange es passt; sonst scrollbar (grosse Schrift, kleine Geräte)
                .frame(maxWidth: .infinity, minHeight: geo.size.height)
            }
            .refreshable { await data.refreshAll() }
        }
        // «+» oben rechts (der Hinweis verweist darauf); in der Merkliste neben der Lupe
        .overlay(alignment: .topTrailing) {
            HStack(spacing: Spacing.sm) {
                // Glocke nur mit Alarmen (z. B. im Portfolio): ohne Paar und ohne Alarm führte sie nur zu «Coin
                // hinzufügen» und doppelte das «+» daneben (wie Android)
                if starterHasAlarms {
                    Button {
                        openBell()
                    } label: {
                        // Alarm-Glocke mit Wellen wie im Kopf der Merkliste
                        Image(systemName: "bell.and.waves.left.and.right")
                            .font(.body)
                            .imageScale(.large)
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                    .tint(accent.primary)
                    .accessibilityLabel(L("alarms_overview_title"))
                }
                addPairButton(labelled: false)
            }
            .padding(.trailing, Spacing.md)
            .padding(.top, Spacing.sm)
        }
        .task { await refreshStarterCoins() }
    }

    /// Haptik, Banner und Ansage kommen mit dem «Erst-Hinzufügen»-Moment (`takeCelebration`).
    private func addStarter(_ symbols: [String]) {
        // Prüfung der Standort-Sperre läuft noch: abwarten, damit die Paare gleich an der richtigen Börse landen
        if StarterGeoBlock.isProbing {
            Task { @MainActor in
                await StarterGeoBlock.waitForProbe()
                addStarterNow(symbols)
            }
            return
        }
        addStarterNow(symbols)
    }

    private func addStarterNow(_ symbols: [String]) {
        let added = withAnimation(.spring(duration: 0.35)) {
            data.addStarterCoins(symbols)
        }
        // Neue Paare haben Kurs-Mitteilungen an: Erlaubnis fragen (wie im Explorer) — vorher die kurze
        // Erklärung (`NotificationPrePrompt`), aber erst nach dem Banner mit «Alarm setzen» (`askNotificationsAfterHint`)
        if added > 0 { notificationAskPending = true }
        // Gleich abholen, damit die neuen Zeilen schon im ersten Bild verborgen sind
        takeCelebration()
        // Kein Banner mit «Alarm setzen» (z. B. schon Alarme da): gleich fragen
        if banner?.action == nil { askNotificationsAfterHint() }
    }

    /// Ausstehende Frage nach Mitteilungen stellen, sobald kein Banner mit Aktion mehr steht. Wurde «Alarm setzen»
    /// getippt (Alarme offen), fragt das Speichern des Alarms selbst (`.alarmCreated`) — hier dann nichts.
    func askNotificationsAfterHint() {
        guard notificationAskPending, banner?.action == nil else { return }
        notificationAskPending = false
        if alarmsFor == nil {
            NotificationPrePrompt.request(.pairsAdded)
        }
    }
}
