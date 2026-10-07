import SwiftUI

/// Kurse, Devisenkurs und Coin-Liste für Portfolio-Tab, Detailansicht und
/// Erfassen-Blatt (auch aus der Merkliste) — wie `PortfolioViewModel.kt`.
/// Die Transaktionen selbst liegen in `AppData.portfolio`.
/// Kurse werden erst geladen, wenn eine Ansicht `refresh` aufruft — das Blatt
/// allein holt nur den Kurs des gewählten Coins.
@MainActor
final class PortfolioModel: ObservableObject {
    static let shared = PortfolioModel()

    @Published private(set) var prices = PortfolioPrices()
    /// Kurse werden gerade geladen (Kreisel oben rechts).
    @Published private(set) var refreshing = false
    /// USD → `fxCurrency`; nil = unbekannt (Umrechnungszeile ausblenden).
    @Published private(set) var fxRate: Double?
    @Published private(set) var fxCurrency = ""
    /// Wählbare Coins für die Suche (leer, solange nicht geladen).
    @Published private(set) var coins: [String] = []

    /// Zeitraum des Wertverlaufs (Chips 7 T / 30 T / 1 J / Seit 1. Kauf) — zuletzt gewählt, nur auf
    /// diesem Gerät (`AppSettings.portfolioHistoryRange`, nicht in der Sicherung).
    @Published var historyRange: PortfolioHistoryRange = .month {
        didSet {
            guard historyRange != oldValue else { return }
            if data.settings.portfolioHistoryRange != historyRange { data.settings.portfolioHistoryRange = historyRange }
            recomputeHistory()
            // Nur der gewählte Zeitraum lädt; «Seit 1. Kauf» braucht evtl. mehr Tage
            Task { await loadHistory() }
        }
    }
    /// Wertverlauf des gewählten Zeitraums; nil, solange dessen Tageskurse noch laden (Platzhalter).
    /// Auch zugeklappt gerechnet — die Zeile zeigt die Änderung.
    @Published private(set) var history: PortfolioHistoryUi?

    /// Geladene Tagesschlusskurse und für welche Anfrage.
    private var historyCloses: (request: PortfolioHistoryRequest, closes: [String: [Int: Double]])?
    private var historyTask: Task<Void, Never>?

    private var activeRefreshes = 0
    private var loadingCoins = false

    private init() {
        historyRange = AppData.shared.settings.portfolioHistoryRange
    }

    private var data: AppData { AppData.shared }

    /// Beim Öffnen (60 s Zwischenspeicher), bei neuen Coins bzw. per Ziehen (`force`).
    func refresh(force: Bool) async {
        activeRefreshes += 1
        refreshing = true
        defer {
            activeRefreshes -= 1
            refreshing = activeRefreshes > 0
        }
        let symbols = Array(Set(data.portfolio.map(\.coin)))
        if !symbols.isEmpty {
            // Verlauf parallel (Tageskerzen je Coin 12 h zwischengespeichert; Fehlgeschlagenes neu)
            Task { await loadHistory() }
            // Erst der Zwischenspeicher, damit sofort etwas dasteht (auch für neue Coins)
            prices = PortfolioPriceSource.cached(symbols)
            prices = await PortfolioPriceSource.prices(symbols, force: force)
        }
        let currency = data.settings.portfolioCurrency
        if let rate = await FxRateSource.usdTo(currency), data.settings.portfolioCurrency == currency {
            fxRate = rate
            fxCurrency = currency
        }
        recomputeHistory()
        // Portfolio-Widget: Stand mit denselben Kursen (zeichnet nur bei Änderung neu)
        if data.settings.portfolioCurrency == currency {
            PortfolioWidgetStore.update(transactions: data.portfolio, prices: prices, currency: currency,
                                        rate: rate(for: currency))
        }
    }

