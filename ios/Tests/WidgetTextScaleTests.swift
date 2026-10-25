import SwiftUI
import XCTest
@testable import CryptoChecker

/// Haupttexte der Widgets (Coin-Name, Kurs) mit Dynamic Type: höchstens 1.3 × (`WidgetTextSize.primary`), und
/// `WidgetPrimaryFit` nimmt die gewachsene Fassung nur, wenn sie Platz hat — gerendert mit `ImageRenderer`.
@MainActor
final class WidgetTextScaleTests: XCTestCase {

    // MARK: Regeln

    func testPrimaryScaleIsCapped() {
        XCTAssertEqual(WidgetTextSize.primaryScale(0.8), 1)
        XCTAssertEqual(WidgetTextSize.primaryScale(1), 1)
        XCTAssertEqual(WidgetTextSize.primaryScale(1.2), 1.2, accuracy: 0.0001)
        XCTAssertEqual(WidgetTextSize.primaryScale(2.5), WidgetTextSize.maxPrimaryScale)
        XCTAssertEqual(WidgetTextSize.primaryScale(.nan), 1)
        XCTAssertEqual(WidgetTextSize.primary(14, scale: 3), 14 * 1.3, accuracy: 0.0001)
        XCTAssertEqual(WidgetTextSize.primary(14, scale: 1), 14)
    }

    /// Ein gewachsener Text schrumpft bei Platzmangel nie unter das, was ohne Wachstum galt (Kurs nie gekürzt).
    func testMinimumFactorKeepsOldFloor() {
        for scale in [1, 1.1, 1.3] as [CGFloat] {
            XCTAssertEqual(WidgetTextSize.minimumFactor(0.45, primaryScale: scale) * scale, 0.45, accuracy: 0.0001)
            XCTAssertEqual(WidgetTextSize.minimumFactor(1, primaryScale: scale) * scale, 1, accuracy: 0.0001)
        }
        XCTAssertEqual(WidgetTextSize.minimumFactor(0.7, primaryScale: 0), 0.7)
    }

    func testSecondaryUnchanged() {
        XCTAssertEqual(WidgetTextSize.secondary(11), 12)
        XCTAssertEqual(WidgetTextSize.secondary(12, scale: 2), 15)
    }

    // MARK: Gerendert

    /// Grösse, die `content` bei der Höhe `height` (Breite 300) meldet, gerendert mit `ImageRenderer`.
    private func renderedHeight<Content: View>(_ size: DynamicTypeSize, height: CGFloat,
                                               @ViewBuilder content: () -> Content) -> CGFloat {
        let view = ProposingFrame(width: 300, height: height) { content() }
            .dynamicTypeSize(size)
            .environment(\.locale, Locale(identifier: "en_US"))
        let renderer = ImageRenderer(content: view)
        renderer.scale = 1
        return CGFloat(renderer.cgImage?.height ?? 0)
    }

    /// `dynamic` = Faktor, den `@ScaledMetric` bei der jeweiligen Grösse liefert (fest vorgegeben, siehe
    /// `WidgetPrimaryFit.fixedScale`): Standard 1, «xxxLarge» ≈ 1.35, «accessibility5» ≈ 3.
    private func bars(_ dynamic: CGFloat) -> some View {
        WidgetPrimaryFit(in: .vertical, fixedScale: dynamic) { scale in
            Color.black.frame(width: 10, height: 20 * scale)
        }
    }

    func testGrowsAtLargeTextWhenThereIsRoom() {
        // Bild in ganzen Punkten: ± 1 für Rundung
        XCTAssertEqual(renderedHeight(.large, height: 100) { bars(1) }, 20, accuracy: 1)
        XCTAssertEqual(renderedHeight(.xxxLarge, height: 100) { bars(1.35) }, 26, accuracy: 1)
        XCTAssertEqual(renderedHeight(.accessibility5, height: 100) { bars(3) }, 26, accuracy: 1, "Obergrenze 1.3 ×")
    }

    func testFallsBackToBaseWhenTooTight() {
        XCTAssertEqual(renderedHeight(.xxxLarge, height: 22) { bars(1.35) }, 20, accuracy: 1)
    }

