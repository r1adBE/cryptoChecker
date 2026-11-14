import SwiftUI

/// Oben der Portfolio-Wert vom iPhone (falls dort eingeschaltet), dann die Coins mit Kurs und 24 h (Börse klein), darunter
/// «Aktualisieren», Schalter «Liste vom Handy» und (eigene Liste) «Coins wählen». Veraltete Kurse (`WatchFreshness`, 3 × Intervall des iPhones, je Coin ab dem Stand seines Abrufs) grau mit Alter statt Börse,
/// jede Minute neu bewertet — wie Android.
struct WatchQuoteList: View {
    let model: WatchModel

    var body: some View {
        TimelineView(.everyMinute) { context in
            list(now: Int64(context.date.timeIntervalSince1970 * 1000))
        }
        .navigationTitle("Crypto Checker")
        .task { model.refresh() }
        // Offen: im Intervall des iPhones, nur was fällig ist (`WatchInterval`)
        .task(id: model.settings.interval) {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(WatchInterval.clamp(model.settings.interval) * 60))
                guard !Task.isCancelled else { return }
                model.refresh(force: false)
            }
        }
    }

    private func list(now: Int64) -> some View {
        let coins = model.settings.shown
        let phone = model.settings.mode == .phone
        return List {
            if let p = model.portfolio {
                portfolioRow(p, now: now)
            }
            if coins.isEmpty {
                Text(WatchText.t(phone ? "watch_phone_empty" : "watch_empty"))
                    .multilineTextAlignment(.center)
            }
            ForEach(coins, id: \.self) { coin in
                row(coin, now: now)
            }
            Text(status(coins)).font(.footnote).foregroundStyle(.secondary)
            Button(WatchText.t("watch_refresh")) { model.refresh() }
            // Schalter statt Knopf mit Zustandstext: an = Favoriten des iPhones, aus = eigene Liste (wie Wear `ToggleChip`)
            Toggle(WatchText.t("watch_list_from_phone"), isOn: Binding(get: { phone }, set: { on in
                if on != phone { model.toggleMode() }
            }))
            if !phone {
                NavigationLink(WatchText.t("watch_choose")) { WatchChooseList(model: model) }
            }
        }
    }

    /// «Stand …» = ältester gezeigter Kurs (je Coin eigene Zeit).
    private func status(_ coins: [String]) -> String {
        if model.loading { return WatchText.t("watch_loading") }
        if model.failed { return WatchText.t("watch_error") }
        let time = WatchCache.listTime(model.quotes, coins: coins)
        guard time > 0 else { return "" }
        return WatchText.t("watch_updated", WatchText.date(time).formatted(date: .omitted, time: .shortened))
    }

    /// «Portfolio» vom iPhone (nur mit Schalter dort); veraltet grau mit Alter — wie Android `PortfolioRow`.
    private func portfolioRow(_ p: WatchPortfolio, now: Int64) -> some View {
        let outdated = WatchFreshness.isOutdated(p.time, now: now, intervalMinutes: model.settings.interval)
        let spoken = WatchText.portfolioSpoken(p)
        return VStack(alignment: .leading, spacing: 2) {
            Text(WatchText.t("watch_portfolio")).font(.footnote).foregroundStyle(.secondary)
            Text(WatchText.portfolioTotal(p)).font(.headline).lineLimit(1).minimumScaleFactor(0.7)
                .foregroundStyle(outdated ? HierarchicalShapeStyle.secondary : HierarchicalShapeStyle.primary)
            Text(WatchText.portfolioToday(p) + (outdated ? "  ·  " + WatchText.ago(p.time, now: now) : ""))
                .font(.footnote).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(outdated ? spoken + ", " + WatchText.spokenAge(p.time, now: now) : spoken)
    }

    /// Zeile eines Coins bzw. Paars des iPhones (`key`, Anzeige `WatchPair.label(of:)`, z. B. «BANK Perp»); darunter die
    /// Börse, die den Kurs geliefert hat. Ohne Kurs «—» und, sobald nichts mehr lädt, der Grund: kurz sichtbar in der
    /// zweiten Zeile (`WatchText.missingShort`), ausführlich für VoiceOver (`WatchText.missingReason`) — wie Android `QuoteRow`.
    private func row(_ key: String, now: Int64) -> some View {
        let coin = WatchPair.label(of: key)
        // Börse stillgelegt (`ExchangeRetirement`): kein alter Kurs, «—» mit dem Grund — wie Android
        let retired = WatchPair.parse(key).map { WatchPairQuotes.retired($0, now: now) } ?? false
        let cached = retired ? nil : model.quotes[key]
        let q = cached?.quote
        let time = cached?.time ?? 0
        let price = q.map { WatchText.price($0.price) } ?? "—"
        let change = WatchText.change(q?.change24h)
        let outdated = cached != nil && WatchFreshness.isOutdated(time, now: now, intervalMinutes: model.settings.interval)
        let line = WatchText.t("a11y_watch_row", coin, price, change)
        let missing = cached == nil && !model.loading
        let spoken = missing ? line + ", " + WatchText.missingReason(key) : line
        // Veraltet: Kurs grau, Alter statt Börse
        let detail = outdated ? WatchText.ago(time, now: now) : q?.source
        // Ohne Kurs steht «—» schon oben: die zweite Zeile nennt nur den Grund
        let second = missing ? WatchText.missingShort(key) : change + (detail.map { "  ·  \($0)" } ?? "")
        return VStack(alignment: .leading, spacing: 2) {
            Text("\(coin)  \(price)").font(.headline).lineLimit(1).minimumScaleFactor(0.7)
                .foregroundStyle(outdated ? HierarchicalShapeStyle.secondary : HierarchicalShapeStyle.primary)
            Text(second).font(.footnote).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(outdated ? spoken + ", " + WatchText.spokenAge(time, now: now) : spoken)
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