    /// Zielwährung gewechselt: zuerst der bekannte Kurs, dann frisch abfragen.
    func loadFx(_ currency: String) async {
        if fxCurrency != currency {
            fxRate = FxRateSource.cached(currency)
            fxCurrency = currency
        }
        let rate = await FxRateSource.usdTo(currency)
        if fxCurrency == currency { fxRate = rate ?? fxRate }
        recomputeHistory()
    }

    // MARK: Wertverlauf

    /// Welche Tageskurse der gewählte Zeitraum braucht (Coins und Tage bis heute).
    private func historyRequest() -> PortfolioHistoryRequest {
        PortfolioHistoryRequest.make(data.portfolio, range: historyRange)
    }

    /// Tageskurse der aktuellen Coins für den gewählten Zeitraum laden; ein überholter Abruf
    /// (Coins oder Zeitraum inzwischen geändert) ersetzt den neueren nicht.
    func loadHistory() async {
        let request = historyRequest()
        let closes = await PortfolioHistorySource.dailyCloses(Array(request.coins), days: request.days)
        guard request.covers(historyRequest()) else { return }
        historyCloses = (request: request, closes: closes)
        recomputeHistory()
    }

    /// Verlauf neu rechnen (Transaktionen, Kurse, Währung oder Zeitraum geändert) —
    /// abseits des Hauptthreads; der bisherige Verlauf bleibt stehen, bis der neue fertig ist.
    func recomputeHistory() {
        let txs = data.portfolio
        guard let loaded = historyCloses, loaded.request.covers(historyRequest()) else {
            historyTask?.cancel()
            history = nil
            return
        }
        let currency = data.settings.portfolioCurrency
        let fx = rate(for: currency)
        let converted = currency != "USD" && fx != nil
        let fxFactor: Double = converted ? (fx ?? 1) : 1
        let unit = converted ? currency : PortfolioFormat.usdt
        let range = historyRange
        let live = prices.prices
        let closes = loaded.closes
        historyTask?.cancel()
        historyTask = Task { [weak self] in
            let series = await Task.detached(priority: .userInitiated) {
                PortfolioHistory.build(trades: txs, closes: closes, livePrices: live, range: range,
                                       todayEpochDay: LocalDay.today().epochDay,
                                       dayEndMillis: { PortfolioHistory.dayEndMillis($0) },
                                       fxRate: fxFactor)
            }.value
            guard !Task.isCancelled else { return }
            self?.history = PortfolioHistoryUi(range: range, series: series, unit: unit, converted: converted)
        }
    }

    /// Kurs für die Umrechnungszeile — nur, wenn er zur gewählten Währung gehört.
    func rate(for currency: String) -> Double? {
        fxCurrency == currency ? fxRate : nil
    }

    func loadCoins() async {
        guard coins.isEmpty, !loadingCoins else { return }
        loadingCoins = true
        defer { loadingCoins = false }
        coins = await PortfolioPriceSource.coins(fallback: { await portfolioPairCacheCoins() })
    }

    /// Aktueller USDT-Kurs zum Vorbelegen; nil, wenn keiner zu haben ist.
    func currentPrice(_ coin: String) async -> Double? {
        await PortfolioPriceSource.price(coin)
    }
}

/// Paarliste der Börse «Binance» aus der App (gespeichert oder mitgeliefert):
/// Spot-Paare gegen USDT — Ausweichquelle der Coin-Liste.
private func portfolioPairCacheCoins() async -> [String]? {
    let info = await PairCache.shared.pairs(for: "Binance")
    let list = info.pairs
        .filter { $0.quote == PortfolioPriceSource.stable && $0.contractType == .none }
        .map { $0.base.uppercased() }
        .filter { PortfolioPriceSource.isSymbol($0) }
    return list.isEmpty ? nil : list
}

/// Welche Tagesschlusskurse ein Zeitraum braucht: Coins und Tage bis heute — wie `HistoryRequest` (Android).
struct PortfolioHistoryRequest: Equatable {
    let coins: Set<String>
    let days: Int

