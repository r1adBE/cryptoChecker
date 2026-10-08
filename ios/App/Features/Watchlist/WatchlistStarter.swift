import Accessibility
import SwiftUI

/// Start-Auswahl der leeren Merkliste — wie `StarterPicker` in Android.
///
/// Karte im Stil von «Ungewöhnliche Aktivität»: die fünf Startcoins mit Kurs in der
/// Start-Quote (USDT; Region USA: USD), 24-h-Pille und Auswahlkreis rechts. Zeile
/// tippen = an/ab; anfangs sind alle gewählt. Darunter «Zur Merkliste hinzufügen (n)»
/// und «Alle/Keine auswählen». Hinzufügen geht über `onAdd` (gleicher Weg wie bisher).
@MainActor
struct WatchlistStarterPicker: View {
    let coins: [StarterCoin]
    let us: Bool
    let onAdd: ([String]) -> Void

    @Environment(\.appAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// nil = unberührt (alle gewählt, auch wenn die Liste noch still getauscht wird).
    @State private var selection: Set<String>?
    @State private var prices: [String: StarterPrice] = [:]
    @State private var loadingPrices = true
    /// Schutz gegen doppeltes Tippen.
    @State private var adding = false
    @State private var selectionTick = 0

    private var quote: String { us ? "USD" : "USDT" }

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
        .task(id: coins.map(\.symbol)) { await loadPrices() }
    }

    // MARK: Karte

    private var card: some View {
        VStack(spacing: 0) {
            ForEach(coins) { coin in
                if coin.id != coins.first?.id {
                    Divider()
                        .overlay(AppColors.outlineVariant.opacity(0.5))
                        .padding(.leading, 62)
                }
                row(coin)
            }
        }
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(AppColors.outlineVariant.opacity(0.6), lineWidth: 1)
        )
    }

    private func row(_ coin: StarterCoin) -> some View {
        let selected = isSelected(coin)
        let price = prices[coin.symbol]
        return Button {
            toggle(coin)
        } label: {
            HStack(spacing: 12) {
                CoinBadge(symbol: coin.symbol, size: 36)
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
                Text(PriceFormat.priceWithCurrency(price.price, quote))
                    .font(AppFont.amount(.subheadline, weight: .semibold))
                    .foregroundStyle(AppColors.onSurface)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                WatchlistChangePill(change: price.change)
            }
            .fixedSize(horizontal: true, vertical: false)
            .transition(.opacity)
        } else if loadingPrices {
            VStack(alignment: .trailing, spacing: Spacing.xs) {
                WatchlistSkeletonBlock(width: 72, height: 12)
                WatchlistSkeletonBlock(width: 44, height: 10)
            }
            .transition(.opacity)
        }
        // Fehler: Zeile ohne Kurs, Auswahl geht trotzdem
    }

    /// «Bitcoin, BTC, 98’450 USDT, gestiegen um 2.30%» (+ «Kurse werden geladen …»).
    private func accessibilityText(_ coin: StarterCoin, price: StarterPrice?) -> String {
        var parts: [String?] = [coin.name, coin.symbol]
        if let price {
            parts.append(PriceFormat.priceWithCurrency(price.price, quote))
            parts.append(A11y.change(price.change))
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
        if let hit = await StarterPriceSource.shared.cached(symbols: symbols, us: us) {
            prices = hit
            loadingPrices = false
            return
        }
        loadingPrices = true
        let loaded = await StarterPriceSource.shared.prices(symbols: symbols, us: us)
        if Task.isCancelled { return }
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.25)) {
            prices = loaded
            loadingPrices = false
        }
    }
}

