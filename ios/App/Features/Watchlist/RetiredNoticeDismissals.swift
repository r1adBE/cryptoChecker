import Foundation
import SwiftUI

/// Mit ✕ geschlossene Hinweise (`WatchlistRetiredNotice`) je Börse: der Hinweis nach dem Ende (Eintrag = Name klein) und
/// die Ankündigung davor (`ExchangeRetirement.advanceKey`) getrennt — wer die Ankündigung schliesst, sieht den Hinweis am
/// Stichtag trotzdem. Nur auf diesem Gerät (`SharedStorage.defaults`, wie die übrigen Oberflächen-Merker), nie
/// synchronisiert, nicht in der eigenen Sicherung. Wie Android `RetiredNoticeStore`.
@MainActor
final class RetiredNoticeDismissals: ObservableObject {
    static let shared = RetiredNoticeDismissals()
    static let key = "retired_exchange_notice_dismissed"
    @Published private(set) var keys: Set<String>

    private init() {
        keys = Set(SharedStorage.defaults.stringArray(forKey: Self.key) ?? [])
    }

    /// ✕ am Hinweis nach dem Ende: für `exchange` schliessen.
    func dismiss(_ exchange: String) {
        let name = exchange.trimmingCharacters(in: .whitespaces).lowercased()
        guard !name.isEmpty else { return }
        add(name)
    }

    /// ✕ an der Ankündigung: für `exchange` schliessen (der Hinweis nach dem Ende bleibt).
    func dismissAdvance(_ exchange: String) {
        guard !exchange.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        add(ExchangeRetirement.advanceKey(exchange))
    }

    /// «App zurücksetzen» (`AppData.resetApp`): die Einstellungen sind leer, auch die gemerkten ✕ im Speicher leeren —
    /// sonst fehlte der Hinweis bis zum Neustart, und das nächste ✕ schriebe die alten Einträge zurück.
    func reset() {
        keys = []
    }

    private func add(_ entry: String) {
        keys.insert(entry)
        SharedStorage.defaults.set(keys.sorted(), forKey: Self.key)
    }
}
