import AppIntents
import SwiftUI
import UIKit
import WidgetKit

// Textbausteine der Widgets: Veränderung, Gewinn/Verlust, Aktualisieren-Knopf, Kurs mit kleiner Währung.

/// Veränderung in Prozent: Kursfarbe und immer mit Vorzeichen + / −; ohne Bewegung
/// oder ohne Vorkurs grau «0.00%» (wie Android) — so sehen alle Zeilen gleich aus.
struct WidgetChangeLabel: View {
    let change: Double?
    let palette: WidgetPalette
    var size: CGFloat = 12
    var showsText = true
    /// Pfeil wie in der Prozent-Pille der App; folgt dem Vorzeichen (nie dem Farbtausch),
    /// bei «0.00%» keiner.
    var showsArrow = false
    /// Veränderung über 24 Stunden (`Watch.change24h`): ohne Wert grau «—» statt «0.00%»,
    /// VoiceOver mit Zeitraum («… in 24 Stunden»).
    var day = false
    /// Klein und grau hinter dem Wert, z. B. «24h» (wenn daneben ein anderer Zeitraum steht).
    var suffix: String? = nil
    /// Klein und grau VOR dem Wert, z. B. «24h ↘ −0.25%» (Zeitraum der Veränderung in Listenzeilen);
    /// ohne Wert («—») keiner.
    var prefix: String? = nil
    /// %-Basis von `day`-Werten (VoiceOver: «… in 24 Stunden» / «… heute»).
    var basis: ChangeBasis = .ROLLING_24H
    /// Grau ohne Kursfarbe (deaktiviertes Paar: letzter Wert, keine Abfrage).
    var neutral = false

    static var zeroText: String { String(format: "%.2f%%", locale: Locale.current, 0.0) }

    var body: some View {
        if showsText {
            let text = (day && change == nil) ? "—" : (change.flatMap { PriceFormat.changePercent($0) } ?? Self.zeroText)
            HStack(spacing: 2) {
                if let prefix, change != nil {
                    WidgetChangePrefix(text: prefix, size: size, palette: palette)
                }
                if showsArrow {
                    ChangeArrowIcon(change: change)
                        .font(.system(size: size * 0.75, weight: .bold))
                }
                Text(text)
                    .font(.system(size: size, weight: .semibold))
                    .monospacedDigit()
                    .lineLimit(1)
                    // Mit Pfeil in engen Zeilen lieber etwas kleiner als abgeschnitten
                    .minimumScaleFactor(0.85)
                    .accessibilityLabel(day ? A11y.change(change, basis: basis) : (A11y.change(change) ?? L("a11y_change_flat")))
                if let suffix {
                    Text(suffix)
                        .font(.system(size: size * 0.8, weight: .medium))
                        .foregroundStyle(palette.secondary)
                        .lineLimit(1)
                        .accessibilityHidden(true)
                }
            }
            .foregroundStyle(neutral ? palette.secondary : palette.change(change))
        }
    }
}

/// MEIN Gewinn/Verlust einer Zeile des Portfolio-Widgets: «24h +1’250.00 CHF · +1.50%» in der Kursfarbe nach dem
/// Betrag (nie nach dem Farbtausch, 0 grau); passt es nicht, nur der Betrag (`PortfolioWidgetViews.pnlTexts`);
/// unbekannt grau «—». VoiceOver liest die Zeile als Ganzes. Wie Android `PortfolioWidgetRenderer.fitPnl`.
struct WidgetPnlLabel: View {
    let change: PortfolioListTotalChange?
    let currency: String
    let palette: WidgetPalette
    var hidden = false
    var size: CGFloat = 11
    /// Klein und grau VOR dem Wert, z. B. «24h»; ohne Wert («—») keiner.
    var prefix: String? = nil

    var body: some View {
        if let change {
            let texts = PortfolioWidgetViews.pnlTexts(change, currency: currency, hidden: hidden)
            ViewThatFits(in: .horizontal) {
                ForEach(texts, id: \.self) { line($0) }
            }
            .foregroundStyle(palette.change(Double(change.direction)))
        } else {
            Text("—")
                .font(.system(size: size, weight: .semibold))
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
        }
    }

    private func line(_ text: String) -> some View {
        HStack(spacing: 2) {
            if let prefix {
                WidgetChangePrefix(text: prefix, size: size, palette: palette)
            }
            Text(text)
                .font(.system(size: size, weight: .semibold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.85)
                .privacySensitive()
        }
    }
}

/// Zeitraum vor einer Veränderung («24h»): 80 % der Schrift, grau, für VoiceOver verborgen
/// (das Label nennt den Zeitraum schon). Wie Android `PortfolioWidgetRenderer.periodPrefixed`.
private struct WidgetChangePrefix: View {
    let text: String
    let size: CGFloat
    let palette: WidgetPalette

