import SwiftUI

// Eine Paar-Zeile der Merkliste (Teil von `WatchlistScreen`, gezeigt in WatchlistList.swift): Werte auslesen,
// `WatchlistRow` mit Gesten, VoiceOver-Aktionen und Wischen, darüber Live-Kurs und Status (`WatchlistLiveRow`).

extension WatchlistScreen {
    /// `section`: sichtbare Abteilung (Favoriten bzw. übrige) für «Nach oben/unten» in VoiceOver.
    func row(_ watch: Watch, counts: [Int64: Int], kinds: [Int64: AssetKind],
                     signals: [Int64: [ActivitySignal]],
                     section: [Watch], shape: UnevenRoundedRectangle) -> some View {
        let index = section.firstIndex(where: { $0.id == watch.id })
        let canReorder = !searching && index != nil
        // Werte der Zeile hier auslesen (nicht in der Zeile), damit nur `WatchlistLiveRow` den
        // Live-Kurs und den Status der Zeile beobachtet: ein Tick bzw. «wird aktualisiert» zeichnet genau diese
        // eine Zeile neu
        let pollingStaleAfter = data.staleAfterMillis
        let firedAt = data.alarmFiredAt[watch.id] ?? 0
        let pausedUntil = data.exchangePauses[watch.exchangeKey]
        let sparklines = data.settings.watchlistSparkline && !watch.isNotTraded
        let target = convertTarget
        let rates = convertRates
        let fxTimes = convertFxTimes
        let usdtNotes = convertUsdtNotes
        return WatchlistLiveRow(watch: watch, rollingBasis: !data.settings.changeBasis.isDay,
                                state: data.refreshState) { shown, status in
            // Am Live-Strom: veraltet schon nach 2 Min. ohne Kurs (wie die Status-Pille)
            let staleAfter = LiveStatus.staleAfterMillis(onLiveSocket: status.onLive, pollingAfter: pollingStaleAfter)
            WatchlistRow(
                watch: shown,
                alarmCount: counts[watch.id] ?? 0,
                alarmFiredAt: firedAt,
                kind: kinds[watch.id] ?? .TOKEN,
                now: now,
                staleAfter: staleAfter,
                exchangePausedUntil: pausedUntil,
                loading: status.loading,
                highlighted: highlightedId == watch.id || (selecting && selectedIds.contains(watch.id)),
                sorting: sorting,
                shape: shape,
                selecting: selecting,
                selected: selectedIds.contains(watch.id),
                hasActivity: signals[watch.id] != nil,
                converted: WatchlistConversion.text(shown, target: target, rates: rates, fxTimes: fxTimes,
                                                    usdtNotes: usdtNotes, now: now),
                convertedA11y: WatchlistConversion.accessibilityText(shown, target: target, rates: rates,
                                                                     fxTimes: fxTimes, usdtNotes: usdtNotes, now: now),
                // Nicht mehr gehandelt: kein Mini-Chart
                sparklineEnabled: sparklines,
                celebrationIndex: celebrating[watch.id],
                onTap: {
                    if selecting { toggleSelected(watch.id) } else { actionsFor = WatchlistSheetTarget(id: watch.id) }
                },
                onToggleFavorite: { toggleFavorite(watch) },
                onActivity: {
                    if selecting { toggleSelected(watch.id) } else if !sorting { whyFor = WatchlistSheetTarget(id: watch.id) }
                },
                // Lange drücken: Mehrfachauswahl mit dieser Zeile (wie ⋯ › «Auswählen»), in der
                // Auswahl an-/abhaken wie Tippen. Sortieren weiter nur über ⋯ › Sortieren (dann am
                // Griff ziehen); im Sortiermodus tut langes Drücken nichts (wie Android).
                onLongPress: sorting ? nil : {
                    if selecting { toggleSelected(watch.id) } else { startSelection(with: watch.id) }
                }
            )
        }
        // VoiceOver: verschieben wie per Ziehen (gleiche Abteilung, Reihenfolge gespeichert);
        // Löschen wie nach links wischen (mit «Rückgängig»). «Favorit» hat die Zeile selbst.
        .accessibilityActions {
            if canReorder, let index {
                if index > 0 {
                    Button(L("a11y_move_up")) { moveByAccessibility(watch, .up) }
                }
                if index < section.count - 1 {
                    Button(L("a11y_move_down")) { moveByAccessibility(watch, .down) }
                }
            }
            if !sorting && !selecting {
                Button(L("action_delete")) { swipeDelete(watch) }
                // «Auswählen» wie langes Drücken: Mehrfachauswahl mit dieser Zeile
                Button(L("watchlist_select")) { startSelection(with: watch.id) }
            }
        }
        // Wischen: rechts (führend) = Favorit, schnappt zurück; links = Löschen ohne
        // Rückfrage. Rechts-nach-links-Sprachen spiegeln das von selbst. Nicht im
        // Sortiermodus und nicht mit VoiceOver (dort die Aktionen oben).
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            if swipeEnabled {
                Button { swipeFavorite(watch) } label: {
                    Label(L(watch.favorite ? "favorite_remove" : "favorite_add"),
                          systemImage: watch.favorite ? "star" : "star.fill")
                }
                .tint(accent.primary)
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            if swipeEnabled {
                // Ohne destruktive Rolle — sonst blendet die Liste die Zeile sofort aus,
                // obwohl die Rückfrage «nicht gespeichert» noch kommt und «Abbrechen» nichts löscht
                Button { swipeDelete(watch) } label: {
                    Label(L("action_delete"), systemImage: "trash")
                }
                // Systemrot: weisse Schrift bleibt auch im Dunkelmodus lesbar (AppColors.error ist dort hell)
                .tint(AppColors.destructive)
            }
        }
        // Sprungknopf: welche Zeilen sichtbar sind (Richtung) und ob gescrollt wird
        .onAppear { rowVisibilityChanged(watch.id, visible: true) }
        .onDisappear { rowVisibilityChanged(watch.id, visible: false) }
        .id(watch.id)
        // Paare ohne Fuge als eine Karte (`ListSegment`); die Trennlinie zeichnet die Zeile
        .plainRow(top: ListSegment.gap / 2, bottom: ListSegment.gap / 2)
    }
}

/// Status einer Zeile aus `RefreshState` (je Zeile beobachtet).
struct WatchlistRowStatus {
    /// «wird aktualisiert»: einzeln in Arbeit oder volle Aktualisierung bei einem Paar noch ohne Kurs.
    let loading: Bool
    /// Paar am Live-Strom (Frische-Schwelle 2 Min.).
    let onLive: Bool
}

/// Eine Zeile mit dem Live-Kurs (`LivePrices`, nur Anzeige) und ihrem Status (`RefreshState.rowLoading`,
/// `rowOnLive`) darüber: Nur diese Ansicht liest Kurs und Status ihres Paars, ein WebSocket-Tick bzw. ein
/// Wechsel von «wird aktualisiert» oder Live zeichnet also nur sie neu — nicht die ganze Merkliste.
private struct WatchlistLiveRow<Content: View>: View {
    let watch: Watch
    let rollingBasis: Bool
    let state: RefreshState
    @ViewBuilder let content: (Watch, WatchlistRowStatus) -> Content

    var body: some View {
        let shown = watch.withLive(LivePrices.shared.quote(for: watch.id), rollingBasis: rollingBasis)
        let status = WatchlistRowStatus(loading: state.rowLoading(watch.id, priceMissing: shown.lastPrice == nil),
                                        onLive: state.rowOnLive(watch.id))
        content(shown, status)
    }
}
