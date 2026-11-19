import AppIntents
import SwiftUI
import WidgetKit

// Rechter Teil der Kopfzeile im Puls- und im Portfolio-Widget, genau wie im Merkliste-Widget: der Stand («Uhrzeit ·
// Dauer», eng nur die Uhrzeit; veraltet bzw. ohne Netz mit Hinweis) und der Knopf «Aktualisieren», nach einer
// Aktualisierung 15 s lang ein Countdown (`WidgetHeaderStamp`, `WidgetRefreshState`). Wie Android `WidgetRefreshHeader`.

/// Texte für den Stand, lang → kurz.
enum WidgetHeaderStatus {
    /// «06:42:15 · 840 ms» / «06:42:15» (wie `WidgetData.refreshMeta` im Merkliste-Widget); veraltet «veraltet · …» /
    /// «veraltet»; ohne Netz «Keine Verbindung · Stand …» / «Keine Verbindung». Ohne Zeit (0) leer.
    /// - Parameter duration: Dauer der Aktualisierung zu diesem Stand (`WidgetHeaderStamp.Stamp.duration`); 0 = ohne
    static func candidates(time: Int64, duration: Int64, offline: Bool, outdated: Bool, at date: Date) -> [String] {
        switch WidgetStatusRule.kind(online: !offline, outdated: outdated, time: time) {
        case .offline: [WidgetOffline.label(time, at: date), WidgetOffline.short]
        case .offlineNoTime: [WidgetOffline.short]
        case .outdated: [WidgetOutdated.label(time, at: date), L("widget_outdated")]
        case .time:
            time > 0 ? unique([WidgetData.refreshMeta(at: time, duration: duration),
                               WidgetData.refreshMeta(at: time, duration: 0)]) : []
        }
    }

    private static func unique(_ texts: [String]) -> [String] {
        texts.reduce(into: []) { list, text in if !list.contains(text) { list.append(text) } }
    }
}

/// Eintrag zum Ende der Sperre: bringt das Symbol zurück (der Countdown davor zählt von selbst), wie im
/// Merkliste-Widget (`WatchlistProvider`).
enum WidgetHeaderUnlock {
    static func adding<E: TimelineEntry>(_ entries: [E], blockedUntil: Int64,
                                         copy: (E, Date) -> E) -> [E] {
        guard let first = entries.first,
              let unlock = WidgetRefreshState.unlockAt(blockedUntil: blockedUntil,
                                                       now: Int64(first.date.timeIntervalSince1970 * 1000)) else {
            return entries
        }
        let unlockDate = Date(timeIntervalSince1970: TimeInterval(unlock) / 1000)
        let base = entries.last { $0.date <= unlockDate } ?? first
        return (entries + [copy(base, unlockDate)]).sorted { $0.date < $1.date }
    }
}

/// Stand (erster Text, der passt; sonst keiner) und Knopf rechts in einer Kopfzeile; in der Sperre (`blockedUntil`)
/// statt des Symbols ein Countdown, den WidgetKit selbst herunterzählt (`WidgetRefreshButton`, wie im Merkliste-Widget).
struct WidgetHeaderTrailing<Intent: AppIntent>: View {
    let candidates: [String]
    let palette: WidgetPalette
    let intent: Intent
    /// Gesperrt bis (Wanduhr, ms; 0 = nie) und Zeitpunkt des Eintrags.
    var blockedUntil: Int64 = 0
    var date = Date()

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
            // Gleich gross und gleich gestaltet wie im Merkliste-Widget (Knopf, Countdown, Trefferfläche)
            WidgetRefreshButton(intent: intent, palette: palette, size: 26, blockedUntil: blockedUntil, date: date)
        }
    }
}

/// Tipp auf «Aktualisieren» im Puls- bzw. Portfolio-Widget mit derselben 15-s-Sperre wie im Merkliste-Widget
/// (`WidgetRefreshState.onTap`): ohne Netz nur neu zeichnen; eben erst aktualisiert, Countdown zeichnen (weitere Tipps
/// auf ihn tun nichts); sonst `start` und neu zeichnen. Wie Android `WidgetRefreshHeader.onTap`.
enum WidgetHeaderTap {
    static func perform(_ kind: WidgetHeaderStamp.Kind, widgetKind: String, start: () async -> Void) async {
        let tap = WidgetRefreshState.onTap(online: !(await WidgetOffline.isOffline()), running: false,
                                           lastFinishedAt: WidgetHeaderStamp.load(kind).finishedAt,
                                           now: EpochTime.nowMillis)
        switch tap {
        case .offline:
            break
        case .start:
            await start()
        case .running:
            return
        case .blocked(let until):
            guard WidgetHeaderStamp.blockedUntil(kind) != until else { return }
            WidgetHeaderStamp.setBlockedUntil(kind, until)
        }
        WidgetCenter.shared.reloadTimelines(ofKind: widgetKind)
    }
}

/// Puls-Widget aktualisieren (ausserhalb der Sperre, `WidgetHeaderTap`): frisch laden und neu zeichnen. Wie Android
/// `PulseWidgetRenderer.refreshByTap`.
struct RefreshPulseIntent: AppIntent {
    static let title: LocalizedStringResource = "action_refresh"
    static let isDiscoverable: Bool = false

    func perform() async throws -> some IntentResult {
        await WidgetHeaderTap.perform(.pulse, widgetKind: PulseWidgetData.kind) {
            let startedAt = EpochTime.nowMillis
            let fetched = await RefreshDeadline.run(within: PortfolioProvider.refreshDeadlineMillis) {
                () async -> PulseMarketData? in
                try? await CryptoPulseSource.shared.fetch(force: true)
            }
            if let data = fetched ?? nil, data.hasMarket, data.time > 0 {
                WidgetHeaderStamp.finished(.pulse, dataAt: data.time, startedAt: startedAt)
            }
        }
        return .result()
    }
}

/// Portfolio-Widget aktualisieren (ausserhalb der Sperre): Kurse holen und den Stand neu rechnen (wie der Zeitplan, nur
/// sofort), dann neu zeichnen. Gesperrt oder Portfolio aus: nichts holen. Wie Android `PortfolioWidgetProvider`.
struct RefreshPortfolioIntent: AppIntent {
    static let title: LocalizedStringResource = "action_refresh"
    static let isDiscoverable: Bool = false

    func perform() async throws -> some IntentResult {
        let loaded = SharedStorage.loadSettingsChecked()
        guard loaded.settings.portfolioEnabled,
              !PortfolioLockPolicy.widgetLocked(lockSetting: loaded.appLockAssumed) else {
            WidgetCenter.shared.reloadTimelines(ofKind: PortfolioWidgetStore.kind)
            return .result()
        }
        await WidgetHeaderTap.perform(.portfolio, widgetKind: PortfolioWidgetStore.kind) {
            UsageMeter.shared.countRun()
            _ = await RefreshDeadline.run(within: PortfolioProvider.refreshDeadlineMillis) { () async -> Bool in
                await PortfolioWidgetStore.refresh(reload: false, stamp: true)
                return true
            }
            UsageMeter.shared.flush()
        }
        return .result()
    }
}
