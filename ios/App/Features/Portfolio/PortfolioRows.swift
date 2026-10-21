import SwiftUI

/// Coin-Zeilen, geschlossene Positionen und leeres Portfolio. Wie `PortfolioRows.kt`.
extension PortfolioScreen {
    /// «Geschlossene Positionen (n)» — eingeklappt, nur der realisierte Gewinn/Verlust.
    @ViewBuilder
    func closedSection(_ closed: [CoinPosition]) -> some View {
        Button {
            showClosed.toggle()
        } label: {
            HStack(spacing: Spacing.xs) {
                Image(systemName: "chevron.forward")
                    .font(.caption.weight(.semibold))
                    .rotationEffect(.degrees(showClosed ? 90 : 0))
                Text(L("portfolio_closed", count: closed.count))
                    .font(.subheadline.weight(.medium))
                Spacer()
            }
            .foregroundStyle(AppColors.onSurfaceVariant)
            .padding(.horizontal, 4)
            .padding(.vertical, Spacing.md)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // Chevron ohne eigene Beschreibung: der Zustand steht als Wert (wie Android stateDescription)
        .accessibilityValue(L(showClosed ? "a11y_expanded" : "a11y_collapsed"))
        .sensoryFeedback(.selection, trigger: showClosed)

        if showClosed {
            VStack(spacing: 0) {
                ForEach(Array(closed.enumerated()), id: \.element.coin) { index, position in
                    if index > 0 { RowDivider() }
                    Button {
                        openCoin = position.coin
                    } label: {
                        HStack(spacing: 12) {
                            CoinBadge(symbol: position.coin, size: 32, portfolio: true)
                            Text(position.coin)
                                .font(.body)
                                .foregroundStyle(AppColors.onSurface)
                            Spacer(minLength: 8)
                            PortfolioValuePill(
                                text: PortfolioInsights.mask(
                                    PortfolioFormat.realizedText(position.realized, incomplete: position.realizedIncomplete),
                                    hidden: hideAmounts),
                                value: position.realized)
                        }
                        .padding(.vertical, Spacing.md)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, 2)
            .background(AppColors.containerLow, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
            .transition(.opacity.combined(with: .move(edge: .top)))
        }
    }

    // MARK: Leer

    var emptyState: some View {
        ScrollView {
            VStack(spacing: 0) {
                // Alltagssprache vor dem ersten Eintrag (Build 58): Beispiel ohne Kaufkurs in USDT, Hauptweg
                // «Ersten Kauf eintragen»; Börsen-Datei und Adresse nur als Nebenwege darunter (wie Android)
                EmptyStateView(
                    systemImage: AppSymbol.portfolio,
                    title: L("portfolio_empty_title"),
                    message: L("portfolio_empty_text"),
                    example: L("portfolio_empty_example_plain"),
                    actionTitle: L("portfolio_empty_action"),
                    action: { sheet = PortfolioTxDraft() }
                )
                // Nebenweg: Datei einer Börse übernehmen (CSV-Import)
                Button(L("portfolio_empty_csv")) { csvPickerOpen = true }
                    .accessibilityIdentifier(UiTestTags.portfolioImportCsv)
                    .font(.subheadline.weight(.semibold))
                    .padding(.bottom, 16)
                // Nebenweg: öffentliche Adresse nur beobachten (Bestand ohne Kaufpreis)
                Button(L("portfolio_empty_address")) { addressesOpen = true }
                    .font(.subheadline.weight(.semibold))
                    .padding(.bottom, 16)
                PortfolioDisclaimer()
            }
            .padding(.top, 48)
            // iPad/Querformat: Inhalt höchstens 640 pt breit, mittig
            .readableContentWidth()
        }
    }
}

// MARK: Coin-Zeile

/// Zeile je Coin (wie die Zeilen im Portfolio-Widget): Plakette, Menge klein und grau vor dem Symbol («1.3 BTC»),
/// darunter der Anteil am Gesamtwert («88.9%»), Ø/aktuell; rechts Wert, ± % seit Kauf und unten MEIN
/// Gewinn/Verlust auf der Position über die %-Basis — wie `CoinRow` (Android).
struct PortfolioCoinRow: View {
    let position: CoinPosition
    /// MEIN Gewinn/Verlust (USDT) über die %-Basis (`PortfolioPeriodChange.todayByCoin`): grau «24h», daneben
    /// «+1’250.00 USDT · +1.50%» in der Kursfarbe nach dem Betrag (ein Kauf heute zählt zu seinem Preis);
    /// unbekannt «—». Die Summe der Zeilen ist «Heute» der Gesamtkarte.
    var pnl: PortfolioWidgetChange?
    var basis: ChangeBasis?
    /// Anteil am Gesamtwert (0–100); nil = ohne Wert («—»).
    var sharePercent: Double?
    /// Anzahl Transaktionen: nur die Zahl, klein neben der Menge — wie `TxCountBadge` (Android).
    var txCount = 0
    /// Form in der Liste (oben an der ersten, unten an der letzten Zeile gerundet, dazwischen eckig) — wie `ListSegment`.
    var shape: UnevenRoundedRectangle = ListSegment.shape(0, 1)
    /// Kurs veraltet (gemerkter Stand): aktueller Kurs und Wert abgeblasst wie in der Merkliste.
    var stale = false
    /// Nur ein ungeprüfter Kerzenkurs (kein Bezug): kein Wert («—»), Hinweis «ungeprüft» (`PortfolioPrices.unchecked`).
    var unchecked = false
    /// Abfrage gescheitert, kein gemerkter Kurs: «Kurs gerade nicht abrufbar» (`PortfolioPrices.unavailable`).
    var unavailable = false
    /// Einheit von Wert und Gewinn/Verlust (Anzeigewährung wie im Widget, ohne Devisenkurs USDT); Detail bleibt USDT.
    var unit = PortfolioCardUnit(currency: PortfolioFormat.usdt, fxRate: nil)
    /// Börse, deren Kurs gilt (`PortfolioPrices.sources`); nil = Binance (kein Name). Wie Android `exchange`.
    var exchange: String? = nil
    /// Gekapselter Coin ohne eigenen Börsenkurs, bewertet mit dem Kurs dieser Basis (`PortfolioPrices.viaBase`):
    /// Hinweis «≈ BTC-Kurs (gebrückt/verpackt)». Wie Android `viaBase`.
    var viaBase: String? = nil
    /// Stablecoin, dessen Menge gebrückte Varianten enthält (`AddressBalance.bridgedStables`, z. B. «USDC.e»): Hinweis
    /// «≈ inkl. USDC.e (gebrückt)» — eine Brücke kann abkoppeln, die Menge gilt nur ungefähr. Wie Android.
    var bridgedStable: String? = nil
    @Environment(\.hidePortfolioAmounts) var hideAmounts
    /// Grosse Schrift: Symbol umbrechen statt abschneiden, ab accessibility1 Wert und Gewinn/Verlust unter
    /// Coin und Anteil statt daneben (`DenseRowText`, wie Android).
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let scale: Double = DenseRowText.scale(typeSize)
        let stacked: Bool = DenseRowText.stacksTrailing(scale)
        let layout: AnyLayout = stacked ? AnyLayout(VStackLayout(alignment: .trailing, spacing: Spacing.xs))
            : AnyLayout(HStackLayout(spacing: 12))
        let leadingMaxWidth: CGFloat? = stacked ? CGFloat.infinity : nil
        layout {
            HStack(spacing: 12) {
                CoinBadge(symbol: position.coin, size: ListSegment.logo, portfolio: true)
                leading(lines: DenseRowText.maxLines(scale))
            }
            .frame(maxWidth: leadingMaxWidth, alignment: .leading)
            if !stacked {
                Spacer(minLength: 8)
            }
            trailing
        }
        .padding(.horizontal, Spacing.md)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.container, in: shape)
        // Positionen als eine Karte: Trennlinie und Aussenrand statt eines Rands je Zeile
        .segmentLines(shape)
        .contentShape(shape)
        // VoiceOver (seit Build 93): die Zeile als EIN Satz, die Teile ausgeblendet — wie die Zeile der Merkliste
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: accessibilityText))
    }

