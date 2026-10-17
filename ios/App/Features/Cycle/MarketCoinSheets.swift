import Accessibility
import SwiftUI

// Aktionsblatt und die Seiten danach («Was bewegt sich mit?», «Alarme», Rückgängig, Portfolio) für Coins aus «Heute auffällig».
// Eigene Datei, weil der Ablauf dem der Merkliste folgt und mit dem Aufbau des Tabs nichts teilt
// (wie `MarketCoinSheets.kt`).

// MARK: - Aktionsblatt aus «Heute auffällig»

/// Aktionsblatt und was danach kommt für «Heute auffällig» — dasselbe Blatt wie ein Tipp auf die
/// Zeile in der Merkliste (`WatchActionsSheet`), auch die Abläufe danach wie dort
/// (`WatchlistScreen.withSheets`): «Was bewegt sich mit?» und «Alarme» als eigene Seiten, zurück öffnet das
/// Blatt wieder; Löschen sofort mit «Rückgängig» im Banner; Portfolio erst nach dem Entsperren.
/// Vorschau (Coin nicht in der Merkliste): Kurs aus einer Ticker-Abfrage ohne Speichern,
/// «Zur Merkliste hinzufügen» legt das Paar wie der Hinzufügen-Tab an. Wie Android `MarketCoinSheets`.
@MainActor
struct CycleCoinSheets: ViewModifier {
    @Binding var target: CycleCoinTarget?

    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject private var lock = AppLock.shared
    /// Was nach dem Schliessen des Blatts passieren soll (wie in der Merkliste).
    @State private var pendingWhy: Int64?
    @State private var pendingWhyPreview: Watch?
    @State private var pendingAlarms: Int64?
    @State private var pendingDelete: Watch?
    @State private var pendingPortfolio: Int64?
    /// Aus dem Blatt zu «Alarme» bzw. «Was bewegt sich mit?» gewechselt: Zurück öffnet es wieder.
    @State private var returnTo: CycleCoinTarget?
    @State private var whyFor: CycleWhyTarget?
    @State private var whyPreview: Watch?
    @State private var alarmsFor: Int64?
    @State private var portfolioDraft: PortfolioTxDraft?
    /// «… entfernt» mit «Rückgängig» nach «Löschen».
    @State private var banner: WatchlistBannerMessage?

    func body(content: Content) -> some View {
        content
            .sheet(item: $target, onDismiss: afterSheet) { target in
                sheet(for: target)
                    .environmentObject(data)
                    .environment(\.appAccent, accent)
                    .environment(\.changeView, data.changeView())
                    // Halb hoch öffnen, auf volle Höhe ziehbar (L-16, `WatchActionsDetents`)
                    .modifier(WatchActionsDetents())
                    .presentationDragIndicator(.visible)
                    .presentationCornerRadius(28)
                    .presentationBackground(AppColors.background)
            }
            .navigationDestination(item: $whyFor) { target in
                WatchlistWhySheet(watchId: target.id)
                    .environmentObject(data)
                    .environment(\.appAccent, accent)
            }
            .navigationDestination(item: $whyPreview) { watch in
                WatchlistWhySheet(watchId: WatchPreview.watchId, preview: watch)
                    .environmentObject(data)
                    .environment(\.appAccent, accent)
            }
            .navigationDestination(item: $alarmsFor) { id in
                AlarmsScreen(watchId: id)
            }
            // Zurück aus «Was bewegt sich mit?» bzw. «Alarme»: Blatt wieder öffnen, falls von dort gekommen
            .onChange(of: whyFor) { _, value in if value == nil { reopen() } }
            .onChange(of: whyPreview) { _, value in if value == nil { reopen() } }
            .onChange(of: alarmsFor) { _, value in if value == nil { reopen() } }
            // Wieder gesperrt (Hintergrund-Limit), während das Erfassen-Blatt offen ist: schliessen
            .onChange(of: lock.locked) { _, locked in
                if locked { portfolioDraft = nil }
            }
            // Erste Transaktion mit Portfolio aus: Rückfrage «Portfolio-Tab einschalten?» (wie in der Merkliste)
            .modifier(PortfolioQuickAddPresenter(
                draft: $portfolioDraft,
                onEnabled: { banner = WatchlistBannerMessage(text: L("portfolio_enabled_banner")) },
                onLater: { banner = WatchlistBannerMessage(text: L("portfolio_enable_later_hint")) }
            ))
            .overlay(alignment: .bottom) {
                WatchlistBanner(message: $banner) { deleted in
                    withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { _ = data.restore(deleted) }
                }
                .animation(reduceMotion ? nil : .spring(duration: 0.35), value: banner)
            }
    }

