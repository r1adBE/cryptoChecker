import SwiftUI

/// Texte des Alarm-Protokolls (`AlarmLog`): Ergebnis, Schwelle, gesehener Kurs und die Zeile «Zuletzt geprüft» im
/// Alarm-Editor. Wie `AlarmLogTexts.kt`.
@MainActor
enum AlarmLogTexts {

    /// Ergebnis in klaren Worten, z. B. «Nicht ausgelöst – Kurs veraltet (Stand 11:40)».
    static func outcome(_ entry: AlarmLogEntry) -> String {
        switch entry.outcome {
        case .FIRED: return L("alarm_outcome_fired")
        case .NOT_REACHED: return L("alarm_outcome_not_reached")
        case .ALREADY_TRIGGERED: return L("alarm_outcome_already_triggered")
        case .COOLDOWN: return L("alarm_outcome_cooldown")
        case .STALE: return L("alarm_outcome_stale", entry.exchangeTime.map { time($0) } ?? "—")
        case .MARK_PRICE: return L("alarm_outcome_mark_price")
        case .LOW_LIQUIDITY: return L("alarm_outcome_low_liquidity")
        case .QUIET_HOURS: return L("alarm_outcome_quiet_hours")
        case .NOTIFICATIONS_OFF: return L("alarm_outcome_notifications_off")
        case .NOT_SHOWN: return L("alarm_outcome_not_shown")
        case .NO_RATE: return L("alarm_outcome_no_rate")
        case .NO_PRICE: return L("alarm_outcome_no_price")
        }
    }

    /// Schwelle wie in der Alarm-Übersicht, z. B. «Über 100’000 CHF».
    static func threshold(_ entry: AlarmLogEntry) -> String {
        let alarm = Alarm(id: entry.alarmId, watchId: 0, condition: entry.condition, threshold: entry.threshold,
                          windowHours: entry.windowHours, currency: entry.currency)
        let suffix = entry.condition.isPriceThreshold && entry.currency == nil ? " \(entry.quote)" : ""
        return AlarmTexts.describe(alarm) + suffix
    }

    /// Börse und gesehener Kurs, z. B. «Kraken 99’800 CHF» (Schwelle in eigener Währung: umgerechnet); ohne Kurs nur
    /// die Börse.
    static func seen(_ entry: AlarmLogEntry) -> String {
        let price: String?
        if let currency = entry.currency, let shown = entry.shownPrice {
            price = PriceFormat.priceWithCurrency(shown, currency)
        } else if let value = entry.price {
            price = PriceFormat.priceWithCurrency(value, entry.quote)
        } else {
            price = nil
        }
        let exchange = BidiText.isolate(entry.exchange)
        return price.map { "\(exchange) \($0)" } ?? exchange
    }

    /// Zeit: heute nur die Uhrzeit, sonst mit Datum; mehrmals hintereinander als «von–bis».
    static func time(_ entry: AlarmLogEntry) -> String {
        entry.until > entry.at ? "\(time(entry.at))–\(time(entry.until))" : time(entry.at)
    }

    static func time(_ millis: Int64) -> String {
        AlarmLastCheckedLine.when(millis, now: Date())
    }

    /// «Zuletzt geprüft» im Editor: «14:02 · Kraken 99’800 CHF · Marke nicht erreicht».
    static func lastCheckLine(_ entry: AlarmLogEntry) -> String {
        L("alarm_last_check", [time(entry.at), seen(entry), outcome(entry)].joined(separator: " · "))
    }

    /// Ganze Zeile des Protokolls für VoiceOver.
    static func rowDescription(_ entry: AlarmLogEntry) -> String {
        [time(entry), entry.pair, seen(entry), L("alarm_log_threshold", threshold(entry)), outcome(entry)]
            .joined(separator: ", ")
    }
}

/// Seite «Alarm-Protokoll» — erreichbar aus «Alle Alarme» und neben «Letzte Prüfungen». Neueste zuerst; «Protokoll
/// löschen» mit Rückfrage. Wie `AlarmLogScreen.kt`.
@MainActor
struct AlarmLogScreen: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.scenePhase) private var scenePhase
    @State private var events: [AlarmLogEntry] = []
    @State private var askClear = false

    var body: some View {
        Group {
            if events.isEmpty {
                ScrollView {
                    EmptyStateView(systemImage: "list.bullet.rectangle",
                                   title: L("alarm_log_empty_title"),
                                   message: L("alarm_log_empty_message"))
                        .containerRelativeFrame(.vertical, alignment: .center)
                }
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: Spacing.sm) {
                        Text(L("alarm_log_intro"))
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 4)
                            .padding(.bottom, Spacing.xs)
                        ForEach(Array(events.enumerated()), id: \.offset) { _, entry in
                            AlarmLogRow(entry: entry)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .padding(.bottom, 24)
                    .readableContentWidth()
                }
            }
        }
        .background(AppColors.background.ignoresSafeArea())
        .navigationTitle(L("alarm_log_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !events.isEmpty {
                ToolbarItem(placement: .primaryAction) {
                    Button(role: .destructive) {
                        askClear = true
                    } label: {
                        Image(systemName: "trash")
                    }
                    .accessibilityLabel(L("alarm_log_clear"))
                }
            }
        }
        .confirmationDialog(L("alarm_log_clear_title"), isPresented: $askClear, titleVisibility: .visible) {
            Button(L("alarm_log_clear"), role: .destructive) {
                Task {
                    await Task.detached(priority: .utility) { AlarmLogStore.clearEvents() }.value
                    await reload()
                }
            }
            Button(L("action_cancel"), role: .cancel) {}
        } message: {
            Text(L("alarm_log_clear_message"))
        }
        .task { await reload(retaining: Set(data.snapshot.alarms.map(\.id))) }
        // Zurück in der App (Hintergrund-Aufgabe oder Widget lief inzwischen): neu lesen
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await reload() } }
        }
    }

    /// Verschlüsselte Datei abseits des Hauptfadens lesen; `retaining`: letzte Prüfungen von Alarmen, die mit ihrem
    /// Paar gelöscht wurden, dabei aufräumen.
    private func reload(retaining alarmIds: Set<Int64>? = nil) async {
        events = await Task.detached(priority: .utility) { () -> [AlarmLogEntry] in
            if let alarmIds { AlarmLogStore.retainAlarms(alarmIds) }
            return AlarmLogStore.load().events
        }.value
    }
}

/// Eine Zeile: Zeit, Paar und Börse, gesehener Kurs, Schwelle, Ergebnis — für VoiceOver ein Element.
@MainActor
private struct AlarmLogRow: View {
    let entry: AlarmLogEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(AlarmLogTexts.time(entry)) · \(BidiText.isolate(entry.pair))")
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .foregroundStyle(AppColors.onSurface)
            Text("\(AlarmLogTexts.seen(entry)) · \(AlarmLogTexts.threshold(entry))")
                .font(.caption.monospacedDigit())
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            Text(AlarmLogTexts.outcome(entry))
                .font(.subheadline)
                .foregroundStyle(entry.outcome == .FIRED ? AppColors.onSurface : AppColors.warningText)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, Spacing.xs)
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .appCard()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(AlarmLogTexts.rowDescription(entry))
    }
}
