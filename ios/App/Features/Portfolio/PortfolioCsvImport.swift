import Accessibility
import SwiftUI
import UniformTypeIdentifiers

/// Vorschau des CSV-Imports: Zähler (neu, doppelt, übersprungen mit Grund, ohne Tageskurs/Preis), die ersten
/// Zeilen und «Importieren» — wie `CsvImportPreviewSheet` (Android).
@MainActor
struct PortfolioCsvImportSheet: View {
    let loaded: CsvImportLoaded
    /// Anzahl angelegter Transaktionen; `enabled` = das Portfolio wurde dabei eingeschaltet.
    let onImported: (_ count: Int, _ enabled: Bool) -> Void
    /// Schreiben gescheitert, nachdem das Blatt schon zu war («Abbrechen» während des Schreibens): der Portfolio-Screen
    /// meldet es, statt dass der Fehler still bleibt.
    let onSaveFailed: () -> Void

    @State var preview: CsvImportPreview
    /// Vorschläge je Auszahlung (`CsvImportMapping.proposeWithdrawals`): Vorauswahl und Ziel der Umbuchung — wie Android.
    @State var proposals: [CsvWithdrawalProposal] = []
    /// Wahl je Auszahlung (`CsvImportTx.importKey`); «Importieren» erst, wenn jede gewählt ist.
    @State var choices: [String: CsvWithdrawalChoice] = [:]
    /// Mögliche Umbuchungen (Auszahlung + Einzahlung desselben Coins, `CsvTransferMatch.candidates`) — wie Android.
    @State var links: [CsvTransferLink] = []
    /// Gewählte Umbuchungen (`CsvTransferLink.key`); eindeutige sind vorgewählt.
    @State var linked: Set<String> = []
    /// Gewählte Zeitzone der Zeiten ohne Versatz (nil = Regel: Swissquote Europe/Zurich, sonst Gerät).
    @State var zone: String? = nil
    /// Gewählte Reihenfolge Tag/Monat bei mehrdeutigen Daten (nil = Standard, Tag zuerst) — wie Android `setDateOrder`.
    @State var dateOrder: CsvDateOrder? = nil
    /// Gewähltes Dezimalzeichen bei mehrdeutigen Zahlen («42,000»; nil = Standard: Zeichen = Dezimal) — wie Android
    /// `setDecimalMark`.
    @State var decimalMark: Character? = nil
    /// Tageskurse und USDT/USD-Schlüsse nach einer anderen Reihenfolge Tag/Monat neu geladen (nil = die beim Laden).
    @State private var rates: CsvImportRates? = nil
    /// Tag/Monat bzw. Dezimalzeichen geändert, die Tageskurse dazu aber noch nicht übernommen: die nächste Neuzuordnung
    /// lädt sie (auch wenn inzwischen nur die Zeitzone gewechselt hat und die frühere Neuzuordnung verworfen wird).
    @State var ratesStale = false
    /// Schreiben gescheitert: nichts importiert — Meldung statt «0 importiert» (wie Android `CsvImportState.SaveFailed`).
    @State private var saveFailed = false
    /// Lesart, mit der die gezeigte Vorschau gebaut wurde (die beim Laden: Regel). Wird nur zusammen mit
    /// `preview` gesetzt (nur vom letzten Neu-Lesen, `remapGeneration`) — wie Android `Preview.choice`.
    @State var builtWith = CsvReadChoice()
    /// Nummer des letzten Neu-Lesens; nur dessen Ergebnis zählt (wie Android `rereadGeneration`).
    @State var remapGeneration = 0
    /// Lesart geändert, Datei wird neu gelesen — «Importieren» aus, Vorschau gedimmt (wie Android).
    @State var rereading = false
    /// Gewählte Umbuchungen angewendet, gemerkt statt bei jeder Auswertung von `body` neu
    /// (`updatePlan` nach jeder Änderung von Vorschau, Kandidaten oder Wahl) — wie Android `remember`.
    @State var plan: CsvTransferPlan
    /// Auszahlungen in `plan` (gemerkt).
    @State var withdrawals: [CsvImportTx] = []
    /// Alle Umbuchungen bzw. Auszahlungen im eigenen Blatt (Lazy-Liste).
    @State var showAllLinks = false
    @State var showAllWithdrawals = false
    /// Speichern im Hintergrund mit Fortschritt und «Abbrechen» (`CsvImportRun`) — wie Android `CsvImportState.Saving`.
    @StateObject private var saveRun = CsvImportRun()

