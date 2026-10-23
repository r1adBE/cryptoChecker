import SwiftUI

/// «Einstand für Adressguthaben» einer beobachteten Adresse — wie `PortfolioAddressLotsSheet.kt`: je Coin, wie
/// viel des Guthabens Posten mit Einstand hat (Rest «ohne Einstand»), Hinweis, wenn die Posten das Guthaben übersteigen
/// (älteste zuerst verbraucht, `AddressCostLots.cover`); Liste der Posten (antippen = bearbeiten) und «Posten
/// hinzufügen». Die Posten zählen wie Käufe in dieser Adresse.
@MainActor
struct PortfolioAddressLotsSheet: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.dismiss) private var dismiss
    let addressId: Int64
    /// Bearbeiten: vorhandener Posten oder neuer (Id 0).
    @State private var editing: AddressCostLot?

    private var hidden: Bool { data.settings.hidePortfolioAmounts }

    private func mask(_ text: String) -> String { PortfolioInsights.mask(text, hidden: hidden) }

    var body: some View {
        NavigationStack {
            if let a = data.watchedAddresses.first(where: { $0.id == addressId }) {
                list(a)
                    .navigationTitle(L("address_lots_title"))
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button(L("action_close")) { dismiss() } }
                    }
                    .sheet(item: $editing) { draft in
                        PortfolioAddressLotEditor(initial: draft, coins: coins(a), isNew: draft.id == 0) { lot in
                            let id = draft.id == 0 ? AddressCostLots.nextId(a.costLots) : draft.id
                            var saved = lot
                            saved.id = id
                            data.setWatchedAddressCostLots(a.id, lots: a.costLots.filter { $0.id != id } + [saved])
                            Task { await PortfolioModel.shared.refresh(force: false) }
                        } onDelete: {
                            data.setWatchedAddressCostLots(a.id, lots: a.costLots.filter { $0.id != draft.id })
                            Task { await PortfolioModel.shared.refresh(force: false) }
                        }
                        .presentationDetents([.large])
                    }
            } else {
                Color.clear.onAppear { dismiss() }
            }
        }
    }

    /// Coins der Adresse: Netz-Coin, dann weitere Guthaben.
    private func coins(_ a: WatchedAddress) -> [String] {
        var out = [a.chain.coin]
        for c in AddressCostLots.balances(a).keys.sorted() where !out.contains(c) { out.append(c) }
        return out
    }

    private func list(_ a: WatchedAddress) -> some View {
        List {
            Section {
                Text(a.label ?? WatchedAddressRules.shortAddress(a.address))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                Text(L("address_lots_intro"))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Section {
                // Abdeckung wie im Rechner: erfasste Bewegungen dieser Wallet stecken schon im Guthaben
                ForEach(AddressCostLots.coverage(a, trades: data.portfolio, all: data.watchedAddresses), id: \.coin) { c in
                    VStack(alignment: .leading, spacing: Spacing.xs) {
                        Text(L("address_lots_status", mask(PortfolioFormat.exactAmount(c.lotted, c.coin)),
                               mask(PortfolioFormat.exactAmount(c.base, c.coin))))
                            .font(.subheadline.weight(.semibold))
                        if c.unlotted > 0 {
                            Text(L("address_lots_unlotted", mask(PortfolioFormat.exactAmount(c.unlotted, c.coin))))
                                .font(.subheadline)
                                .foregroundStyle(AppColors.onSurfaceVariant)
                        }
                        if a.lastBalance == nil {
                            Text(L("address_lots_not_loaded")).font(.subheadline).foregroundStyle(AppColors.onSurfaceVariant)
                        } else if c.capped {
                            Text(L("address_lots_capped", mask(PortfolioFormat.exactAmount(c.excess, c.coin))))
                                .font(.subheadline)
                                .foregroundStyle(AppColors.error)
                        }
                    }
                }
                ForEach(AddressCostLots.clean(a.costLots), id: \.id) { lot in
                    Button { editing = lot } label: {
                        VStack(alignment: .leading, spacing: Spacing.xs) {
                            Text(PortfolioFormat.date(lot.time)).font(.caption).foregroundStyle(AppColors.onSurfaceVariant)
                            Text(mask(PortfolioFormat.exactAmount(lot.amount, lot.coin)) + " · "
                                 + mask(lot.cost.map { PortfolioFormat.usdtValue(PortfolioDecimal.double($0)) } ?? "—"))
                                .foregroundStyle(AppColors.onSurface)
                        }
                    }
                }
                Button(L("address_lots_add")) {
                    editing = AddressCostLot(id: 0, coin: a.chain.coin, time: TimeUtils.nowMillis, amount: 0)
                }
                .disabled(a.costLots.count >= AddressCostLots.maxLots)
            }
        }
    }
}

