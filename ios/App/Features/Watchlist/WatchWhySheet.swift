import SwiftUI

// «💡 Was bewegt sich mit?» — wie `WatchWhySheet.kt`.

/// Ladezustand des «Was bewegt sich mit?»-Blatts.
private enum WatchlistWhyState: Equatable {
    case loading
    case loaded(WhyReport)
    case failed
}

/// «Was bewegt sich mit BTC?»: Kopf mit Paar, Kurs und 1h/24h; darunter kurz die Karte «Kurz gesagt ·
/// Stand 14:00» (`WatchlistWhyBriefCard`: ein Satz, bis zu drei auffällige Faktoren als grauer Text)
/// und zugeklappt «Alle Werte und Quellen» (für die Sitzung gemerkt) mit den
/// Teilen: (1) «Beobachtung» mit Datenstand — Signale und Messwerte als Tatsache;
/// (2) «Mögliche Einordnung» — nur als zeitliches Zusammentreffen formuliert, nie als Ursache;
/// (3) ausführlicher Hinweis «kein Ursachenbeweis» und die Quellen. Ganz unten kurz «Zeitgleich
/// heisst nicht Ursache · Keine Anlageberatung».
/// Als Seite im `NavigationStack` des Tabs geöffnet (wie «Alarme»). Wie `WhySheet` in `WatchWhySheet.kt`.
struct WatchlistWhySheet: View {
    let watchId: Int64
    /// Vorschau-Paar (nicht in der Merkliste, aus «Heute auffällig»), falls `watchId` keinen
    /// gespeicherten Eintrag meint; ohne Signale (das Paar wird nicht überwacht).
    var preview: Watch? = nil

    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var state: WatchlistWhyState = .loading
    @State private var attempt = 0
    /// 24-h-Wert (= Änderung, rollend) aus den Kerzen einer anderen Börse; nil = nicht.
    @State private var candleSource: String? = nil
    /// «Alle Werte und Quellen» aufgeklappt (Startwert: zuletzt in dieser Sitzung).
    @State private var allValues = WatchlistWhyAllValuesState.expanded

    var body: some View {
        // Nicht mehr gehandelt bzw. deaktiviert: kein «Was bewegt sich mit?»
        if let watch = data.watch(watchId) ?? preview, !watch.isNotTraded, !watch.disabled {
            content(watch)
                // Eigene Seite (Merkliste, Markt): Titel oben, Zurück schliesst sie
                .navigationTitle(L("why_title"))
                .navigationBarTitleDisplayMode(.inline)
                .background(AppColors.background.ignoresSafeArea())
        } else {
            // Paar wurde inzwischen gelöscht — oder wird nicht mehr gehandelt (kein Urteil auf alten Daten)
            Color.clear.onAppear { dismiss() }
        }
    }

    /// Gleichlauf mit Bitcoin (Stunden-Renditen, Kerzen von «Was bewegt sich mit?»): eng bzw. unabhängig; nil = kein Satz.
    static func btcLine(_ link: BtcLink?, base: String) -> String? {
        guard let link else { return nil }
        let symbol = BidiText.isolate(base.trimmingCharacters(in: .whitespaces).uppercased())
        switch link {
        case .tight: return L("why_btc_tight", symbol)
        case .independent: return L("why_btc_independent", symbol)
        }
    }