    init(loaded: CsvImportLoaded, onImported: @escaping (_ count: Int, _ enabled: Bool) -> Void,
         onSaveFailed: @escaping () -> Void) {
        self.loaded = loaded
        self.onImported = onImported
        self.onSaveFailed = onSaveFailed
        _preview = State(initialValue: loaded.preview)
        let plan = CsvTransferMatch.apply(loaded.preview.transactions, links: [], chosen: [])
        _plan = State(initialValue: plan)
        _withdrawals = State(initialValue: plan.transactions.filter { $0.type == .WITHDRAWAL })
    }

    /// Aktuell gewählte Lesart.
    var chosenRead: CsvReadChoice { CsvReadChoice(zone: zone, dateOrder: dateOrder, decimalMark: decimalMark) }

    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) var accent
    @Environment(\.dismiss) private var dismiss

    private static let previewRows = 8

    /// Gewählte Umbuchungen angewendet: so wird gespeichert (Auszahlungen darin brauchen keine Wahl mehr) — wie Android.
    /// Nur nach Änderungen neu berechnet.
    func updatePlan() {
        let next = CsvTransferMatch.apply(preview.transactions, links: links, chosen: linked)
        plan = next
        withdrawals = next.transactions.filter { $0.type == .WITHDRAWAL }
    }

    var body: some View {
        let txs = plan.transactions
        let total = txs.count + plan.storedUpdates.count
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.sm) {
                    Text(L("portfolio_csv_import_format", PortfolioCsvImporter.formatName(preview.format)))
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                    // Erkannte Spalten der Kopfzeile — wie Android
                    if !preview.columns.isEmpty {
                        hint(L("portfolio_csv_import_columns", preview.columns.joined(separator: ", ")))
                    }
                    // Neue Lesart wird gelesen — Hinweis, Rest gedimmt
                    if rereading {
                        Text(L("portfolio_csv_import_rereading"))
                            .font(.subheadline)
                            .foregroundStyle(accent.primary)
                    }
                    Text(L("portfolio_csv_import_new", count: txs.count))
                        .font(.body.weight(.semibold))
                        .opacity(rereading ? 0.4 : 1)
                    if preview.duplicates > 0 {
                        Text(L("portfolio_csv_import_duplicates", count: preview.duplicates))
                    }
                    if !preview.skipped.isEmpty {
                        Text(L("portfolio_csv_import_skipped", count: preview.skipped.count))
                        ForEach(Array(preview.skipped.prefix(Self.previewRows).enumerated()), id: \.offset) { _, skip in
                            Text(L("portfolio_csv_import_line", skip.line, L(PortfolioCsvImporter.skipKey(skip.reason))))
                                .font(.caption)
                                .foregroundStyle(AppColors.onSurfaceVariant)
                                .padding(.leading, 12)
                        }
                    }
                    if !preview.missingFx.isEmpty {
                        hint(L("portfolio_csv_import_missing_fx", preview.missingFx.map(String.init).joined(separator: ", ")))
                    }
                    // Fiat-Preise ohne USDT/USD-Tagesschluss: mit USDT = 1 umgerechnet (Vermerk wie im Portfolio)
                    if !preview.usdtApproximate.isEmpty {
                        hint(L("portfolio_usdt_assumed") + " ("
                             + preview.usdtApproximate.map(String.init).joined(separator: ", ") + ")")
                    }
                    if !preview.missingPrice.isEmpty {
                        hint(L("portfolio_csv_import_missing_price", preview.missingPrice.map(String.init).joined(separator: ", ")))
                    }
                    // Kauf/Verkauf gegen Stablecoin als Tausch gebucht: der Stablecoin erscheint im Bestand — wie Android
                    if preview.stableSwaps {
                        hint(L("portfolio_csv_import_stable_swaps"))
                    }
                    // Zeiten ohne Zeitzone in der Datei: verwendete Zeitzone zeigen und wählen lassen — wie Android
                    if preview.localTimes { zonePicker }
                    // Nur mehrdeutige Daten («03/04/2024»): Tag/Monat wählen lassen statt still Tag zuerst — wie Android
                    if preview.dateOrderAmbiguous { dateOrderPicker }
                    // Kein Dezimalzeichen erkennbar, aber «42,000»: 42000 oder 42 wählen lassen — wie Android
                    if let sample = preview.decimalSample { decimalPicker(sample) }
                    VStack(alignment: .leading, spacing: Spacing.sm) {
                        // Auszahlung + Einzahlung desselben Coins (Datei oder früherer Import): als eine Umbuchung buchen
                        if !links.isEmpty { transferLinks }
                        // Auszahlungen in der Datei: je Zeile wählen — eigene Wallet (Umbuchung) oder verlässt das Portfolio
                        if !withdrawals.isEmpty {
                            withdrawalChoices(withdrawals)
                        }
                        if !txs.isEmpty {
                            Divider().padding(.vertical, Spacing.xs)
                            ForEach(Array(txs.prefix(Self.previewRows).enumerated()), id: \.offset) { _, tx in
                                previewRow(tx)
                            }
                            if txs.count > Self.previewRows {
                                hint(L("portfolio_csv_import_more", txs.count - Self.previewRows))
                            }
                        }
                    }
                    // Gedimmt, solange neu gelesen wird
                    .opacity(rereading ? 0.4 : 1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, Spacing.lg)
                .padding(.vertical, 8)
            }
            .background(AppColors.background.ignoresSafeArea())
            .navigationTitle(L("portfolio_csv_import_action"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    // Auch während des Speicherns: bis zum Schreiben abbrechen, nichts gespeichert
                    Button(L("action_cancel")) { saveRun.cancel(); dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("portfolio_csv_import_confirm", total), action: confirm)
                        .accessibilityIdentifier(UiTestTags.csvImportConfirm)
                        .fontWeight(.semibold)
                        .disabled(total == 0 || saveRun.phase != nil
                                  || !CsvImportMapping.allWithdrawalsDecided(txs, choices: choices)
                                  || !CsvReadChoice.canConfirm(rereading: rereading, builtWith: builtWith, chosen: chosenRead))
                }
            }
        }
        .tint(accent.primary)
        // Speichern: Fortschritt «Zeile X von Y» mit «Abbrechen» über dem Blatt; nicht wegwischen (dann «Abbrechen»)
        .overlay {
            if let phase = saveRun.phase {
                CsvImportProgressOverlay(phase: phase, progress: saveRun.progress) {
                    saveRun.cancel()
                    dismiss()
                }
            }
        }
        .interactiveDismissDisabled(saveRun.phase != nil)
        // Vorschläge wurden beim Laden im Hintergrund gerechnet
        .onAppear { applyProposals(loaded.proposals) }
        // Screenreader hört, dass neu gelesen wird
        .onChange(of: rereading) { _, now in
            if now { AccessibilityNotification.Announcement(L("portfolio_csv_import_rereading")).post() }
        }
        .alert(L("portfolio_csv_import_action"), isPresented: $saveFailed) {
            Button(L("action_close"), role: .cancel) { dismiss() }
        } message: {
            Text(L("portfolio_csv_import_failed"))
        }
    }

    /// Vorschläge übernehmen (`CsvImportProposals`, im Hintergrund gerechnet): je Auszahlung (zählende beobachtete Adresse
    /// mit passender Änderung → Umbuchung, sonst keine Vorauswahl); eine schon getroffene Wahl bleibt. Dazu mögliche
    /// Umbuchungen zwischen Börsen in der Datei und gegen gespeicherte Importe; schon gezeigte behalten ihre Wahl
    /// (`CsvTransferMatch.carryOver`) — wie Android `applyProposals`.
    private func applyProposals(_ p: CsvImportProposals) {
        var next: [String: CsvWithdrawalChoice] = [:]
        for proposal in p.list {
            if let c = choices[proposal.importKey] ?? proposal.choice { next[proposal.importKey] = c }
        }
        proposals = p.list
        choices = next
        linked = CsvTransferMatch.carryOver(p.links, seen: Set(links.map(\.key)), chosen: linked)
        links = p.links
        updatePlan()
    }

    /// Datei mit der gewählten Zeitzone, Reihenfolge Tag/Monat und dem Dezimalzeichen neu lesen und zuordnen — einmal
    /// lesen (bis 16 MB) und zuordnen abseits des Haupt-Threads (`PortfolioCsvImporter.reread`); Tageskurse und
    /// USDT/USD-Schlüsse nur neu laden, wenn sie veraltet sind (`ratesStale`) oder ein Tag ausserhalb der geladenen liegt
    /// (`CsvImportRateDays.cover`), sonst bleiben die bekannten. Die Vorschau
    /// wird auf dem MainActor übernommen; inzwischen anders gewählt: verwerfen.
    func remap() {
        let text = loaded.text
        let existingKeys = loaded.existingKeys
        let known = rates ?? CsvImportRates(fx: loaded.rates, usdtUsd: loaded.usdtUsd,
                                            days: CsvImportRateDays.of(loaded.result.rows, zone: loaded.result.fxZone))
        let deviceZone = TimeZone.current.identifier
        let choice = chosenRead
        let refetch = ratesStale
        let addresses = data.watchedAddresses
        let stored = data.portfolio.compactMap(PortfolioCsvImporter.storedMove)
        // Bis die Vorschau zur Wahl passt, kein «Importieren»
        rereading = true
        remapGeneration += 1
        let generation = remapGeneration
        typealias Remapped = (preview: CsvImportPreview, rates: CsvImportRates, proposals: CsvImportProposals)
        Task { @MainActor in
            let computed = await Task.detached(priority: .userInitiated) { () -> Remapped in
                let read = await PortfolioCsvImporter.reread(text, choice: choice, deviceZone: deviceZone,
                                                             existingKeys: existingKeys, known: known, refetch: refetch)
                return (preview: read.preview, rates: read.rates,
                        proposals: CsvImportProposals.of(read.preview, addresses: addresses, stored: stored))
            }.value
            // Inzwischen anders gewählt bzw. neuer angestossen: verwerfen (die neuere Neuzuordnung übernimmt)
            guard generation == remapGeneration, chosenRead == choice else { return }
            // Neu geladen (veraltet oder ein Tag ausserhalb der geladenen) oder unverändert: gilt fürs nächste Neu-Lesen
            rates = computed.rates
            if refetch { ratesStale = false }
            preview = computed.preview
            builtWith = choice
            rereading = false
            applyProposals(computed.proposals)
        }
    }

    func hint(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AppColors.onSurfaceVariant)
    }

    /// «12.06.2024 · Kauf» / «0.0028 BTC · 95’287.63 USDT» — zwei Zeilen, je bis zu drei Zeilen hoch
    /// (vorher eine Zeile mit lineLimit(1): bei grosser Schrift fehlten Menge und Preis) — wie Android `PreviewRow`.
    func previewRow(_ tx: CsvImportTx) -> some View {
        var amounts = [PortfolioFormat.exactAmount(tx.amount, tx.coin)]
        if let p = tx.priceUsdt { amounts.append(PortfolioFormat.price(PortfolioDecimal.double(p))) }
        return VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: PortfolioFormat.date(tx.time) + " · " + PortfolioFormat.typeLabel(tx.type))
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)
            Text(verbatim: amounts.joined(separator: " · "))
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(.subheadline.monospacedDigit())
        .accessibilityElement(children: .combine)
    }

    private func confirm() {
        // Nicht während des Neu-Lesens und nur mit der Vorschau der aktuellen Lesart
        guard CsvReadChoice.canConfirm(rereading: rereading, builtWith: builtWith, chosen: chosenRead) else { return }
        let plan = CsvTransferMatch.apply(preview.transactions, links: links, chosen: linked)
        // Jede Auszahlung (ohne verknüpfte Umbuchung) braucht eine Wahl (der Knopf ist sonst aus) — wie Android
        guard CsvImportMapping.allWithdrawalsDecided(plan.transactions, choices: choices) else { return }
        var destinations: [String: String?] = [:]
        for p in proposals { destinations[p.importKey] = p.transferTo }
        // Datei «neueste zuerst»: älteste zuerst speichern (Id folgt der Zeit) — wie Android. Neue Zeilen, die gegen eine
        // gespeicherte Seite aufgehen, als Ersatz dabei: gespeichert nur, wenn die Verknüpfung beim Speichern nicht geht
        let rows = CsvTransferMatch.withFallbacks(preview.transactions, plan: plan)
        let txs: [CsvImportTx] = CsvImportMapping.insertOrder(
            CsvImportMapping.applyWithdrawalChoices(rows, choices: choices, destinations: destinations))
        // Kennung dieses Importlaufs («Import rückgängig»): Format, Zeit, Hash der Zeilenschlüssel — wie Android
        let batch = PortfolioImportBatch.newId(format: preview.format.rawValue,
                                               time: Int64(Date().timeIntervalSince1970 * 1000),
                                               keys: txs.map(\.importKey))
        let list = txs.map(PortfolioCsvImporter.tx)
        let storedUpdates = plan.storedUpdates
        let fallbacks = CsvTransferMatch.fallbacks(plan)
        // Zusammenstellen im Hintergrund mit Fortschritt; «Abbrechen» bis zum Schreiben lässt alles unverändert
        let report = saveRun.begin(.saving)
        saveRun.attach(Task {
            // Speichern zu Ende führen, auch wenn die App währenddessen in den Hintergrund geht
            let backgroundTask = BackgroundTaskGuard.begin("csvImportSave")
            defer { backgroundTask.end() }
            // Anlegen und Verknüpfen in EINEM Schreibvorgang — wie Android `insertImportedLinked`
            let written = await data.insertImportedPortfolioTxs(list, batch: batch, links: storedUpdates,
                                                                fallbacks: fallbacks, progress: report)
            // «Abbrechen» gedrückt: das Blatt ist schon zu
            let closed = Task.isCancelled
            // Abgebrochen vor dem Schreiben: nichts gespeichert. Kam «Abbrechen» erst während des Schreibens (im
            // Hintergrund), gilt der Import: melden wie sonst
            if written == .cancelled && closed { return }
            saveRun.end()
            switch written {
            case .written(let inserted, let linked):
                finishSave(inserted: inserted, linked: linked)
            // Nicht geschrieben: Grund nennen statt «0 importiert» — wie Android
            case .failed: reportSaveFailed(closed: closed)
            case .cancelled: break
            }
        })
    }

    /// Nach dem Schreiben (angelegt und verknüpft, nicht mehr abbrechbar): Portfolio bei Bedarf einschalten, melden und
    /// schliessen.
    private func finishSave(inserted: Int, linked: Int) {
        // Erste Transaktion mit ausgeschaltetem Portfolio: einschalten (Tab erscheint), wie von Hand — nur neu angelegte
        var enabled = false
        if inserted > 0, PortfolioOff.enableOnSave(isNew: true, portfolioEnabled: data.settings.portfolioEnabled) {
            data.settings.portfolioEnabled = true
            enabled = true
        }
        onImported(inserted + linked, enabled)
        dismiss()
    }

    /// Schreiben gescheitert: Meldung im Blatt; ist es schon zu (`closed`), meldet der Portfolio-Screen.
    private func reportSaveFailed(closed: Bool) {
        if closed { onSaveFailed() } else { saveFailed = true }
    }
}
