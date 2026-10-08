import SwiftUI
import UIKit

// MARK: Schritt-Karte

/// Fläche für einen Schritt — wie `StepCard` in Android, mit feiner Kontur.
struct ExplorerStepCard<Content: View>: View {
    var highlighted: Bool = false
    @ViewBuilder var content: () -> Content
    @Environment(\.appAccent) var accent

    var body: some View {
        VStack(alignment: .leading, spacing: 0, content: content)
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .strokeBorder(
                        highlighted ? accent.primary.opacity(0.35) : AppColors.outlineVariant.opacity(0.35),
                        lineWidth: highlighted ? 1 : 0.5
                    )
            )
    }
}

/// Schritt-Kopf: Nummer im Kreis, aktiv bzw. erledigt in der Akzentfarbe,
/// noch nicht erreichbar blass.
struct ExplorerStepHeader: View {
    let number: Int
    let title: String
    let active: Bool
    let done: Bool
    @Environment(\.appAccent) var accent

    var body: some View {
        let lit = active || done
        HStack(spacing: Spacing.sm) {
            ZStack {
                Circle()
                    .fill(lit ? AnyShapeStyle(accent.primary.gradient) : AnyShapeStyle(AppColors.containerHighest))
                if done {
                    Image(systemName: "checkmark")
                        .scaledFont(size: 11, weight: .heavy, relativeTo: .caption)
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                        .foregroundStyle(accent.onPrimary)
                        .transition(.scale.combined(with: .opacity))
                } else {
                    Text(verbatim: LocaleNumbers.integer(number))
                        .scaledFont(size: 12, weight: .bold, design: .rounded, relativeTo: .caption)
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                        .foregroundStyle(lit ? accent.onPrimary : AppColors.onSurfaceVariant)
                }
            }
            .frame(width: 24, height: 24)
            .animation(.spring(duration: 0.35), value: done)
            Text(title)
                .font(.headline)
                .foregroundStyle(lit ? AppColors.onSurface : AppColors.onSurfaceVariant)
        }
        .padding(.bottom, 12)
    }
}

/// Kleiner Hinweistext unter einem Schritt.
struct ExplorerHint: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 8)
    }
}

/// Fehlerzeile mit «Erneut versuchen».
struct ExplorerErrorRow: View {
    let message: String
    let onRetry: () -> Void
    @Environment(\.appAccent) var accent

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(AppColors.error)
            Text(L("check_error_generic_prefix", message))
                .font(.footnote)
                .foregroundStyle(AppColors.error)
                .lineLimit(4)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(L("action_retry"), action: onRetry)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(accent.primary)
                .buttonStyle(.borderless)
        }
        .padding(12)
        .background(AppColors.error.opacity(0.08), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(.top, Spacing.sm)
    }
}

// MARK: Auswahlfeld

/// Auswahlfeld (wie `ComboBox`): Beschriftung oben, Wert darunter; öffnet eine Liste.
struct ExplorerPickerField: View {
    let label: String
    let value: String?
    var placeholder: String? = nil
    var enabled: Bool = true
    var badge: Bool = false
    let action: () -> Void
    @Environment(\.appAccent) var accent

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if badge, let value, !value.isEmpty {
                    CoinBadge(symbol: value, size: 32)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(enabled ? accent.primary : AppColors.onSurfaceVariant)
                    Text(displayText)
                        .font(.body.weight(value == nil ? .regular : .semibold))
                        .foregroundStyle(value == nil ? AppColors.onSurfaceVariant : AppColors.onSurface)
                        .lineLimit(1)
                }
                Spacer(minLength: 4)
                if enabled {
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.md)
            .frame(minHeight: 56)
            .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(ExplorerPressStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.6)
    }

    private var displayText: String {
        if let value, !value.isEmpty { return value }
        return placeholder ?? "—"
    }
}

/// Leichtes Eindrücken beim Antippen.
struct ExplorerPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

// MARK: Auswahlliste

/// Durchsuchbare Liste für Börse, Coin, Gegenwährung und Kontrakt.
/// Favoriten (Stern oder langes Drücken) stehen zuerst — wie `ComboBox.kt`.
@MainActor
struct ExplorerPickerSheet: View {
    let title: String
    let items: [String]
    let selected: String?
    /// nil: keine Favoriten (Kontrakt).
    let favoriteKind: FavoriteKind?
    var badges: Bool = false
    @ObservedObject var data: AppData
    let onSelect: (String) -> Void

