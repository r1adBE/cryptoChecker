import SwiftUI

// Lange Schritte des CSV-Imports (Laden samt Vorschau, Speichern): Zustand mit Abbruch (`CsvImportRun`) und die Anzeige
// mit Fortschrittsbalken und «Abbrechen» (`CsvImportProgressOverlay`) — wie der Lade- bzw. Speicherdialog von
// `PortfolioCsvImportFlow` (Android). Eigene Datei, weil Vorschau-Blatt und Portfolio-Ansicht beide sie brauchen.

/// Laufender langer Schritt des CSV-Imports: Art, gedrosselter Fortschritt (aus dem Hintergrund gemeldet) und die
/// Aufgabe, die «Abbrechen» abbricht.
@MainActor
final class CsvImportRun: ObservableObject {
    enum Phase: Equatable {
        /// Datei lesen, Tageskurse laden, Vorschau umrechnen.
        case loading
        /// Neue Transaktionen anlegen.
        case saving
    }

    /// Angezeigter Schritt; nil = keiner (keine Anzeige).
    @Published private(set) var phase: Phase?
    /// Fortschritt «Zeile X von Y»; nil = ohne Zeilenzahl (Kreis statt Balken).
    @Published private(set) var progress: CsvImportProgress?
    private var task: Task<Void, Never>?
    /// Nummer des laufenden Schritts: späte Meldungen eines früheren Schritts zählen nicht.
    private var generation = 0

    /// Schritt `phase` beginnen (ein laufender wird abgebrochen). Liefert die Meldung des Fortschritts für die Arbeit im
    /// Hintergrund (`CsvProgressThrottle` ruft sie gedrosselt; übernommen wird auf dem MainActor).
    func begin(_ phase: Phase) -> @Sendable (CsvImportProgress) -> Void {
        task?.cancel()
        task = nil
        generation += 1
        let run = generation
        self.phase = phase
        progress = nil
        return { p in
            Task { @MainActor in self.apply(p, run: run) }
        }
    }

    /// Aufgabe des Schritts (für «Abbrechen»).
    func attach(_ task: Task<Void, Never>) {
        self.task = task
    }

    /// Schritt fertig: Anzeige weg.
    func end() {
        task = nil
        generation += 1
        phase = nil
        progress = nil
    }

    /// «Abbrechen»: Aufgabe abbrechen (sie verwirft ihr Ergebnis, gespeichert wird nichts) und Anzeige weg.
    func cancel() {
        task?.cancel()
        end()
    }

    private func apply(_ p: CsvImportProgress, run: Int) {
        guard run == generation, phase != nil else { return }
        // Meldungen kommen in eigenen Aufgaben: eine ältere nie über eine neuere schreiben
        if let current = progress, current.step == p.step, p.done < current.done { return }
        progress = p
    }
}

/// Fortschritt über der Ansicht: Titel, ohne Zeilenzahl Kreis und Text, sonst Balken «Zeile X von Y»; «Abbrechen».
/// VoiceOver: modal, Text und Zeile als ein Element mit dem Balken als Wert.
struct CsvImportProgressOverlay: View {
    let phase: CsvImportRun.Phase
    let progress: CsvImportProgress?
    let onCancel: () -> Void
    @Environment(\.appAccent) private var accent

    private var label: String {
        L(phase == .saving ? "portfolio_csv_import_saving" : "portfolio_csv_import_loading")
    }

    /// Nur ein Fortschritt des passenden Schritts mit Zeilen zählt (sonst Kreis).
    private var shown: CsvImportProgress? {
        let step: CsvImportStep = phase == .saving ? .SAVE : .PREVIEW
        guard let progress, progress.step == step, progress.total > 0 else { return nil }
        return progress
    }

    var body: some View {
        ZStack {
            AppColors.shadow.opacity(0.32).ignoresSafeArea()
            VStack(alignment: .leading, spacing: Spacing.lg) {
                Text(L("portfolio_csv_import_action"))
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
                content
                HStack {
                    Spacer()
                    Button(L("action_cancel"), action: onCancel)
                        .tint(accent.primary)
                }
            }
            .padding(Spacing.xl)
            .frame(maxWidth: 420)
            .background(RoundedRectangle(cornerRadius: Radius.sheet, style: .continuous).fill(AppColors.containerHigh))
            .padding(Spacing.xl)
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
        }
    }

    @ViewBuilder
    private var content: some View {
        if let p = shown {
            let line = L("portfolio_csv_import_progress", LocaleNumbers.integer(p.done, grouping: true),
                         LocaleNumbers.integer(p.total, grouping: true))
            VStack(alignment: .leading, spacing: Spacing.sm) {
                Text(label)
                ProgressView(value: p.fraction)
                    .tint(accent.primary)
                Text(line)
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .monospacedDigit()
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(label)
            .accessibilityValue(line)
        } else {
            HStack(spacing: Spacing.md) {
                ProgressView()
                    .tint(accent.primary)
                Text(label)
            }
            .accessibilityElement(children: .combine)
        }
    }
}
