import SwiftUI

/// Register der Karte «Marktdaten»: Reihenfolge = Reihenfolge der Register — wie `MarketDataTab` (Android).
enum MarketDataTab: Int, Hashable, Sendable {
    case spot = 0
    case futures = 1
}

/// Zuletzt gewähltes Register für die Dauer der Sitzung, getrennt für Futures- und Spot-Paare;
/// Standard: Futures bei Futures-Paaren, Spot bei Spot-Paaren. Wie `MarketDataTabMemory` (Android).
enum MarketDataTabMemory {
    // Hinter einer Sperre statt `nonisolated(unsafe)`: thread-sicher, ohne Aufrufer an den MainActor zu binden
    private static let state = LockedBox((forFutures: MarketDataTab.futures, forSpot: MarketDataTab.spot))

    static func get(futuresPair: Bool) -> MarketDataTab {
        state.read { futuresPair ? $0.forFutures : $0.forSpot }
    }

    static func set(futuresPair: Bool, _ tab: MarketDataTab) {
        state.write { if futuresPair { $0.forFutures = tab } else { $0.forSpot = tab } }
    }
}

/// Karte «Marktdaten» mit den Registern «Spot | Futures» (`RegisterTabs`) — wie `MarketDataSection`:
///  - Spot: Marktkapitalisierung (+ 24h), Volumen, Vol./Marktkap., FDV und Mengen von CoinGecko
///    (`WatchlistSpotSection`); erst geladen, wenn das Register offen ist.
///  - Futures: Funding Rate (+ nächste Zahlung) und Open Interest des Perpetuals — bei Spot-Paaren des
///    USDT-Perpetuals des Coins; bei Perpetuals gleich beim Öffnen des Blatts geladen.
/// ⓘ blendet die Erklärungen zum offenen Register ein.
struct WatchlistMarketDataSection: View {
    let watch: Watch

    @Environment(\.appAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var tab: MarketDataTab
    @State private var showExplain = false
    @State private var futures: FuturesInfo? = nil
    @State private var futuresLoaded = false
    @State private var spot: SpotData? = nil
    @State private var spotLoaded = false
    /// Paar, für das die Werte oben gelten (bearbeitetes Paar → neu laden).
    @State private var loadedPair: WatchEdit.Key? = nil

    init(watch: Watch) {
        self.watch = watch
        _tab = State(initialValue: MarketDataTabMemory.get(futuresPair: watch.contractType != .none))
    }

    private var futuresPair: Bool { watch.contractType != .none }

    /// Perpetuals wie bisher sofort, alles andere erst mit offenem Register (keine unnötige Abfrage).
    private var needFutures: Bool { tab == .futures || watch.contractType.isPerpetual }

    private struct LoadKey: Hashable {
        let pair: WatchEdit.Key
        let tab: MarketDataTab
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            header
            RegisterTabs(titles: [L("explorer_contract_spot"), L("market_data_tab_futures")],
                         selected: tab.rawValue) { index in
                let next = MarketDataTab(rawValue: index) ?? .spot
                tab = next
                MarketDataTabMemory.set(futuresPair: futuresPair, next)
            }
            .padding(.bottom, -Spacing.sm)
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        // Kennzahlen kommen später: weich einfügen (bei reduzierter Bewegung sofort)
        .animation(reduceMotion ? nil : .spring(duration: 0.35), value: futuresLoaded)
        .animation(reduceMotion ? nil : .spring(duration: 0.35), value: spotLoaded)
        .task(id: LoadKey(pair: WatchEdit.Key(watch), tab: tab)) { await load() }
    }

    /// Titel mit ⓘ (Erklärungen ein/aus).
    private var header: some View {
        HStack(spacing: 8) {
            Label(L("market_data_title"), systemImage: "chart.bar.xaxis")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppColors.onSurfaceVariant)
            Spacer(minLength: 8)
            Button {
                showExplain.toggle()
            } label: {
                Image(systemName: "info.circle")
                    .font(.system(size: 20))
                    .foregroundStyle(showExplain ? accent.primary : AppColors.onSurfaceVariant)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L("explain_show"))
            .accessibilityAddTraits(showExplain ? [.isSelected] : [])
            .padding(.vertical, -10)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch tab {
        case .spot:
            WatchlistSpotSection(data: spot, loaded: spotLoaded, showExplain: showExplain)
        case .futures:
            futuresBody
        }
    }

    @ViewBuilder
    private var futuresBody: some View {
        if showExplain {
            MarketDataExplanation(text: L("label_value", L("futures_funding"), L("explain_funding")))
            MarketDataExplanation(text: L("label_value", L("futures_open_interest"), L("explain_open_interest")))
        }
        if let futures {
            WatchlistFuturesSection(info: futures, watch: watch)
        } else {
            MarketDataMessage(text: L(futuresLoaded ? "futures_no_data" : "loading_hint"))
        }
    }

    /// Lädt, was das offene Register braucht (jedes nur einmal je Paar).
    private func load() async {
        let pair = WatchEdit.Key(watch)
        if loadedPair != pair {
            // Paar bearbeitet: alte Werte verwerfen
            loadedPair = pair
            futures = nil
            futuresLoaded = false
            spot = nil
            spotLoaded = false
        }
        if needFutures && !futuresLoaded {
            let info = await Self.loadFutures(watch)
            if Task.isCancelled { return }
            futures = info
            futuresLoaded = true
        }
        if tab == .spot && !spotLoaded {
            let data = await Self.loadSpot(watch)
            if Task.isCancelled { return }
            spot = data
            spotLoaded = true
        }
    }

    /// Perpetuals aus dem eigenen Kontrakt, alle anderen Krypto-Paare aus dem USDT-Perpetual des Coins
    /// (Binance, sonst Bybit, OKX); TradFi-Paare ohne Perpetual nie. Wie `fetchFutures` (Android).
    private static func loadFutures(_ watch: Watch) async -> FuturesInfo? {
        if watch.contractType.isPerpetual {
            return try? await FuturesDataSource.fetch(watch: watch)
        }
        guard SpotMarket.eligible(marketKey: watch.marketKey, tradFi: isTradFi(watch)) else { return nil }
        return try? await FuturesDataSource.fetchForBase(baseAsset: watch.baseAsset)
    }

    /// Spot-Kennzahlen (CoinGecko); nil bei TradFi, DEX-Pools und unbekannten Coins. Wie `fetchSpot` (Android).
    private static func loadSpot(_ watch: Watch) async -> SpotData? {
        guard SpotMarket.eligible(marketKey: watch.marketKey, tradFi: isTradFi(watch)),
              let symbol = SpotMarket.lookupSymbol(watch.baseAsset) else { return nil }
        return await SpotDataSource.shared.fetch(symbol: symbol)
    }

    private static func isTradFi(_ watch: Watch) -> Bool {
        CoinLogoStore.tradFiPairs.contains(watch.logoPairKey)
    }
}

/// Kurzer grauer Satz im Register («Wird geladen…», «Keine Futures-Daten für diesen Coin»).
struct MarketDataMessage: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.vertical, Spacing.sm)
    }
}