    var body: some View {
        Text(text)
            .font(.system(size: size * 0.8, weight: .medium))
            .foregroundStyle(palette.secondary)
            .lineLimit(1)
            .padding(.trailing, 2)
            .accessibilityHidden(true)
    }
}

/// Knopf «Aktualisieren» (interaktiv, iOS 17). In der 15-s-Sperre (`WidgetRefreshState.blockedUntil`)
/// steht statt des Symbols ein Countdown, den WidgetKit selbst herunterzählt; zum Ende der Sperre hat die
/// Timeline einen eigenen Eintrag, dann ist das Symbol wieder da. Der Countdown nimmt Tipps an, startet aber
/// nichts (gleiche Sperre in `RefreshPricesIntent`).
struct WidgetRefreshButton: View {
    let palette: WidgetPalette
    var size: CGFloat = 26
    /// Gesperrt bis (Wanduhr, ms; 0 = nie) und Zeitpunkt des Eintrags.
    var blockedUntil: Int64 = 0
    var date = Date()

    var body: some View {
        let seconds = WidgetRefreshState.remainingSeconds(blockedUntil: blockedUntil,
                                                          now: Int64(date.timeIntervalSince1970 * 1000))
        Button(intent: RefreshPricesIntent()) {
            Group {
                if seconds > 0 {
                    Text(timerInterval: date...Date(timeIntervalSince1970: TimeInterval(blockedUntil) / 1000),
                         countsDown: true)
                        .font(.system(size: size * 0.36, weight: .semibold))
                        .monospacedDigit()
                        .foregroundStyle(palette.secondary)
                        .multilineTextAlignment(.center)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                } else {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: size * 0.46, weight: .bold))
                        .foregroundStyle(palette.text) // neutral wie der Titel, nicht in der Akzentfarbe
                }
            }
            .frame(width: size, height: size)
            .background(Circle().fill(palette.text.opacity(palette.dark ? 0.12 : 0.08)))
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        // VoiceOver: «Aktualisieren – wieder möglich in 12 s» statt der laufenden Ziffern
        .accessibilityLabel(seconds > 0 ? L("widget_refresh_blocked_a11y", seconds) : L("action_refresh"))
    }
}

/// Kurs gross, Gegenwert klein daneben.
struct WidgetPriceText: View {
    let price: Double?
    let quote: String
    let size: CGFloat
    let palette: WidgetPalette
    var weight: Font.Weight = .bold
    /// Farbe des Kurses; nil = `palette.text`. Deaktiviert: `palette.secondary` statt Deckkraft (wie Android).
    var color: Color? = nil

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: size * 0.18) {
            Text(PriceFormat.price(price))
                .font(.system(size: size, weight: weight, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(color ?? palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.45)
                .invalidatableContent()
            if price != nil, (price ?? 0) > 0 {
                Text(quote)
                    .font(.system(size: max(CGFloat(WidgetQuoteText.minQuoteSize), size * 0.42), weight: .semibold))
                    .foregroundStyle(palette.secondary)
                    .lineLimit(1)
                    .fixedSize()
            }
        }
    }
}

extension Text {
    /// `text` mit kleiner, grauer Währung `quote` (`WidgetQuoteText`) — wie der Börsenname der Merkliste:
    /// der Rest in `size`/`weight`, die Währung in `WidgetQuoteText.quoteSize` und `quoteColor`. Ohne
    /// Währung im Text (z. B. «•••») der ganze Text gleich. Wie `WidgetToolkit.quoteText` (Android).
    static func quoted(_ text: String, quote: String, size: CGFloat, weight: Font.Weight = .semibold,
                       design: Font.Design = .default, quoteColor: Color) -> Text {
        let font = Font.system(size: size, weight: weight, design: design)
        let parts = WidgetQuoteText.split(text, quote: quote)
        guard let currency = parts.quote else { return Text(verbatim: text).font(font) }
        let quoteFont = Font.system(size: CGFloat(WidgetQuoteText.quoteSize(for: Double(size))), weight: .semibold)
        let before = Text(verbatim: parts.before).font(font)
        let middle = Text(verbatim: currency).font(quoteFont).foregroundStyle(quoteColor)
        let after = Text(verbatim: parts.after).font(font)
        return before + middle + after
    }
}
