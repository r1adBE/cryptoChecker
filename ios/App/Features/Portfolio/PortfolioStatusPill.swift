import SwiftUI

// Status der Portfolio-Kurse über der Liste (aktuell · veraltet · offline) und die Zeitangabe «Stand …».
// Eigene Datei, weil Portfolio-Seite und Gesamtkarte sie gemeinsam nutzen (wie `PortfolioStatusPill.kt`).

/// Uhrzeit des Stands der Portfolio-Kurse — wie `portfolioStampTime` (Android).
enum PortfolioStamp {
    /// Heute nur die Uhrzeit («14:05»), sonst Datum und Uhrzeit.
    static func time(_ millis: Int64) -> String {
        let date = Date(millis: millis)
        let today = Calendar.current.isDateInToday(date)
        return date.formatted(date: today ? .omitted : .abbreviated, time: .shortened)
    }
}

/// Status der Portfolio-Kurse wie in der Merkliste (`DataStatusPill`): grün «Alles aktuell · vor 2 Min.»,
/// rot «Kurse veraltet · Stand 09:12», neutral «Offline · Stand 14:05» (bzw. «Offline» ohne Stand).
/// Nichts bei `.none`. Wie `PortfolioStatusPill` (Android).
struct PortfolioStatusPill: View {
    let state: PortfolioPriceState
    let updatedAt: Int64
    let now: Int64

    var body: some View {
        if let text {
            DataStatusPill(text: text, tone: tone, spoken: spoken)
        }
    }

    private var text: String? {
        switch state {
        case .none: return nil
        case .fresh: return L("watchlist_all_fresh", WatchlistTime.ago(updatedAt, now: now))
        case .stale: return L("portfolio_status_stale", PortfolioStamp.time(updatedAt))
        case .offline:
            return updatedAt > 0 ? L("offline_status_since", PortfolioStamp.time(updatedAt)) : L("offline_status")
        }
    }

    private var tone: DataStatusTone {
        switch state {
        case .fresh: return .fresh
        case .stale: return .warn
        case .none, .offline: return .neutral
        }
    }

    /// VoiceOver: Zeit ausgeschrieben («vor 2 Minuten») wie die Merkliste.
    private var spoken: String? {
        guard state == .fresh, let text else { return nil }
        let short = WatchlistTime.ago(updatedAt, now: now)
        return A11ySpeech.spokenDots(text.replacingOccurrences(of: short, with: WatchlistTime.spokenAgo(updatedAt, now: now)))
    }
}