    /// VoiceOver: Menge und Coin, Wert und Anteil (`a11y_portfolio_position`, wie das Portfolio-Widget), Hinweis zum
    /// Kurs, offener Gewinn/Verlust seit Kauf, Gewinn/Verlust über die %-Basis, «Kurs veraltet», Börse und Anzahl
    /// Transaktionen; Beträge verborgen: «Betrag verborgen», die Menge entfällt — wie `coinRowSpoken` (Android).
    var accessibilityText: String {
        // Hinweis zum Kurs in derselben Reihenfolge wie die Anzeige (nur einer)
        var note: String?
        if unchecked {
            note = L("portfolio_price_unchecked")
        } else if unavailable && position.value == nil {
            note = L("portfolio_price_unavailable")
        } else if let viaBase {
            note = L("portfolio_price_via_base", viaBase)
        } else if let bridgedStable {
            note = L("portfolio_amount_bridged_stable", bridgedStable)
        }
        // Ohne Wert steht der Hinweis an seiner Stelle (nicht «—» vorlesen)
        let value: String
        if hideAmounts {
            value = L("a11y_amount_hidden")
        } else if let v = position.value {
            value = PriceFormat.valueWithCurrency(unit.of(v), unit.code)
        } else {
            value = note ?? "—"
        }
        let share = sharePercent.flatMap { $0.isFinite
            ? String(format: "%.1f%%", locale: Locale.current, Swift.min(100, Swift.max(0, $0))) : nil } ?? "—"
        let name = WidgetQuantityFormat.spokenName(position.coin,
                                                   quantity: hideAmounts ? nil : PriceFormat.exactAmount(position.holdingsExact))
        var parts: [String?] = [L("a11y_portfolio_position", name, value, share)]
        if position.oversold { parts.append(L("portfolio_oversold")) }
        if note != value { parts.append(note) }
        // «Seit Kauf» wie die Pille der Zeile; fehlt bei einem Teil der Kaufpreis, «Preis fehlt»
        if !position.priceMissing || position.unrealizedPartial {
            parts.append(L("portfolio_unrealized") + ": " + (A11y.change(position.unrealizedPercent) ?? "—"))
        }
        if position.priceMissing { parts.append(L("portfolio_price_missing")) }
        if let basis {
            let amount = pnl.flatMap { $0.amount.isFinite ? unit.of($0.amount) : nil }
            let percent = amount == nil ? nil : pnl?.percent.flatMap { $0.isFinite ? $0 : nil }
            parts.append(Self.pnlAccessibility(amount: amount, percent: percent, basis: basis, unit: unit, hidden: hideAmounts))
        }
        if stale { parts.append(L("a11y_price_outdated")) }
        if let exchange, !exchange.trimmingCharacters(in: .whitespaces).isEmpty { parts.append(exchange) }
        if txCount > 0 { parts.append(L("portfolio_transactions") + ": " + LocaleNumbers.integer(txCount)) }
        return A11y.join(parts)
    }

