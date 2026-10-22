import SwiftUI

/// Gruppe, Notiz, Portfolio und Aktualisieren im Aktionsblatt.
extension WatchActionsSheet {
    // MARK: Portfolio & Gruppe

    /// Gruppe, Notiz, «Zum Portfolio hinzufügen» (auch mit Portfolio aus) und Aktualisieren.
    func groupCard(_ watch: Watch, refreshing: Bool) -> some View {
        VStack(spacing: 0) {
            WatchValueRow(
                icon: "folder",
                title: L("group_title"),
                value: watch.groupName,
                detail: nil
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
        // Seit Build 105: Rückfrage F-08 vorher; «Abbrechen» = nichts, kein Banner
        data.withWatchlistConfirm {
            data.setDisabled(ids: [watch.id], !watch.disabled)
            // Seit Build 106 (wie Android): gesperrt → nur der Hinweis, kein Banner «deaktiviert», keine Haptik
            if !data.watchlistWritesBlocked {
                Haptics.play(.lightImpact)
                onDisabledChange(watch)
            }
            dismiss()
        }
    }

    /// Eigene Notiz (#233): ganzer Text unter dem Titel, Tippen bearbeitet.
    private func noteRow(_ watch: Watch) -> some View {
        Button {
            Haptics.play(.selection)
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

/// Zeile mit Titel links und aktuellem Wert rechts (Gruppe).
private struct WatchValueRow: View {
    let icon: String
    let title: String
    /// nil = noch nicht gesetzt.
    let value: String?
    /// Zweite, kleinere Zeile unter dem Wert (optional).
    let detail: String?
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