    /// Deckt eine Ladung für diese Anfrage `needed` ab (alle Coins, genug Tage)?
    func covers(_ needed: PortfolioHistoryRequest) -> Bool {
        coins.isSuperset(of: needed.coins) && days >= needed.days
    }

    /// «Seit 1. Kauf» braucht je nach erstem Kauf mehr Tage (`PortfolioHistory.candleDays`).
    static func make(_ txs: [PortfolioTx], range: PortfolioHistoryRange) -> PortfolioHistoryRequest {
        let first = txs.filter { $0.amount > 0 && !$0.amount.isInfinite }.map(\.time).min()
        let days = PortfolioHistory.candleDays(range: range, firstTradeMillis: first,
                                               todayEpochDay: LocalDay.today().epochDay,
                                               dayEndMillis: { PortfolioHistory.dayEndMillis($0) })
        return PortfolioHistoryRequest(coins: Set(txs.map { PortfolioCalculator.normalizeCoin($0.coin) }), days: days)
    }
}

/// Wertverlauf für die Karte über den Positionen: `series` in `unit`
/// (`converted` = mit dem heutigen Devisenkurs aus USDT umgerechnet) — wie `PortfolioHistoryUi` (Android).
struct PortfolioHistoryUi: Equatable {
    let range: PortfolioHistoryRange
    let series: PortfolioHistorySeries
    let unit: String
    let converted: Bool
}

/// Eingabe des Erfassen-Blatts; `txId` 0 = neue Transaktion — wie `TxDraft`.
struct PortfolioTxDraft: Identifiable {
    let id = UUID()
    var txId: Int64 = 0
    var coin = ""
    var type: PortfolioTxType = .BUY
    var amount: Double?
    var priceUsdt: Double?
    var time: Int64 = TimeUtils.nowMillis
    var note: String?

    init(coin: String = "", priceUsdt: Double? = nil) {
        self.coin = PortfolioCalculator.normalizeCoin(coin)
        self.priceUsdt = priceUsdt
    }

    init(tx: PortfolioTx) {
        txId = tx.id
        coin = tx.coin
        type = tx.type
        amount = tx.amount
        priceUsdt = tx.priceUsdt
        time = tx.time
        note = tx.note
    }

    var isEdit: Bool { txId != 0 }
}

// MARK: Formate

/// Gemeinsame Formate für das Portfolio — wie `PortfolioFormat` (Android).
enum PortfolioFormat {
    static let usdt = "USDT"

    /// Beträge unter einem halben Cent gelten als 0 (grau, ohne Vorzeichen).
    static func isZero(_ value: Double) -> Bool { abs(value) < 0.005 }

    /// «1’234.56 USDT».
    static func usdtValue(_ value: Double) -> String { PriceFormat.valueWithCurrency(value, usdt) }

    static func price(_ value: Double?) -> String { PriceFormat.priceWithCurrency(value, usdt) }

    /// «+1’234.56 USDT» / «−12.00 USDT» / «0.00 USDT».
    static func signedUsdt(_ value: Double) -> String {
        let sign = isZero(value) ? "" : (value > 0 ? "+" : "−")
        return sign + PriceFormat.valueWithCurrency(abs(value), usdt)
    }

    /// «+12.34%», bei praktisch 0 «0.00%».
    static func signedPercent(_ value: Double) -> String { PriceFormat.changePercent(value) ?? "0.00%" }