    private func content(_ watch: Watch) -> some View {
        let report: WhyReport? = {
            if case .loaded(let r) = state { return r }
            return nil
        }()
        let signals = WatchlistActivity.active(data.activityReports[watch.id], now: EpochTime.nowMillis,
                                               sensitivity: data.settings.activitySensitivity)
        // «Was bewegt sich mit?» zeigt bei rollender Basis die Änderung selbst; bei «seit 00:00» rechnet es eigene Kerzen
        let candleNote: String? = candleSource.flatMap { source in
            report?.change24h != nil && !data.settings.changeBasis.isDay ? L("change_candle_source", source) : nil
        }

        return ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // Kopf: Frage, Paar, Kurs, 1h/24h
                HStack(spacing: 8) {
                    Image(systemName: "lightbulb.fill")
                        .scaledFont(size: 15, weight: .semibold, relativeTo: .subheadline)
                        .foregroundStyle(accent.primary)
                    Text(watch.baseAsset.isEmpty ? L("why_title") : L("why_title_coin", watch.baseAsset))
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                HStack(spacing: 12) {
                    CoinBadge(symbol: watch.baseAsset, size: 40, logo: CoinLogos.allowed(forExchange: watch.exchangeKey),
                              pair: watch.logoPairKey)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(watch.displayName)
                            .font(AppFont.headline)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        Text(watch.exchangeName)
                            .font(.subheadline)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                }
                .padding(.top, Spacing.sm)

                HStack(spacing: 8) {
                    Text(PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset))
                        .font(AppFont.amount(.title3, weight: .semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    WatchlistWhyChangeBadge(label: L("why_change_1h"), change: report?.change1h)
                    WatchlistWhyChangeBadge(label: L("why_change_24h"), change: report?.change24h, note: candleNote)
                }
                .padding(.top, 12)
                .padding(.bottom, candleNote == nil ? 16 : 2)
                // 24-h-Wert aus fremden Kerzen (z. B. Kraken-Paar → Binance): klein darunter sagen, woher
                if let candleNote {
                    Text(candleNote)
                        .font(.caption2)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .padding(.bottom, 16)
                }

                // Oben kurz: «Kurz gesagt · Stand …» mit Satz und auffälligen Faktoren;
                // darunter zugeklappt «Alle Werte und Quellen» (beim Laden ein form-gleicher Platzhalter)
                switch state {
                case .loading:
                    WatchlistWhySkeleton()
                        .transition(.opacity)
                case .failed:
                    VStack(alignment: .leading, spacing: Spacing.md) {
                        WatchlistWhyEmpty(onRetry: { attempt += 1 })
                        WatchlistWhySignals(signals: signals)
                    }
                case .loaded(let r):
                    loadedContent(r, watch: watch, signals: signals)
                        // Nur überblenden: der Platzhalter hat dieselbe Form, nichts rückt nach
                        .transition(.opacity)
                }

                // Fusszeile kurz; der ausführliche Hinweis steht unter «Alle Werte und Quellen»
                Text(L("why_footer_short"))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Spacing.xl)
            .padding(.top, 24)
            .padding(.bottom, 16)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: state)
        }
        .scrollIndicators(.hidden)
        .background(AppColors.background.ignoresSafeArea())
        .task(id: attempt) {
            state = .loading
            if let loaded = await data.explain(watch) {
                state = .loaded(loaded)
            } else if !Task.isCancelled {
                state = .failed
            }
        }
        // Woher die Änderung kommt (fremde Kerzen, z. B. Kraken-Paar → Binance)
        .task(id: "\(watch.id)|\(watch.lastUpdate)") {
            candleSource = await PriceRefresher.foreignCandleSource(watch)
        }
    }

    /// Geladener Bericht: Karte «Kurz gesagt» und zugeklappt «Alle Werte und Quellen».
    /// Eigene Funktion, damit der grosse ViewBuilder oben den Type-Checker nicht überfordert.
    private func loadedContent(_ r: WhyReport, watch: Watch, signals: [ActivitySignal]) -> some View {
        let factors = WhyFactors.rank(r)
        return VStack(alignment: .leading, spacing: Spacing.md) {
            if !r.hasMarketData {
                WatchlistWhyEmpty(onRetry: nil)
            }
            WatchlistWhyBriefCard(report: r, factors: factors)
            WatchlistWhyAllValuesCard(expanded: $allValues) {
                WatchlistWhyAllValues(report: r, factors: factors, signals: signals,
                                      btcLine: Self.btcLine(r.btcLink, base: watch.baseAsset))
            }
        }
    }
}
