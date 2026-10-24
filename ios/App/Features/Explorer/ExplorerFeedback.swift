import SwiftUI
import UIKit

// Rückmeldungen von «Paar hinzufügen»: Meldungsleiste (Snackbar), Blatt «Paarliste abgleichen» und das
// HTTP-Protokoll für Entwickler.
// Eigene Datei, weil es Rückmeldungen über dem Ablauf sind, nicht Teil der Schritte (wie `ExplorerFeedback.kt`).

// MARK: Paarliste abgleichen

/// Blatt zur Paarliste der Börse: Zeitpunkt des letzten Abgleichs, Anzahl, allfälliger Fehler und
/// der Knopf zum Neuladen (Android zeigt das nur als Statuszeile in `ExplorerSteps.kt`).
struct ExplorerSyncSheet: View {
    let marketName: String
    let info: MarketPairsInfo?
    let state: ExplorerPairListSync
    let onSync: () -> Void
    @Environment(\.dismiss) var dismiss
    @Environment(\.appAccent) var accent
    @Environment(\.onAccent) var onAccent

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            header

            Text(L("explorer_sync_sheet_text"))
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)

            facts

            if let failure = state.error {
                Text(L("check_error_generic_prefix", failure))
                    .font(.footnote)
                    .foregroundStyle(AppColors.error)
            }

            syncButton

            Button(L("action_close")) { dismiss() }
                .font(.headline)
                .foregroundStyle(accent.primary)
                .frame(maxWidth: .infinity)
        }
        .padding(24)
        // Oben verankert: Fehlertext und Ladeanzeige lassen den Rest nicht springen (feste Höhe)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
        .presentationBackground(AppColors.background)
    }

    private var header: some View {
        HStack(spacing: Spacing.md) {
            Image(systemName: "arrow.triangle.2.circlepath")
                .font(AppFont.headline)
                .foregroundStyle(accent.primary)
                .frame(width: 48, height: 48)
                .background(accent.container.opacity(0.6), in: Circle())
                .symbolEffect(.pulse, isActive: state.inProgress)
            VStack(alignment: .leading, spacing: 2) {
                Text(L("explorer_sync_sheet_title"))
                    .font(.title3.weight(.semibold))
                Text(marketName)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        }
    }

    private var facts: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Label(L("explorer_sync_sheet_last", syncedText), systemImage: "clock")
            Label(L("sync_pairs_count", info?.count ?? 0), systemImage: "number")
                .monospacedDigit()
        }
        .font(.subheadline)
        .foregroundStyle(AppColors.onSurface)
        .padding(Spacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }

    private var syncButton: some View {
        Button(action: onSync) {
            HStack(spacing: 8) {
                if state.inProgress {
                    ProgressView().tint(onAccent)
                }
                Text(L("explorer_sync_sheet_action"))
                    .font(.headline)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, Spacing.lg)
        }
        .buttonStyle(AccentButtonStyle())
        .disabled(state.inProgress)
    }

    private var syncedText: String {
        if let stamp = info?.lastSyncDate, stamp > 0 { return TickerView.sameDayTimeOrDate(stamp) }
        return L("explorer_sync_never")
    }
}

// MARK: Rückmeldung

/// Meldung am unteren Rand mit optionalem Knopf: «Ansehen» (zur Merkliste) oder «Alarm setzen». Fläche, Rahmen und
/// Symbol je Art (`ExplorerMessage.kind`, `snackbarSurface`).
struct ExplorerSnackbar: View {
    @Binding var message: ExplorerMessage?
    let onView: () -> Void
    /// «Alarm setzen» öffnet die Alarme dieses Eintrags.
    var onAlarm: (Int64) -> Void = { _ in }
    /// «Erneut versuchen» sucht den DEX-Begriff noch einmal.
    var onRetryDex: (String) -> Void = { _ in }
    /// «Rückgängig» entfernt die eben neu angelegten Paare.
    var onUndo: ([Int64]) -> Void = { _ in }
    @Environment(\.appAccent) var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver

