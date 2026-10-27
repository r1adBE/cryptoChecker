import SwiftUI

/// Liste der Coins mit Kurs und 24 h (Börse klein), darunter «Aktualisieren», Umschalter der Liste und (eigene Liste)
/// «Coins wählen».
struct WatchQuoteList: View {
    let model: WatchModel

    var body: some View {
        let coins = model.settings.shown
        let phone = model.settings.mode == .phone
        List {
            if coins.isEmpty {
                Text(WatchText.t(phone ? "watch_phone_empty" : "watch_empty"))
                    .multilineTextAlignment(.center)
            }
            ForEach(coins, id: \.self) { coin in
                row(coin)
            }
            Text(status).font(.footnote).foregroundStyle(.secondary)
            Button(WatchText.t("watch_refresh")) { Task { await model.refresh() } }
            Button(WatchText.t(phone ? "watch_mode_phone" : "watch_mode_own")) { model.toggleMode() }
            if !phone {
                NavigationLink(WatchText.t("watch_choose")) { WatchChooseList(model: model) }
            }
        }
        .navigationTitle("Crypto Checker")
        .task { await model.refresh() }
    }

    private var status: String {
        if model.loading { return WatchText.t("watch_loading") }
        if model.failed { return WatchText.t("watch_error") }
        guard model.time > 0 else { return "" }
        let date = Date(timeIntervalSince1970: TimeInterval(model.time) / 1000)
        return WatchText.t("watch_updated", date.formatted(date: .omitted, time: .shortened))
    }

    private func row(_ coin: String) -> some View {
        let q = model.quotes[coin]
        let price = q.map { WatchText.price($0.price) } ?? "—"
        let change = WatchText.change(q?.change24h)
        return VStack(alignment: .leading, spacing: 2) {
            Text("\(coin)  \(price)").font(.headline).lineLimit(1).minimumScaleFactor(0.7)
            Text(change + (q.map { "  ·  \($0.source)" } ?? "")).font(.footnote).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(WatchText.t("a11y_watch_row", coin, price, change))
    }
}

/// Eigene Liste: die gängigen Coins zum Antippen (höchstens `WatchCoins.max`).
struct WatchChooseList: View {
    let model: WatchModel

    var body: some View {
        let own = model.settings.own
        List {
            Text(WatchText.t("watch_choose_hint", WatchCoins.max)).font(.footnote).foregroundStyle(.secondary)
            ForEach(WatchCoins.top, id: \.self) { coin in
                let checked = own.contains(coin)
                Toggle(coin, isOn: Binding(get: { checked }, set: { _ in model.toggle(coin) }))
                    .disabled(!checked && own.count >= WatchCoins.max)
            }
        }
        .navigationTitle(WatchText.t("watch_choose"))
    }
}
