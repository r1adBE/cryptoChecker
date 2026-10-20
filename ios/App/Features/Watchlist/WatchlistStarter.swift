import Accessibility
import SwiftUI

/// Start-Auswahl der leeren Merkliste — wie `StarterPicker` in Android.
///
/// Karte im Stil von «Ungewöhnliche Aktivität»: die fünf Startcoins mit Kurs in der
/// Start-Quote USDT (Binance, in den USA Binance.US — `StarterPairs.marketKey`), 24-h-Änderung und Auswahlkreis rechts. Zeile
/// tippen = an/ab; anfangs sind alle gewählt. Darunter «Zur Merkliste hinzufügen (n)»
/// und «Alle/Keine auswählen». Hinzufügen geht über `onAdd` (gleicher Weg wie bisher).
@MainActor
struct WatchlistStarterPicker: View {
    let coins: [StarterCoin]
    /// Ländercode der Geräte-Region (Startbörse und Quote, siehe `StarterPairs`).
    let region: String?
    let onAdd: ([String]) -> Void

    @Environment(\.appAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// nil = unberührt (alle gewählt, auch wenn die Liste noch still getauscht wird).
    @State private var selection: Set<String>?
    @State private var prices: [String: StarterPrice] = [:]
    @State private var loadingPrices = true
    /// Abruf fertig, aber kein einziger Kurs (offline, Börse nicht erreichbar): «Keine Verbindung» mit
    /// «Erneut versuchen» unter der Karte (wie Android `StarterPricesState.failed`).
    @State private var pricesFailed = false
    /// «Erneut versuchen»: startet das Laden der Kurse neu.
    @State private var retryTick = 0
    /// Schutz gegen doppeltes Tippen.
    @State private var adding = false
    @State private var selectionTick = 0
    /// Logos der Start-Coins geladen (bzw. nach 4 s aufgegeben): bis dahin ruhiger Platzhalter statt
    /// Initialen — kein Umspringen ein paar Sekunden nach dem Installieren (wie Android).
    @State private var logosReady = false
    @Environment(\.coinLogosEnabled) private var coinLogosEnabled

    private var quote: String { StarterPairs.startQuote(region) }

    private func isSelected(_ coin: StarterCoin) -> Bool {
        selection?.contains(coin.symbol) ?? true
    }

    private var selectedSymbols: [String] {
        coins.filter { isSelected($0) }.map(\.symbol)
    }

    var body: some View {
        let count = selectedSymbols.count
        let allSelected = count == coins.count
        VStack(spacing: 12) {
            card
                .padding(.top, 4)

            if pricesFailed && !loadingPrices {
                // Hinzufügen geht trotzdem; nur sagen, was los ist, und erneut laden lassen
                HStack(spacing: Spacing.xs) {
                    Image(systemName: "icloud.slash")
                        .accessibilityHidden(true)
                    Text(L("offline_status"))
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button(L("try_again")) { retryTick += 1 }
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(accent.primary)
                        .frame(minHeight: 44)
                }
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurfaceVariant)
            }

            // Ein Satz, was die App hier tut: «Kurs beobachten, Alarm setzen»
            Label {
                Text(L("starter_watch_alarm_hint"))
            } icon: {
                // Neutrales Kurs-Symbol (wie «Linie» im Chart-Umschalter): die Alarm-Glocke im Kopf erscheint
                // erst mit Alarmen, im leeren Start würde sie auf nichts zeigen — wie Android
                Image(systemName: "chart.xyaxis.line")
                    .accessibilityHidden(true)
            }
            .font(.subheadline)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .multilineTextAlignment(.center)
            .padding(.top, 4)

            // Und was man danach bekommt: Tipp auf einen Coin öffnet Alarm und «Was bewegt sich mit?» (Symbol wie
            // der ⚡ der Zeile) — wie Android
            Label {
                Text(L("watch_hint_tap_why"))
            } icon: {
                Image(systemName: "bolt")
                    .accessibilityHidden(true)
            }
            .font(.subheadline)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .multilineTextAlignment(.center)

            Button {
                add()
            } label: {
                Label(L("starter_add_selected", count), systemImage: "plus")
                    .font(.headline)
                    .contentTransition(.numericText(value: Double(count)))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Spacing.lg)
            }
            .buttonStyle(AccentButtonStyle())
            .disabled(count == 0 || adding)
            .accessibilityIdentifier(UiTestTags.starterAdd)

            Button {
                selectionTick += 1
                withAnimation(reduceMotion ? nil : .snappy) {
                    selection = allSelected ? [] : Set(coins.map(\.symbol))
                }
            } label: {
                Text(L(allSelected ? "starter_select_none" : "starter_select_all"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(accent.primary)
                    .padding(.vertical, Spacing.sm)
                    .padding(.horizontal, 12)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .sensoryFeedback(.selection, trigger: selectionTick)
        // Kurse: einmal für alle fünf, neu bei einer anderen Liste
        // Auch bei gewechselter Startbörse (Standort-Sperre → Binance.US) neu laden
        // «Erneut versuchen» zählt `retryTick` hoch und lädt damit ebenfalls neu
        .task(id: coins.map(\.symbol) + [region ?? "", String(retryTick)]) { await loadPrices() }
        // Logos der Start-Coins zuerst (höchstens 4 s warten, danach Initialen)
        .task(id: coins.map(\.symbol)) { await loadStarterLogos() }
    }

    private func loadStarterLogos() async {
        guard coinLogosEnabled else {
            logosReady = true
            return
        }
        let symbols = coins.map(\.symbol)
        await withTaskGroup(of: Void.self) { group in
            group.addTask { _ = await CoinLogoStore.shared.ensureStarterLogos(symbols) }
            group.addTask { try? await Task.sleep(nanoseconds: 4_000_000_000) }
            await group.next()
            group.cancelAll()
        }
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) { logosReady = true }
    }

    // MARK: Karte

    /// Wie die Merkliste: die Zeilen als eine Karte mit feinen Trennlinien (`ListSegment`).
    private var card: some View {
        VStack(spacing: ListSegment.gap) {
            ForEach(Array(coins.enumerated()), id: \.element.id) { index, coin in
                let shape = ListSegment.shape(index, coins.count)
                row(coin)
                    .frame(maxWidth: .infinity)
                    .background(AppColors.container, in: shape)
                    .segmentLines(shape)
            }
        }
    }

    private func row(_ coin: StarterCoin) -> some View {
        let selected = isSelected(coin)
        let price = prices[coin.symbol]
        return Button {
            toggle(coin)
        } label: {
            HStack(spacing: 12) {
                if logosReady || !coinLogosEnabled {
                    CoinBadge(symbol: coin.symbol, size: ListSegment.logo)
                } else {
                    Circle()
                        .fill(AppColors.containerHighest)
                        .frame(width: ListSegment.logo, height: ListSegment.logo)
                        .accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: coin.name)
                        .font(.headline)
                        .foregroundStyle(AppColors.onSurface)
                        .lineLimit(1)
                    Text(verbatim: coin.symbol)
                        .font(.subheadline.monospaced())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                priceColumn(price)

                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .scaledFont(size: 22, weight: .semibold, relativeTo: .title3)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .foregroundStyle(selected ? accent.primary : AppColors.outline)
                    .contentTransition(.symbolEffect(.replace))
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.md)
            .frame(minHeight: 60)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // VoiceOver: eine Zeile, ein Satz; Standardaktion schaltet die Auswahl
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: accessibilityText(coin, price: price)))
        .accessibilityValue(selected ? Text(verbatim: "") : Text(L("ios_a11y_not_selected")))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : [.isButton])
        .accessibilityAction { toggle(coin) }
    }

