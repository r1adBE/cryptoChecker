import SwiftUI
import UniformTypeIdentifiers

/// Vorschau des CSV-Imports: Zähler (neu, doppelt, übersprungen mit Grund, ohne Tageskurs/Preis), die ersten
/// Zeilen und «Importieren» — wie `CsvImportPreviewSheet` (Android).
@MainActor
struct PortfolioCsvImportSheet: View {
    let loaded: CsvImportLoaded
    /// Anzahl angelegter Transaktionen; `enabled` = das Portfolio wurde dabei eingeschaltet.
    let onImported: (_ count: Int, _ enabled: Bool) -> Void

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
    /// Tageskurse nach einer anderen Reihenfolge Tag/Monat neu geladen (nil = die beim Laden).
    @State private var rates: [String: [(day: Int, rate: Double)]]? = nil
    /// Tag/Monat bzw. Dezimalzeichen geändert, die Tageskurse dazu aber noch nicht übernommen: die nächste Neuzuordnung
    /// lädt sie (auch wenn inzwischen nur die Zeitzone gewechselt hat und die frühere Neuzuordnung verworfen wird).
    @State var ratesStale = false
    /// Schreiben gescheitert: nichts importiert — Meldung statt «0 importiert» (wie Android `CsvImportState.SaveFailed`).
    @State private var saveFailed = false

