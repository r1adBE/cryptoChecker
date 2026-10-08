import Foundation

/// Pause je Börse (#19): Antwortet eine Börse mit HTTP 429/418 («zu viele Anfragen») oder
/// scheitert sie zweimal hintereinander nur an Zeitüberschreitungen, wird sie eine Weile
/// übersprungen — 30 s, 1, 2, 4, 8 Min., höchstens 15 Min.; ein «Retry-After» der Börse gilt,
/// wenn es länger ist. Ihre Paare behalten solange den letzten Kurs; der Bericht zeigt
/// «pausiert bis 19:45 (zu viele Anfragen)». Ein Erfolg setzt alles zurück.
/// Reine Logik — wie `ExchangeBackoff.kt`.
enum ExchangeBackoff {

    /// Erste Pause.
    static let baseMillis: Int64 = 30_000
    /// Längste Pause (auch ein längeres «Retry-After» wird darauf gekürzt).
    static let maxMillis: Int64 = 15 * 60_000
    /// So viele Durchläufe in Folge nur mit Zeitüberschreitungen → Pause.
    static let timeoutRuns = 2

    /// Was ein Durchlauf bei einer Börse erlebt hat.
    enum Outcome: Sendable { case success, rateLimited, timeout, other }

    struct State: Equatable, Sendable {
        /// Pausen in Folge ohne Erfolg dazwischen — bestimmt die Dauer der nächsten.
        var strikes = 0
        /// Bis dahin wird die Börse übersprungen (0 = keine Pause).
        var pausedUntil: Int64 = 0
        /// `.RATE_LIMIT` oder `.TIMEOUT`; nil = noch nie pausiert.
        var reason: RefreshFailure? = nil
        /// Durchläufe in Folge, die nur an Zeitüberschreitungen scheiterten.
        var timeoutRuns = 0
    }

    /// Dauer der n-ten Pause in Folge (1 → 30 s, 2 → 1 Min., 3 → 2 Min. … höchstens 15 Min.).
    static func delayMillis(_ strike: Int) -> Int64 {
        if strike <= 1 { return baseMillis }
        let shift = min(strike - 1, 10)
        return min(baseMillis << Int64(shift), maxMillis)
    }

    static func isPaused(_ state: State?, now: Int64) -> Bool {
        guard let state else { return false }
        return state.pausedUntil > now
    }

    /// Neuer Zustand nach einem Durchlauf; nil = nichts zu merken (alles gut).
    static func next(_ state: State?, outcome: Outcome, now: Int64, retryAfterMillis: Int64? = nil) -> State? {
        let s = state ?? State()
        switch outcome {
        case .success:
            return nil
        case .rateLimited:
            let strikes = s.strikes + 1
            let asked = min(max(retryAfterMillis ?? 0, 0), maxMillis)
            return State(strikes: strikes, pausedUntil: now + max(delayMillis(strikes), asked),
                         reason: .RATE_LIMIT, timeoutRuns: 0)
        case .timeout:
            let runs = s.timeoutRuns + 1
            if runs < timeoutRuns {
                var copy = s
                copy.timeoutRuns = runs
                return copy
            }
            let strikes = s.strikes + 1
            return State(strikes: strikes, pausedUntil: now + delayMillis(strikes), reason: .TIMEOUT, timeoutRuns: 0)
        case .other:
            // Anderer Fehler: keine Pause, Zeitüberschreitungen nicht mehr «in Folge»
            var copy = s
            copy.timeoutRuns = 0
            return copy.strikes == 0 && copy.pausedUntil == 0 ? nil : copy
        }
    }

    /// Ergebnis einer Börse aus den Ursachen ihrer Fehler: Schon ein «zu viele Anfragen»
    /// pausiert; sonst zählt jeder Kurs als Erfolg; nur Zeitüberschreitungen → `.timeout`.
    static func outcome(failures: [RefreshFailure], updated: Int) -> Outcome {
        if failures.contains(.RATE_LIMIT) { return .rateLimited }
        if updated > 0 || failures.isEmpty { return .success }
        if failures.allSatisfy({ $0 == .TIMEOUT }) { return .timeout }
        return .other
    }

