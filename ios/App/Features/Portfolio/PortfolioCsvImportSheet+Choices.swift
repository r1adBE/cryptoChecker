import SwiftUI

// Wahlen in der Vorschau des CSV-Imports: Umbuchungen, Auszahlungen, Zeitzone, Tag/Monat und Dezimalzeichen.
// Eigene Datei, damit das Blatt selbst nur Zähler, Zeilen und den Ablauf enthält.

extension PortfolioCsvImportSheet {
    /// Seit Build 103: so viele Umbuchungen bzw. Auszahlungen einzeln im Blatt; alle über «Alle anzeigen» (wie Android).
    static var listRows: Int { 10 }

    /// Umbuchungen zwischen Börsen: je Kandidat «Auszahlung → Einzahlung» mit Schalter (eindeutige vorgewählt),
    /// Netzwerkgebühr, Hinweis auf eine Seite aus einem früheren Import bzw. auf mehrere Treffer — wie Android `TransferLinks`.
    var transferLinks: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Divider().padding(.vertical, Spacing.xs)
            Text(L("portfolio_csv_import_transfers_title"))
                .font(.subheadline.weight(.semibold))
            hint(L("portfolio_csv_import_transfers_hint"))
            ForEach(links.prefix(Self.listRows)) { link in linkRow(link) }
            if links.count > Self.listRows {
                Button(L("explorer_bulk_show_all", links.count)) { showAllLinks = true }
                    .font(.subheadline)
                    .buttonStyle(.borderless)
                    .disabled(rereading)
            }
        }
        .sheet(isPresented: $showAllLinks) {
            fullList(L("portfolio_csv_import_transfers_title"), done: { showAllLinks = false }) {
                ForEach(links) { link in linkRow(link) }
            }
        }
    }

    private func linkRow(_ link: CsvTransferLink) -> some View {
        Toggle(isOn: Binding(
            get: { linked.contains(link.key) },
            set: { on in
                linked = CsvTransferMatch.toggle(links, chosen: linked, key: link.key, on: on)
                updatePlan()
            }
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
        // Seit Build 104: während des Neu-Lesens aus (nicht nur gedimmt) — wie Android
        .disabled(rereading)
        .padding(.top, 4)
    }

    /// Auszahlungen der Datei: je Zeile «Auf eigene Wallet (Umbuchung)» / «Verlässt das Portfolio (Auszahlung)», vorgewählt
    /// nur bei passender beobachteter Adresse («Vorschlag: passt zu …»); dazu «Alle …» — wie Android `WithdrawalChoices`.
    func withdrawalChoices(_ withdrawals: [CsvImportTx]) -> some View {
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
                .disabled(rereading)
            }
            ForEach(withdrawals.prefix(Self.listRows), id: \.importKey) { tx in
                withdrawalRow(tx, destination: destinations[tx.importKey])
            }
            if withdrawals.count > Self.listRows {
                Button(L("explorer_bulk_show_all", withdrawals.count)) { showAllWithdrawals = true }
                    .font(.subheadline)
                    .buttonStyle(.borderless)
                    .disabled(rereading)
            }
        }
        .sheet(isPresented: $showAllWithdrawals) {
            fullList(L("portfolio_csv_import_withdrawals_title"), done: { showAllWithdrawals = false }) {
                ForEach(withdrawals, id: \.importKey) { tx in
                    withdrawalRow(tx, destination: destinations[tx.importKey])
                }
            }
        }
    }

    private func withdrawalRow(_ tx: CsvImportTx, destination: String?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            previewRow(tx)
            if let to = destination {
                hint(L("portfolio_csv_import_choice_proposed", to))
            }
            choiceButton(tx, .TRANSFER, L("portfolio_csv_import_choice_transfer"))
            choiceButton(tx, .WITHDRAWAL, L("portfolio_csv_import_choice_withdrawal"))
        }
        .padding(.top, 4)
    }

    /// Seit Build 103: alle Zeilen einer Liste im eigenen Blatt, als `List` (nur sichtbare Zeilen gebaut).
    private func fullList<Rows: View>(_ title: String, done: @escaping () -> Void,
                                      @ViewBuilder rows: () -> Rows) -> some View {
        NavigationStack {
            List { rows() }
                .listStyle(.plain)
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L("action_close"), action: done)
                    }
                }
        }
        .tint(accent.primary)
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
        .disabled(rereading)
    }

    /// Alle Auszahlungen gleich («Alle auf eigene Wallet» / «Alle verlassen das Portfolio»).
    private func setAll(_ choice: CsvWithdrawalChoice) {
        var next: [String: CsvWithdrawalChoice] = [:]
        for p in proposals { next[p.importKey] = choice }
        choices = next
    }

    /// «Zeiten ohne Zeitzone gelesen als: …» mit Auswahl Gerät / Europe/Zurich / UTC.
    var zonePicker: some View {
        let device: String = TimeZone.current.identifier
        var options: [String] = [device]
        for z in [CsvImportParser.swissZone, "UTC"] where !options.contains(z) { options.append(z) }
        let choice = Binding<String>(
            get: { preview.zone },
            set: { z in
                zone = z
                remap()
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
            .disabled(rereading)
        }
    }

    /// «Mehrdeutige Daten (z. B. 03/04/2024) gelesen als:» mit Auswahl «Tag/Monat» / «Monat/Tag». Die Tage können sich um
    /// Monate verschieben: Tageskurse neu laden — wie Android `setDateOrder`.
    var dateOrderPicker: some View {
        let choice = Binding<CsvDateOrder>(
            get: { preview.dateOrder },
            set: { order in
                dateOrder = order
                ratesStale = true
                remap()
            }
        )
        return VStack(alignment: .leading, spacing: 2) {
            hint(L("portfolio_csv_import_date_order"))
            Picker(L("portfolio_csv_import_date_order"), selection: choice) {
                Text(L("portfolio_csv_import_date_day_first")).tag(CsvDateOrder.DAY_FIRST)
                Text(L("portfolio_csv_import_date_month_first")).tag(CsvDateOrder.MONTH_FIRST)
            }
            .pickerStyle(.segmented)
            .disabled(rereading)
        }
    }

    /// «Mehrdeutige Zahlen (z. B. 42,000) gelesen als:» mit Auswahl «42,000 = 42000» / «42,000 = 42». Preise und Mengen
    /// ändern sich: Tageskurse neu laden — wie Android `DecimalPicker`/`setDecimalMark`.
    func decimalPicker(_ sample: String) -> some View {
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
                ratesStale = true
                remap()
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
            .disabled(rereading)
        }
    }
}
