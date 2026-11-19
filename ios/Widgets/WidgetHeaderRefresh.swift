import AppIntents
import SwiftUI
import WidgetKit

// Rechter Teil der Kopfzeile im Puls- und im Portfolio-Widget, einheitlich mit dem Merkliste-Widget: der Stand
// (Datum und Uhrzeit, eng nur die Uhrzeit; veraltet bzw. ohne Netz mit Hinweis) und der Knopf «Aktualisieren».
// Wie Android `WidgetRefreshHeader`.

/// Texte für den Stand, lang → kurz.
enum WidgetHeaderStatus {
    /// «19. Nov., 10:12» / «10:12»; veraltet «veraltet · …» / «veraltet»; ohne Netz «Keine Verbindung · Stand …» /
    /// «Keine Verbindung». Ohne Zeit (0) leer.
    static func candidates(time: Int64, offline: Bool, outdated: Bool, at date: Date) -> [String] {
        switch WidgetStatusRule.kind(online: !offline, outdated: outdated, time: time) {
        case .offline: [WidgetOffline.label(time, at: date), WidgetOffline.short]
        case .offlineNoTime: [WidgetOffline.short]
        case .outdated: [WidgetOutdated.label(time, at: date), L("widget_outdated")]
        case .time: time > 0 ? [dateTime(time), timeOnly(time)] : []
        }
    }

    /// «19. Nov., 10:12» — mit Datum, damit klar ist, von wann der Stand ist.
    static func dateTime(_ time: Int64) -> String {
        Date(timeIntervalSince1970: TimeInterval(time) / 1000)
            .formatted(.dateTime.day().month(.abbreviated).hour().minute())
    }

    private static func timeOnly(_ time: Int64) -> String {
        Date(timeIntervalSince1970: TimeInterval(time) / 1000).formatted(date: .omitted, time: .shortened)
    }
}

/// Stand (erster Text, der passt; sonst keiner) und Knopf rechts in einer Kopfzeile.
struct WidgetHeaderTrailing<Intent: AppIntent>: View {
    let candidates: [String]
    let palette: WidgetPalette
    let intent: Intent

    var body: some View {
        HStack(spacing: 4) {
            ViewThatFits(in: .horizontal) {
                ForEach(candidates, id: \.self) { text in
                    Text(text)
                        .font(.system(size: WidgetTextSize.minSecondary))
                        .monospacedDigit()
                        .foregroundStyle(palette.secondary)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                }
                Color.clear.frame(width: 0, height: 0)
            }
            Button(intent: intent) {
                Image(systemName: "arrow.clockwise")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(palette.text) // neutral wie im Merkliste-Widget
                    .frame(width: 22, height: 22)
                    .background(Circle().fill(palette.text.opacity(palette.dark ? 0.12 : 0.08)))
                    // Trefferfläche grösser als der Kreis, ohne die Kopfzeile höher zu machen
                    .padding(12)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .padding(-12)
            .accessibilityLabel(L("action_refresh"))
        }
    }
}

/// Puls-Widget aktualisieren: frisch laden — ausser die Daten sind jünger als 30 s (schont die freie Quelle bei
/// mehreren Tipps) — und neu zeichnen. Ohne Netz nur neu zeichnen. Wie Android `PulseWidgetRenderer.refreshByTap`.
struct RefreshPulseIntent: AppIntent {
    static let title: LocalizedStringResource = "action_refresh"
    static let isDiscoverable: Bool = false
    static let tapMinAgeMillis: Int64 = 30_000

    func perform() async throws -> some IntentResult {
        let stored = CryptoPulseSource.stored()
        let young = stored.map { EpochTime.nowMillis - $0.time < Self.tapMinAgeMillis } ?? false
        if !young, !(await WidgetOffline.isOffline()) {
            _ = await RefreshDeadline.run(within: PortfolioProvider.refreshDeadlineMillis) { () async -> Bool in
                (try? await CryptoPulseSource.shared.fetch(force: true)) != nil
            }
        }
        WidgetCenter.shared.reloadTimelines(ofKind: PulseWidgetData.kind)
        return .result()
    }
}

/// Portfolio-Widget aktualisieren: Kurse holen und den Stand neu rechnen (wie der Zeitplan, nur sofort), dann neu
/// zeichnen. Gesperrt oder Portfolio aus: nichts holen. Wie Android `PortfolioWidgetProvider.ACTION_REFRESH`.
struct RefreshPortfolioIntent: AppIntent {
    static let title: LocalizedStringResource = "action_refresh"
    static let isDiscoverable: Bool = false

    func perform() async throws -> some IntentResult {
        let loaded = SharedStorage.loadSettingsChecked()
        if loaded.settings.portfolioEnabled, !PortfolioLockPolicy.widgetLocked(lockSetting: loaded.appLockAssumed),
           !(await WidgetOffline.isOffline()) {
            UsageMeter.shared.countRun()
            _ = await RefreshDeadline.run(within: PortfolioProvider.refreshDeadlineMillis) { () async -> Bool in
                await PortfolioWidgetStore.refresh(reload: false)
                return true
            }
            UsageMeter.shared.flush()
        }
        WidgetCenter.shared.reloadTimelines(ofKind: PortfolioWidgetStore.kind)
        return .result()
    }
}