extension AddressCostLot: Identifiable {}

/// Dialog eines Postens — wie `AddressLotEditor` (Android): Coin (nur wenn die Adresse mehrere hält), Kaufdatum (bis
/// heute), Menge und Einstand gesamt oder je Coin (USDT, gespeichert wie eingegeben). Vorschlag: Menge × geprüfter
/// Tagesschluss des Kaufdatums mit «Übernehmen», änderbar.
@MainActor
struct PortfolioAddressLotEditor: View {
    @Environment(\.dismiss) private var dismiss
    let initial: AddressCostLot
    let coins: [String]
    let isNew: Bool
    let onSave: (AddressCostLot) -> Void
    let onDelete: () -> Void

    @State private var coin = ""
    @State private var date = Date()
    @State private var amountText = ""
    @State private var perCoin = false
    @State private var valueText = ""
    @State private var close: Decimal?
    @State private var tried = false
    @State private var loaded = false

    /// Dezimalzeichen, mit dem die Texte vorbelegt und gelesen werden: einmal beim Öffnen festgehalten (`@State` übersteht
    /// das Neu-Erzeugen der Ansicht) statt bei jedem Zeichnen neu gelesen — ändert sich die Region bei offenem Blatt,
    /// bleibt «2,125» 2,125 statt 2125. Wie `PortfolioTxSheet.decimalSeparator` (auch hier).
    @State private var sep: Character = ThresholdParser.localeDecimalSeparator
    private var amount: Decimal? { PriceFormat.parseDecimalAmount(amountText, decimalSeparator: sep).flatMap { $0 > 0 ? $0 : nil } }
    private var value: Decimal? { PriceFormat.parseDecimalAmount(valueText, decimalSeparator: sep).flatMap { $0 >= 0 ? $0 : nil } }
    private var lot: AddressCostLot? {
        guard let amount, let value else { return nil }
        return AddressCostLot(id: initial.id, coin: coin, time: date.millis, amount: amount,
                              priceUsdt: perCoin ? value : nil, costUsdt: perCoin ? nil : value)
    }

    var body: some View {
        NavigationStack {
            Form {
                if coins.count > 1 {
                    Picker("", selection: $coin) { ForEach(coins, id: \.self) { Text($0).tag($0) } }
                        .pickerStyle(.segmented)
                }
                DatePicker(L("address_lots_date"), selection: $date, in: ...Date(), displayedComponents: .date)
                TextField(L("address_lots_amount") + " (\(coin))", text: $amountText).keyboardType(.decimalPad)
                Picker("", selection: $perCoin) {
                    Text(L("address_lots_mode_total")).tag(false)
                    Text(L("address_lots_mode_price")).tag(true)
                }
                .pickerStyle(.segmented)
                TextField(L(perCoin ? "address_lots_price" : "address_lots_total"), text: $valueText).keyboardType(.decimalPad)
                if let c = close, let suggested = perCoin ? c : amount.flatMap({ AddressCostLots.suggestion(amount: $0, close: c) }) {
                    Text(L("cost_gap_suggestion", PortfolioFormat.date(date.millis), PortfolioFormat.usdtValue(PortfolioDecimal.double(suggested))))
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                    Button(L("cost_gap_apply")) {
                        valueText = PriceFormat.exactAmountForInput(suggested, decimalSeparator: sep)
                    }
                }
                if tried && lot == nil {
                    Text(L("address_lots_invalid")).font(.subheadline).foregroundStyle(AppColors.error)
                }
                if !isNew {
                    Button(L("action_delete"), role: .destructive) {
                        onDelete()
                        dismiss()
                    }
                }
            }
            .navigationTitle(L(isNew ? "address_lots_new" : "address_lots_edit"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_save")) {
                        tried = true
                        if let lot {
                            onSave(lot)
                            dismiss()
                        }
                    }
                }
            }
            .onAppear {
                guard !loaded else { return }
                loaded = true
                coin = initial.coin
                date = Date(millis: initial.time)
                amountText = initial.amount > 0 ? PriceFormat.exactAmountForInput(initial.amount, decimalSeparator: sep) : ""
                perCoin = initial.priceUsdt != nil
                valueText = PriceFormat.exactAmountForInput(initial.priceUsdt ?? initial.costUsdt, decimalSeparator: sep)
            }
            // Vorschlag: geprüfter Tagesschluss des Kaufdatums (gleiche Quelle wie das Erfassen-Blatt)
            .task(id: "\(coin)|\(CutoffDay(date).year)-\(CutoffDay(date).month)-\(CutoffDay(date).day)") {
                guard !coin.isEmpty else { return }
                close = nil
                close = await PortfolioCostGapUi.close(coin, millis: date.millis)
            }
        }
    }
}