    @ViewBuilder
    private func priceColumn(_ price: StarterPrice?) -> some View {
        if let price {
            VStack(alignment: .trailing, spacing: 4) {
                // Gegenwährung klein und grau wie der Börsenname (wie in der Merkliste)
                PriceWithQuote(price: PriceFormat.price(price.price), quote: price.quote ?? quote,
                               font: AppFont.amount(.subheadline, weight: .semibold))
                    .minimumScaleFactor(0.75)
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    // Tageswert statt gleitender 24 h: klein und grau davor «Seit 00:00 UTC» (wie Android)
                    if price.sinceUtcDay, price.change != nil {
                        Text(A11y.changeLongLabel(.UTC_DAY))
                            .font(.caption)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .lineLimit(1)
                    }
                    ChangePill(change: price.change)
                }
            }
            .fixedSize(horizontal: true, vertical: false)
            .transition(.opacity)
        } else if loadingPrices {
            SkeletonPulse {
                VStack(alignment: .trailing, spacing: Spacing.xs) {
                    SkeletonBlock(width: 72, height: 12)
                    SkeletonBlock(width: 44, height: 10)
                }
            }
            .transition(.opacity)
        }
        // Fehler: Zeile ohne Kurs, Auswahl geht trotzdem
    }

    /// «Bitcoin, BTC, 98’450 USDT, gestiegen um 2.30%» (+ «Kurse werden geladen …»).
    private func accessibilityText(_ coin: StarterCoin, price: StarterPrice?) -> String {
        var parts: [String?] = [coin.name, coin.symbol]
        if let price {
            parts.append(PriceFormat.priceWithCurrency(price.price, price.quote ?? quote))
            // Tageswert: «gestiegen um 1.20% seit 0 Uhr UTC» statt ohne Zeitraum
            parts.append(price.sinceUtcDay && price.change != nil ? A11y.change(price.change, basis: .UTC_DAY) : A11y.change(price.change))
        } else if loadingPrices {
            parts.append(L("starter_loading_prices"))
        }
        return A11y.join(parts)
    }

    // MARK: Ablauf

    private func toggle(_ coin: StarterCoin) {
        selectionTick += 1
        var current = selection ?? Set(coins.map(\.symbol))
        if current.contains(coin.symbol) { current.remove(coin.symbol) } else { current.insert(coin.symbol) }
        withAnimation(reduceMotion ? nil : .snappy) { selection = current }
    }

    private func add() {
        let symbols = selectedSymbols
        guard !adding, !symbols.isEmpty else { return }
        adding = true
        onAdd(symbols)
        // Bleibt die Liste leer (z. B. Börse unbekannt), wieder freigeben
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            adding = false
        }
    }

    private func loadPrices() async {
        let symbols = coins.map(\.symbol)
        if let hit = await StarterPriceSource.shared.cached(symbols: symbols, region: region) {
            prices = hit
            loadingPrices = false
            pricesFailed = hit.isEmpty
            return
        }
        loadingPrices = true
        pricesFailed = false
        let loaded = await StarterPriceSource.shared.prices(symbols: symbols, region: region)
        if Task.isCancelled { return }
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.25)) {
            // Fehler: alte Kurse bleiben (wie Android); gar keiner da → «Keine Verbindung»
            if !loaded.isEmpty { prices = loaded }
            loadingPrices = false
            pricesFailed = prices.isEmpty
        }
    }
}