    /// Links: Menge und Symbol, Anteil (Transaktionen, Börse, «veraltet»), Ø/aktuell.
    private func leading(lines: Int) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: Spacing.xs) {
                // Menge klein und grau vor dem Symbol; eng: Menge wird gekürzt, das Symbol bleibt
                Text(PortfolioInsights.mask(PriceFormat.exactAmount(position.holdingsExact), hidden: hideAmounts))
                    .font(AppFont.amount(.caption))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Text(position.coin)
                    .font(.headline)
                    .foregroundStyle(AppColors.onSurface)
                    .lineLimit(lines)
                    .layoutPriority(1)
                if position.oversold {
                    Image(systemName: "exclamationmark.circle.fill")
                        .scaledFont(size: 13, relativeTo: .footnote)
                        .foregroundStyle(AppColors.error)
                        .accessibilityLabel(L("portfolio_oversold"))
                }
            }
            HStack(spacing: Spacing.xs) {
                // Anteil am Gesamtwert, daneben die Anzahl Transaktionen
                Text(sharePercent.flatMap { $0.isFinite
                    ? String(format: "%.1f%%", locale: Locale.current, Swift.min(100, Swift.max(0, $0))) : nil } ?? "—")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                    .layoutPriority(1)
                if txCount > 0 {
                    Text(verbatim: LocaleNumbers.integer(txCount))
                        .font(.caption.weight(.semibold).monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .padding(.horizontal, Spacing.xs)
                        .padding(.vertical, 1)
                        .background(AppColors.containerHighest, in: Capsule())
                        .fixedSize()
                        .layoutPriority(2)
                        .accessibilityLabel(Text(verbatim: L("portfolio_transactions") + ": " + LocaleNumbers.integer(txCount)))
                }
                // Build 46: Börse des Kurses («· Kraken», nur mit eigener Börse) und nur bei veraltetem Kurs
                // «veraltet» in Fehlerfarbe (wie die Merkliste). Fehlt Platz, kürzt zuerst die Börse,
                // dann der Anteil; Zahl und «veraltet» bleiben ganz — wie Android
                if let exchange, !exchange.trimmingCharacters(in: .whitespaces).isEmpty {
                    Text(verbatim: "· " + BidiText.isolate(exchange))
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                if stale {
                    Text(verbatim: "· " + L("widget_outdated"))
                        .font(.caption)
                        .foregroundStyle(AppColors.error)
                        .lineLimit(1)
                        .fixedSize()
                        .layoutPriority(2)
                }
            }
            // «Ø … · aktuell …»; passt das nicht, fällt zuerst «aktuell …» weg (Ø bleibt) — wie Android
            ViewThatFits(in: .horizontal) {
                Text(L("portfolio_avg_and_now", PriceFormat.price(position.avgCost), PriceFormat.price(position.currentPrice)))
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
                Text(L("portfolio_avg_only", PriceFormat.price(position.avgCost)))
                    .lineLimit(1)
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(AppColors.onSurfaceVariant)
            // Ø nur über den Teil mit Kaufpreis: benennen, über welche Menge («Ø von 0.5 BTC mit Kaufpreis»)
            if position.avgCostPartial {
                Text(L("portfolio_avg_partial",
                       PortfolioInsights.mask(PortfolioFormat.exactAmount(position.pricedHoldingsExact, position.coin), hidden: hideAmounts)))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(2)
            }
        }
    }

    /// Rechts: Wert, ± % seit Kauf und MEIN Gewinn/Verlust über die %-Basis.
    private var trailing: some View {
        VStack(alignment: .trailing, spacing: 4) {
            PriceWithQuote.text(position.value.map { PortfolioInsights.mask(PriceFormat.valueWithCurrency(unit.of($0), unit.code), hidden: hideAmounts) } ?? "—")
                .font(AppFont.amount(.subheadline, weight: .semibold))
                // Veraltet: Sekundärfarbe wie in der Merkliste statt halber Deckkraft (Kontrast)
                .foregroundStyle(stale ? AppColors.onSurfaceVariant : AppColors.onSurface)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .contentTransition(.numericText())
            if unchecked {
                Text(L("portfolio_price_unchecked"))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
            } else if unavailable && position.value == nil {
                // Fehler statt «kein Kurs»: die Quelle antwortet gerade nicht — wie Android
                Text(L("portfolio_price_unavailable"))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
            } else if let viaBase {
                // Kein eigener Kurs: Wert mit dem Kurs der Basis — nie als eigener Kurs ausgeben
                Text(L("portfolio_price_via_base", viaBase))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            } else if let bridgedStable {
                // Gebrückte Stablecoin-Variante mitgezählt: Menge nur ungefähr, wie WBTC markiert
                Text(L("portfolio_amount_bridged_stable", bridgedStable))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            // «Seit Kauf» des Teils mit Kaufpreis; fehlt bei einem Teil der Kaufpreis, darunter «Preis fehlt» — wie Android
            if !position.priceMissing || position.unrealizedPartial {
                PortfolioPlPill(percent: position.unrealizedPercent)
            }
            if position.priceMissing {
                Text(L("portfolio_price_missing"))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
            }
            if let basis {
                // Zeitraum grau wie neben der Änderung der Merkliste, Gewinn/Verlust als farbige Schrift
                let amount = pnl.flatMap { $0.amount.isFinite ? unit.of($0.amount) : nil }
                let percent = amount == nil ? nil : pnl?.percent.flatMap { $0.isFinite ? $0 : nil }
                HStack(spacing: 4) {
                    Text(A11y.changeShortLabel(basis))
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                    TonePill(text: Self.pnlText(amount: amount, percent: percent, unit: unit, hidden: hideAmounts),
                             tone: PortfolioFormat.plTone(amount))
                }
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Self.pnlAccessibility(amount: amount, percent: percent, basis: basis, unit: unit, hidden: hideAmounts))
            }
        }
    }

    /// «+1’250.00 CHF · +1.50%» (`amount` schon in `unit`); Betrag verborgen «•••» (das Prozent bleibt); ohne Prozent
    /// nur der Betrag; unbekannt «—» — wie `RowPnl` (Android).
    static func pnlText(amount: Double?, percent: Double?, unit: PortfolioCardUnit, hidden: Bool) -> String {
        guard let amount else { return "—" }
        let text = PortfolioInsights.mask(unit.signed(amount), hidden: hidden)
        guard let percent else { return text }
        return text + " · " + PortfolioFormat.signedPercent(percent)
    }

    /// VoiceOver: «gestiegen um 1’250.00 USDT, gestiegen um 1.50% in 24 Stunden»; unbekannt «… nicht verfügbar».
    static func pnlAccessibility(amount: Double?, percent: Double?, basis: ChangeBasis, unit: PortfolioCardUnit, hidden: Bool) -> String {
        guard let amount else { return A11y.change(nil, basis: basis) }
        let spokenAmount: String
        if hidden {
            spokenAmount = L("a11y_amount_hidden")
        } else if PortfolioFormat.isZero(amount) {
            spokenAmount = L("a11y_change_flat")
        } else {
            spokenAmount = L(amount > 0 ? "a11y_change_up" : "a11y_change_down", PriceFormat.valueWithCurrency(abs(amount), unit.code))
        }
        return A11y.changePhrase(A11y.join([spokenAmount, percent.flatMap { A11y.change($0) }]), basis: basis)
    }
}
