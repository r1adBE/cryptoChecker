import SwiftUI
import UniformTypeIdentifiers

/// Vorschau des CSV-Imports: Zähler (neu, doppelt, übersprungen mit Grund, ohne Tageskurs/Preis), die ersten
/// Zeilen und «Importieren» — wie `CsvImportPreviewSheet` (Android).
@MainActor
struct PortfolioCsvImportSheet: View {
    let loaded: CsvImportLoaded
    /// Anzahl angelegter Transaktionen; `enabled` = das Portfolio wurde dabei eingeschaltet.
    let onImported: (_ count: Int, _ enabled: Bool) -> Void

    @State private var preview: CsvImportPreview
    /// Vorschläge je Auszahlung (`CsvImportMapping.proposeWithdrawals`): Vorauswahl und Ziel der Umbuchung — wie Android.
    @State private var proposals: [CsvWithdrawalProposal] = []
    /// Wahl je Auszahlung (`CsvImportTx.importKey`); «Importieren» erst, wenn jede gewählt ist.
    @State private var choices: [String: CsvWithdrawalChoice] = [:]
    /// Mögliche Umbuchungen (Auszahlung + Einzahlung desselben Coins, `CsvTransferMatch.candidates`) — wie Android.
    @State private var links: [CsvTransferLink] = []
    /// Gewählte Umbuchungen (`CsvTransferLink.key`); eindeutige sind vorgewählt.
    @State private var linked: Set<String> = []
    /// Gewählte Zeitzone der Zeiten ohne Versatz (nil = Regel: Swissquote Europe/Zurich, sonst Gerät).
    @State private var zone: String? = nil
    /// Gewählte Reihenfolge Tag/Monat bei mehrdeutigen Daten (nil = Standard, Tag zuerst) — wie Android `setDateOrder`.
    @State private var dateOrder: CsvDateOrder? = nil
    /// Gewähltes Dezimalzeichen bei mehrdeutigen Zahlen («42,000»; nil = Standard: Zeichen = Dezimal) — wie Android
    /// `setDecimalMark`.
    @State private var decimalMark: Character? = nil
    /// Tageskurse nach einer anderen Reihenfolge Tag/Monat neu geladen (nil = die beim Laden).
    @State private var rates: [String: [(day: Int, rate: Double)]]? = nil

    init(loaded: CsvImportLoaded, onImported: @escaping (_ count: Int, _ enabled: Bool) -> Void) {
        self.loaded = loaded
        self.onImported = onImported
        _preview = State(initialValue: loaded.preview)
    }

    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
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

