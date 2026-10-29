import SwiftUI

// Gemeinsame Bausteine der Portfolio-Ansichten: Formate (`PortfolioFormat`), Gewinn-/Wert-/Prozent-Pillen,
// Kennzahl, Hinweis, Haftungshinweis und Knopf «Hinzufügen».
// Eigene Datei, weil alle Portfolio-Ansichten sie teilen (wie `PortfolioUi.kt`).

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
        // Vorzeichen und Zahl als links-nach-rechts-Insel (RTL: «+» bleibt vor der Zahl) — wie Android
        let number = PriceFormat.valueWithCurrency(abs(value), "").trimmingCharacters(in: .whitespaces)
        return BidiText.ltr(sign + number) + " " + usdt
    }

    /// Betrag mit Vorzeichen nur bei Verlust («−1’234.56 CHF», sonst «1’234.56 CHF»; Steuer-Summen): echtes Minus
    /// U+2212, praktisch 0 (`isZero`) ohne Vorzeichen statt «-0.00»; Vorzeichen und Zahl als links-nach-rechts-Insel
    /// (RTL). Wie Android.
    static func signedMoney(_ value: Double, unit: String) -> String {
        let sign = isZero(value) || value > 0 ? "" : "−"
        let number = PriceFormat.valueWithCurrency(abs(value), "").trimmingCharacters(in: .whitespaces)
        return BidiText.ltr(sign + number) + " " + unit
    }

    /// Realisiert mit unbekanntem Teil (`incomplete`, Verkäufe ohne Kaufpreis/Verkaufspreis):
    /// nur unbekannt «—», sonst «+12.00 USDT (unvollständig)». Vollständig: wie `signedUsdt`. Wie Android.
    static func realizedText(_ value: Double, incomplete: Bool) -> String {
        if !incomplete { return signedUsdt(value) }
        return isZero(value) ? "—" : signedUsdt(value) + " (" + L("portfolio_export_incomplete") + ")"
    }

    /// Realisiert anzeigen: Betrag ungleich 0 oder ein Teil unbekannt.
    static func showRealized(_ value: Double, incomplete: Bool) -> Bool { incomplete || !isZero(value) }

    /// «+12.34%», bei praktisch 0 «0.00%».
    static func signedPercent(_ value: Double) -> String { PriceFormat.changePercent(value) ?? PriceFormat.zeroPercent() }

    static func amount(_ value: Double, _ coin: String) -> String { "\(PriceFormat.amount(value)) \(coin)" }

    /// Exakte Menge ohne Rundung («0.00000001 BTC»), Tausendertrennung wie `amount`.
    static func exactAmount(_ value: Decimal, _ coin: String) -> String { "\(PriceFormat.exactAmount(value)) \(coin)" }

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .none
        return f
    }()

    static func date(_ millis: Int64) -> String { dateFormatter.string(from: Date(millis: millis)) }

    /// Kursfarbe für ± (Schema, hoher Kontrast, Tausch — wie die Prozent-Änderung der
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
        case .TRANSFER: return L("portfolio_tx_transfer")
        case .REWARD: return L("portfolio_tx_reward")
        case .DEPOSIT: return L("portfolio_tx_deposit")
        case .WITHDRAWAL: return L("portfolio_tx_withdrawal")
        case .SWAP: return L("portfolio_tx_swap")
        case .GIFT: return L("portfolio_tx_gift")
        case .PAYMENT: return L("portfolio_tx_payment")
        }
    }

    /// Zugänge grün, Abgänge rot, Umbuchung neutral.
    static func typeColor(_ type: PortfolioTxType) -> Color {
        if type.isInflow { return PriceColors.up }
        if type.isOutflow { return PriceColors.down }
        return AppColors.onSurfaceVariant
    }
}

// MARK: Bausteine

/// Prozent wie überall (`ChangePill`): farbige Schrift ohne Pille in der Kursfarbe, Pfeil und
/// Vorzeichen («↘ −0.25%»); 0 grau «0.00%», ohne Wert «—» — wie `PlPill` (Android).
struct PortfolioPlPill: View {
    let percent: Double?
    var size: ChangePillSize = .small

    var body: some View {
        ChangePill(change: percent, size: size, dashWhenMissing: true)
    }
}

extension PortfolioFormat {
    /// Richtung eines Betrags oder Prozents für `TonePill`: Null-Regel des Portfolios (halber Cent).
    static func plTone(_ value: Double?) -> PillTone { PillTone.forValue(value, isZero: isZero) }
}

