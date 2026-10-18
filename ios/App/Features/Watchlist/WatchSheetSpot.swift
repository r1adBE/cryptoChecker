import SwiftUI

/// Register «Spot» der Karte «Marktdaten»: lädt noch, keine Spot-Daten (TradFi, DEX, unbekannter Coin)
/// oder die Kennzahlen — wie `SpotSection` (Android).
struct WatchlistSpotSection: View {
    let data: SpotData?
    let loaded: Bool
    let showExplain: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            if showExplain { explanations }
            if let data {
                WatchlistSpotValues(data: data)
            } else {
                MarketDataMessage(text: L(loaded ? "spot_no_data" : "loading_hint"))
            }
        }
    }

    private var explanations: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            MarketDataExplanation(text: L("spot_market_cap") + ": " + L("explain_spot_market_cap"))
            MarketDataExplanation(text: L("spot_volume_24h") + ": " + L("explain_spot_volume"))
            MarketDataExplanation(text: L("spot_fdv") + ": " + L("explain_spot_fdv"))
            MarketDataExplanation(text: L("explain_spot_supply"))
            MarketDataExplanation(text: L("explain_spot_source"))
        }
    }
}

/// Die Kennzahlen in zwei Spalten, darunter der Umlauf mit Balken und die Quelle.
private struct WatchlistSpotValues: View {
    let data: SpotData

    /// Quelle der Spot-Kennzahlen (Eigenname, nicht übersetzt).
    private static let source = "CoinGecko"

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            HStack(alignment: .top, spacing: 12) {
                SpotMetric(label: L("spot_market_cap"), value: SpotMarket.usd(data.marketCap)) {
                    // 24h-Veränderung der Marktkapitalisierung als farbige Schrift wie alle Werte mit Vorzeichen
                    ChangePill(change: data.marketCapChange24h, size: .small)
                        .padding(.top, 2)
                }
                SpotMetric(label: L("spot_volume_24h"), value: SpotMarket.usd(data.volume24h))
            }
            HStack(alignment: .top, spacing: 12) {
                SpotMetric(label: L("spot_vol_mcap"), value: volumeShare)
                SpotMetric(label: L("spot_fdv"), value: SpotMarket.usd(data.fdv))
            }
            HStack(alignment: .top, spacing: 12) {
                SpotMetric(label: L("spot_total_supply"), value: SpotMarket.supply(data.totalSupply, symbol: data.symbol))
                SpotMetric(label: L("spot_max_supply"), value: SpotMarket.supply(data.maxSupply, symbol: data.symbol))
            }
            SpotMetric(label: L("spot_circulating"), value: SpotMarket.supply(data.circulatingSupply, symbol: data.symbol)) {
                circulatingShare
            }
            Text(sourceLine)
                .font(.caption2)
                .foregroundStyle(AppColors.onSurfaceVariant)
            CoinGeckoAttribution()
        }
    }

    private var volumeShare: String {
        SpotMarket.percent(SpotMarket.volumeToMarketCap(volume: data.volume24h, marketCap: data.marketCap))
    }

    private var sourceLine: String {
        guard let updated = data.lastUpdated else { return L("futures_source", Self.source) }
        return L("spot_source_updated", Self.source, PriceFormat.time(updated))
    }

    /// Anteil Umlauf an der Höchstmenge: Balken in der Themenfarbe und «94.76% der Höchstmenge im Umlauf».
    @ViewBuilder
    private var circulatingShare: some View {
        if let ratio = SpotMarket.supplyRatio(circulating: data.circulatingSupply, max: data.maxSupply) {
            SupplyBar(ratio: ratio)
                .padding(.top, Spacing.xs)
            Text(L("spot_supply_of_max", SpotMarket.percent(ratio * 100)))
                .font(.caption2)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, 2)
        }
    }
}

/// Bezeichnung klein und grau, Wert darunter; `extra` unter dem Wert (Änderung, Balken).
private struct SpotMetric<Extra: View>: View {
    let label: String
    let value: String
    let extra: Extra

    init(label: String, value: String, @ViewBuilder extra: () -> Extra) {
        self.label = label
        self.value = value
        self.extra = extra()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.caption)
                .foregroundStyle(AppColors.onSurfaceVariant)
            Text(value)
                .font(AppFont.amount(.headline, weight: .semibold))  // Betragsschrift wie Android
                .fixedSize(horizontal: false, vertical: true)
            extra
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

extension SpotMetric where Extra == EmptyView {
    init(label: String, value: String) {
        self.init(label: label, value: value) { EmptyView() }
    }
}

/// Anteil als Balken in der Themenfarbe auf grauer Spur.
private struct SupplyBar: View {
    let ratio: Double
    @Environment(\.appAccent) private var accent

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width * CGFloat(Swift.min(Swift.max(ratio, 0), 1))
            ZStack(alignment: .leading) {
                Capsule().fill(AppColors.outlineVariant)
                Capsule().fill(accent.primary).frame(width: width)
            }
        }
        .frame(height: 6)
        .accessibilityHidden(true)
    }
}