/// Erklärung unter der Kopfzeile (ⓘ), kleiner Nebentext, umbrechend.
struct MarketDataExplanation: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// Funding Rate, nächste Zahlung und Open Interest eines Perpetuals — wie `FuturesSection`.
struct WatchlistFuturesSection: View {
    let info: FuturesInfo
    let watch: Watch

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            HStack(alignment: .top, spacing: 12) {
                fundingColumn
                    .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .leading, spacing: 3) {
                    Text(L("futures_open_interest"))
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                    Text(info.openInterestUsd.map { "$" + Self.compactNumber($0) } ?? "—")
                        .font(AppFont.amount(.headline, weight: .semibold))  // Betragsschrift wie Android
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Text(sourceText)
                .font(.caption2)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// Herkunft: «Quelle: WOO X Futures», wenn die beobachtete Börse selbst liefert; sonst als Richtwert
    /// gekennzeichnet — bei Perpetuals mit dem Hinweis, dass Funding und Open Interest je Börse gelten
    /// (`FuturesSources.note`). Wie `futuresSourceText` (Android).
    private var sourceText: String {
        let perpetual = watch.contractType.isPerpetual
        if FuturesSources.note(perpetual: perpetual, marketKey: watch.marketKey, source: info.source, pairId: watch.pairId) == nil {
            return L("futures_source", info.source)
        }
        if perpetual {
            return L("futures_reference_hint", info.source, watch.marketName)
        }
        return L("futures_reference_spot", info.source)
    }

    private var fundingColumn: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(L("futures_funding"))
                .font(.caption)
                .foregroundStyle(AppColors.onSurfaceVariant)
            // Positiv: Longs zahlen an Shorts, negativ umgekehrt — als farbige Schrift wie alle Werte
            // mit Vorzeichen; gerundet 0 bzw. ohne Wert grau
            fundingLine
                .padding(.top, 2)
            // Alarme rechnen pro 8 Std.: den umgerechneten Wert zeigen, damit ein abgetippter Wert passt
            if let interval = info.intervalHours, interval != 8,
               let per8h = DerivativesAlarm.fundingPer8h(info.fundingRatePercent, source: info.source,
                                                         intervalHours: info.fundingIntervalHours) {
                Text(L("futures_funding_per8h_equiv", PriceFormat.signedPercent(per8h, decimals: Self.fundingDecimals)))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            if let next = info.nextFundingTime {
                TimelineView(.periodic(from: .now, by: 30)) { context in
                    let minutes = max(0, (next - context.date.millis) / 60_000)
                    Text(L("futures_next_funding", Int(minutes / 60), Int(minutes % 60)))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
        }
    }

    /// Änderung; nicht alle 8 Stunden abgerechnet (Hyperliquid stündlich, manche Binance-/Bybit-Kontrakte
    /// alle 1, 2 oder 4 Std.) mit «pro Std.» bzw. «pro 4 Std.» dahinter — wie Android.
    private var fundingLine: some View {
        let interval = info.intervalHours
        let hasRate = info.fundingRatePercent?.isFinite ?? false
        return HStack(spacing: Spacing.xs) {
            fundingPill(info.fundingRatePercent)
            if interval != 8 && hasRate {
                Text(interval.map { $0 == 1 ? L("futures_funding_per_hour") : L("futures_funding_per_hours", $0) }
                     ?? L("futures_funding_interval_unknown"))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        }
    }

    /// Nachkommastellen der Funding Rate («+0.0100%») — wie Android.
    private static let fundingDecimals = 4

    /// Funding Rate als farbige Schrift in der Kursfarbe, «—» grau ohne Wert.
    private func fundingPill(_ rate: Double?) -> some View {
        let value = rate.flatMap { $0.isFinite ? $0 : nil }
        let text = value.map { PriceFormat.signedPercent($0, decimals: Self.fundingDecimals) } ?? "—"
        let tone = PillTone.forValue(value) { DecimalText.rounded(abs($0), Self.fundingDecimals) == 0 }
        return TonePill(text: text, tone: tone, size: .medium)
    }

    /// Offene Positionen kurz in der Sprache der App («1,2 Mrd.», "1.2B") — wie Android `CompactAmount.number`.
    static func compactNumber(_ value: Double) -> String {
        LocaleNumbers.compact(value)
    }
}