    /// Zeile wie in der Merkliste (Name/Börse links, Kurs/Veränderung rechts): Bei «xxxLarge» bleibt die Zeile in
    /// jeder Höhe ab der Grundgrösse innerhalb der Höhe — gewachsen, wo es passt, sonst in Grundgrösse.
    func testWatchRowNeverOverflows() {
        let base = renderedHeight(.large, height: 500) { row(1) }
        let grown = renderedHeight(.xxxLarge, height: 500) { row(1.35) }
        XCTAssertGreaterThan(grown, base, "Haupttexte wachsen bei grosser Schrift")
        for height in stride(from: base, through: grown + 8, by: 2) {
            let shown = renderedHeight(.xxxLarge, height: height) { row(1.35) }
            XCTAssertLessThanOrEqual(shown, height + 1, "Überlauf bei Zeilenhöhe \(height)")
        }
    }

    private func row(_ dynamic: CGFloat) -> some View {
        WidgetPrimaryFit(in: .vertical, fixedScale: dynamic) { scale in
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: "BTC/USDT").font(.system(size: 14 * scale, weight: .bold)).lineLimit(1)
                        .minimumScaleFactor(WidgetTextSize.minimumFactor(1, primaryScale: scale))
                    Text(verbatim: "Binance").font(.system(size: 12)).lineLimit(1)
                }
                Spacer(minLength: 4)
                VStack(alignment: .trailing, spacing: 1) {
                    Text(verbatim: "104,523.12").font(.system(size: 14 * scale, weight: .bold, design: .rounded))
                        .lineLimit(1)
                        .minimumScaleFactor(WidgetTextSize.minimumFactor(0.45, primaryScale: scale))
                    Text(verbatim: "+1.25%").font(.system(size: 12, weight: .semibold)).lineLimit(1)
                }
            }
        }
    }
}

/// Schlägt dem Inhalt eine feste Grösse vor und meldet, was der Inhalt daraus macht (wie ein Widget-Platz).
private struct ProposingFrame: Layout {
    let width: CGFloat
    let height: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        subviews.first?.sizeThatFits(ProposedViewSize(width: width, height: height)) ?? .zero
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(width: width, height: height))
    }
}

/// Alle Widgets mit Haupttexten (Coin-Name, Kurs, Gesamtwert, Schlagzeile) wachsen mit Dynamic Type über
/// `WidgetPrimaryFit` bzw. `primaryScale` — die Widget-Views gehören zum Widget-Ziel und lassen sich hier nicht
/// rendern, darum die Prüfung an den Quellen (wie `SettingsLogicTests` an `WidgetStyle.swift`).
final class WidgetPrimaryScaleUsageTests: XCTestCase {
    private static let files = [
        "WatchlistWidgetViews.swift", "SingleWidget.swift", "SingleWidgetHeader.swift", "PortfolioWidgetHome.swift",
        "PortfolioWidgetList.swift", "PortfolioWidgetOverview.swift", "PortfolioWidgetLockScreen.swift",
        "PulseWidgetHome.swift", "PriceLiveActivityWidget.swift",
    ]

    func testWidgetsUsePrimaryScale() throws {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../Widgets")
        for name in Self.files {
            // Fehlt die Datei, soll der Test scheitern statt still zu bestehen
            let source = try String(contentsOf: dir.appendingPathComponent(name).standardized, encoding: .utf8)
            let scales = source.contains("WidgetPrimaryFit") || source.contains("primaryScale")
                || source.contains("scale: CGFloat")
            XCTAssertTrue(scales, "\(name): Haupttexte ohne Dynamic Type (WidgetPrimaryFit / primaryScale)")
        }
    }

    /// Portfolio-Liste (zweizeilig): mit grosser Schrift höhere Zeilen, also weniger Positionen
    /// (`rowHeight(fontScale:)`).
    func testPortfolioListShowsFewerRowsWithLargeText() {
        func rows(_ scale: Double) -> Int {
            PortfolioWidgetViews.listRowsBelowTotal(
                height: 330, header: 16, gap: 4, row: PortfolioWidgetViews.rowHeight(wide: false, fontScale: scale),
                footer: 16, count: 20, fontScale: scale, wide: false).rows
        }
        XCTAssertLessThan(rows(1.3), rows(1))
        XCTAssertGreaterThan(rows(1.3), 0)
    }
}