    init(loaded: CsvImportLoaded, onImported: @escaping (_ count: Int, _ enabled: Bool) -> Void) {
        self.loaded = loaded
        self.onImported = onImported
        _preview = State(initialValue: loaded.preview)
    }

    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) var accent
    @Environment(\.dismiss) private var dismiss

    private static let previewRows = 8

    /// Gewählte Umbuchungen angewendet: so wird gespeichert (Auszahlungen darin brauchen keine Wahl mehr) — wie Android.
    private var plan: CsvTransferPlan { CsvTransferMatch.apply(preview.transactions, links: links, chosen: linked) }

    var body: some View {
        let plan = self.plan
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
                    Text(L("portfolio_csv_import_new", count: txs.count))
                        .font(.body.weight(.semibold))
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
                    // Auszahlung + Einzahlung desselben Coins (Datei oder früherer Import): als eine Umbuchung buchen
                    if !links.isEmpty { transferLinks }
                    // Auszahlungen in der Datei: je Zeile wählen — eigene Wallet (Umbuchung) oder verlässt das Portfolio
                    let withdrawals: [CsvImportTx] = txs.filter { $0.type == .WITHDRAWAL }
                    if !withdrawals.isEmpty {
                        withdrawalChoices(withdrawals)
                    }
                    if !txs.isEmpty {
                        Divider().padding(.vertical, Spacing.xs)
                        ForEach(Array(txs.prefix(Self.previewRows).enumerated()), id: \.offset) { _, tx in
                            Text(rowText(tx))
                                .font(.subheadline.monospacedDigit())
                                .lineLimit(1)
                        }
                        if txs.count > Self.previewRows {
                            hint(L("portfolio_csv_import_more", txs.count - Self.previewRows))
                        }
                    }
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
                    Button(L("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("portfolio_csv_import_confirm", total), action: confirm)
                        .accessibilityIdentifier(UiTestTags.csvImportConfirm)
                        .fontWeight(.semibold)
                        .disabled(total == 0 || !CsvImportMapping.allWithdrawalsDecided(txs, choices: choices))
                }
            }
        }
        .tint(accent.primary)
        .onAppear { propose() }
        .alert(L("portfolio_csv_import_action"), isPresented: $saveFailed) {
            Button(L("action_close"), role: .cancel) { dismiss() }
        } message: {
            Text(L("portfolio_csv_import_failed"))
        }
    }

    /// Vorschläge je Auszahlung (zählende beobachtete Adresse mit passender Änderung → Umbuchung, sonst keine
    /// Vorauswahl); eine schon getroffene Wahl bleibt. Dazu mögliche Umbuchungen zwischen Börsen in der Datei und gegen
    /// gespeicherte Importe; schon gezeigte behalten ihre Wahl (`CsvTransferMatch.carryOver`) — wie Android `propose`.
    private func propose() {
        let list = CsvImportMapping.proposeWithdrawals(preview.transactions, addresses: data.watchedAddresses)
        var next: [String: CsvWithdrawalChoice] = [:]
        for p in list {
            if let c = choices[p.importKey] ?? p.choice { next[p.importKey] = c }
        }
        proposals = list
        choices = next
        let candidates = CsvTransferMatch.candidates(preview.transactions, place: preview.format.exchangeName,
                                                     stored: data.portfolio.compactMap(PortfolioCsvImporter.storedMove))
        linked = CsvTransferMatch.carryOver(candidates, seen: Set(links.map(\.key)), chosen: linked)
        links = candidates
    }

    /// Datei mit der gewählten Zeitzone, Reihenfolge Tag/Monat und dem Dezimalzeichen neu lesen und zuordnen — einmal
    /// lesen (bis 16 MB) und zuordnen abseits des Haupt-Threads; Tageskurse nur neu laden, wenn sie veraltet sind
    /// (`ratesStale`). Die Vorschau wird auf dem MainActor übernommen; inzwischen anders gewählt: verwerfen.
    func remap() {
        let text = loaded.text
        let existingKeys = loaded.existingKeys
        let knownRates = rates ?? loaded.rates
        let deviceZone = TimeZone.current.identifier
        let chosenZone = zone
        let order = dateOrder
        let mark = decimalMark
        let refetch = ratesStale
        Task { @MainActor in
            let computed = await Task.detached(priority: .userInitiated) {
                () -> (preview: CsvImportPreview, rates: [String: [(day: Int, rate: Double)]]) in
                let result = CsvImportParser.parse(text, deviceZone: deviceZone, zone: chosenZone, dateOrder: order,
                                                   decimalMark: mark)
                let fresh = refetch ? await PortfolioCsvImporter.fetchRates(result) : knownRates
                let mapped = PortfolioCsvImporter.mapPreview(result, existingKeys: existingKeys, rates: fresh,
                                                             withdrawalsAsTransfer: false)
                return (preview: mapped, rates: fresh)
            }.value
            // Inzwischen anders gewählt: verwerfen (die neuere Neuzuordnung übernimmt)
            guard zone == chosenZone, dateOrder == order, decimalMark == mark else { return }
            if refetch {
                rates = computed.rates
                ratesStale = false
            }
            preview = computed.preview
            propose()
        }
    }

    func hint(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AppColors.onSurfaceVariant)
    }

    /// «12.06.2024 · Kauf · 0.0028 BTC · 95’287.63 USDT».
    func rowText(_ tx: CsvImportTx) -> String {
        var parts = [PortfolioFormat.date(tx.time), PortfolioFormat.typeLabel(tx.type),
                     PortfolioFormat.exactAmount(tx.amount, tx.coin)]
        if let p = tx.priceUsdt { parts.append(PortfolioFormat.price(PortfolioDecimal.double(p))) }
        return parts.joined(separator: " · ")
    }

    private func confirm() {
        let plan = self.plan
        // Jede Auszahlung (ohne verknüpfte Umbuchung) braucht eine Wahl (der Knopf ist sonst aus) — wie Android
        guard CsvImportMapping.allWithdrawalsDecided(plan.transactions, choices: choices) else { return }
        var destinations: [String: String?] = [:]
        for p in proposals { destinations[p.importKey] = p.transferTo }
        // Datei «neueste zuerst»: älteste zuerst speichern (Id folgt der Zeit) — wie Android
        let txs: [CsvImportTx] = CsvImportMapping.insertOrder(
            CsvImportMapping.applyWithdrawalChoices(plan.transactions, choices: choices, destinations: destinations))
        // Kennung dieses Importlaufs («Import rückgängig»): Format, Zeit, Hash der Zeilenschlüssel — wie Android
        let batch = PortfolioImportBatch.newId(format: preview.format.rawValue,
                                               time: Int64(Date().timeIntervalSince1970 * 1000),
                                               keys: txs.map(\.importKey))
        // Nicht geschrieben (nil): Grund nennen statt «0 importiert» — wie Android
        guard let inserted = data.insertImportedPortfolioTxs(txs.map(PortfolioCsvImporter.tx), batch: batch) else {
            saveFailed = true
            return
        }
        // Gespeicherte Seiten verknüpfter Umbuchungen: neu gelesen, inzwischen gelöscht oder verknüpft → ausgelassen;
        // die Transaktion wechselt in diesen Importlauf («Import rückgängig» setzt sie zurück) — wie Android `linkStored`
        var linkedStored = 0
        var linkFailed = false
        for u in plan.storedUpdates {
            guard let current = data.portfolio.first(where: { $0.id == u.id }), current.linkedImportKey == nil else { continue }
            guard data.savePortfolioTx(PortfolioCsvImporter.linked(current, u, batch: batch)) != nil else {
                linkFailed = true
                continue
            }
            linkedStored += 1
        }
        let count = inserted + linkedStored
        // Nur Verknüpfungen und keine davon geschrieben: ebenfalls ein Fehler, nicht «0 importiert»
        if count == 0 && linkFailed {
            saveFailed = true
            return
        }
        // Erste Transaktion mit ausgeschaltetem Portfolio: einschalten (Tab erscheint), wie von Hand — nur neu angelegte
        var enabled = false
        if inserted > 0, PortfolioOff.enableOnSave(isNew: true, portfolioEnabled: data.settings.portfolioEnabled) {
            data.settings.portfolioEnabled = true
            enabled = true
        }
        onImported(count, enabled)
        dismiss()
    }
}
