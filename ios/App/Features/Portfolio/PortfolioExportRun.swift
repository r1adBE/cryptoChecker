import SwiftUI

// Ablauf eines Exports (Stichtag-Export und Arbeitsunterlage): Zustand, Abbrechen, «Erneut versuchen» und die Fehlerart
// der Meldung — wie `PortfolioExportRun.kt`. Anders als Android wählt iOS die Zieldatei erst nach dem Rechnen
// (`.fileExporter`): Während des Laufs liegt noch nichts auf der Platte, «Abbrechen» verwirft das Ergebnis.

/// Art eines Export-Fehlers mit dem Schlüssel ihres Texts (gleich in `export_errors.json` und Android):
/// Datei nicht schreibbar bzw. Speicherort weg, Kurse fehlen (offline, Börse), sonstiges.
enum ExportFailureKind: String, CaseIterable, Sendable {
    case FILE, PRICES, OTHER

    var key: String {
        switch self {
        case .FILE: return "portfolio_export_failed_file"
        case .PRICES: return "portfolio_export_failed_prices"
        case .OTHER: return "portfolio_export_failed"
        }
    }
}

/// Fehler beim Schreiben einer Exportdatei (Datei weg, keine Berechtigung, Speicher voll).
struct ExportWriteError: Error {
    let underlying: any Error
}

/// Zuordnung Fehler → Art (`ExportFailureKind`); zugrunde liegende Fehler (`NSUnderlyingErrorKey`) werden mitgeprüft.
/// Wie Android `ExportFailures.classify`.
enum ExportFailures {
    nonisolated static func classify(_ error: any Error) -> ExportFailureKind {
        var current: (any Error)? = error
        var depth = 0
        while let e = current, depth < 8 {
            if e is ExportWriteError { return .FILE }
            // Datei-Fehler von Foundation: `NSFileErrorMinimum` (0) … `NSFileErrorMaximum` (1023)
            if let cocoa = e as? CocoaError, (0...1023).contains(cocoa.code.rawValue) { return .FILE }
            if e is URLError { return .PRICES }
            current = (e as NSError).userInfo[NSUnderlyingErrorKey] as? any Error
            depth += 1
        }
        return .OTHER
    }
}

/// Ein Export zur Zeit: `start` rechnet den Text und bietet ihn danach über die Dateiablage an (`document`,
/// `exporting`); `cancel` bricht ab und verwirft das Ergebnis; `retry` wiederholt den letzten fehlgeschlagenen Export.
@MainActor
final class ExportRun: ObservableObject {
    /// Rechnet den Inhalt der Datei (auf dem MainActor gestartet, die Arbeit selbst läuft im Hintergrund).
    typealias Build = @MainActor () async throws -> String

    enum State: Equatable {
        case idle, running
        case failed(ExportFailureKind)
    }

    @Published private(set) var state: State = .idle
    /// Fertige Datei für die Dateiablage; nil = keine.
    @Published private(set) var document: CutoffCsvDocument?
    @Published private(set) var fileName = ""
    /// Dateiablage offen (Bindung für `.fileExporter`).
    @Published var exporting = false
    private var task: Task<Void, Never>?
    private var lastName = ""
    private var lastBuild: Build?

    var running: Bool { state == .running }

    /// Export beginnen (nicht, solange einer läuft). Gibt die Aufgabe zurück (für Tests).
    @discardableResult
    func start(fileName name: String, build: @escaping Build) -> Task<Void, Never>? {
        guard state != .running else { return nil }
        state = .running
        lastName = name
        lastBuild = build
        let run = Task { [weak self] in
            let result: Result<String, any Error>
            do {
                result = .success(try await build())
            } catch {
                result = .failure(error)
            }
            // «Abbrechen»: Ergebnis verwerfen, nichts anbieten
            guard let self, !Task.isCancelled else { return }
            self.finish(result, name: name)
        }
        task = run
        return run
    }

    private func finish(_ result: Result<String, any Error>, name: String) {
        task = nil
        switch result {
        case .success(let csv):
            guard let data = csv.data(using: .utf8) else {
                state = .failed(.OTHER)
                return
            }
            document = CutoffCsvDocument(data: data)
            fileName = name
            state = .idle
            exporting = true
        case .failure(let error):
            state = .failed(ExportFailures.classify(error))
        }
    }

    /// Laufenden Export abbrechen: Zustand gleich `idle`, ein spätes Ergebnis wird verworfen.
    func cancel() {
        guard state == .running else { return }
        task?.cancel()
        task = nil
        document = nil
        exporting = false
        state = .idle
    }

    /// Letzten fehlgeschlagenen Export wiederholen (neu rechnen, dann wieder die Dateiablage).
    func retry() {
        guard case .failed = state, let build = lastBuild else { return }
        state = .idle
        start(fileName: lastName, build: build)
    }

    /// Ergebnis der Dateiablage: true = gesichert. Fehler (nicht dein Abbrechen) als Meldung mit Art.
    func finishExport(_ result: Result<URL, any Error>) -> Bool {
        document = nil
        switch result {
        case .success:
            return true
        case .failure(let error):
            if (error as? CocoaError)?.code != .userCancelled {
                state = .failed(ExportFailures.classify(error))
            }
            return false
        }
    }

    /// Fehlermeldung geschlossen (neuer Export, Seite verlassen).
    func dismissFailure() {
        if case .failed = state { state = .idle }
    }
}

/// Export fehlgeschlagen: Text nach der Art und «Erneut versuchen»; VoiceOver sagt die Meldung an — wie Android
/// `ExportFailureRow`.
struct PortfolioExportFailureRow: View {
    let kind: ExportFailureKind
    let onRetry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(L(kind.key))
                .font(.subheadline)
                .foregroundStyle(AppColors.error)
                .fixedSize(horizontal: false, vertical: true)
            Button(L("action_retry"), action: onRetry)
                .font(.subheadline.weight(.semibold))
                .accessibilityIdentifier(UiTestTags.exportRetry)
        }
        .padding(.horizontal, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear {
            AccessibilityNotification.Announcement(L(kind.key)).post()
        }
    }
}

/// Stichtag-CSV als Export-Lauf (allgemeiner Stichtag-Export und Bestand der Arbeitsunterlage) — wie Android `build`.
/// Beobachtete Adressen (zählen im Portfolio) als eigene Zeilen; nicht lesbar: ohne Adressen, aber in der Datei
/// vermerkt und das Total «unvollständig» — nie still eine zu tiefe Summe.
enum CutoffExportJob {
    @MainActor
    static func build(transactions: [PortfolioTx], day: CutoffDay, currency: String, method: CostMethod,
                      header: [String] = [], zone: TimeZone = .current) -> ExportRun.Build {
        let texts = CutoffCsvTexts.localized(currency: currency)
        let watched = WatchedAddressStore.loadChecked()
        let addresses = watched.file.addresses
        let addressesUnreadable: Bool = !watched.intact
        return {
            await PortfolioCutoffExporter.build(transactions: transactions, day: day, currency: currency, texts: texts,
                                                method: method, addresses: addresses,
                                                addressesUnreadable: addressesUnreadable, header: header, zone: zone)
        }
    }
}