/// Leere Merkliste (Start-Auswahl) und der «Erst-Hinzufügen»-Moment.
extension WatchlistScreen {
    /// Holt den ausstehenden Moment ab, sobald die Merkliste sichtbar ist (aktiver Tab, Seite
    /// «Paar hinzufügen» geschlossen). Start-Tipp: Banner, eine Haptik und die VoiceOver-Ansage
    /// hier; beim Hinzufügen über die Seite hat sie das schon gemacht — hier nur die Zeilen.
    func takeCelebration() {
        guard router.tab == .watchlist, !router.showExplorer, let celebration = data.takeAddCelebration() else { return }
        var positions: [Int64: Int] = [:]
        for (index, id) in celebration.watchIds.enumerated() { positions[id] = index }
        celebrating = positions
        if celebration.announceInWatchlist {
            firstAddHaptic += 1
            // Noch kein Alarm auf den neuen Paaren: «Alarm setzen» öffnet die Alarme des
            // ersten (wie im Aktionsblatt). Kein weiterer Banner danach.
            let counts = data.activeAlarmCounts
            let noAlarms = celebration.watchIds.allSatisfy { (counts[$0] ?? 0) == 0 }
            let action: WatchlistBannerAction? = noAlarms ? celebration.watchIds.first.map { id in
                WatchlistBannerAction(title: L("add_alarm_action")) { alarmsFor = id }
            } : nil
            banner = WatchlistBannerMessage(text: celebration.message, long: true, action: action)
            let text = celebration.message
            Task { @MainActor in
                // Erst nach dem Wechsel zur Liste ansagen, sonst geht es im Fokuswechsel unter
                try? await Task.sleep(nanoseconds: 400_000_000)
                AccessibilityNotification.Announcement(text).post()
            }
        }
        // Moment vorbei: Zeilen wieder im Normalzustand (Versatz + Einblenden + Häkchen)
        let ids = Set(celebration.watchIds)
        let nanos = UInt64(max(celebration.watchIds.count - 1, 0)) * 90_000_000 + 2_600_000_000
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: nanos)
            if Set(celebrating.keys) == ids { celebrating = [:] }
        }
    }

    /// Start-Tipp: frische Liste, sonst Zwischenspeicher (auch älter), sonst Ersatzliste —
    /// so steht sofort etwas da.
    private var shownStarterCoins: [StarterCoin] {
        starterCoins ?? StarterCoins.initial(us: StarterCoins.isUS())
    }

    /// Top 5 nach Marktkapitalisierung höchstens alle 24 h neu holen; kommt die Liste,
    /// bevor jemand tippt, wird still getauscht.
    private func refreshStarterCoins() async {
        let us = StarterCoins.isUS()
        guard !StarterCoins.isFresh(us: us) else { return }
        guard let fresh = await StarterCoins.load(us: us), !Task.isCancelled else { return }
        if data.watches.isEmpty { starterCoins = fresh }
    }

    /// Leere Merkliste: die fünf grössten Coins zur Auswahl (alle vorgewählt) und in
    /// einem Schritt hinzufügen; darunter der Weg über «Paar hinzufügen» (auch «+» oben rechts).
    var emptyState: some View {
        let coins = shownStarterCoins
        return GeometryReader { geo in
            ScrollView {
                VStack(spacing: Spacing.md) {
                    WatchlistLogo(size: 64)
                        .padding(16)
                        .background(accent.container.opacity(0.5), in: Circle())
                        .accessibilityHidden(true)
                    Text(L("starter_title"))
                        .font(.title3.weight(.semibold))
                        .multilineTextAlignment(.center)
                        .accessibilityAddTraits(.isHeader)
                    Text(L("starter_text"))
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)

                    WatchlistStarterPicker(coins: coins, us: StarterCoins.isUS()) { symbols in
                        addStarter(symbols)
                    }

                    // Kein Erklärsatz davor: der Knopf sagt selbst, was er tut
                    Button {
                        router.openExplorer()
                    } label: {
                        Text(L("starter_custom"))
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(accent.primary)
                            .multilineTextAlignment(.center)
                            .padding(.vertical, 8)
                            .padding(.horizontal, 12)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, Spacing.lg)
                .padding(.vertical, Spacing.xxl)
                .frame(maxWidth: 480)
                // Mittig, solange es passt; sonst scrollbar (grosse Schrift, kleine Geräte)
                .frame(maxWidth: .infinity, minHeight: geo.size.height)
            }
            .refreshable { await data.refreshAll() }
        }
        // «+» oben rechts (der Hinweis verweist darauf); in der Merkliste steht es neben der Lupe
        .overlay(alignment: .topTrailing) {
            addPairButton
                .padding(.trailing, Spacing.xs)
        }
        .task { await refreshStarterCoins() }
    }

    /// Haptik, Banner und Ansage kommen mit dem «Erst-Hinzufügen»-Moment (`takeCelebration`).
    private func addStarter(_ symbols: [String]) {
        withAnimation(.spring(duration: 0.35)) {
            _ = data.addStarterCoins(symbols)
        }
        // Gleich abholen, damit die neuen Zeilen schon im ersten Bild verborgen sind
        takeCelebration()
    }
}
