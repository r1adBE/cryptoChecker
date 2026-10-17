import Foundation
import UserNotifications

/// Kalender wichtiger US-Wirtschaftsdaten — wie `MacroCalendarRepository.kt`.
/// Quelle: öffentliche Datei der App-Webseite (`MacroCalendar.url`, GitHub Pages), höchstens einmal
/// am Tag geholt (ohne Schlüssel, ohne Angaben zum Gerät). Zwischenspeicher 24 h; bei Fehlern der
/// Zwischenspeicher (auch abgelaufen), sonst die mitgelieferte Datei `macro_events.json`.
actor MacroCalendarSource {
    static let shared = MacroCalendarSource()

    private static let cacheKey = "macro_events_cache"
    private static let cacheAtKey = "macro_events_cache_at"
    /// Nach einem Fehler frühestens nach 30 Min. erneut versuchen.
    private static let retryMillis: Int64 = 30 * 60_000
    private static let timeoutSeconds: Double = 12

    /// Zuletzt geliefert, mit dem Zeitpunkt, ab dem die 24 h zählen.
    private var memory: (at: Int64, events: [MacroEvent])?

    /// Termine; wirft nie.
    func events() async -> [MacroEvent] {
        let now = TimeUtils.nowMillis
        if let memory, MacroCalendar.isFresh(savedAt: memory.at, now: now) { return memory.events }
        let store = UserDefaults.standard
        let cached = store.data(forKey: Self.cacheKey)
            .flatMap { try? JSONDecoder().decode([MacroEvent].self, from: $0) }
        let savedAt = Int64(store.double(forKey: Self.cacheAtKey))
        if let cached, MacroCalendar.isFresh(savedAt: savedAt, now: now) {
            memory = (savedAt, cached)
            return cached
        }
        let downloaded: [MacroEvent]?? = try? await AsyncTimeout.run(seconds: Self.timeoutSeconds) {
            let text = try await MarketHTTP.call(MacroCalendar.url)
            return MacroCalendar.parse(text, now: now)
        }
        if let fresh = downloaded ?? nil {
            if let data = try? JSONEncoder().encode(fresh) {
                store.set(data, forKey: Self.cacheKey)
                store.set(Double(now), forKey: Self.cacheAtKey)
            }
            memory = (now, fresh)
            return fresh
        }
        let fallback = cached ?? Self.bundled(now: now)
        memory = (now - MacroCalendar.ttlMillis + Self.retryMillis, fallback)
        return fallback
    }

    private static func bundled(now: Int64) -> [MacroEvent] {
        guard let url = Bundle.main.url(forResource: "macro_events", withExtension: "json"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return MacroCalendar.parse(text, now: now) ?? []
    }
}

/// Morgen-Mitteilung «Wirtschaftstermine» (Optionen → Alarme & Benachrichtigungen, Standard aus):
/// lokal geplante Mitteilungen um 08:00 an den nächsten Tagen mit Terminen, aus dem gespeicherten
/// Kalender — wie `MacroNotifyWorker.kt`. Nachtruhe wie bei den übrigen Markt-Mitteilungen (lautlos).
/// Neu geplant beim Öffnen der App, beim Laden des Markt-Tabs und beim Umschalten.
enum MacroNotifications {
    static let idPrefix = "macro-"
    /// Höchstens so viele Tage im Voraus planen (iOS erlaubt insgesamt 64 geplante Mitteilungen).
    static let maxDays = 8

    /// Geplante Mitteilungen an Einstellung und Kalender anpassen.
    static func refresh(settings: AppSettings) {
        Task.detached {
            var events: [MacroEvent] = []
            if settings.macroNotifications { events = await MacroCalendarSource.shared.events() }
            await reschedule(settings: settings, events: events)
        }
    }

    static func reschedule(settings: AppSettings, events: [MacroEvent]) async {
        let center = UNUserNotificationCenter.current()
        let pending = await center.pendingNotificationRequests()
        let old = pending.map(\.identifier).filter { $0.hasPrefix(idPrefix) }
        if !old.isEmpty { center.removePendingNotificationRequests(withIdentifiers: old) }
        guard settings.macroNotifications, await Notifier.isAuthorized() else { return }

        let calendar = Calendar.current
        let now = Date()
        var days: [Date] = []
        for event in events {
            let day = calendar.startOfDay(for: MacroCalendar.date(event.time))
            if !days.contains(day) { days.append(day) }
        }
        var planned = 0
        for day in days.sorted() {
            guard planned < maxDays,
                  let notifyAt = calendar.date(bySettingHour: MacroCalendar.notifyHour, minute: 0, second: 0, of: day),
                  notifyAt > now else { continue }
            let todays = MacroCalendar.eventsOnDay(events, day: day, calendar: calendar)
            guard !todays.isEmpty else { continue }
            let content = UNMutableNotificationContent()
            content.title = L("notification_macro_title")
            content.body = MacroCalendar.todayText(todays)
            content.sound = .default
            content.threadIdentifier = "market"
            // Tippen: Markt-Tab beim Wirtschaftsdaten-Hinweis (wie Android)
            content.userInfo = ["open": "cycle/macro"]
            Notifier.applyQuietHours(content, settings, date: notifyAt)
            let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: notifyAt)
            let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
            let id = idPrefix + String(Int64(day.timeIntervalSince1970))
            try? await center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
            planned += 1
        }
    }
}
