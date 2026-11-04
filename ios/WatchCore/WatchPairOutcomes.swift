import Foundation
import os

/// Ergebnis des letzten eigenen Abrufs je Paar eines Favoriten (`WatchPairQuotes.Outcome`) — nur im Speicher, für den
/// Grund bei «—» (`WatchText.missingReason`). Wie Android `WatchPairOutcomes`.
enum WatchPairOutcomes {
    private static let outcomes = OSAllocatedUnfairLock(initialState: [String: WatchPairQuotes.Outcome]())

    static func record(_ key: String, _ outcome: WatchPairQuotes.Outcome) {
        outcomes.withLock { $0[key] = outcome }
    }

    static func outcome(_ key: String) -> WatchPairQuotes.Outcome? {
        outcomes.withLock { $0[key] }
    }
}
