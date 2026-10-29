import SwiftUI

/// Gruppe, Notiz, Portfolio und Aktualisieren im Aktionsblatt.
extension WatchActionsSheet {
    // MARK: Portfolio & Gruppe

    /// Gruppe, Notiz, «Zum Portfolio hinzufügen» (auch mit Portfolio aus) und Aktualisieren.
    func groupCard(_ watch: Watch, refreshing: Bool) -> some View {
        VStack(spacing: 0) {
            // «Gruppe: Langfristig» bzw. «Gruppe» — wie Android
            WatchActionRow(
                icon: "folder",
                title: watch.groupName.map { L("group_value", $0) } ?? L("group_title")
            ) { editGroup = true }
            RowDivider()
            noteRow(watch)
            // Auch mit ausgeschaltetem Portfolio: das erste Speichern schaltet es ein
            if PortfolioOff.offerAddToPortfolio(preview: preview != nil) {
                RowDivider()
                Button {
                    Haptics.play(.selection)
                    onAddToPortfolio(watch.id)
                    dismiss()
                } label: {
                    HStack(spacing: 12) {
                        AppSymbol.view(AppSymbol.portfolio, size: 22)
                            .font(.body.weight(.medium))
                            .foregroundStyle(accent.primary)
                            .frame(width: 26)
                        Text(L("portfolio_add_from_watch"))
                            .font(.body)
                            .foregroundStyle(AppColors.onSurface)
                            .lineLimit(1)
                        Spacer(minLength: 8)
                        Image(systemName: "chevron.forward")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(AppColors.outline)
                    }
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            // Deaktiviertes Paar: kein «Aktualisieren» (es wird bewusst nicht abgefragt)
            if !watch.disabled {
                RowDivider()
                refreshRow(watch, refreshing: refreshing)
            }
            RowDivider()
            // Wie Android «Als Widget hinzufügen»; iOS kann das nicht aus der App → drei Schritte
            Button {
                Haptics.play(.selection)
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
                    Image(systemName: "chevron.forward")
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
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }

    /// «Aktualisieren»: nur dieses Paar neu abfragen (Kreisel, solange es läuft).
    private func refreshRow(_ watch: Watch, refreshing: Bool) -> some View {
        Button {
            Haptics.play(.lightImpact)
            // Chart des Paars (alle Zeiträume) mit dem neuen Kurs neu laden
            SheetChartStore.shared.invalidate(watch)
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
    }

    /// Deaktivieren (keine Abfragen, keine Mitteilungen, Alarme ruhen) bzw. wieder aktivieren —
    /// mit kurzem Hinweis darunter. Wie die `SheetAction` mit `hint` (Android).
    func disableRow(_ watch: Watch) -> some View {
        Button {
            toggleDisabled(watch)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: watch.disabled ? "play.circle" : "pause.circle")
                    .font(.body.weight(.medium))
                    .foregroundStyle(accent.primary)
                    .frame(width: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L(watch.disabled ? "watch_enable" : "watch_disable"))
                        .font(.body)
                        .foregroundStyle(AppColors.onSurface)
                    Text(L(watch.disabled ? "watch_enable_hint" : "watch_disable_hint"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                Spacer(minLength: 8)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }

    /// Deaktivieren bzw. wieder aktivieren (dann gleich den Kurs holen); Blatt zu, der Aufrufer
    /// zeigt ein kurzes Banner (`onDisabledChange`).
    func toggleDisabled(_ watch: Watch) {
        // Rückfrage «nicht gespeichert» vorher; «Abbrechen» = nichts, kein Banner
        data.withWatchlistConfirm {
            data.setDisabled(ids: [watch.id], !watch.disabled)
            // Wie Android: gesperrt → nur der Hinweis, kein Banner «deaktiviert», keine Haptik
            if !data.watchlistWritesBlocked {
                Haptics.play(.lightImpact)
                onDisabledChange(watch)
            }
            dismiss()
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

    /// Eigene Notiz (#233): «Notiz bearbeiten» und der ganze Text darunter, ohne Notiz (auch leer) «Notiz
    /// hinzufügen» — Texte wie Android. Tippen bearbeitet.
    private func noteRow(_ watch: Watch) -> some View {
        let note = watch.note.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        return Button {
            Haptics.play(.selection)
            editNote = true
        } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "note.text")
                    .font(.body.weight(.medium))
                    .foregroundStyle(accent.primary)
                    .frame(width: 26)
                VStack(alignment: .leading, spacing: 3) {
                    Text(L(note == nil ? "note_add" : "note_edit"))
                        .font(.body)
                        .foregroundStyle(AppColors.onSurface)
                    if let note {
                        Text(note)
                            .font(.subheadline)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.outline)
                    .padding(.top, 4)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Aktionszeile mit Symbol, Titel und Pfeil (Gruppe).
private struct WatchActionRow: View {
    let icon: String
    let title: String
    let action: () -> Void
    @Environment(\.appAccent) var accent

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
                    .truncationMode(.middle)
                Spacer(minLength: 8)
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.outline)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
