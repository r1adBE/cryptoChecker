import SwiftUI

/// Bitcoin-Dominanz und Altcoin-Saison als zwei Zeilen unter «Einordnung». Tippen zeigt bei
/// der Dominanz die Anteile als Balken, bei der Altcoin-Saison Balken, Erklärung, Stand mit
/// «Aktualisieren» und die Quelle. Wie `DominanceRows` in Android.
struct CycleDominanceRows: View {
    let dominance: CycleLoad<Dominance>
    let altSeason: CycleLoad<AltSeason>
    let onRetry: () -> Void
    /// Stand der gezeigten Altcoin-Saison (Zwischenspeicher 3 h); nil = noch nichts.
    var altSeasonAsOf: Int64? = nil
    var altSeasonRefreshing = false
    var onRefreshAltSeason: () -> Void = {}
    /// Herkunft und Stand der Dominanz (CoinGecko) bzw. der Altcoin-Saison (Kerzen-Anbieter).
    var dominanceStamp: DataStamp? = nil
    var altSeasonStamp: DataStamp? = nil
    @State private var dominanceExpanded = false
    @State private var altExpanded = false

    var body: some View {
        let d = dominance.value
        let a = altSeason.value
        let altLabel = a.map { L($0.index >= 75 ? "altseason_alt" : ($0.index <= 25 ? "altseason_btc" : "altseason_mixed")) } ?? ""
        VStack(spacing: 0) {
            MarketRow(
                title: L("insights_dominance_title"),
                // BTC steht rechts; hier ETH (ohne ETH-Wert bleibt die Zeile leer, gleiche Höhe)
                secondary: d?.eth.map { L("insights_dominance_eth", CycleFormat.percent1($0)) } ?? "",
                value: d.map { CycleFormat.percent1($0.btc) },
                state: CycleFearGreedRow.rowState(dominance),
                stamp: dominanceStamp,
                onRetry: onRetry,
                expanded: d == nil ? nil : $dominanceExpanded,
                details: AnyView(VStack(alignment: .leading, spacing: 0) {
                    if let d {
                        shareBar(d)
                        CycleSourceText(text: L("insights_source_dominance"))
                    }
                })
            )
            MarketRow(
                title: L("insights_altseason_title"),
                // Anbieter und Alter («… · Binance · heute 14:05») hängt MarketRow aus dem Stand an
                secondary: altLabel,
                value: a.map { LocaleNumbers.integer($0.index) },
                state: CycleFearGreedRow.rowState(altSeason),
                stamp: altSeasonStamp,
                onRetry: onRetry,
                expanded: a == nil ? nil : $altExpanded,
                details: AnyView(VStack(alignment: .leading, spacing: 0) {
                    if let a {
                        CycleProgressBar(fraction: Double(a.index) / 100, label: L("insights_altseason_title"))
                        Text(L("insights_altseason_value", count: a.outperformers, a.outperformers, a.total))
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .padding(.top, Spacing.xs)
                        if let asOf = altSeasonAsOf {
                            altSeasonAsOfRow(asOf)
                        }
                        CycleSourceText(text: L("insights_source_dominance"))
                    }
                })
            )
        }
    }

    /// «Stand 14:05» und ein kleines «Aktualisieren»: erst 5 Min. nach dem letzten Stand
    /// wieder aktiv (`CycleCachePolicy.manualMinInterval`), sonst gilt der 3-h-Zwischenspeicher.
    private func altSeasonAsOfRow(_ asOf: Int64) -> some View {
        TimelineView(.periodic(from: .now, by: 15)) { context in
            let now = Int64(context.date.timeIntervalSince1970 * 1000)
            let enabled = !altSeasonRefreshing
                && CycleCachePolicy.canManualRefresh(savedAt: asOf, now: now, minInterval: CycleCachePolicy.manualMinInterval)
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(L("pulse_updated", PriceFormat.time(asOf)))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(L("action_refresh"), action: onRefreshAltSeason)
                    .font(.caption.weight(.semibold))
                    .buttonStyle(.borderless)
                    .disabled(!enabled)
            }
            .padding(.top, 8)
        }
    }

    /// Anteile als Balken: BTC · ETH · übrige.
    private func shareBar(_ d: Dominance) -> some View {
        let btc = max(d.btc, 0.1)
        let eth = max(d.eth ?? 0, 0)
        let rest = max(100 - d.btc - eth, 0)
        let sum = btc + eth + rest
        return GeometryReader { geo in
            HStack(spacing: 0) {
                Rectangle()
                    .fill(AssetColors.bitcoin)
                    .frame(width: geo.size.width * CGFloat(btc / sum))
                if eth > 0 {
                    Rectangle()
                        .fill(AssetColors.ethereum)
                        .frame(width: geo.size.width * CGFloat(eth / sum))
                }
                if rest > 0 {
                    Rectangle()
                        .fill(AppColors.outlineVariant)
                        .frame(width: geo.size.width * CGFloat(rest / sum))
                }
            }
        }
        .frame(height: 10)
        .clipShape(Capsule())
        .accessibilityHidden(true)
    }
}