    @Environment(\.dismiss) var dismiss
    @Environment(\.appAccent) var accent
    @State var query = ""

    private var favorites: Set<String> {
        guard let favoriteKind else { return [] }
        return data.favorites[favoriteKind] ?? []
    }

    /// Treffer am Anfang zuerst, dann enthaltende; Favoriten jeweils oben.
    private var visible: [String] {
        let q = query.trimmingCharacters(in: .whitespaces)
        let matches: [String]
        if q.isEmpty {
            matches = items
        } else {
            let starts = items.filter { $0.range(of: q, options: [.caseInsensitive, .anchored]) != nil }
            let contains = items.filter {
                $0.range(of: q, options: [.caseInsensitive, .anchored]) == nil && $0.range(of: q, options: .caseInsensitive) != nil
            }
            matches = starts + contains
        }
        let favs = favorites
        return matches.filter { favs.contains($0) } + matches.filter { !favs.contains($0) }
    }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List {
                    let rows = visible
                    if rows.isEmpty {
                        Text("—")
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .listRowBackground(AppColors.containerLow)
                    }
                    ForEach(rows, id: \.self) { item in
                        row(item)
                            .id(item)
                    }
                    if favoriteKind != nil {
                        Section {
                            EmptyView()
                        } footer: {
                            Text(L("hint_favorites_list"))
                                .font(.footnote)
                                .foregroundStyle(AppColors.onSurfaceVariant)
                        }
                    }
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
                .background(AppColors.background)
                .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always))
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .onSubmit(of: .search) {
                    if let first = visible.first { pick(first) }
                }
                .onAppear {
                    if let selected, items.contains(selected) {
                        proxy.scrollTo(selected, anchor: .center)
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                }
            }
        }
        .tint(accent.primary)
        // Volle Höhe: das Suchfeld (Tastatur) würde ein halbes Blatt sonst auf «gross» springen lassen
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .presentationBackground(AppColors.background)
    }

    @ViewBuilder
    private func row(_ item: String) -> some View {
        let isSelected = item == selected
        let isFavorite = favorites.contains(item)
        HStack(spacing: 12) {
            if badges { CoinBadge(symbol: item, size: 30) }
            Text(item)
                .font(.body.weight(isSelected ? .semibold : .regular))
                .foregroundStyle(isSelected ? accent.primary : AppColors.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
            if isSelected {
                Image(systemName: "checkmark")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(accent.primary)
            }
            if let favoriteKind {
                Button {
                    toggle(favoriteKind, item)
                } label: {
                    Image(systemName: isFavorite ? "star.fill" : "star")
                        .font(.body)
                        .foregroundStyle(isFavorite ? accent.primary : AppColors.outline)
                        .frame(width: 36, height: 36)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L(isFavorite ? "favorite_remove" : "favorite_add"))
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { pick(item) }
        .listRowBackground(isSelected ? accent.container.opacity(0.45) : AppColors.containerLow)
        .contextMenu {
            if let favoriteKind {
                Button {
                    toggle(favoriteKind, item)
                } label: {
                    Label(L(isFavorite ? "favorite_remove" : "favorite_add"),
                          systemImage: isFavorite ? "star.slash" : "star")
                }
            }
        }
    }

    private func toggle(_ kind: FavoriteKind, _ item: String) {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        withAnimation(.snappy) { data.toggleFavorite(kind, item) }
    }

    private func pick(_ item: String) {
        UISelectionFeedbackGenerator().selectionChanged()
        onSelect(item)
        dismiss()
    }
}

// MARK: Paarliste aktualisieren

/// Paarliste der Börse: letzte Synchronisierung, Anzahl, Fehler — wie `SyncPairsDialog.kt`.
struct ExplorerSyncSheet: View {
    let marketName: String
    let info: MarketPairsInfo?
    let state: ExplorerPairsUpdateState
    let onSync: () -> Void
    @Environment(\.dismiss) var dismiss
    @Environment(\.appAccent) var accent

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: Spacing.md) {
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(AppFont.headline)
                    .foregroundStyle(accent.primary)
                    .frame(width: 48, height: 48)
                    .background(accent.container.opacity(0.6), in: Circle())
                    .symbolEffect(.pulse, isActive: state.inProgress)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("checker_add_dynamic_currency_pairs_dialog_title"))
                        .font(.title3.weight(.semibold))
                    Text(marketName)
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
            Text(L("checker_add_check_currency_empty_warning_summary"))
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)

            VStack(alignment: .leading, spacing: Spacing.xs) {
                Label(L("checker_add_dynamic_currency_pairs_dialog_last_sync", lastSyncText), systemImage: "clock")
                Label(L("sync_pairs_count", info?.count ?? 0), systemImage: "number")
                    .monospacedDigit()
            }
            .font(.subheadline)
            .foregroundStyle(AppColors.onSurface)
            .padding(Spacing.md)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: 16, style: .continuous))

            if let error = state.error {
                Text(L("check_error_generic_prefix", error))
                    .font(.footnote)
                    .foregroundStyle(AppColors.error)
            }

            Button(action: onSync) {
                HStack(spacing: 8) {
                    if state.inProgress {
                        ProgressView().tint(accent.onPrimary)
                    }
                    Text(L("checker_add_dynamic_currency_pairs_dialog_synchronize"))
                        .font(.headline)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.lg)
            }
            .buttonStyle(AccentButtonStyle())
            .disabled(state.inProgress)

            Button(L("action_close")) { dismiss() }
                .font(.headline)
                .foregroundStyle(accent.primary)
                .frame(maxWidth: .infinity)
        }
        .padding(24)
        // Oben angeschlagen: Fehlerzeile und Ladeanzeige verschieben den Inhalt nicht (fixe Höhe)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
        .presentationBackground(AppColors.background)
    }

    private var lastSyncText: String {
        guard let info, info.lastSyncDate > 0 else { return L("checker_add_dynamic_currency_pairs_dialog_last_sync_never") }
        return TickerView.sameDayTimeOrDate(info.lastSyncDate)
    }
}