    private func sheet(for target: CycleCoinTarget) -> some View {
        let storedId: Int64? = { if case .stored(let id) = target { return id } else { return nil } }()
        let preview: Watch? = { if case .preview(let watch) = target { return watch } else { return nil } }()
        let id = storedId ?? WatchPreview.watchId
        return WatchActionsSheet(
            watchId: id,
            // Wie in der Merkliste: ⚡ an «Was bewegt sich mit?», wenn das Paar gerade Signale hat
            hasActivity: data.watch(id)?.isNotTraded == false &&
                !WatchlistActivity.active(data.activityReports[id], now: TimeUtils.nowMillis,
                                          sensitivity: data.settings.activitySensitivity).isEmpty,
            onOpenAlarms: { pendingAlarms = $0 },
            onDelete: { pendingDelete = $0 },
            onWhy: { pendingWhy = $0 },
            onAddToPortfolio: { pendingPortfolio = $0 },
            preview: preview,
            onWhyPreview: { pendingWhyPreview = $0 },
            // Wie in der Merkliste: kurzes Banner nach «Deaktivieren»/«Aktivieren»
            onDisabledChange: { before in
                banner = WatchlistBannerMessage(
                    text: L(before.disabled ? "watch_enabled_banner" : "watch_disabled_banner", before.displayName),
                    icon: before.disabled ? "play.circle.fill" : "pause.circle.fill"
                )
            }
        )
    }

    /// Nach dem Schliessen des Blatts: «Was bewegt sich mit?», Alarme, Löschen oder Portfolio — wie in der Merkliste.
    private func afterSheet() {
        if let id = pendingWhy {
            pendingWhy = nil
            returnTo = .stored(id)
            whyFor = CycleWhyTarget(id: id)
        }
        if let watch = pendingWhyPreview {
            pendingWhyPreview = nil
            returnTo = .preview(watch)
            whyPreview = watch
        }
        if let id = pendingAlarms {
            pendingAlarms = nil
            returnTo = .stored(id)
            alarmsFor = id
        }
        if let watch = pendingDelete {
            pendingDelete = nil
            delete(watch)
        }
        if let id = pendingPortfolio {
            pendingPortfolio = nil
            // Portfolio-Sperre: Das Erfassen-Blatt zeigt Bestände — erst nach dem Entsperren
            Task { @MainActor in
                let open = await lock.requireUnlock(PortfolioLockPolicy.quickAddNeedsUnlock(locked:))
                guard open, let watch = data.watch(id) else { return }
                portfolioDraft = WatchlistScreen.makePortfolioDraft(for: watch)
            }
        }
    }

    /// Zurück aus «Alarme» bzw. «Was bewegt sich mit?»: Blatt wieder öffnen (gelöschtes Paar: nicht).
    private func reopen() {
        guard let back = returnTo else { return }
        returnTo = nil
        if case .stored(let id) = back, data.watch(id) == nil { return }
        target = back
    }

    /// Wie nach links wischen in der Merkliste: sofort löschen, «Rückgängig» im Banner, Ansage.
    private func delete(_ watch: Watch) {
        var result: DeletedWatch?
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { result = data.deleteForUndo(watch) }
        guard let deleted = result else { return }
        let text = L("watchlist_removed", deleted.watch.displayPair)
        banner = WatchlistBannerMessage(text: text, icon: "trash", undo: deleted)
        Task { @MainActor in
            // Nach dem Fokuswechsel ansagen, sonst geht es unter
            try? await Task.sleep(nanoseconds: 300_000_000)
            AccessibilityNotification.Announcement(text).post()
        }
    }
}