    /// Anzeigedauer: mit «Alarm setzen» 6 s (mit VoiceOver 12 s), lange Meldungen 5 s, sonst 3 s.
    private func lifetime(_ note: ExplorerMessage) -> UInt64 {
        let second: UInt64 = 1_000_000_000
        if note.alarmWatchId != nil || note.retryDexQuery != nil || note.undoIds != nil {
            return (voiceOver ? 12 : 6) * second
        }
        return (note.long ? 5 : 3) * second
    }

    var body: some View {
        if let note = message {
            HStack(spacing: 12) {
                // Symbol nach Art (Erfolg Häkchen, Fehler Ausrufezeichen, Info keines) — wie Android
                SnackbarKindIcon(kind: note.kind)
                Text(note.text)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(AppColors.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
                actionButton(for: note)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, Spacing.lg)
            .snackbarSurface(note.kind)
            .shadow(color: AppColors.shadow.opacity(0.25), radius: 16, y: 6)
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
            // Reduzierte Bewegung: nur überblenden statt von unten hereinschieben
            .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.move(edge: .bottom).combined(with: .opacity))
            .task(id: note.id) {
                try? await Task.sleep(nanoseconds: lifetime(note))
                guard !Task.isCancelled else { return }
                withAnimation(.spring(duration: 0.35)) { self.message = nil }
            }
            .gesture(DragGesture(minimumDistance: 10).onEnded { drag in
                guard drag.translation.height > 10 else { return }
                withAnimation { self.message = nil }
            })
        }
    }

    @ViewBuilder
    private func actionButton(for note: ExplorerMessage) -> some View {
        if let watchId = note.alarmWatchId {
            Button {
                withAnimation { self.message = nil }
                onAlarm(watchId)
            } label: {
                actionLabel(L("add_alarm_action"))
            }
            .buttonStyle(.borderless)
        } else if let ids = note.undoIds {
            Button {
                withAnimation { self.message = nil }
                onUndo(ids)
            } label: {
                actionLabel(L("action_undo"))
            }
            .buttonStyle(.borderless)
        } else if let query = note.retryDexQuery {
            Button {
                withAnimation { self.message = nil }
                onRetryDex(query)
            } label: {
                actionLabel(L("action_retry"))
            }
            .buttonStyle(.borderless)
        } else if note.showView {
            Button {
                onView()
                withAnimation { self.message = nil }
            } label: {
                actionLabel(L("action_view"))
            }
            .buttonStyle(.borderless)
        }
    }

    private func actionLabel(_ text: String) -> some View {
        Text(text)
            .font(.subheadline.weight(.bold))
            .foregroundStyle(accent.primary)
    }
}

// MARK: HTTP-Protokoll

/// Entwickleroption: die jüngsten Börsenanfragen, jede Sekunde neu gelesen (Rolle wie `LogBox.kt`).
struct ExplorerLogBox: View {
    @Environment(\.appAccent) var accent
    @Environment(\.onAccent) var onAccent

    /// Höchstens 10 Zeilen, jede auf 400 Zeichen gekürzt.
    private static func recentLines() -> [String] {
        HttpLog.all.suffix(10).map { line in
            line.count > 400 ? String(line.prefix(400)) + "..." : line
        }
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let lines = Self.recentLines()
            VStack(alignment: .leading, spacing: 0) {
                titleBar
                Text(lines.isEmpty ? "—" : lines.joined(separator: "\n"))
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundStyle(accent.onContainer)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                    .background(accent.container.opacity(0.7))
            }
            .clipShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        }
    }

    private var titleBar: some View {
        HStack {
            Label(L("log_box_title"), systemImage: "terminal")
                .font(.subheadline.weight(.semibold))
            Spacer()
            Button {
                HttpLog.clear()
            } label: {
                Image(systemName: "trash")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L("action_clear"))
        }
        .foregroundStyle(onAccent)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(accent.primary)
    }
}