// MARK: Rückmeldung

/// Snackbar unten: Text und optional «Ansehen» (öffnet die Merkliste) bzw. «Alarm setzen».
struct ExplorerSnackbar: View {
    @Binding var message: ExplorerMessage?
    let onView: () -> Void
    /// «Alarm setzen»: Alarme dieses Paars öffnen.
    var onAlarm: (Int64) -> Void = { _ in }
    @Environment(\.appAccent) var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver

    /// Mit «Alarm setzen» ~6 s (VoiceOver 12 s), lange Meldungen 5 s, sonst 3 s.
    private func nanos(_ m: ExplorerMessage) -> UInt64 {
        if m.alarmWatchId != nil { return voiceOver ? 12_000_000_000 : 6_000_000_000 }
        return m.long ? 5_000_000_000 : 3_000_000_000
    }

    var body: some View {
        if let message {
            HStack(spacing: 12) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(accent.primary)
                Text(message.text)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(AppColors.onToast)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let alarmId = message.alarmWatchId {
                    Button {
                        withAnimation { self.message = nil }
                        onAlarm(alarmId)
                    } label: {
                        Text(L("add_alarm_action"))
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(accent.primary)
                    }
                    .buttonStyle(.borderless)
                } else if message.showView {
                    Button {
                        onView()
                        withAnimation { self.message = nil }
                    } label: {
                        Text(L("action_view"))
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(accent.primary)
                    }
                    .buttonStyle(.borderless)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, Spacing.lg)
            .background(AppColors.toastBackground, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .shadow(color: AppColors.shadow.opacity(0.25), radius: 16, y: 6)
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
            // Weniger Bewegung: nur einblenden, nicht hereingleiten
            .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.move(edge: .bottom).combined(with: .opacity))
            .task(id: message.id) {
                try? await Task.sleep(nanoseconds: nanos(message))
                if !Task.isCancelled { withAnimation(.spring(duration: 0.35)) { self.message = nil } }
            }
            .gesture(DragGesture(minimumDistance: 10).onEnded { v in
                if v.translation.height > 10 { withAnimation { self.message = nil } }
            })
        }
    }
}

// MARK: HTTP-Protokoll

/// Entwickleroption: letzte Anfragen an die Börsen — wie `LogBox.kt`.
struct ExplorerLogBox: View {
    @Environment(\.appAccent) var accent

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let lines = HttpLog.all.suffix(10).map { $0.count > 400 ? String($0.prefix(400)) + "..." : $0 }
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Label("Logs", systemImage: "terminal")
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
                .foregroundStyle(accent.onPrimary)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(accent.primary)

                Text(lines.isEmpty ? "—" : lines.joined(separator: "\n"))
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundStyle(accent.onContainer)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                    .background(accent.container.opacity(0.7))
            }
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }
}