/// Betrag mit Vorzeichen («+12.40 USDT», «−3.00 CHF») als farbige Schrift ohne Pille in der
/// Kursfarbe mit Pfeil davor («↗ +12.40 USDT») — wie die Prozent-Änderung; `text` ist schon formatiert
/// (ggf. «•••»), `value` gibt Richtung und Pfeil (0/unbekannt grau ohne Pfeil).
/// Wie `PlValuePill` (Android).
struct PortfolioValuePill: View {
    let text: String
    let value: Double?
    var size: ChangePillSize = .small

    var body: some View {
        let tone = PortfolioFormat.plTone(value)
        TonePill(text: text, tone: tone, size: size, icon: tone.arrowSymbol)
    }
}

/// Prozent als farbige Schrift mit Pfeil und Vorzeichen («↗ +4.20%», «↘ −1.20%»), Farbe und Pfeil
/// nach dem Betrag `value` (Null-Regel des Portfolios) — für Stellen, die Betrag oder Prozent zeigen.
/// Praktisch 0: grau «0.00%» ohne Pfeil. Wie `PlPercentPill` (Android).
struct PortfolioPercentPill: View {
    let percent: Double
    let value: Double?
    var size: ChangePillSize = .small

    var body: some View {
        let tone = PortfolioFormat.plTone(value)
        let text = PriceFormat.changePercent(percent)
        TonePill(text: text ?? PriceFormat.zeroPercent(),
                 tone: tone,
                 size: size,
                 icon: text == nil ? nil : tone.arrowSymbol)
    }
}

/// Kleine Kennzahl: Beschriftung oben, Wert darunter.
struct PortfolioMetric: View {
    let label: String
    let value: String
    var valueColor: Color = AppColors.onSurface
    /// Gesetzt: Wert als farbige Schrift in dieser Bedeutung (Gewinn/Verlust), statt Text in `valueColor`.
    var valueTone: PillTone? = nil
    /// Währung am Ende klein und grau wie in der Merkliste (`PriceWithQuote.text`); aus für Mengen («0.5 BTC»).
    var smallQuote = true

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            // Beschriftungen wie «Unrealisierter Gewinn/Verlust» dürfen umbrechen
            Text(label)
                .font(.caption)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
            metricValue
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var metricValue: some View {
        if let valueTone {
            TonePill(text: value, tone: valueTone, size: .medium, icon: valueTone.arrowSymbol)
                .padding(.top, 2)
        } else {
            (smallQuote ? PriceWithQuote.text(value) : Text(verbatim: value))
                .font(AppFont.amount(.subheadline, weight: .medium))
                .foregroundStyle(valueColor)
                // Grosse Schrift: erst verkleinern, dann umbrechen — der Betrag wird nie abgeschnitten
                .lineLimit(2)
                .minimumScaleFactor(0.75)
        }
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

/// Kurzer Fusstext unter den Listen: «Arbeitsunterlage, keine Steuer- oder Anlageberatung» plus Pfad zur
/// Datenschutzerklärung; was an Daten wohin geht, steht dort und nicht unter jeder Liste.
struct PortfolioDisclaimer: View {
    var body: some View {
        Text(L("portfolio_footer_short"))
            .font(.caption2)
            .accessibilityIdentifier(UiTestTags.portfolioDisclaimer)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 16)
            .padding(.vertical, 16)
    }
}

/// «+»-Knopf unten rechts — wie Android `AppFab`: gefüllt in der Akzentfarbe, 56 pt (M3-Grösse),
/// Ecken `Radius.card`. Schatten nur hell und schwach (wie die FAB-Erhöhung in Android); dunkel
/// ohne, ein farbiger Schatten wirkt auf dem dunklen Grund wie ein Leuchten.
struct PortfolioAddButton: View {
    let action: () -> Void
    @Environment(\.appAccent) private var accent
    @Environment(\.onAccent) private var onAccent
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Button(action: action) {
            Image(systemName: "plus")
                .scaledFont(size: 22, weight: .semibold, relativeTo: .title2)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .foregroundStyle(onAccent)
                .frame(width: 56, height: 56)
                .background(accent.primary, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                .shadow(color: colorScheme == .dark ? .clear : accent.primary.opacity(0.25), radius: 6, y: 3)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("portfolio_add_tx"))
        // Bei `.xxxLarge` gedeckelt: darüber Symbol und Name gross beim langen Drücken
        .largeContentLabel(L("portfolio_add_tx"), systemImage: "plus")
        .padding(.trailing, Spacing.lg)
        .padding(.bottom, Spacing.lg)
    }
}
