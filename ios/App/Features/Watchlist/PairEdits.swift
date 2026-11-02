import SwiftUI

/// Menü ⋯ der Paarseite und die Schreibwege dahinter: Gruppe, Notiz, «Zum Portfolio hinzufügen», Aktualisieren, Widget,
/// Vorlesen, Sperrbildschirm, Sortieren, Deaktivieren und zuletzt Löschen. Wie Android `PairOverflowMenu`.
extension PairScreen {
    // MARK: Menü ⋯

    /// Alles Seltene aus dem früheren Abschnitt «Mehr» — eine Liste statt ausgeklappter Karten.
    func overflowMenu(_ watch: Watch) -> some View {
        Menu {
            Button { editGroup = true } label: {
                Label(watch.groupName.map { L("group_value", $0) } ?? L("group_title"), systemImage: "folder")
            }
            Button { editNote = true } label: {
                Label(L(hasNote(watch) ? "note_edit" : "note_add"), systemImage: "note.text")
            }
            // Auch mit ausgeschaltetem Portfolio: das erste Speichern schaltet es ein
            if PortfolioOff.offerAddToPortfolio(preview: false) {
                Button { addToPortfolio(watch) } label: {
                    Label { Text(L("portfolio_add_from_watch")) } icon: { AppSymbol.view(AppSymbol.portfolio, size: 17) }
                }
            }
            // Deaktiviertes Paar: kein «Aktualisieren» (es wird bewusst nicht abgefragt)
            if !watch.disabled {
                Button { refresh(watch) } label: { Label(L("action_refresh"), systemImage: "arrow.clockwise") }
                    .disabled(refreshState.refreshingWatchIds.contains(watch.id))
            }
            // Wie Android «Als Widget hinzufügen»; iOS kann das nicht aus der App → drei Schritte
            Button { showWidgetHelp = true } label: { Label(L("watch_action_add_widget"), systemImage: "square.grid.2x2") }
            Toggle(isOn: ttsBinding) { Label(L("watchlist_tts"), systemImage: "speaker.wave.2") }
            Button { toggleLiveActivity(watch) } label: {
                Label(L(liveActivityOn ? "live_activity_stop" : "live_activity_start"),
                      systemImage: liveActivityOn ? "lock.rectangle.on.rectangle.fill" : "lock.rectangle.on.rectangle")
            }
            moveSection(watch)
            Section {
                Button { toggleDisabled(watch) } label: {
                    Label(L(watch.disabled ? "watch_enable" : "watch_disable"),
                          systemImage: watch.disabled ? "play.circle" : "pause.circle")
                }
                Button(role: .destructive) { onDelete(watch) } label: { Label(L("action_delete"), systemImage: "trash") }
            }
        } label: {
            Image(systemName: "ellipsis.circle")
                .scaledFont(size: 20, relativeTo: .title3)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .tint(accent.primary)
        .accessibilityLabel(L("action_more"))
        .task(id: watch.id) { liveActivityOn = LiveActivityController.isRunning(watchId: watch.id) }
    }

    private func hasNote(_ watch: Watch) -> Bool {
        !(watch.note ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// «Vorlesen» mit Rückfrage «nicht gespeichert»; «Abbrechen» bzw. abgelehnt: der Schalter zeigt den echten Stand.
    private var ttsBinding: Binding<Bool> {
        Binding(
            get: { data.watch(currentId)?.ttsEnabled ?? false },
            set: { enabled in
                data.withWatchlistConfirm {
                    // Sprachausgabe war aus bzw. «Nur Alarme»: jetzt an — kurz sagen, wo man es ändert
                    if let w = data.watch(currentId), data.setTtsEnabled(w, enabled) {
                        toast = ToastMessage(L("watchlist_tts_speech_on"))
                    }
                    if data.watchlistWritesBlocked { refusedToggles &+= 1 }
                } cancelled: { refusedToggles &+= 1 }
            }
        )
    }

    /// «Aktualisieren»: nur dieses Paar neu abfragen, Chart des Paars (alle Zeiträume) mit dem neuen Kurs neu laden.
    func refresh(_ watch: Watch) {
        Haptics.play(.lightImpact)
        SheetChartStore.shared.invalidate(watch)
        Task { await data.refreshOne(watch.id) }
    }

    /// Portfolio-Sperre: Das Erfassen-Blatt zeigt Bestände — erst nach dem Entsperren.
    private func addToPortfolio(_ watch: Watch) {
        Haptics.play(.selection)
        let id = watch.id
        Task { @MainActor in
            let open = await lock.requireUnlock(PortfolioLockPolicy.quickAddNeedsUnlock(locked:))
            guard open, let current = data.watch(id) else { return }
            portfolioDraft = WatchlistScreen.makePortfolioDraft(for: current)
        }
    }

    // MARK: Schreibwege

    /// Deaktivieren bzw. wieder aktivieren (dann gleich den Kurs holen); die Seite bleibt und zeigt den neuen Zustand,
    /// dazu ein kurzer Hinweis — wie Android.
    func toggleDisabled(_ watch: Watch) {
        // Rückfrage «nicht gespeichert» vorher; «Abbrechen» = nichts, kein Hinweis
        data.withWatchlistConfirm {
            data.setDisabled(ids: [watch.id], !watch.disabled)
            // Wie Android: gesperrt → nur der Hinweis der Sperre, kein «deaktiviert», keine Haptik
            if !data.watchlistWritesBlocked {
                Haptics.play(.lightImpact)
                toast = ToastMessage(L(watch.disabled ? "watch_enabled_banner" : "watch_disabled_banner", watch.displayName))
            }
        }
    }

    /// Notiz-Blatt «Speichern»/«Entfernen»: erst nach bestätigter Rückfrage «nicht gespeichert» schreiben und
    /// das Blatt schliessen; «Abbrechen» = Blatt bleibt mit dem Text (wie der Alarm-Editor).
    func saveNote(_ note: String?, close: @escaping @MainActor () -> Void, cancelled: @escaping @MainActor () -> Void) {
        data.withWatchlistConfirm({
            if let w = data.watch(currentId) { data.setNote(w, note) }
            close()
        }, cancelled: cancelled)
    }

    /// Gruppen-Blatt: Auswahl bzw. neue Gruppe wie `saveNote` erst nach bestätigter Rückfrage, dann Blatt zu.
    func saveGroup(_ group: String?, close: @escaping @MainActor () -> Void, cancelled: @escaping @MainActor () -> Void) {
        data.withWatchlistConfirm({
            if let w = data.watch(currentId) { data.setGroup(w, group) }
            close()
        }, cancelled: cancelled)
    }
}
