import SwiftUI

/// Statuszeile der Merkliste — wie `WatchlistStatus.kt`.
extension WatchlistScreen {
    /// Ehrlicher Status: wie viele Kurse sind veraltet? Daneben die Dauer des
    /// letzten Durchlaufs, ein Tipp zeigt die Aufschlüsselung.
    func statusRow(_ watches: [Watch]) -> some View {
        let staleAfter = data.staleAfterMillis
        // «Nicht mehr gehandelt» ist kein Fehler: zählt weder als veraltet noch als gescheitert
        let traded = watches.filter { !ConnectionErrors.isNotTraded($0.lastError) }
        let staleCount = traded.filter { WatchlistTime.isStale($0, now: now, staleAfter: staleAfter) }.count
        let newest = traded.map(\.lastUpdate).max() ?? 0
        // Keine Verbindung: der letzte Durchlauf scheiterte bei ALLEN Paaren am Netz
        let offline = !traded.isEmpty && traded.allSatisfy { ConnectionErrors.isOffline($0.lastError) }
        let failed = traded.filter { $0.lastError != nil }.count
        let warn = staleCount > 0 || offline || failed > 0
        // Kein gehandeltes Paar in der Ansicht (leere Gruppe, alle nicht mehr gehandelt):
        // neutral statt grün — es gibt nichts, das «aktuell» sein könnte
        let none = traded.isEmpty
        // Gerät offline: ruhig (neutral), kein Rot — es wird dann nicht aktualisiert
        let deviceOffline = !data.online && !none
        let tone = none || deviceOffline ? AppColors.onSurfaceVariant : (warn ? AppColors.error : PriceColors.ok)
        let text: String
        if watches.isEmpty {
            text = L("watchlist_status_group_empty")
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
        } else if newest > 0 {
            text = L("watchlist_all_fresh", WatchlistTime.ago(newest, now: now))
        } else {
            text = L("watchlist_pull_to_refresh")
        }
        return HStack(spacing: 8) {
            HStack(spacing: 8) {
                Circle()
                    .fill(tone)
                    .frame(width: 8, height: 8)
                    .shadow(color: tone.opacity(0.7), radius: 4)
                    .phaseAnimator(data.refreshing ? [0.35, 1.0] : [1.0]) { view, phase in
                        view.opacity(phase)
                    } animation: { _ in .easeInOut(duration: 0.6) }
                Text(text)
                    .font(.caption.weight(.medium).monospacedDigit())
                    .foregroundStyle(AppColors.onSurface)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .contentTransition(.opacity)
                // Kurse kommen per WebSocket (Runde 31)
                if !data.liveExchanges.isEmpty { WatchlistLiveBadge(color: tone) }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, Spacing.sm)
            .background(tone.opacity(0.12), in: Capsule())
            // Nach einer Aktualisierung wechseln Farbe und Text weich (nicht hart)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: text)

            Spacer(minLength: 4)

            if data.lastRefreshMillis > 0 {
                Button { showReport = true } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "timer").scaledFont(size: 11, weight: .semibold, relativeTo: .caption)
                        Text(L("watchlist_last_run", PriceFormat.duration(data.lastRefreshMillis)))
                            .font(.caption.monospacedDigit())
                            .lineLimit(1)
                    }
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .padding(.horizontal, Spacing.sm)
                    .padding(.vertical, Spacing.sm)
                    .background(.ultraThinMaterial, in: Capsule())
                    .overlay(Capsule().strokeBorder(AppColors.outlineVariant.opacity(0.5), lineWidth: 0.5))
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L("watchlist_refresh_report"))
            }

            // Lupe und «+»: in der Sortieransicht ausgeblendet
            if !sorting {
                Button { openSearch() } label: {
                    // Gefüllter Kreis wie «+» daneben (neutral statt Akzentfarbe)
                    Image(systemName: "magnifyingglass")
                        .scaledFont(size: 13, weight: .bold, relativeTo: .caption)
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                        .foregroundStyle(AppColors.onSurface)
                        .frame(width: WatchlistSearch.buttonSize, height: WatchlistSearch.buttonSize)
                        .background(AppColors.containerHighest, in: Circle())
                        // Tippfläche 44 pt, sichtbar bleibt der kleine Kreis
                        .contentShape(Circle().inset(by: -(44 - WatchlistSearch.buttonSize) / 2))
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L("watchlist_search_open"))

                // «+» rechts neben der Lupe: Seite «Paar hinzufügen»
                Button { router.openExplorer() } label: {
                    Image(systemName: "plus")
                        .scaledFont(size: 13, weight: .bold, relativeTo: .caption)
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                        .foregroundStyle(accent.onPrimary)
                        .frame(width: WatchlistSearch.buttonSize, height: WatchlistSearch.buttonSize)
                        .background(accent.primary, in: Circle())
                        // Tippfläche 44 pt, sichtbar bleibt der kleine Kreis
                        .contentShape(Circle().inset(by: -(44 - WatchlistSearch.buttonSize) / 2))
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L("shortcut_add"))
            }
        }
    }
}