    static func amount(_ value: Double, _ coin: String) -> String { "\(PriceFormat.amount(value)) \(coin)" }

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .none
        return f
    }()

    static func date(_ millis: Int64) -> String { dateFormatter.string(from: Date(millis: millis)) }

    /// Kursfarbe für ± (Schema, hoher Kontrast, Tausch — wie die Prozent-Pille der
    /// Merkliste), grau bei 0 oder unbekannt. Werte aus der Umgebung der Ansicht
    /// (`\.priceColorScheme`, `\.priceHighContrast`, `\.priceColorsInverted`).
    static func plColor(_ value: Double?, scheme: PriceColorScheme, highContrast: Bool, inverted: Bool) -> Color {
        guard let value, !isZero(value) else { return AppColors.onSurfaceVariant }
        return scheme.forChange(value, highContrast: highContrast, inverted: inverted)
    }

    static func typeLabel(_ type: PortfolioTxType) -> String {
        switch type {
        case .BUY: return L("portfolio_buy")
        case .SELL: return L("portfolio_sell")
        }
    }

    static func typeColor(_ type: PortfolioTxType) -> Color {
        switch type {
        case .BUY: return PriceColors.up
        case .SELL: return PriceColors.down
        }
    }
}

// MARK: Bausteine

/// Prozent als Pille wie in der Merkliste; «—» ohne Wert.
struct PortfolioPlPill: View {
    let percent: Double?
    // Neu zeichnen, wenn Kursfarben, Tausch oder Kontrast wechseln
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceColorsInverted) private var inverted
    @Environment(\.priceHighContrast) private var highContrast

    var body: some View {
        let color = PortfolioFormat.isZero(percent ?? 0)
            ? AppColors.onSurfaceVariant
            : priceColors.forChange(percent, highContrast: highContrast, inverted: inverted)
        HStack(spacing: 3) {
            // Pfeil wie in der Merkliste (nach Vorzeichen, nie getauscht)
            ChangeArrowIcon(change: percent)
                .scaledFont(size: 9, weight: .bold, relativeTo: .caption2)
            Text(percent.map { PortfolioFormat.signedPercent($0) } ?? "—")
                .font(.system(.caption, design: .rounded).weight(.semibold).monospacedDigit())
        }
        .foregroundStyle(color)
        .lineLimit(1)
        .padding(.horizontal, 8)
        .padding(.vertical, 2)
        .background(color.opacity(0.14), in: Capsule())
        .contentTransition(.numericText())
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
    }
}

/// Kleine Kennzahl: Beschriftung oben, Wert darunter.
struct PortfolioMetric: View {
    let label: String
    let value: String
    var valueColor: Color = AppColors.onSurface

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            // Beschriftungen wie «Unrealisierter Gewinn/Verlust» dürfen umbrechen
            Text(label)
                .font(.caption)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
            Text(value)
                .font(.system(.subheadline, design: .rounded).weight(.medium).monospacedDigit())
                .foregroundStyle(valueColor)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Hinweis, warum ± fehlt.
struct PortfolioHint: View {
    let text: String
    var color: Color = AppColors.onSurfaceVariant

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(color)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// «Nur auf diesem Gerät gespeichert. Keine Anlageberatung.»
struct PortfolioDisclaimer: View {
    var body: some View {
        Text(L("portfolio_disclaimer"))
            .font(.caption2)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 16)
            .padding(.vertical, 16)
    }
}

/// Runder «+»-Knopf unten rechts (wie der FloatingActionButton).
struct PortfolioAddButton: View {
    let action: () -> Void
    @Environment(\.appAccent) private var accent

    var body: some View {
        Button(action: action) {
            Image(systemName: "plus")
                .scaledFont(size: 22, weight: .semibold, relativeTo: .title2)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .foregroundStyle(accent.onPrimary)
                .frame(width: 58, height: 58)
                .background(accent.primary, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .shadow(color: accent.primary.opacity(0.35), radius: 10, y: 4)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("portfolio_add_tx"))
        .padding(.trailing, 20)
        .padding(.bottom, 20)
    }
}

extension View {
    /// Karte im Stil der App (Fläche, Rand, 18er-Rundung).
    func portfolioSurface(padding: CGFloat = 16, radius: CGFloat = 18) -> some View {
        self
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .strokeBorder(AppColors.outlineVariant.opacity(0.45), lineWidth: 1)
            )
    }
}