    /// Umbuchungen zwischen Börsen: je Kandidat «Auszahlung → Einzahlung» mit Schalter (eindeutige vorgewählt),
    /// Netzwerkgebühr, Hinweis auf eine Seite aus einem früheren Import bzw. auf mehrere Treffer — wie Android `TransferLinks`.
    private var transferLinks: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Divider().padding(.vertical, Spacing.xs)
            Text(L("portfolio_csv_import_transfers_title"))
                .font(.subheadline.weight(.semibold))
            hint(L("portfolio_csv_import_transfers_hint"))
            ForEach(links) { link in
                Toggle(isOn: Binding(
                    get: { linked.contains(link.key) },
                    set: { on in linked = CsvTransferMatch.toggle(links, chosen: linked, key: link.key, on: on) }
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: PortfolioFormat.date(link.withdrawalTime) + " · "
                             + PortfolioFormat.exactAmount(link.sent, link.coin) + " → "
                             + PortfolioFormat.exactAmount(link.received, link.coin))
                            .font(.subheadline.monospacedDigit())
                        // Börsen nur, wenn beide bekannt sind (eigene CSV hat keine)
                        if let from = link.from, let to = link.to {
                            hint(from + " → " + to)
                        }
                        if let fee = link.fee {
                            hint(L("portfolio_csv_import_transfer_fee", link.feeUnit == .COIN
                                   ? PortfolioFormat.exactAmount(fee, link.coin)
                                   : PortfolioFormat.usdtValue(PortfolioDecimal.double(fee))))
                        }
                        if link.withdrawalId != nil || link.depositId != nil {
                            hint(L("portfolio_csv_import_transfer_stored"))
                        }
                        if !link.unambiguous {
                            hint(L("portfolio_csv_import_transfer_ambiguous"))
                        }
                    }
                }
                .tint(accent.primary)
                .accessibilityHint(L("portfolio_csv_import_transfer_link"))
                .padding(.top, 4)
            }
        }
    }

    /// Auszahlungen der Datei: je Zeile «Auf eigene Wallet (Umbuchung)» / «Verlässt das Portfolio (Auszahlung)», vorgewählt
    /// nur bei passender beobachteter Adresse («Vorschlag: passt zu …»); dazu «Alle …» — wie Android `WithdrawalChoices`.
    private func withdrawalChoices(_ withdrawals: [CsvImportTx]) -> some View {
        let destinations: [String: String] = proposals.reduce(into: [:]) { out, p in
            if let to = p.transferTo { out[p.importKey] = to }
        }
        let decided: Bool = CsvImportMapping.allWithdrawalsDecided(withdrawals, choices: choices)
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            Divider().padding(.vertical, Spacing.xs)
            Text(L("portfolio_csv_import_withdrawals_title"))
                .font(.subheadline.weight(.semibold))
            if !decided {
                hint(L("portfolio_csv_import_choose_each"))
            }
            // «Alle …» nur bei mehreren Auszahlungen
            if withdrawals.count > 1 {
                HStack(spacing: Spacing.sm) {
                    Button(L("portfolio_csv_import_all_transfer")) { setAll(.TRANSFER) }
                    Button(L("portfolio_csv_import_all_withdrawal")) { setAll(.WITHDRAWAL) }
                }
                .font(.subheadline)
                .buttonStyle(.borderless)
            }
            ForEach(withdrawals, id: \.importKey) { tx in
                VStack(alignment: .leading, spacing: 4) {
                    Text(rowText(tx))
                        .font(.subheadline.monospacedDigit())
                        .lineLimit(1)
                    if let to = destinations[tx.importKey] {
                        hint(L("portfolio_csv_import_choice_proposed", to))
                    }
                    choiceButton(tx, .TRANSFER, L("portfolio_csv_import_choice_transfer"))
                    choiceButton(tx, .WITHDRAWAL, L("portfolio_csv_import_choice_withdrawal"))
                }
                .padding(.top, 4)
            }
        }
    }

    /// Eine Wahl als Auswahlknopf (Häkchen = gewählt), Screenreader: «ausgewählt».
    private func choiceButton(_ tx: CsvImportTx, _ choice: CsvWithdrawalChoice, _ title: String) -> some View {
        let selected: Bool = choices[tx.importKey] == choice
        return Button {
            choices[tx.importKey] = choice
        } label: {
            Label(title, systemImage: selected ? "checkmark.circle.fill" : "circle")
                .font(.subheadline)
                .frame(minHeight: 44, alignment: .leading)
        }
        .buttonStyle(.borderless)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Alle Auszahlungen gleich («Alle auf eigene Wallet» / «Alle verlassen das Portfolio»).
    private func setAll(_ choice: CsvWithdrawalChoice) {
        var next: [String: CsvWithdrawalChoice] = [:]
        for p in proposals { next[p.importKey] = choice }
        choices = next
    }

    /// «Zeiten ohne Zeitzone gelesen als: …» mit Auswahl Gerät / Europe/Zurich / UTC.
    private var zonePicker: some View {
        let device: String = TimeZone.current.identifier
        var options: [String] = [device]
        for z in [CsvImportParser.swissZone, "UTC"] where !options.contains(z) { options.append(z) }
        let choice = Binding<String>(
            get: { preview.zone },
            set: { z in
                zone = z
                preview = loaded.remapped(withdrawalsAsTransfer: false, zone: z, dateOrder: dateOrder,
                                          decimalMark: decimalMark, rates: rates)
                propose()
            }
        )
        return VStack(alignment: .leading, spacing: 2) {
            hint(L("portfolio_csv_import_zone", preview.zone))
            Picker(L("portfolio_csv_import_zone", preview.zone), selection: choice) {
                ForEach(options, id: \.self) { z in
                    Text(z == device ? L("portfolio_csv_import_zone_device", z) : z).tag(z)
                }
            }
            .pickerStyle(.menu)
            .tint(accent.primary)
        }
    }

    /// «Mehrdeutige Daten (z. B. 03/04/2024) gelesen als:» mit Auswahl «Tag/Monat» / «Monat/Tag». Die Tage können sich um
    /// Monate verschieben: Tageskurse neu laden — wie Android `setDateOrder`.
    private var dateOrderPicker: some View {
        let choice = Binding<CsvDateOrder>(
            get: { preview.dateOrder },
            set: { order in
                dateOrder = order
                let text = loaded.text
                let chosenZone = zone
                let mark = decimalMark
                Task { @MainActor in
                    let result = CsvImportParser.parse(text, deviceZone: TimeZone.current.identifier, zone: chosenZone,
                                                       dateOrder: order, decimalMark: mark)
                    let fresh = await PortfolioCsvImporter.fetchRates(result)
                    // Inzwischen anders gewählt: verwerfen
                    guard dateOrder == order, decimalMark == mark else { return }
                    rates = fresh
                    preview = loaded.remapped(withdrawalsAsTransfer: false, zone: chosenZone, dateOrder: order,
                                              decimalMark: mark, rates: fresh)
                    propose()
                }
            }
        )
        return VStack(alignment: .leading, spacing: 2) {
            hint(L("portfolio_csv_import_date_order"))
            Picker(L("portfolio_csv_import_date_order"), selection: choice) {
                Text(L("portfolio_csv_import_date_day_first")).tag(CsvDateOrder.DAY_FIRST)
                Text(L("portfolio_csv_import_date_month_first")).tag(CsvDateOrder.MONTH_FIRST)
            }
            .pickerStyle(.segmented)
        }
    }

    /// «Mehrdeutige Zahlen (z. B. 42,000) gelesen als:» mit Auswahl «42,000 = 42000» / «42,000 = 42». Preise und Mengen
    /// ändern sich: Tageskurse neu laden — wie Android `DecimalPicker`/`setDecimalMark`.
    private func decimalPicker(_ sample: String) -> some View {
        var options: [(mark: Character, label: String)] = []
        var seen = Set<Decimal>()
        for m in [Character("."), Character(",")] {
            guard let v = CsvImportParser.sampleValue(sample, mark: m), seen.insert(v).inserted else { continue }
            options.append((mark: m, label: sample + " = " + CsvImport.text(v)))
        }
        let choice = Binding<String>(
            get: { preview.decimalMark.map { String($0) } ?? "" },
            set: { raw in
                guard let mark = raw.first else { return }
                decimalMark = mark
                let text = loaded.text
                let chosenZone = zone
                let order = dateOrder
                Task { @MainActor in
                    let result = CsvImportParser.parse(text, deviceZone: TimeZone.current.identifier, zone: chosenZone,
                                                       dateOrder: order, decimalMark: mark)
                    let fresh = await PortfolioCsvImporter.fetchRates(result)
                    // Inzwischen anders gewählt: verwerfen
                    guard decimalMark == mark, dateOrder == order else { return }
                    rates = fresh
                    preview = loaded.remapped(withdrawalsAsTransfer: false, zone: chosenZone, dateOrder: order,
                                              decimalMark: mark, rates: fresh)
                    propose()
                }
            }
        )
        return VStack(alignment: .leading, spacing: 2) {
            hint(L("portfolio_csv_import_decimal", sample))
            Picker(L("portfolio_csv_import_decimal", sample), selection: choice) {
                ForEach(options.indices, id: \.self) { i in
                    Text(verbatim: options[i].label).tag(String(options[i].mark))
                }
            }
            .pickerStyle(.segmented)
        }
    }

    private func hint(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AppColors.onSurfaceVariant)
    }

    /// «12.06.2024 · Kauf · 0.0028 BTC · 95’287.63 USDT».
    private func rowText(_ tx: CsvImportTx) -> String {
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
        let inserted = data.insertImportedPortfolioTxs(txs.map(PortfolioCsvImporter.tx), batch: batch)
        // Gespeicherte Seiten verknüpfter Umbuchungen: neu gelesen, inzwischen gelöscht oder verknüpft → ausgelassen;
        // die Transaktion wechselt in diesen Importlauf («Import rückgängig» setzt sie zurück) — wie Android `linkStored`
        var linkedStored = 0
        for u in plan.storedUpdates {
            guard let current = data.portfolio.first(where: { $0.id == u.id }), current.linkedImportKey == nil,
                  data.savePortfolioTx(PortfolioCsvImporter.linked(current, u, batch: batch)) != nil else { continue }
            linkedStored += 1
        }
        let count = inserted + linkedStored
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
