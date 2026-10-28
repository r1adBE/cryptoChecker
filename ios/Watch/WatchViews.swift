import SwiftUI

/// Liste der Coins mit Kurs und 24 h (Börse klein), darunter «Aktualisieren», Umschalter der Liste und (eigene Liste)
/// «Coins wählen». Veraltete Kurse (`WatchFreshness`) grau mit Alter statt Börse, jede Minute neu bewertet — wie Android.
struct WatchQuoteList: View {
    let model: WatchModel

    var body: some View {
        TimelineView(.everyMinute) { context in
            list(now: Int64(context.date.timeIntervalSince1970 * 1000))
        }
        .navigationTitle("Crypto Checker")
        .task { await model.refresh() }
    }

    private func list(now: Int64) -> some View {
        let coins = model.settings.shown
        let phone = model.settings.mode == .phone
        return List {
            if coins.isEmpty {
                Text(WatchText.t(phone ? "watch_phone_empty" : "watch_empty"))
                    .multilineTextAlignment(.center)
            }
            ForEach(coins, id: \.self) { coin in
                row(coin, now: now)
            }
            Text(status).font(.footnote).foregroundStyle(.secondary)
            Button(WatchText.t("watch_refresh")) { Task { await model.refresh() } }
            Button(WatchText.t(phone ? "watch_mode_phone" : "watch_mode_own")) { model.toggleMode() }
            if !phone {
                NavigationLink(WatchText.t("watch_choose")) { WatchChooseList(model: model) }
            }
        }
    }

    private var status: String {
        if model.loading { return WatchText.t("watch_loading") }
        if model.failed { return WatchText.t("watch_error") }
        guard model.time > 0 else { return "" }
        let date = Date(timeIntervalSince1970: TimeInterval(model.time) / 1000)
        return WatchText.t("watch_updated", date.formatted(date: .omitted, time: .shortened))
    }

    private func row(_ coin: String, now: Int64) -> some View {
        let q = model.quotes[coin]
        let price = q.map { WatchText.price($0.price) } ?? "—"
        let change = WatchText.change(q?.change24h)
        let outdated = q != nil && WatchFreshness.isOutdated(model.time, now: now)
        let spoken = WatchText.t("a11y_watch_row", coin, price, change)
        // Veraltet: Kurs grau, Alter statt Börse
        let detail = outdated ? WatchText.ago(model.time, now: now) : q?.source
        return VStack(alignment: .leading, spacing: 2) {
            Text("\(coin)  \(price)").font(.headline).lineLimit(1).minimumScaleFactor(0.7)
                .foregroundStyle(outdated ? HierarchicalShapeStyle.secondary : HierarchicalShapeStyle.primary)
            Text(change + (detail.map { "  ·  \($0)" } ?? "")).font(.footnote).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(outdated ? spoken + ", " + WatchText.spokenAge(model.time, now: now) : spoken)
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