    /// «Retry-After»-Kopfzeile → Sekunden: Zahl oder HTTP-Datum. Unlesbar oder fehlend → nil.
    static func parseRetryAfterSeconds(_ header: String?, now: Int64) -> Int64? {
        let value = (header ?? "").trimmingCharacters(in: .whitespaces)
        if value.isEmpty { return nil }
        if let seconds = Int64(value) { return max(seconds, 0) }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, d MMM yyyy HH:mm:ss zzz"
        guard let date = f.date(from: value) else { return nil }
        let at = Int64(date.timeIntervalSince1970 * 1000)
        return max((at - now) / 1000, 0)
    }

    /// Anhang an den Fehlertext von HTTP-Fehlern, z. B. «HTTP 429 (retry-after 30 s)».
    static func retryAfterSuffix(_ seconds: Int64?) -> String {
        guard let seconds else { return "" }
        return " (retry-after \(seconds) s)"
    }

    /// Längstes «Retry-After» aus Fehlertexten (Millisekunden); nil = keines.
    static func retryAfterMillis(_ errors: [String?]) -> Int64? {
        guard let regex = try? NSRegularExpression(pattern: #"retry-after (\d+) s"#) else { return nil }
        let values: [Int64] = errors.compactMap { error in
            guard let lower = error?.lowercased(),
                  let match = regex.firstMatch(in: lower, range: NSRange(lower.startIndex..., in: lower)),
                  let range = Range(match.range(at: 1), in: lower) else { return nil }
            return Int64(lower[range])
        }
        return values.max().map { $0 * 1000 }
    }

    /// Zustände je Börse als kurzer Text (wie Android).
    static func encode(_ states: [String: State]) -> String {
        states.keys.sorted().compactMap { key in
            guard let s = states[key] else { return nil }
            return [key, String(s.strikes), String(s.pausedUntil), s.reason?.rawValue ?? "", String(s.timeoutRuns)]
                .joined(separator: "|")
        }.joined(separator: ";")
    }

    /// Unlesbare Einträge fallen weg.
    static func decode(_ text: String?) -> [String: State] {
        guard let text, !text.isEmpty else { return [:] }
        var result: [String: State] = [:]
        for entry in text.split(separator: ";", omittingEmptySubsequences: false) {
            let parts = entry.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
            guard parts.count == 5, !parts[0].isEmpty,
                  let strikes = Int(parts[1]), let until = Int64(parts[2]), let runs = Int(parts[4]) else { continue }
            result[parts[0]] = State(strikes: strikes, pausedUntil: until,
                                     reason: RefreshFailure(rawValue: parts[3]), timeoutRuns: runs)
        }
        return result
    }
}

/// App-Start bis zum ersten Bild der Merkliste, nur lokal gemessen (Bericht «Ablauf») — wie
/// `AppStartTiming.kt`.
enum AppStartTiming {
    /// Lag zwischen Prozessstart und Aufbau der Oberfläche mehr als das, lief der Prozess schon.
    static let coldGapMillis: Int64 = 5_000
    /// Unplausibel lange Werte werden verworfen.
    static let maxMillis: Int64 = 60_000

    static func elapsed(processStart: Int64?, uiCreated: Int64, firstFrame: Int64) -> Int64? {
        var from = uiCreated
        if let processStart, processStart <= uiCreated, uiCreated - processStart <= coldGapMillis {
            from = processStart
        }
        let millis = firstFrame - from
        return millis >= 0 && millis <= maxMillis ? millis : nil
    }
}

/// Kein Netz (#22/#23): nicht aktualisieren; kommt das Netz zurück, genau EINE Aktualisierung.
enum OfflineGate {
    static func resumeOnChange(previous: Bool?, online: Bool) -> Bool { previous == false && online }
}
